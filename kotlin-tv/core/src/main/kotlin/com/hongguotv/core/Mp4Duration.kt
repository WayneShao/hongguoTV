package com.hongguotv.core

/** Movie duration from mvhd, with strict box boundaries and overflow checks. */
object Mp4Duration {
    fun read(data: ByteArray): Long? = runCatching {
        fun uint(at: Int): Long { require(at>=0 && at+4<=data.size); return java.nio.ByteBuffer.wrap(data,at,4).int.toLong() and 0xffffffffL }
        fun find(start: Int,end: Int,name: String): Int? {
            var at=start
            while(at+8<=end) {
                val size=uint(at); if(size<8 || size>end-at) return null
                if(String(data,at+4,4,Charsets.US_ASCII)==name) return at
                at+=size.toInt()
            }
            return null
        }
        val moov=find(0,data.size,"moov") ?: return null
        val mvhd=find(moov+8,moov+uint(moov).toInt(),"mvhd") ?: return null
        val end=mvhd+uint(mvhd).toInt(); val body=mvhd+8
        val version=data[body].toInt() and 255
        val scaleOffset=body+if(version==1) 20 else 12
        require(version in 0..1 && scaleOffset+(if(version==1) 12 else 8) <= end)
        val scale=uint(scaleOffset)
        val duration=if(version==1) java.nio.ByteBuffer.wrap(data,scaleOffset+4,8).long else uint(scaleOffset+4)
        require(scale>0 && duration>0 && duration<Long.MAX_VALUE/1000)
        (duration*1000/scale).takeIf { it in 1..86_400_000 }
    }.getOrNull()
}
