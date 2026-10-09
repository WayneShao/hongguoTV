package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class StereoGeometryTest {
    @Test fun landscapeFitsWithoutDistortion() {
        val (x,y)=StereoGeometry.scale(16f/9,640,480,0)
        assertEquals(1f,x,.0001f); assertEquals(.75f,y,.0001f)
    }
    @Test fun portraitRemainsComplete() {
        val (x,y)=StereoGeometry.scale(9f/16,640,480,0)
        assertEquals(.421875f,x,.0001f); assertEquals(1f,y,.0001f)
    }
    @Test fun zoomCropsAndFillStretches() {
        val (x,y)=StereoGeometry.scale(16f/9,640,480,4)
        assertEquals(4f/3,x,.0001f); assertEquals(1f,y,.0001f)
        assertEquals(1f to 1f,StereoGeometry.scale(9f/16,640,480,3))
    }
    @Test fun invalidSizesNeverProduceNaNs() {
        assertEquals(1f to 1f,StereoGeometry.scale(Float.NaN,0,0,0))
        assertEquals(1f to 1f,StereoGeometry.scale(0f,640,480,4))
    }
}
