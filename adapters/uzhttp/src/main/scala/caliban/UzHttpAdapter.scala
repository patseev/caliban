package caliban

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import caliban.ResponseValue.{ ObjectValue, StreamValue }
import caliban.Value.NullValue
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import uzhttp.HTTPError.BadRequest
import uzhttp.Request.Method
import uzhttp.Status.Ok
import uzhttp.header.Headers
import uzhttp.websocket.{ Close, Frame, Text }
import uzhttp.{ HTTPError, Request, Response }
import zio.Exit.Failure
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.{ Take, ZStream, ZTransducer }
import zio._

object UzHttpAdapter {

  def makeHttpService[R, E](
    path: String,
    interpreter: GraphQLInterpreter[R, E],
    skipValidation: Boolean = false,
    enableIntrospection: Boolean = true
  ): PartialFunction[Request, ZIO[R, HTTPError, Response]] = {

    // POST case
    case req if req.method == Method.POST && req.uri.getPath == path =>
      for {
        body <- req.body match {
                 case Some(value) => value.transduce(ZTransducer.utf8Decode).runHead
                 case None        => ZIO.fail(BadRequest("Missing body"))
               }
        req <- if (req.headers.get(Headers.ContentType).exists(_.startsWith("application/graphql")))
                ZIO.succeed(GraphQLRequest(query = body))
              else
                ZIO.fromEither(decode[GraphQLRequest](body.getOrElse(""))).mapError(e => BadRequest(e.getMessage))
        res <- executeHttpResponse(
                interpreter,
                req,
                skipValidation = skipValidation,
                enableIntrospection = enableIntrospection
              )
      } yield res

    // GET case
    case req if req.method == Method.GET && req.uri.getPath == path =>
      val params = Option(req.uri.getQuery)
        .getOrElse("")
        .split("&")
        .toList
        .flatMap(_.split("=").toList match {
          case key :: value :: Nil => Some(key -> URLDecoder.decode(value, "UTF-8"))
          case _                   => None
        })
        .toMap

      for {
        variables <- ZIO
                      .foreach(params.get("variables"))(s => ZIO.fromEither(decode[Map[String, InputValue]](s)))
                      .mapError(e => BadRequest(e.getMessage))
        extensions <- ZIO
                       .foreach(params.get("extensions"))(s => ZIO.fromEither(decode[Map[String, InputValue]](s)))
                       .mapError(e => BadRequest(e.getMessage))
        req = GraphQLRequest(params.get("query"), params.get("operationName"), variables, extensions)
        res <- executeHttpResponse(
                interpreter,
                req,
                skipValidation = skipValidation,
                enableIntrospection = enableIntrospection
              )
      } yield res
  }

  def makeWebSocketService[R, E](
    path: String,
    interpreter: GraphQLInterpreter[R, E],
    skipValidation: Boolean = false,
    enableIntrospection: Boolean = true,
    keepAliveTime: Option[Duration] = None
  ): PartialFunction[Request, ZIO[R, HTTPError, Response]] = {
    case req @ Request.WebsocketRequest(_, uri, _, _, inputFrames) if uri.getPath == path =>
      for {
        subscriptions <- Ref.make(Map.empty[String, Fiber[Throwable, Unit]])
        sendQueue     <- Queue.unbounded[Take[Nothing, Frame]]
        _ <- inputFrames.collect { case Text(text, _) => text }.mapM { text =>
              for {
                msg     <- Task.fromEither(decode[Json](text))
                msgType = msg.hcursor.downField("type").success.flatMap(_.value.asString).getOrElse("")
                _ <- RIO.whenCase(msgType) {
                      case "connection_init" =>
                        sendQueue.offer(Take.single(Text("""{"type":"connection_ack"}"""))) *>
                          Task.whenCase(keepAliveTime) {
                            case Some(time) =>
                              sendQueue
                                .offer(Take.single(Text("""{"type":"ka"}""")))
                                .repeat(Schedule.spaced(time))
                                .provideLayer(Clock.live)
                                .fork
                          }
                      case "connection_terminate" =>
                        sendQueue.offerAll(List(Take.single(Close), Take.end))
                      case "start" =>
                        val payload = msg.hcursor.downField("payload")
                        val id      = msg.hcursor.downField("id").success.flatMap(_.value.asString).getOrElse("")
                        RIO.whenCase(payload.as[GraphQLRequest]) {
                          case Right(req) =>
                            for {
                              result <- interpreter.executeRequest(
                                         req,
                                         skipValidation = skipValidation,
                                         enableIntrospection = enableIntrospection
                                       )
                              _ <- result.data match {
                                    case ObjectValue((fieldName, StreamValue(stream)) :: Nil) =>
                                      stream.foreach { item =>
                                        sendMessage(
                                          sendQueue,
                                          "data",
                                          id,
                                          GraphQLResponse(ObjectValue(List(fieldName -> item)), result.errors).asJson
                                        )
                                      }.onExit {
                                        case Failure(cause) if !cause.interrupted =>
                                          sendMessage(
                                            sendQueue,
                                            "error",
                                            id,
                                            Json.obj("message" -> Json.fromString(cause.squash.toString))
                                          )
                                        case _ =>
                                          sendQueue.offer(Take.single(Text(s"""{"type":"complete","id":"$id"}""")))
                                      }.fork
                                        .flatMap(fiber => subscriptions.update(_.updated(id, fiber)))
                                    case other =>
                                      sendMessage(sendQueue, "data", id, GraphQLResponse(other, result.errors).asJson) *>
                                        sendQueue.offer(
                                          Take.single(Text(s"""{"type":"complete","id":"$id"}"""))
                                        )
                                  }
                            } yield ()
                        }
                      case "stop" =>
                        val id = msg.hcursor.downField("id").success.flatMap(_.value.asString).getOrElse("")
                        subscriptions
                          .modify(map => (map.get(id), map - id))
                          .flatMap(fiber =>
                            IO.whenCase(fiber) {
                              case Some(fiber) => fiber.interrupt
                            }
                          )
                    }
              } yield ()
            }.runDrain
              .mapError(e => BadRequest(e.getMessage))
              .forkDaemon
        ws <- Response
               .websocket(req, ZStream.fromQueue(sendQueue).flattenTake)
               .map(_.addHeaders("Sec-WebSocket-Protocol" -> "graphql-ws"))
      } yield ws
  }

  private def sendMessage(
    sendQueue: Queue[Take[Nothing, Frame]],
    messageType: String,
    id: String,
    payload: Json
  ): UIO[Unit] =
    sendQueue
      .offer(
        Take.single(
          Text(
            Json
              .obj(
                "id"      -> Json.fromString(id),
                "type"    -> Json.fromString(messageType),
                "payload" -> payload
              )
              .noSpaces
          )
        )
      )
      .unit

  private def executeHttpResponse[R, E](
    interpreter: GraphQLInterpreter[R, E],
    request: GraphQLRequest,
    skipValidation: Boolean,
    enableIntrospection: Boolean
  ): URIO[R, Response] =
    interpreter
      .executeRequest(request, skipValidation = skipValidation, enableIntrospection = enableIntrospection)
      .foldCause(cause => GraphQLResponse(NullValue, cause.defects).asJson, _.asJson)
      .map(gqlResult =>
        Response.const(
          gqlResult.noSpaces.getBytes(StandardCharsets.UTF_8),
          Ok,
          contentType = s"application/json; charset=${StandardCharsets.UTF_8.name()}"
        )
      )

}
