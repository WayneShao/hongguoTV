// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Known-cover compatibility for the fqnovelpic object store.
 *
 * Account endpoints (favorites / history / reservations) return signed HEIC URLs from
 * `*.fqnovelpic.com`. HEIF decoding needs API 28+, so those covers stay blank on
 * Android 7/8 TVs. The same `novel-pic/{hash}` object is served as a JPEG from
 * byteimg with the website's shrink template, so only that exact CDN shape is
 * rewritten. Everything else, including lookalike hosts, malformed hashes and
 * unrelated public artwork, passes through unchanged.
 */
object CoverArt {
    private const val MARKER = "/novel-pic/"
    private val HASH = Regex("[0-9a-fA-F]{16,64}")

    /** Cover to persist and display. Returns the cleaned input when it is not a known shape. */
    fun forDisplay(raw: String): String {
        val cleaned = cleanUrl(raw).trim()
        return compatible(cleaned) ?: cleaned
    }

    /**
     * Compatible fallback for rows already persisted with a signed source URL, or null
     * when the input is not the exact known source. Never returns a fallback for the
     * fallback host itself, so recovery cannot loop.
     */
    fun fallbackUrl(raw: String): String? = compatible(cleanUrl(raw).trim())

    private fun compatible(value: String): String? {
        if (value.isEmpty()) return null
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.port != 443) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val host = url.host
        if (host != "fqnovelpic.com" && !host.endsWith(".fqnovelpic.com")) return null
        val path = url.encodedPath
        if (!path.startsWith(MARKER)) return null
        val key = path.substring(MARKER.length)
        if (key.isEmpty() || '/' in key) return null
        val hash = key.substringBefore('~')
        if (!HASH.matches(hash)) return null
        return "https://p3-novel.byteimg.com/novel-pic/$hash~tplv-shrink:640:0.image"
    }
}
