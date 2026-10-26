package cn.visualassistant.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceNameTest{
    @Test fun extractsUserRoomName(){assertEquals("卧室",normalizePlaceName("这是卧室房间"));assertEquals("工作区",normalizePlaceName("这里是工作区"))}
    @Test fun rejectsRecognitionFillers(){listOf("过去","这里","那个","不知道").forEach{assertNull(normalizePlaceName(it))}}
}
