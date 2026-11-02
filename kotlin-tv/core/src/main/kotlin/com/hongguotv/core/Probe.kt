package com.hongguotv.core

fun main(args: Array<String>) {
    if(args.contains("--updates-probe")) {
        val proxy=System.getenv("HONGGUO_PROBE_PROXY_PORT")?.toIntOrNull()
        for((pkg,voice) in listOf("com.hongguotv.nativeapp" to false,"com.hongguotv.rayneo" to false,"com.hongguotv.rayneo" to true)) {
            AppUpdateRepository(pkg,voice) { builder ->
                proxy?.let { port -> builder.proxySelector(object: java.net.ProxySelector() {
                    override fun select(uri: java.net.URI)=listOf(if(uri.host=="api.github.com") java.net.Proxy.NO_PROXY else
                        java.net.Proxy(java.net.Proxy.Type.HTTP,java.net.InetSocketAddress("127.0.0.1",port)))
                    override fun connectFailed(uri: java.net.URI,sa: java.net.SocketAddress,e: java.io.IOException) {}
                }) }
            }.use { repository ->
                val update=repository.latest()
                println("latest variant=${update.fileName} version=${update.versionName} code=${update.versionCode} bytes=${update.size}")
                System.getenv("HONGGUO_APK_DIRECTORY")?.let {dir ->
                    val file=java.io.File(dir,update.fileName)
                    check(UpdateFiles.verify(file,update)) {"Local published APK mismatch: ${update.fileName}"}
                    println("localApkMatches=true")
                }
                if(pkg.endsWith("nativeapp") && args.contains("--download-update")) {
                    val dir=java.nio.file.Files.createTempDirectory("hongguo-update-probe").toFile()
                    try {repository.download(update,java.io.File(dir,"update.apk")) {};println("releaseDownloadVerified=true")}
                    finally {dir.deleteRecursively()}
                }
            }
        }
        return
    }
    if(args.contains("--preload-probe")) {
        val repository=ContentRepository()
        fun slot()=object: PlayerDeviceStore { var value: String?=null; override fun load()=value; override fun save(value: String?) { this.value=value } }
        ContentRepository.searchDeviceStore=slot(); ContentRepository.deviceStore=slot()
        val dir=java.nio.file.Files.createTempDirectory("hongguo-preload-probe").toFile()
        try {
            val series=repository.search("总裁").items.first()
            val detail=repository.detail(series.id)
            RemoteVideo(repository.http,repository.stream(detail.episodes.first(),480)).use { video ->
                video.prepare(); println("preloadProbe durationMs=${video.durationMs()} size=${video.total}")
                video.preload(dir,{false})
                check(video.read(0,16).copyOfRange(4,8).contentEquals("ftyp".toByteArray()))
                println("preloadProbe cachedBytes=${video.cachedSize()} complete=${video.cachedSize()==video.total}")
            }
            check(dir.listFiles().orEmpty().isEmpty())
        } finally {
            ContentRepository.searchDeviceStore=null;ContentRepository.deviceStore=null
            dir.deleteRecursively();repository.http.connectionPool.evictAll();repository.http.dispatcher.executorService.shutdown()
        }
        return
    }

    if(args.contains("--account-qr-only")) {
        val account=AccountRepository()
        try {
            val qr=account.createLoginQr()
            check(qr.image.startsWith("data:image/"))
            println("accountQr imageReceived=true expiresInSeconds=${(qr.expiresAt-System.currentTimeMillis())/1000}")
        } finally { account.cancelLoginQr(); account.http.connectionPool.evictAll(); account.http.dispatcher.executorService.shutdown() }
        return
    }
    if (args.contains("--search-only")) {
        val http=okhttp3.OkHttpClient.Builder().connectTimeout(10,java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request=chain.request()
                val response=chain.proceed(request)
                println("searchProbe ${request.url.host}${request.url.encodedPath} http=${response.code}")
                response
            }.build()
        ContentRepository.searchDeviceStore=object: PlayerDeviceStore {
            var value: String?=null
            override fun load()=value
            override fun save(value: String?) { this.value=value; println("searchProbe candidate validated and committed") }
        }
        val repository=ContentRepository(http)
        try {
            for (keyword in listOf("大佬", "修仙")) {
                val first=repository.search(keyword)
                check(first.items.isNotEmpty()) { "No results for $keyword" }
                println("searchProbe query=$keyword page=1 count=${first.items.size} more=${first.hasMore}")
                if(first.hasMore) {
                    val second=repository.search(keyword,2)
                    val overlap=first.items.map { it.id }.intersect(second.items.map { it.id }.toSet()).size
                    check(second.items.isNotEmpty() && overlap < second.items.size) { "Search page repeated or empty" }
                    println("searchProbe query=$keyword page=2 count=${second.items.size} overlap=$overlap")
                }
            }
        } finally {
            ContentRepository.searchDeviceStore=null
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        }
        return
    }
    val repository=ContentRepository()
    if(args.contains("--ranking")) {
        try {
            val first=repository.comicRanking()
            val metadata=checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen=mutableSetOf<String>()
            for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                val result=if(pageNumber==1) first else repository.comicRanking(pageNumber)
                val ranking=checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore==(pageNumber<ranking.totalPages))
                println("rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
    val search=repository.search(keyword); println("search=${search.items.size}; hasMore=${search.hasMore}")
    if(type==ContentType.COMIC && search.hasMore) {
        val next=repository.search(keyword,2)
        println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if(type==ContentType.COMIC) {
        val next=repository.home(2,type)
        println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream=repository.stream(detail.episodes.first())
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
