package chatroom.grpc

import com.auth0.jwt.interfaces.DecodedJWT
import io.grpc._
import org.slf4j.LoggerFactory

class JwtClientInterceptor extends ClientInterceptor {

  val logger = LoggerFactory.getLogger(classOf[JwtClientInterceptor])

  override def interceptCall[ReqT, RespT](methodDescriptor: MethodDescriptor[ReqT, RespT], callOptions: CallOptions, channel: Channel) = {
    new ForwardingClientCall.SimpleForwardingClientCall[ReqT, RespT](channel.newCall(methodDescriptor, callOptions)) {
      override def start(responseListener:ClientCall.Listener[RespT], headers:Metadata):Unit = {
        // TODO Convert JWT Context to Metadata header
        super.start(responseListener, headers)
      }
    }
  }
}
