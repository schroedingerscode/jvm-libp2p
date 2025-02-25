package io.libp2p.etc.util.netty.mux

import io.libp2p.etc.util.netty.AbstractChildChannel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.util.ReferenceCountUtil
import java.net.SocketAddress

/**
 * Alternative effort to start MultistreamChannel implementation from AbstractChannel
 */
class MuxChannel<TData>(
    val parent: AbtractMuxHandler<TData>,
    val id: MuxId,
    var initializer: ChannelHandler? = null
) : AbstractChildChannel(parent.ctx!!.channel(), id) {

    private var remoteDisconnected = false
    private var localDisconnected = false

    override fun metadata(): ChannelMetadata = ChannelMetadata(true)
    override fun localAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().localAddress(), id)

    override fun remoteAddress0() =
        MultiplexSocketAddress(parent.getChannelHandlerContext().channel().remoteAddress(), id)

    override fun doRegister() {
        super.doRegister()
        pipeline().addLast(initializer)
    }

    override fun doWrite(buf: ChannelOutboundBuffer) {
        while (true) {
            val msg = buf.current() ?: break
            try {
                // the msg is released by both onChildWrite and buf.remove() so we need to retain
                // however it is still to be confirmed that no buf leaks happen here TODO
                ReferenceCountUtil.retain(msg)
                parent.onChildWrite(this, msg as TData)
                buf.remove()
            } catch (cause: Throwable) {
                buf.remove(cause)
            }
        }
    }

    override fun doDisconnect() {
        localDisconnected = true
        parent.localDisconnect(this)
        deactivate()
        closeIfBothDisconnected()
    }

    fun onRemoteDisconnected() {
        pipeline().fireUserEventTriggered(RemoteWriteClosed())
        remoteDisconnected = true
        closeIfBothDisconnected()
    }

    override fun doClose() {
        super.doClose()
        parent.onClosed(this)
    }

    override fun onClientClosed() {
        parent.localClose(this)
    }

    private fun closeIfBothDisconnected() {
        if (remoteDisconnected && localDisconnected) closeImpl()
    }
}

class RemoteWriteClosed

data class MultiplexSocketAddress(val parentAddress: SocketAddress, val streamId: MuxId) : SocketAddress() {
    override fun toString(): String {
        return "Mux[$parentAddress-$streamId]"
    }
}
