// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.AuthSession
import com.hongguotv.core.AccountRepository
import org.json.JSONObject

/**
 * 登录会话的本地存档。
 *
 * Cookie 与 X-Tt-Token 只写入应用私有 SharedPreferences，不写日志、不放进错误提示。
 * 退出登录或服务端返回未登录时，由界面清掉这份存档。
 */
internal object AuthStore {
    private const val FILE="native-auth-v1"
    private const val KEY_SESSION="session"

    fun restore(context: Context,repository: AccountRepository) {
        val prefs=context.applicationContext.getSharedPreferences(FILE,Context.MODE_PRIVATE)
        val raw=prefs.getString(KEY_SESSION,"").orEmpty()
        if(raw.isBlank()) { repository.restoreSession(null); return }
        val session=runCatching { AuthSession.fromJson(JSONObject(raw)) }.getOrNull()
        if(session!=null) repository.restoreSession(session)
        else prefs.edit().remove(KEY_SESSION).apply()
    }

    fun save(context: Context,session: AuthSession) {
        context.applicationContext.getSharedPreferences(FILE,Context.MODE_PRIVATE).edit()
            .putString(KEY_SESSION,session.toJson().toString()).apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(FILE,Context.MODE_PRIVATE).edit()
            .remove(KEY_SESSION).apply()
    }
}
