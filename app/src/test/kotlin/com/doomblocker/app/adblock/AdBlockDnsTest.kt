package com.doomblocker.app.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockDnsTest {

  @Test
  fun `domain blocklist blocks ad and tracker subdomains without breaking content CDNs`() {
    // Reklam ve takipçi alan adları engellenmeli
    assertTrue(AdBlockDomainList.isBlocked("googleads.g.doubleclick.net"))
    assertTrue(AdBlockDomainList.isBlocked("pagead2.googlesyndication.com"))
    assertTrue(AdBlockDomainList.isBlocked("an.facebook.com"))
    assertTrue(AdBlockDomainList.isBlocked("pixel.facebook.com"))
    assertTrue(AdBlockDomainList.isBlocked("ads-api.twitter.com"))
    assertTrue(AdBlockDomainList.isBlocked("events.redditmedia.com"))
    assertTrue(AdBlockDomainList.isBlocked("ms.applovin.com"))
    assertTrue(AdBlockDomainList.isBlocked("adserver.example.com"))

    // Normal içerik alan adları engellenmemeli
    assertFalse(AdBlockDomainList.isBlocked("www.instagram.com"))
    assertFalse(AdBlockDomainList.isBlocked("i.instagram.com"))
    assertFalse(AdBlockDomainList.isBlocked("scontent.cdninstagram.com"))
    assertFalse(AdBlockDomainList.isBlocked("www.youtube.com"))
    assertFalse(AdBlockDomainList.isBlocked("www.reddit.com"))
    assertFalse(AdBlockDomainList.isBlocked("x.com"))
  }

  @Test
  fun `dns packet handler parses IPv4 UDP DNS query and builds valid 0_0_0_0 blackhole response`() {
    // Örnek DNS sorgusu: "an.facebook.com" (Type A = 1, Class IN = 1)
    val dnsPayload = byteArrayOf(
      0x12, 0x34, // Transaction ID
      0x01, 0x00, // Standard query, RD=1
      0x00, 0x01, // QDCOUNT = 1
      0x00, 0x00, // ANCOUNT = 0
      0x00, 0x00, // NSCOUNT = 0
      0x00, 0x00, // ARCOUNT = 0
      // QNAME: 2 "an" 8 "facebook" 3 "com" 0
      0x02, 'a'.code.toByte(), 'n'.code.toByte(),
      0x08, 'f'.code.toByte(), 'a'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(),
      'b'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'k'.code.toByte(),
      0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
      0x00,
      0x00, 0x01, // QTYPE = A (1)
      0x00, 0x01, // QCLASS = IN (1)
    )

    val dummyQueryMeta = ParsedDnsQueryPacket(
      ipHeaderLength = 20,
      sourceIp = byteArrayOf(10, 215.toByte(), 173.toByte(), 1),
      destIp = byteArrayOf(10, 215.toByte(), 173.toByte(), 2),
      sourcePort = 54321,
      destPort = 53,
      dnsPayload = dnsPayload,
      queriedDomain = "an.facebook.com",
      qType = 1,
      questionEndOffset = dnsPayload.size,
    )

    val rawRequestPacket = DnsPacketHandler.wrapDnsResponseInIpv4Udp(
      originalQuery = dummyQueryMeta.copy(
        sourceIp = dummyQueryMeta.destIp,
        destIp = dummyQueryMeta.sourceIp,
        sourcePort = 53,
        destPort = 54321,
      ),
      dnsResponsePayload = dnsPayload,
    )

    val parsed = DnsPacketHandler.parseIpv4UdpDnsQuery(rawRequestPacket, rawRequestPacket.size)
    assertNotNull(parsed)
    assertEquals("an.facebook.com", parsed!!.queriedDomain)
    assertEquals(1, parsed.qType)

    val blackholePacket = DnsPacketHandler.buildBlackholeDnsResponse(parsed)
    // Son 4 byte 0.0.0.0 (RDATA) olmalı
    val n = blackholePacket.size
    assertEquals(0.toByte(), blackholePacket[n - 4])
    assertEquals(0.toByte(), blackholePacket[n - 3])
    assertEquals(0.toByte(), blackholePacket[n - 2])
    assertEquals(0.toByte(), blackholePacket[n - 1])
  }
}
