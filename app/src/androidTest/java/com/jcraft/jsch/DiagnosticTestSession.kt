package com.jcraft.jsch

/** Package-local JSch constructor is accessible here; this session never creates a socket. */
class DiagnosticTestSession(private val testChannel: ChannelExec) :
    Session(JSch(), "diagnostic-test", "127.0.0.1", 22) {
    @Volatile private var live = true
    override fun openChannel(type: String): Channel = testChannel
    override fun isConnected(): Boolean = live
    override fun disconnect() { live = false }
}
