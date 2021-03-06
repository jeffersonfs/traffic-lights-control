package trafficlightscontrol

import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef, ActorRefFactory }
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.{ BinaryFrame, TextFrame }
import spray.http.HttpRequest
import spray.can.websocket.FrameCommandFailed
import spray.http.Uri
import spray.can.websocket.`package`.FrameCommand
import spray.http.Uri.Path
import scala.util.matching.Regex
import java.util.regex.Pattern

object ws {
  final case class Push(msg: String)
  final case class Pull(path: String, msg: String)
  final case class Open(path: String, origin: ActorRef)

  type Route[T] = PartialFunction[String, T]
  case class RouteMatcher[T](pattern: String, value: T) extends Route[T] {
    val regex: Pattern = Pattern.compile(pattern.replace("*", ".*?"))
    def apply(path: String): T = applyOrElse(path,{p:String => throw new Exception})
    def isDefinedAt(path: String): Boolean = regex.matcher(path).matches()
    override def applyOrElse[A1 <: String, B1 >: T](x: A1, default: A1 => B1): B1 = if (isDefinedAt(x)) value else default(x)
  }
  object Routes {
    def apply[T](routes: (String, T)*): Route[T] = {
      routes map { case (p, a) => RouteMatcher(p, a) } reduce[Route[T]] { case (f, p) => f orElse p }
    }
  }
}

class WebSocketServiceActor(webSocketRoute: ws.Route[ActorRef], httpListenerActor: ActorRef) extends Actor with ActorLogging {

  def receive = {
    // when a new connection comes in we register a WebSocketConnection actor
    // as the per connection handler
    case Http.Connected(remoteAddress, localAddress) =>
      val serverConnection = sender()
      val conn = context.actorOf(Props(classOf[WebSocketServiceWorker], serverConnection, webSocketRoute, httpListenerActor))
      serverConnection ! Http.Register(conn)
  }
}

class WebSocketServiceWorker(val serverConnection: ActorRef, webSocketRoute: ws.Route[ActorRef], httpListenerActor: ActorRef) extends spray.routing.HttpServiceActor with websocket.WebSocketServerWorker {
  override def receive = preHandshaking orElse handshaking orElse businessLogicNoUpgrade orElse closeLogic

  var path: String = _
  var target: ActorRef = _

  def preHandshaking: Receive = {

    case msg @ websocket.HandshakeRequest(state) =>
      state match {
        case wsFailure: websocket.HandshakeFailure => handshaking(msg)
        case wsContext: websocket.HandshakeContext =>
          path = wsContext.request.uri.path.toString
          webSocketRoute.lift(path) match {
            case Some(ref) => {
              target = ref
              handshaking(msg)
            }
            case None => {
              throw new Exception(s"route for $path does not exists")
            }
          }
      }
  }

  def businessLogic: Receive = {

    case websocket.UpgradedToWebSocket =>
      target ! ws.Open(path, serverConnection)

    case TextFrame(bytes) =>
      target forward ws.Pull(path, bytes.decodeString("utf-8"))

    case BinaryFrame(bytes) =>
      target forward ws.Pull(path, bytes.decodeString("utf-8"))

    case ws.Push(msg) => send(TextFrame(msg))

    case msg: FrameCommandFailed =>
      log.error("frame command failed", msg)

    case msg: HttpRequest => httpListenerActor forward msg
  }

  def businessLogicNoUpgrade: Receive = {
    case msg => httpListenerActor forward msg
  }

}

trait WebSocketProducerActor{
  _:Actor =>
    def push(target:ActorRef, message:String) {
      target ! FrameCommand(TextFrame(message))
    }
}

class EchoWebServiceListenerActor extends Actor {
  def receive = {
    case ws.Pull(path, text) =>
      sender ! ws.Push(s"$path: $text")
  }
}
