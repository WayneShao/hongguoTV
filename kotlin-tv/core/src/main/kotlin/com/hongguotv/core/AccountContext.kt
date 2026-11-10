// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.security.MessageDigest
import java.util.Collections
import org.json.JSONArray

/** Captured identity; no credentials are included in diagnostic output. */
class AccountContext internal constructor(
    session: AuthSession,
    val epoch: Long,
    val owner: String?,
    val legacyOwner: String,
    val credentialOwner: String,
) {
    val session=session.copy(cookies=Collections.unmodifiableMap(LinkedHashMap(session.cookies)))
    override fun toString()="AccountContext(epoch=$epoch,resolved=${owner!=null})"
}

data class FavoriteMembership(val ids: Set<String>,val complete: Boolean=false)

internal fun accountHash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
internal fun canonicalAccountId(value: String)=value.takeIf { it.matches(Regex("[0-9]{1,30}")) && it.any { c -> c!='0' } }
internal fun credentialOwner(session: AuthSession): String = accountHash(JSONArray().put(session.user.id).put(session.token)
    .put(JSONArray(session.cookies.toSortedMap().map { (key,value) -> JSONArray().put(key).put(value) })).toString())
