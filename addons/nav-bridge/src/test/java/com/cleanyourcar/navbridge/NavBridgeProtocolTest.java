package com.cleanyourcar.navbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavBridgeProtocolTest {
    @Test
    public void extractQueryFromHttpOrRaw_parsesHttpGetUrlEncodedAndRawBleStrings() {
        assertEquals("10.7769,106.7009",
                NavBridgeProtocol.extractQueryFromHttpOrRaw(
                        "GET /nav?q=10.7769%2C106.7009 HTTP/1.1\r\nHost: 172.20.10.2\r\n\r\n"));
        assertEquals("QMVX+2P",
                NavBridgeProtocol.extractQueryFromHttpOrRaw(
                        "GET /nav?q=QMVX%2B2P HTTP/1.1\r\n\r\n"));
        assertEquals("10.7769,106.7009",
                NavBridgeProtocol.extractQueryFromHttpOrRaw("  10.7769,106.7009 \n"));
        assertEquals("QMVX+2P",
                NavBridgeProtocol.extractQueryFromHttpOrRaw("QMVX+2P"));
        assertEquals("QMVX+2P",
                NavBridgeProtocol.extractQueryFromHttpOrRaw("GET /nav?q=QMVX+2P HTTP/1.1\r\n\r\n"));
    }
}
