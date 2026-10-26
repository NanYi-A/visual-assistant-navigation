package cn.visualassistant.app.home

import org.junit.Assert.*
import org.junit.Test

class HomeGeoPresenceTest{
    @Test fun nearbyFixIsHome(){assertTrue(HomeGeoPresence.evaluate(HomeGeoAnchor(39.9,116.4,12f),GeoFix(39.9001,116.4001,15f)).atHome)}
    @Test fun distantFixIsAway(){assertFalse(HomeGeoPresence.evaluate(HomeGeoAnchor(39.9,116.4,10f),GeoFix(39.91,116.41,10f)).atHome)}
    @Test fun inaccurateFixCannotConfirm(){val result=HomeGeoPresence.evaluate(HomeGeoAnchor(39.9,116.4,10f),GeoFix(39.9,116.4,300f));assertFalse(result.atHome);assertFalse(result.reliable)}
}
