package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class PlaybackFailureTest {
    @Test fun onlyKnownLowerTiersAreOffered() {
        assertEquals(listOf(480,360),PlaybackFailure.lowerQualities(StreamInfo("",null,"720P",listOf(1080,720,480,480,360,0),720)))
        assertTrue(PlaybackFailure.lowerQualities(StreamInfo("",null,"自动")).isEmpty())
        assertTrue(PlaybackFailure.lowerQualities(null).isEmpty())
    }
    @Test fun terminalCausesDoNotTurnNetworkTimeoutIntoTerminalError() {
        assertTrue(PlaybackFailure.terminal(java.io.IOException("wrapper",NoCompatibleStreamException())))
        assertTrue(PlaybackFailure.terminal(java.io.InterruptedIOException("请求已取消")))
        assertFalse(PlaybackFailure.terminal(java.net.SocketTimeoutException()))
        assertFalse(PlaybackFailure.terminal(java.io.InterruptedIOException("timeout")))
        assertFalse(PlaybackFailure.terminal(java.io.IOException("network")))
    }
}
