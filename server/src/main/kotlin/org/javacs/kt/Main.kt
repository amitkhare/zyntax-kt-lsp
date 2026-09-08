package org.javacs.kt

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.eclipse.lsp4j.launch.LSPLauncher
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.tcpStartServer
import org.javacs.kt.util.tcpConnectToClient
import kotlin.system.exitProcess

class Args {
    /*
     * The language server can currently be launched in three modes:
     *  - Stdio, in which case no argument should be specified (used by default)
     *  - TCP Server, in which case the client has to connect to the specified tcpServerPort (used by the Docker image)
     *  - TCP Client, in which case the server will connect to the specified tcpClientPort/tcpClientHost (optionally used by VSCode)
     */

    @Parameter(names = ["--tcpServerPort", "-sp"])
    var tcpServerPort: Int? = null
    @Parameter(names = ["--tcpClientPort", "-p"])
    var tcpClientPort: Int? = null
    @Parameter(names = ["--tcpClientHost", "-h"])
    var tcpClientHost: String = "localhost"
    @Parameter(names = ["--version", "-V"])
    var versionCheck: Boolean = false
}

fun main(argv: Array<String>) {
    // Redirect java.util.logging calls (e.g. from LSP4J)
    LOG.connectJULFrontend()

    val args = Args().also { JCommander.newBuilder().addObject(it).build().parse(*argv) }

    if (args.versionCheck) {
        println(System.getProperty("kotlinLanguageServer.version") ?: "unknown")
        return
    }

    val (inStream, outStream) = args.tcpClientPort?.let {
        // Launch as TCP Client
        LOG.connectStdioBackend()
        tcpConnectToClient(args.tcpClientHost, it)
    } ?: args.tcpServerPort?.let {
        // Launch as TCP Server
        LOG.connectStdioBackend()
        tcpStartServer(it)
    } ?: run {
        // Launch as stdio (default)
        // NOTE: Do NOT call LOG.connectStdioBackend() for stdio transport --
        // stdout IS the LSP transport stream. Raw log text would corrupt the
        // JSON-RPC protocol. Log messages during initialization are buffered in
        // the Logger's outQueue and will be flushed via window/logMessage
        // notifications AFTER the InitializeResult response is sent (see
        // KotlinLanguageServer.initialize()).
        Pair(System.`in`, System.out)
    }

    val exitCode = try {
        KotlinLanguageServer().use { server ->
            val threads = Executors.newSingleThreadExecutor { Thread(it, "client") }
            try {
                val launcher = LSPLauncher.createServerLauncher(server, inStream, outStream, threads) { it }
                server.connect(launcher.remoteProxy)
                val listening = launcher.startListening()
                val transportEnded = CompletableFuture<Int>()
                Thread.startVirtualThread {
                    try {
                        listening.get()
                        transportEnded.complete(1)
                    } catch (error: Exception) {
                        transportEnded.completeExceptionally(error)
                    }
                }
                try {
                    server.exitStatus.applyToEither(transportEnded) { it }.get()
                } finally {
                    listening.cancel(true)
                }
            } finally {
                threads.shutdownNow()
            }
        }
    } catch (error: Exception) {
        error.printStackTrace(System.err)
        1
    } finally {
        AsyncExecutor.shutdown(false)
        LOG.shutdown()
    }
    exitProcess(exitCode)
}
