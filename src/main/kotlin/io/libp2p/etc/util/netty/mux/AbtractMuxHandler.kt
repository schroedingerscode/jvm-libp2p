package io.libp2p.etc.util.netty.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.etc.IS_INITIATOR
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.util.netty.nettyInitializer
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.concurrent.CompletableFuture
import java.util.function.Function

typealias MuxChannelInitializer<TData> = (MuxChannel<TData>) -> Unit

abstract class AbtractMuxHandler<TData>(var inboundInitializer: MuxChannelInitializer<TData>? = null) :
    ChannelInboundHandlerAdapter() {

    private val streamMap: MutableMap<MuxId, MuxChannel<TData>> = mutableMapOf()
    var ctx: ChannelHandlerContext? = null
    private val activeFuture = CompletableFuture<Void>()
    private var closed = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        super.handlerAdded(ctx)
        this.ctx = ctx
    }

    override fun channelActive(ctx: ChannelHandlerContext?) {
        activeFuture.complete(null)
        super.channelActive(ctx)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        activeFuture.completeExceptionally(ConnectionClosedException())
        closed = true
        super.channelUnregistered(ctx)
    }

    fun getChannelHandlerContext(): ChannelHandlerContext {
        return ctx ?: throw Libp2pException("Internal error: handler context should be initialized at this stage")
    }

    protected fun childRead(id: MuxId, msg: TData) {
        val child = streamMap[id] ?: throw Libp2pException("Channel with id $id not opened")
        child.pipeline().fireChannelRead(msg)
    }

    abstract fun onChildWrite(child: MuxChannel<TData>, data: TData): Boolean

    protected fun onRemoteOpen(id: MuxId) {
        val initializer = inboundInitializer ?: throw Libp2pException("Illegal state: inbound stream handler is not set up yet")
        val child = createChild(id, nettyInitializer {
            initializer(it as MuxChannel<TData>)
        }, false)
        onRemoteCreated(child)
    }

    protected fun onRemoteDisconnect(id: MuxId) {
        // the channel could be RESET locally, so ignore remote CLOSE
        streamMap[id]?.onRemoteDisconnected()
    }

    protected fun onRemoteClose(id: MuxId) {
        // the channel could be RESET locally, so ignore remote RESET
        streamMap[id]?.closeImpl()
    }

    fun localDisconnect(child: MuxChannel<TData>) {
        onLocalDisconnect(child)
    }

    fun localClose(child: MuxChannel<TData>) {
        onLocalClose(child)
    }

    fun onClosed(child: MuxChannel<TData>) {
        streamMap.remove(child.id)
    }

    abstract override fun channelRead(ctx: ChannelHandlerContext, msg: Any)
    protected open fun onRemoteCreated(child: MuxChannel<TData>) {}
    protected abstract fun onLocalOpen(child: MuxChannel<TData>)
    protected abstract fun onLocalClose(child: MuxChannel<TData>)
    protected abstract fun onLocalDisconnect(child: MuxChannel<TData>)

    private fun createChild(id: MuxId, initializer: ChannelHandler, initiator: Boolean): MuxChannel<TData> {
        val child = MuxChannel(this, id, initializer)
        child.attr(IS_INITIATOR).set(initiator)
        streamMap[id] = child
        ctx!!.channel().eventLoop().register(child)
        return child
    }

    protected open fun createChannel(id: MuxId, initializer: ChannelHandler) = MuxChannel(this, id, initializer)

    protected abstract fun generateNextId(): MuxId

    fun newStream(outboundInitializer: MuxChannelInitializer<TData>): CompletableFuture<MuxChannel<TData>> {
        try {
            checkClosed() // if already closed then event loop is already down and async task may never execute
            return activeFuture.thenApplyAsync(Function {
                checkClosed() // close may happen after above check and before this point
                val child = createChild(generateNextId(), nettyInitializer {
                    it as MuxChannel<TData>
                    onLocalOpen(it)
                    outboundInitializer(it)
                }, true)
                child
            }, getChannelHandlerContext().channel().eventLoop())
        } catch (e: Exception) {
            return completedExceptionally(e)
        }
    }

    private fun checkClosed() = if (closed) throw ConnectionClosedException("Can't create a new stream: connection was closed: " + ctx!!.channel()) else Unit
}
