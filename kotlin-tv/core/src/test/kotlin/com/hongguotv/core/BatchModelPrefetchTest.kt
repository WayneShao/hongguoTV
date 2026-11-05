package com.hongguotv.core

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class BatchModelPrefetchTest {
    private fun stream()=StreamInfo("https://example.invalid/video",null,"720P")
    @Test fun cacheIsSingleUseBoundedAndExpires() {
        var now=0L
        val cache=BatchModelCache {now}
        (1..7).forEach {cache.put("device","$it",720,stream())}
        assertNull(cache.take("device","1",720))
        assertNull(cache.take("other","2",720))
        assertNotNull(cache.take("device","2",720))
        assertNull(cache.take("device","2",720))
        now=120_000
        assertNull(cache.take("device","3",720))
    }
    @Test fun batchRequiresGateUsesThreeAndAcceptsOnlyRequestedIds() {
        var now=10_000L
        val gate=PreloadGate {now}
        val cache=BatchModelCache {now}
        val requests=mutableListOf<List<String>>()
        val batch=BatchModelPrefetch(Executor {it.run()},gate,{"device"},{720},
            {_,ids,_ -> requests.add(ids);mapOf(ids[0] to stream(),"foreign" to stream())},cache,{now})
        batch.update((1..15).map {"$it"})
        assertFalse(batch.pump())
        batch.configure(PreloadSettings(enabled=true,batchModels=true))
        assertFalse(batch.pump())
        gate.update(true,30_000,60_000)
        assertTrue(batch.pump())
        assertEquals(listOf("1","2","3"),requests.single())
        assertFalse(batch.pump())
        assertNull(cache.take("device","foreign",720))
        assertNotNull(cache.take("device","1",720))
        batch.update(listOf("2","3","4"))
        assertTrue(batch.pump())
        batch.configure(PreloadSettings(enabled=false))
        assertNull(cache.take("device","2",720))
    }
    @Test fun reopenNormalizesKeysPreservesOverlapAndRefreshesExpiredWindow() {
        var now=10_000L;var owner="device"
        val gate=PreloadGate {now};gate.update(true,30_000,60_000)
        val cache=BatchModelCache {now};val requests=mutableListOf<List<String>>()
        val batch=BatchModelPrefetch(Executor {it.run()},gate,{owner},{720},
            {_,ids,_ ->requests.add(ids);ids.associateWith {stream()}},cache,{now})
        batch.configure(PreloadSettings(enabled=true,batchModels=true));batch.update(listOf("1:720","2:720","3:720"))
        assertTrue(batch.pump());assertEquals(listOf("1","2","3"),requests.last())
        batch.update(listOf("2:720","3:720","4:720"))
        assertNotNull(cache.take(owner,"1",720));assertNotNull(cache.take(owner,"2",720))
        assertTrue(batch.pump());assertFalse(batch.pump())
        now+=120_000;assertTrue(batch.pump())
        owner="new-device";batch.update(listOf("2:720"))
        assertNull(cache.take("device","3",720))
        batch.close();assertFalse(batch.pump())
        batch.configure(PreloadSettings(enabled=true,batchModels=true));assertTrue(batch.pump())
    }
    @Test fun failureCooldownAndCancelledQueuedWork() {
        var now=10_000L
        val gate=PreloadGate {now};gate.update(true,30_000,60_000)
        val tasks=mutableListOf<Runnable>();var calls=0
        val batch=BatchModelPrefetch(Executor {tasks.add(it)},gate,{"device"},{720},
            {_,_,_ ->calls++;throw java.io.IOException()},clock={now})
        batch.configure(PreloadSettings(enabled=true,batchModels=true));batch.update(listOf("1"))
        assertTrue(batch.pump());assertFalse(batch.pump())
        batch.yieldToForeground();tasks.removeAt(0).run();assertEquals(0,calls)
        now+=4_000;assertTrue(batch.pump());tasks.removeAt(0).run()
        assertFalse(batch.pump());now+=30_000;assertTrue(batch.pump())
        batch.close();tasks.removeAt(0).run();assertEquals(1,calls)
    }
}
