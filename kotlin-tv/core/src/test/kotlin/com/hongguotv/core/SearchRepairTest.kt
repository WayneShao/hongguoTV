package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SearchRepairTest {
    private class Store(var value: String? = null) : PlayerDeviceStore {
        var writes = 0
        override fun load() = value
        override fun save(value: String?) { this.value = value; writes++ }
    }
    @After fun reset() { ContentRepository.searchDeviceStore = null }
    private fun repo(reply: (Request) -> String) = ContentRepository(OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply(it.request()).toResponseBody()).build()
    }.build())
    private val appResult = """{"code":0,"search_tabs":[{"tab_type":11,"has_more":false,"data":[{"video_data":[{"series_id":"123","title":"测试"}]}]}]}"""

    @Test fun websiteFallbackUsesJsonAndKeepsItsPaginationSource() {
        var appCalls = 0
        val repo = repo { r ->
            if (r.url.host.endsWith("fqnovel.com")) { appCalls++; throw IOException("offline") }
            assertEquals("/api/search/result", r.url.encodedPath)
            assertEquals("测试", r.url.queryParameter("query"))
            val page = r.url.queryParameter("page")!!.toInt()
            """{"searchList":[{"video_data":{"series_id":"$page","series_name":"测试"}}],"pageNum":$page,"totalPages":2,"hasMore":${page==1}}"""
        }
        assertTrue(repo.search("测试").hasMore)
        val second = repo.search("测试", 2)
        assertEquals("2", second.items.single().id)
        assertFalse(second.hasMore)
        assertEquals(1, appCalls)
    }
    @Test fun websiteRejectsWrongPageRatherThanRepeatingFirstPage() {
        val repo = repo { r -> if(r.url.host.endsWith("fqnovel.com")) throw IOException("offline")
            """{"searchList":[],"pageNum":1,"hasMore":false}""" }
        assertThrows(IOException::class.java) { repo.websiteSearch("测试", 2) }
    }
    @Test fun websiteMissingListIsNotAnEmptySuccess() {
        val repo = repo { r -> if(r.url.host.endsWith("fqnovel.com")) throw IOException("offline"); "{}" }
        assertThrows(IOException::class.java) { repo.search("测试") }
    }
    @Test fun emptyAppResponseProbesOneRegisteredIdentityThenPersists() {
        val store = Store(); ContentRepository.searchDeviceStore = store
        var registrations = 0; var requests = 0
        val repo = repo { r ->
            if(r.url.encodedPath.contains("device_register")) { registrations++; """{"device_id":1234567890123456789,"install_id":987654321012345678}""" }
            else { requests++; if(r.url.queryParameter("device_id") == "1234567890123456789") appResult else "" }
        }
        assertEquals("123", repo.search("测试").items.single().id)
        assertEquals(1, registrations); assertEquals(1, store.writes)
        assertEquals("1234567890123456789", PlayerDevice.fromJson(store.value!!)!!.deviceId)
        repo.search("测试")
        assertEquals(1, registrations); assertEquals(3, requests); assertEquals(1, store.writes)
    }
    @Test fun unsuccessfulCandidateDoesNotOverwriteOldIdentity() {
        val old = PlayerDevice("123", "456", "", "", "").toJson()
        val store = Store(old); ContentRepository.searchDeviceStore = store
        var registrations = 0
        val repo = repo { r ->
            if(r.url.encodedPath.contains("device_register")) { registrations++; """{"device_id":"789","install_id":"987"}""" } else ""
        }
        assertThrows(IOException::class.java) { repo.search("测试") }
        assertEquals(1, registrations); assertEquals(0, store.writes); assertEquals(old, store.value)
    }
    @Test fun networkFailureNeverRegisters() {
        ContentRepository.searchDeviceStore = Store()
        val repo = repo { r -> assertFalse(r.url.encodedPath.contains("device_register")); throw IOException("timeout") }
        assertThrows(IOException::class.java) { repo.search("测试") }
    }
    @Test fun validEmptyResultDoesNotRegister() {
        val store=Store(); ContentRepository.searchDeviceStore=store
        val repo=repo { r -> assertFalse(r.url.encodedPath.contains("device_register"));
            """{"code":0,"search_tabs":[{"tab_type":11,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("不存在").items.isEmpty()); assertEquals(0,store.writes)
    }
    @Test fun malformedAppResponseDoesNotRegister() {
        ContentRepository.searchDeviceStore = Store()
        var registrations = 0
        val repo=repo { r -> if(r.url.encodedPath.contains("device_register")) registrations++; "<html>error</html>" }
        assertThrows(IOException::class.java) { repo.search("测试") }
        assertEquals(0, registrations)
    }
    @Test fun invalidRegistrationCannotBecomePersistedIdentity() {
        val store=Store(); ContentRepository.searchDeviceStore=store
        val repo=repo { r -> if(r.url.encodedPath.contains("device_register"))
            """{"device_id":0,"install_id":"123abc"}""" else "" }
        assertThrows(IOException::class.java) { repo.search("测试") }
        assertEquals(0,store.writes)
    }
    @Test fun appPaginationNeverSwitchesToWebsiteMidSession() {
        var calls=0
        val repo=repo { r ->
            assertTrue(r.url.host.endsWith("fqnovel.com"))
            calls++
            if(calls>1) throw IOException("timeout")
            appResult.replace("\"has_more\":false", "\"has_more\":true")
        }
        repo.search("测试")
        assertThrows(IOException::class.java) { repo.search("测试",2) }
        assertEquals(2,calls)
    }

}
