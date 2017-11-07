package chatroom

import java.io.IOException

import brave.Tracing
import brave.grpc.GrpcTracing
import chatroom.AuthService.AuthenticationServiceGrpc.AuthenticationServiceBlockingStub
import chatroom.AuthService.{AuthenticationRequest, AuthenticationServiceGrpc, AuthorizationRequest}
import chatroom.ChatService._
import chatroom.grpc.{Constant, JwtCallCredential}
import com.typesafe.scalalogging.LazyLogging
import io.grpc._
import io.grpc.stub.{MetadataUtils, StreamObserver}
import zipkin.reporter.AsyncReporter
import zipkin.reporter.urlconnection.URLConnectionSender

import scala.util.Try

case class ChannelManager(authChannel: ManagedChannel, authService: AuthenticationServiceBlockingStub) extends LazyLogging {

  // Channels
  private var optChatChannel: Option[ManagedChannel] = Option.empty[ManagedChannel]
  private var optChatRoomService: Option[ChatRoomServiceGrpc.ChatRoomServiceBlockingStub] = Option.empty[ChatRoomServiceGrpc.ChatRoomServiceBlockingStub]
  private var optToServer: Option[StreamObserver[ChatMessage]] = Option.empty[StreamObserver[ChatMessage]]

  def initChatChannel(token:String, clientOutput: String => Unit): Unit = {
    logger.info("initializing chat services with token: " + token)

    val metadata = new Metadata()
    metadata.put(Constant.JWT_METADATA_KEY, token)

    val reporter = AsyncReporter.create(URLConnectionSender.create("http://localhost:9411/api/v1/spans"))
    val tracing = GrpcTracing.create(Tracing.newBuilder.localServiceName("chat-channel").reporter(reporter).build)

    val chatChannel = ManagedChannelBuilder
      .forTarget("localhost:9092")
      .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
      .intercept(tracing.newClientInterceptor())
      .asInstanceOf[ManagedChannelBuilder[_]]
      .usePlaintext(true)
      .asInstanceOf[ManagedChannelBuilder[_]]
      .build

    optChatChannel = Some(chatChannel)

    val jwtCallCredentials = new JwtCallCredential(token)
    initChatServices(jwtCallCredentials)
    initChatStream(jwtCallCredentials, clientOutput)
  }

  /**
    * Initialize Chat Services
    *
    */
  def initChatServices(jwtCallCredentials: JwtCallCredential): Unit = {
    optChatChannel.foreach { chatChannel =>
      val chatRoomService = ChatRoomServiceGrpc.blockingStub(chatChannel).withCallCredentials(jwtCallCredentials)
      optChatRoomService = Some(chatRoomService)
    }
  }

  /**
    * Initalize Chat Stream
    */
  def initChatStream(jwtCallCredentials: JwtCallCredential, clientOutput: String => Unit): Unit = {

    val streamObserver = new StreamObserver[ChatMessageFromServer] {
      override def onError(t: Throwable): Unit = {
        logger.error("gRPC error", t)
        shutdown()
      }
      override def onCompleted(): Unit = {
        logger.error("server closed connection, shutting down...")
        shutdown()
      }
      override def onNext(chatMessageFromServer: ChatMessageFromServer): Unit = {
        try {
          clientOutput(s"${chatMessageFromServer.getTimestamp.seconds} ${chatMessageFromServer.from}> ${chatMessageFromServer.message}")
        }
        catch {
          case exc: IOException =>
            logger.error("Error printing to console", exc)
          case exc: Throwable => logger.error("grpc exception", exc)
        }
      }
    }

    optChatChannel.foreach { chatChannel =>
      val chatStreamService = ChatStreamServiceGrpc.stub(chatChannel).withCallCredentials(jwtCallCredentials)
      val toServer = chatStreamService.chat(streamObserver)
      optToServer = Some(toServer)
    }
  }

  def shutdown(): Unit = {
    logger.info("Closing Chat Channels")
    optChatChannel.map(chatChannel => chatChannel.shutdown())
    authChannel.shutdown()
  }

  /**
    * Authenticate the username/password with AuthenticationService
    *
    * @param username
    * @param password
    * @return If authenticated, return the authentication token, else, return null
    */
  def authenticate(username: String, password: String, clientOutput: String => Unit): Option[String] = {
    logger.info("authenticating user: " + username)
    (for {
      authenticationResponse <- Try(authService.authenticate(new AuthenticationRequest(username, password)))
      token = authenticationResponse.token
      authorizationResponse <- Try(authService.authorization(new AuthorizationRequest(token)))
    } yield {
      logger.info("user has these roles: " + authorizationResponse.roles)
      token
    }).fold({
      case e: StatusRuntimeException =>
        if (e.getStatus.getCode == Status.Code.UNAUTHENTICATED) {
          logger.error("user not authenticated: " + username, e)
        } else {
          logger.error("caught a gRPC exception", e)
        }
        None
    }, Some(_))
  }

  /**
    * List all the chat rooms from the server
    */
  def listRooms(clientOutput: String => Unit): Unit = {
    logger.info("listing rooms")
    optChatRoomService.foreach {
      chatRoomService =>
        val rooms = chatRoomService.getRooms(Empty.defaultInstance)
        rooms.foreach {
          room =>
            try
              clientOutput("Room: " + room.name)
            catch {
              case e: IOException =>
                e.printStackTrace()
            }
        }
    }
  }

  /**
    * Leave the room
    */
  def leaveRoom(room: String): Unit = {
    logger.info("leaving room: " + room)
    optToServer.foreach {
      chatStreamObserver =>
        val message = ChatMessage(`type` = MessageType.LEAVE, roomName = room)
        chatStreamObserver.onNext(message)
        logger.info("left room: " + room);
    }
  }

  /**
    * Join a Room
    *
    * @param room
    */
  def joinRoom(room: String): Unit = {
    logger.info("joinining room: " + room)
    optToServer.foreach {
      chatStreamObserver =>
        val message = ChatMessage(`type` = MessageType.JOIN, roomName = room)
        chatStreamObserver.onNext(message)
        logger.info("joined room: " + room)
    }
  }

  /**
    * Create Room
    *
    * @param room
    */
  def createRoom(room: String): Unit = {
    logger.info(s"create room: $room  optChatRoomService:$optChatRoomService")

    optChatRoomService.foreach {
      chatRoomService =>
        try {
          logger.info(s"chatRoomService: $chatRoomService")
          chatRoomService.createRoom(Room(name = room))
          logger.info("created room: " + room)
        }
        catch {
          case exc: Throwable => logger.error("Error creating room", exc)
        }
    }
  }

  /**
    * Send a message
    *
    * @param room
    * @param message
    */
  def sendMessage(room: String, message: String): Unit = {
    logger.info("sending chat message")
    optToServer match {
      case Some(toServer) =>
        val chatMessage = ChatMessage(MessageType.TEXT, room, message)
        toServer.onNext(chatMessage)
      case None =>
        logger.info("Not Connected")
    }
  }
}

object ChannelManager extends LazyLogging  {

  /**
    * Initialize a managed channel to connect to the auth service.
    * Set the authChannel and authService
    */
  def apply(): ChannelManager = {
    logger.info("initializing auth service")
    val reporter = AsyncReporter.create(URLConnectionSender.create("http://localhost:9411/api/v1/spans"))
    val tracing = GrpcTracing.create(Tracing.newBuilder.localServiceName("auth-channel").reporter(reporter).build)
    val authChannel: ManagedChannel = ManagedChannelBuilder
      .forTarget("localhost:9091")
      .intercept(tracing.newClientInterceptor())
      .usePlaintext(true)
      .asInstanceOf[ManagedChannelBuilder[_]]
      .build
    val authService: AuthenticationServiceBlockingStub = AuthenticationServiceGrpc.blockingStub(authChannel)
    ChannelManager(authChannel, authService)
  }
}
