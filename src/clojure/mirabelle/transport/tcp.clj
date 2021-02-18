;; This code is from the Riemann code base
;; Copyright Riemann authors (riemann.io), thanks to them!
(ns mirabelle.transport.tcp
  (:import [java.net InetSocketAddress]
           (javax.net.ssl SSLContext)
           [io.netty.bootstrap ServerBootstrap]
           [io.netty.channel ChannelOption
                             ChannelHandlerContext
                             ChannelFutureListener
                             ChannelInboundHandlerAdapter]
           [io.netty.channel.group ChannelGroup]
           [io.netty.handler.codec LengthFieldBasedFrameDecoder
                                   LengthFieldPrepender]
           [io.netty.handler.ssl SslHandler]
           [io.netty.channel.epoll EpollEventLoopGroup EpollServerSocketChannel]
           [io.netty.channel.kqueue KQueueEventLoopGroup KQueueServerSocketChannel]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.channel.socket.nio NioServerSocketChannel])
  (:require [less.awful.ssl :as ssl]
            [com.stuartsierra.component :as component]
            [corbihttp.log :as log]
            [mirabelle.stream :as stream]
            [mirabelle.action :as a]
            [mirabelle.transport :as transport]))

(defn int32-frame-decoder
  []
  ; Offset 0, 4 byte header, skip those 4 bytes.
  (LengthFieldBasedFrameDecoder. Integer/MAX_VALUE, 0, 4, 0, 4))

(defn int32-frame-encoder
  []
  (LengthFieldPrepender. 4))

(defn gen-tcp-handler
  "Wraps Netty boilerplate for common TCP server handlers. Given a reference to
  a core, a stats package, a channel group, and a handler fn, returns a
  ChannelInboundHandlerAdapter which calls (handler core stats
  channel-handler-context message) for each received message.

  Automatically handles channel closure, and handles exceptions thrown by the
  handler by logging an error and closing the channel."
  [stream-handler ^ChannelGroup channel-group handler]
  (proxy [ChannelInboundHandlerAdapter] []
    (channelActive [ctx]
      (.add channel-group (.channel ctx)))

    (channelRead [^ChannelHandlerContext ctx ^Object message]
      (try
        (handler stream-handler ctx message)
        (catch java.nio.channels.ClosedChannelException _
          (log/info {} "channel closed"))))

    (exceptionCaught [^ChannelHandlerContext ctx ^Throwable cause]
      (log/error {} cause "TCP handler caught")
      (.close (.channel ctx)))

    (isSharable [] true)))

(defn kqueue-netty-implementation
   []
   {:event-loop-group-fn #(KQueueEventLoopGroup.)
     :channel KQueueServerSocketChannel})

(defn epoll-netty-implementation
   []
   {:event-loop-group-fn #(EpollEventLoopGroup.)
     :channel EpollServerSocketChannel})

(defn nio-netty-implementation
   []
   {:event-loop-group-fn #(NioEventLoopGroup.)
     :channel NioServerSocketChannel})

