package com.hongguotv.core

/** Application installs narrowly scoped platform compatibility before creating clients. */
object HttpTransport {
    @Volatile var configure: (okhttp3.OkHttpClient.Builder)->Unit = {}
    fun builder()=okhttp3.OkHttpClient.Builder().also(configure)
}
