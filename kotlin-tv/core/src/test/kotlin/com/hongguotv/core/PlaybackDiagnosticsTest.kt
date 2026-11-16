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
    @Test fun timeoutStagesUseFixedCategoriesWithoutLeakingMessages() {
        val secret="https://host/path?token=secret device_id=123456"
        val expected=mapOf(
            PlaybackDiagnostics.Stage.PREPARATION to "准备超时",
            PlaybackDiagnostics.Stage.PREFETCH_WAIT to "等待预加载超时",
            PlaybackDiagnostics.Stage.TRANSPORT to "传输超时",
            PlaybackDiagnostics.Stage.DEADLINE to "截止时间超时")
        expected.forEach { (stage,label) ->
            val line=PlaybackDiagnostics.line(java.net.SocketTimeoutException(secret),"TV","5.0",21,"0.12.3",false,0,stage)
            assertTrue("$stage -> $line",line.contains(label))
            assertFalse(line.contains("secret"));assertFalse(line.contains("123456"))
        }
    }
    @Test fun deadlineMarkerWinsOverTimeStage() {
        val line=PlaybackDiagnostics.line(PlaybackDeadlineException(),"TV","5.0",21,"0.12.3",false,1,PlaybackDiagnostics.Stage.PREFETCH_WAIT)
        assertTrue(line.contains("截止时间超时"))
    }
    @Test fun typedFailuresKeepTheirExistingCategoryRegardlessOfStage() {
        val stage=PlaybackDiagnostics.Stage.PREPARATION
        assertTrue(PlaybackDiagnostics.line(PlaybackBudgetExceededException(),"TV","5.0",21,"0.12.3",false,3,stage).contains("预算耗尽"))
        assertTrue(PlaybackDiagnostics.line(PlaybackModelException("secret"),"TV","5.0",21,"0.12.3",false,0,stage).contains("播放模型异常"))
        assertTrue(PlaybackDiagnostics.line(DeviceRejectedException("secret"),"TV","5.0",21,"0.12.3",false,0,stage).contains("设备请求被拒绝"))
        assertTrue(PlaybackDiagnostics.line(NoCompatibleStreamException(),"TV","5.0",21,"0.12.3",false,0,stage).contains("资源不兼容"))
        val decoding=PlaybackDiagnostics.line(java.net.SocketTimeoutException("secret"),"TV","5.0",21,"0.12.3",true,0,PlaybackDiagnostics.Stage.TRANSPORT)
        assertTrue(decoding.contains("解码失败"))
    }
    @Test fun nonTimeoutFailuresAreNotReclassifiedByStage() {
        val stage=PlaybackDiagnostics.Stage.PREPARATION
        assertTrue(PlaybackDiagnostics.line(java.net.UnknownHostException("secret"),"TV","5.0",21,"0.12.3",false,0,stage).contains("地址解析失败"))
        assertTrue(PlaybackDiagnostics.line(java.io.IOException("secret"),"TV","5.0",21,"0.12.3",false,0,stage).contains("网络请求失败"))
        assertTrue(PlaybackDiagnostics.line(IllegalStateException("secret"),"TV","5.0",21,"0.12.3",false,0,stage).contains("播放失败"))
        assertTrue(PlaybackDiagnostics.line(java.net.SocketTimeoutException("secret"),"TV","5.0",21,"0.12.3",false,0).contains("网络超时"))
        val cancelled=java.io.InterruptedIOException("请求已取消")
        assertTrue(PlaybackDiagnostics.line(cancelled,"TV","5.0",21,"0.12.3",false,0,stage).contains("网络请求失败"))
    }
    @Test fun okhttpStyleInterruptedTimeoutUsesTheStageCategory() {
        val timeout=java.io.InterruptedIOException("timeout")
        val line=PlaybackDiagnostics.line(timeout,"TV","5.0",21,"0.12.3",false,0,PlaybackDiagnostics.Stage.TRANSPORT)
        assertTrue(line.contains("传输超时"));assertFalse(line.contains("timeout"))
    }
    @Test fun prefetchWaitNoticeIsAFixedCategory() {
        assertEquals("诊断：等待预加载超时",PlaybackDiagnostics.timeoutLine(PlaybackDiagnostics.Stage.PREFETCH_WAIT))
    }
}