(def netty-implementation
  (let [mac-or-freebsd? (re-find #"(mac|freebsd)" (System/getProperty "os.name"))
       linux? (re-find  #"linux" (System/getProperty "os.name"))
       sfbit? (re-find #"(x86_64|amd64)" (System/getProperty "os.arch"))
       native?  (= (System/getProperty "netty.native.implementation") "true")]
    (cond (and native? sfbit? linux?) (epoll-netty-implementation)
             (and native? sfbit? mac-or-freebsd?) (kqueue-netty-implementation)
             :else (nio-netty-implementation))))

(defn tcp-handler
  "Given a core, a channel, and a message, applies the message to core and
  writes a response back on this channel."
  [stream-handler ^ChannelHandlerContext ctx ^Object message]
  (let [t1 (:decode-time message)]
    (.. ctx
      ; Actually handle request
      (writeAndFlush (transport/handle stream-handler message))

      ; Record time from parse to write completion
      (addListener
        (reify ChannelFutureListener
          (operationComplete [this fut]
            (comment (metrics/update! stats
                                      (- (System/nanoTime) t1)))
            nil))))))

(defn ssl-handler
  "Given an SSLContext, creates a new SSLEngine and a corresponding Netty
  SslHandler wrapping it."
  [^SSLContext context]
  (-> context
    .createSSLEngine
    (doto (.setUseClientMode false)
          (.setNeedClientAuth true))
    SslHandler.
    ; TODO: Where did this go in 4.0.21?
    ; (doto (.setEnableRenegotiation false))
    ))

(defn build-initializer
  "A channel pipeline initializer for a TCP server."
  [stream-handler shared-event-executor channel-group ssl-context]
  ; Gross hack; should re-work the pipeline macro
  (if ssl-context
    (transport/channel-initializer
               ssl                 (ssl-handler ssl-context)
               int32-frame-decoder (int32-frame-decoder)
      ^:shared int32-frame-encoder (int32-frame-encoder)
      ^:shared protobuf-decoder    (transport/protobuf-decoder)
      ^:shared protobuf-encoder    (transport/protobuf-encoder)
      ^:shared msg-decoder         (transport/msg-decoder)
      ^:shared msg-encoder         (transport/msg-encoder)
      ^{:shared true :executor shared-event-executor} handler
      (gen-tcp-handler stream-handler channel-group tcp-handler))

    (transport/channel-initializer
               int32-frame-decoder  (int32-frame-decoder)
      ^:shared int32-frame-encoder  (int32-frame-encoder)
      ^:shared protobuf-decoder     (transport/protobuf-decoder)
      ^:shared protobuf-encoder     (transport/protobuf-encoder)
      ^:shared msg-decoder          (transport/msg-decoder)
      ^:shared msg-encoder          (transport/msg-encoder)
      ^{:shared true :executor shared-event-executor} handler
      (gen-tcp-handler stream-handler channel-group tcp-handler))))

(defrecord TCPServer [host ;; config
                      port ;; config
                      key ;; config
                      cert ;; config
                      cacert ;;config
                      so-backlog ;; config
                      channel-group ;; runtime
                      initializer ;; runtime
                      killer ;; runtime
                      registry ;; dependency
                      stream-handler ;; dependency
                      shared-event-executor ;; dependency
                      ]
  component/Lifecycle
  (start [this]
    (log/info {} "starting" (str "tcp-server " host ":" port))
    (let [killer (atom nil)
          channel-grp (transport/channel-group
                       (str "tcp-server " host ":" port))

          initializer (if (and key cert cacert)
                        (let [ssl-context (ssl/ssl-context
                                           key cert cacert)]
                          (build-initializer stream-handler
                                             shared-event-executor
                                             channel-grp
                                             ssl-context))

                        ;; A standard handler
                        (build-initializer stream-handler
                                           shared-event-executor
                                           channel-grp
                                           nil))]
      (locking transport/ioutil-lock
        (locking this
          (when-not @killer
            (let [event-loop-group-fn (:event-loop-group-fn
                                       netty-implementation)
                  boss-group (event-loop-group-fn)
                  worker-group (event-loop-group-fn)
                  bootstrap (ServerBootstrap.)]
                                        ; Configure bootstrap
              (doto bootstrap
                (.group boss-group worker-group)
                (.channel (:channel netty-implementation))
                (.option ChannelOption/SO_REUSEADDR true)
                (.option ChannelOption/SO_BACKLOG so-backlog)
                (.childOption ChannelOption/SO_REUSEADDR true)
                (.childOption ChannelOption/SO_KEEPALIVE true)
                (.childHandler initializer))
              ;; Start bootstrap
              (->> (InetSocketAddress. host (int port))
                   (.bind bootstrap)
                   (.sync)
                   (.channel)
                   (.add channel-grp))
              (log/info {} "TCP server" host port "online")
              ;; fn to close server
              (reset! killer
                      (fn killer []
                        (.. channel-grp close awaitUninterruptibly)
                        ;; Shut down workers and boss concurrently.
                        (let [w (transport/shutdown-event-executor-group worker-group)
                              b (transport/shutdown-event-executor-group boss-group)]
                          @w
                          @b)
                        (log/info {} "TCP server" host port "shut down")))))))
      (assoc this :channel-group channel-grp :initializer initializer :killer killer)))

  (stop [this]
         (locking this
           (when @killer
             (@killer)
             (reset! killer nil)))))