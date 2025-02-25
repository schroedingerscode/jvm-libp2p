package io.libp2p.network

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Network
import io.libp2p.core.PeerId
import io.libp2p.core.TransportNotSupportedException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.transport.Transport
import io.libp2p.etc.types.anyComplete
import io.libp2p.etc.types.toVoidCompletableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class NetworkImpl(
    override val transports: List<Transport>,
    override val connectionHandler: ConnectionHandler
) : Network {

    /**
     * The connection table.
     */
    override val connections = CopyOnWriteArrayList<Connection>()

    init {
        transports.forEach(Transport::initialize)
    }

    override fun close(): CompletableFuture<Unit> {
        val futs = transports.map(Transport::close)
        return CompletableFuture.allOf(*futs.toTypedArray())
            .thenCompose {
                val connCloseFuts = connections.map { it.nettyChannel.close().toVoidCompletableFuture() }
                CompletableFuture.allOf(*connCloseFuts.toTypedArray())
            }.thenApply { }
    }

    override fun listen(addr: Multiaddr): CompletableFuture<Unit> =
        getTransport(addr).listen(addr, createHookedConnHandler(connectionHandler))
    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> = getTransport(addr).unlisten(addr)
    override fun disconnect(conn: Connection): CompletableFuture<Unit> =
        conn.nettyChannel.close().toVoidCompletableFuture()

    private fun getTransport(addr: Multiaddr) =
        transports.firstOrNull { tpt -> tpt.handles(addr) }
            ?: throw TransportNotSupportedException("no transport to handle addr: $addr")

    private fun createHookedConnHandler(handler: ConnectionHandler) =
        ConnectionHandler.createBroadcast(listOf(
            handler,
            ConnectionHandler.create { conn ->
                connections += conn
                conn.closeFuture().thenAccept { connections -= conn }
            }
        ))

    override fun connect(
        id: PeerId,
        vararg addrs: Multiaddr
    ): CompletableFuture<Connection> {

        // we already have a connection for this peer, short circuit.
        connections.find { it.secureSession.remoteId == id }
            ?.apply { return CompletableFuture.completedFuture(this) }

        // 1. check that some transport can dial at least one addr.
        // 2. trigger dials in parallel via all transports.
        // 3. when the first dial succeeds, cancel all pending dials and return the connection. // TODO cancel
        // 4. if no emitted dial succeeds, or if we time out, fail the future. make sure to cancel
        //    pending dials to avoid leaking.
        val connectionFuts = addrs.mapNotNull { addr ->
            transports.firstOrNull { tpt -> tpt.handles(addr) }?.let { addr to it }
        }.map {
            it.second.dial(it.first, createHookedConnHandler(connectionHandler))
        }
        return anyComplete(connectionFuts)
    }
}