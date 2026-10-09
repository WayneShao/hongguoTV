package com.hongguotv.core

object StereoGeometry {
    fun scale(videoAspect: Float, eyeWidth: Int, height: Int, mode: Int): Pair<Float, Float> {
        if (!videoAspect.isFinite() || videoAspect <= 0 || eyeWidth <= 0 || height <= 0) return 1f to 1f
        val outputAspect = eyeWidth.toFloat() / height
        return when (mode) {
            0 -> if (videoAspect > outputAspect) 1f to outputAspect/videoAspect else videoAspect/outputAspect to 1f
            4 -> if (videoAspect > outputAspect) videoAspect/outputAspect to 1f else 1f to outputAspect/videoAspect
            else -> 1f to 1f
        }
    }
}
