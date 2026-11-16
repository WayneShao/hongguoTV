// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class BufferingDiagnosticsTest {
    private fun recorder(): Pair<BufferingDiagnostics,MutableList<String>> {
        val lines=mutableListOf<String>()
        return BufferingDiagnostics { lines+=it } to lines
    }

    @Test fun entryExitEmitsOneIntervalWithDurationAndNumericContext() {
        val (diagnostics,lines)=recorder()
        diagnostics.state(2,1_000,true,true,0)
        diagnostics.state(2,1_400,true,true,500)
        diagnostics.state(2,1_900,false,true,800)
        assertEquals(2,lines.size)
        assertTrue(lines[0].contains("第 2 集 开始"));assertTrue(lines[0].contains("已缓存 0 ms"))
        assertTrue(lines[1].contains("第 2 集 结束"));assertTrue(lines[1].contains("耗时 900 ms"))
        assertTrue(lines[1].contains("已缓存 800 ms"));assertTrue(lines[1].contains("就绪"))
    }

    @Test fun repeatedBufferingEventsDoNotDuplicateTheInterval() {
        val (diagnostics,lines)=recorder()
        repeat(5) { diagnostics.state(1,100L+it,true,true,0) }
        assertEquals(1,lines.size)
        diagnostics.close(200)
        assertEquals(2,lines.size)
    }

    @Test fun episodeSwitchClosesPreviousIntervalBeforeTrackingTheNext() {
        val (diagnostics,lines)=recorder()
        diagnostics.state(1,0,true,true,0)
        diagnostics.switchEpisode(2,100)
        assertEquals(2,lines.size);assertTrue(lines[1].contains("第 1 集 结束"));assertTrue(lines[1].contains("耗时 100 ms"));assertTrue(lines[1].contains("切集"))
        diagnostics.state(2,150,true,true,0)
        diagnostics.state(2,260,false,true,0)
        assertEquals(4,lines.size)
        assertTrue(lines[2].contains("第 2 集 开始"))
        assertTrue(lines[3].contains("第 2 集 结束"));assertTrue(lines[3].contains("耗时 110 ms"))
    }

    @Test fun pauseEndsTheIntervalAndResumingStartsANewOne() {
        val (diagnostics,lines)=recorder()
        diagnostics.state(3,0,true,true,0)
        diagnostics.state(3,120,true,false,0)
        diagnostics.state(3,300,true,true,0)
        diagnostics.state(3,450,false,true,0)
        assertEquals(4,lines.size)
        assertTrue(lines[1].contains("耗时 120 ms"));assertTrue(lines[1].contains("暂停"))
        assertTrue(lines[2].contains("开始"));assertTrue(lines[3].contains("耗时 150 ms"))
    }

    @Test fun closeOnErrorAndTeardownResetsWithoutCarryOverOrDuplicateEnds() {
        val (diagnostics,lines)=recorder()
        diagnostics.state(4,0,true,true,0)
        diagnostics.close(90,BufferingDiagnostics.Stop.ERROR)
        diagnostics.close(120)
        assertEquals(2,lines.size);assertTrue(lines[1].contains("第 4 集 结束"));assertTrue(lines[1].contains("错误"))
        diagnostics.state(5,300,true,true,0)
        diagnostics.close(350)
        assertEquals(4,lines.size)
        assertTrue(lines[2].contains("第 5 集 开始"));assertTrue(lines[3].contains("第 5 集 结束"))
        assertTrue(lines[3].contains("耗时 50 ms"))
    }

    @Test fun emittedLinesContainOnlyOrdinalNumbersAndFixedText() {
        val (diagnostics,lines)=recorder()
        diagnostics.state(1,0,true,true,123)
        diagnostics.state(1,100,false,true,456)
        lines.forEach { line ->
            assertTrue(line,Regex("""^缓冲诊断：第 \d+ 集 (开始|结束) · .+$""").matches(line))
            assertFalse(line,line.contains("http"));assertFalse(line,line.contains("id="))
        }
        diagnostics.state(6,200,true,true,0)
        diagnostics.close(201)
        assertTrue(lines.last(),Regex("""^缓冲诊断：第 6 集 结束 · 耗时 1 ms · 已缓存 0 ms · 原因 关闭$""").matches(lines.last()))
    }
}
