package com.hongguotv.nativeapp

import android.app.Application
import android.os.Build
import com.hongguotv.core.HttpTransport
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

class TvApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        if(Build.VERSION.SDK_INT>=24) return
        try { installLegacyRoots() }
        catch(error: Exception) {
            // A vendor TLS provider failure must leave the normal system trust intact.
            android.util.Log.w("TvApplication","Supplemental root setup failed; retaining system TLS",error)
        }
    }

    private fun installLegacyRoots() {
        fun trust(store: KeyStore?): X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(store)}
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val system=trust(null)
        val roots=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null)}
        for(name in listOf("usertrust-ecc","digicert-g2")) assets.open("legacy-roots/$name.pem").use {
            roots.setCertificateEntry(name,CertificateFactory.getInstance("X.509").generateCertificate(it))
        }
        val extra=trust(roots)
        val manager=object: X509TrustManager {
            override fun getAcceptedIssuers()=system.acceptedIssuers+extra.acceptedIssuers
            override fun checkClientTrusted(chain: Array<X509Certificate>,type: String)=system.checkClientTrusted(chain,type)
            override fun checkServerTrusted(chain: Array<X509Certificate>,type: String) {
                try {system.checkServerTrusted(chain,type)}
                catch(original: CertificateException) {
                    try {extra.checkServerTrusted(chain,type)}catch(_: CertificateException){throw original}
                }
            }
        }
        val ssl=SSLContext.getInstance("TLS").apply{init(null,arrayOf<TrustManager>(manager),null)}
        HttpTransport.configure={it.sslSocketFactory(ssl.socketFactory,manager)}
    }
}
