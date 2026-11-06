package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test fun exceptionSecretsNeverReachDiagnosticLine() {
        val secret="https://host/path?token=secret Cookie: session=private device_id=123456"
        val line=PlaybackDiagnostics.line(java.io.IOException(secret),"TV","5.0",21,"0.12.3",false,2)
        assertFalse(line.contains("secret"));assertFalse(line.contains("private"));assertFalse(line.contains("123456"))
        assertTrue(line.contains("网络"));assertTrue(line.contains("API 21"));assertTrue(line.contains("已自动重试 2/3"))
    }
    @Test fun fieldsAreBoundedAndControlCharactersRemoved() {
        val line=PlaybackDiagnostics.line(NoCompatibleStreamException(),"x".repeat(400)+"\n","5\n",21,"v".repeat(400),false,99)
        assertTrue(line.contains("已自动重试 3/3"))
        assertTrue(line.length<180);assertFalse(line.contains('\n'));assertTrue(line.contains("不兼容"))
    }
}
