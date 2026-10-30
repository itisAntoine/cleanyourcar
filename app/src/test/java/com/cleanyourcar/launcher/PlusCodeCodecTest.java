package com.cleanyourcar.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlusCodeCodecTest {
    @Test
    public void decode_fullOpenLocationCode_returnsAccurateCoordinates() {
        PlusCodeCodec.LatLon pos = PlusCodeCodec.decode("7P28QMVX+2P", 10.7769, 106.7009);
        assertNotNull(pos);
        assertEquals(10.7925625, pos.lat, 0.001);
        assertEquals(106.6993125, pos.lon, 0.001);
    }

    @Test
    public void decode_short6CharCode_recoversUsingReferenceCityAndHandlesWhitespaceCase() {
        PlusCodeCodec.LatLon pos = PlusCodeCodec.decode(" qmvx+2p ", 10.7769, 106.7009);
        assertNotNull(pos);
        assertEquals(10.7925625, pos.lat, 0.001);
        assertEquals(106.6993125, pos.lon, 0.001);

        // Across 1.0-degree grid boundary (refLat = 11.05 > 11.0, target is 10.7925)
        PlusCodeCodec.LatLon crossBoundary = PlusCodeCodec.decode("QMVX+2P", 11.05, 106.7009);
        assertNotNull(crossBoundary);
        assertEquals(10.7925625, crossBoundary.lat, 0.001);
        assertEquals(106.6993125, crossBoundary.lon, 0.001);

        // Pure 6-character code without '+' (e.g. "QMVX2P")
        PlusCodeCodec.LatLon noPlus6 = PlusCodeCodec.decode("QMVX2P", 10.7769, 106.7009);
        assertNotNull(noPlus6);
        assertEquals(10.7925625, noPlus6.lat, 0.001);
        assertEquals(106.6993125, noPlus6.lon, 0.001);
    }

    @Test
    public void decode_invalidCode_returnsNullWithoutThrowing() {
        assertNull(PlusCodeCodec.decode("", 10.7769, 106.7009));
        assertNull(PlusCodeCodec.decode("INVALID!", 10.7769, 106.7009));
        assertNull(PlusCodeCodec.decode("1111+11", 10.7769, 106.7009));
    }

    @Test
    public void navPayloadParser_handlesRawCoordsGeoUriGoogleMapsUrlAndPlusCode() {
        PlusCodeCodec.LatLon raw = NavPayloadParser.parse("10.7721, 106.6983", 10.7769, 106.7009);
        assertNotNull(raw);
        assertEquals(10.7721, raw.lat, 0.0001);
        assertEquals(106.6983, raw.lon, 0.0001);

        PlusCodeCodec.LatLon url = NavPayloadParser.parse(
                "https://www.google.com/maps/place/Ben+Thanh/@10.7725,106.6980,17z/data=!3m1",
                10.7769, 106.7009);
        assertNotNull(url);
        assertEquals(10.7725, url.lat, 0.0001);
        assertEquals(106.6980, url.lon, 0.0001);

        PlusCodeCodec.LatLon plus = NavPayloadParser.parse("QMVX+2P", 10.7769, 106.7009);
        assertNotNull(plus);
        assertEquals(10.7925625, plus.lat, 0.001);

        PlusCodeCodec.LatLon plusWithUrlEncodedCity = NavPayloadParser.parse(
                "QMVX+2P%20Ho%20Chi%20Minh", 10.7769, 106.7009);
        assertNotNull(plusWithUrlEncodedCity);
        assertEquals(10.7925625, plusWithUrlEncodedCity.lat, 0.001);

        assertNull(NavPayloadParser.parse("110.7769, 106.7009", 10.7769, 106.7009));
        assertNull(NavPayloadParser.parse("-110.7769, 106.7009", 10.7769, 106.7009));
        assertNull(NavPayloadParser.parse("999.0,999.0", 10.7769, 106.7009));
        assertNull(NavPayloadParser.parse("not-a-location", 10.7769, 106.7009));
    }
}
