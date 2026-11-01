package com.doomblocker.app.adblock

import java.io.ByteArrayOutputStream

data class ParsedDnsQueryPacket(
  val ipHeaderLength: Int,
  val sourceIp: ByteArray,
  val destIp: ByteArray,
  val sourcePort: Int,
  val destPort: Int,
  val dnsPayload: ByteArray,
  val queriedDomain: String,
  val qType: Int,
  val questionEndOffset: Int,
)

object DnsPacketHandler {

  /**
   * TUN arayüzünden okunan ham IPv4 paketini inceler.
   * Eğer paket UDP port 53 (DNS) sorgusu ise alan adını ve paket bilgilerini döner.
   */
  fun parseIpv4UdpDnsQuery(packet: ByteArray, length: Int): ParsedDnsQueryPacket? {
    if (length < 28) return null // Min IPv4 (20) + UDP (8)

    val version = (packet[0].toInt() ushr 4) and 0x0F
    if (version != 4) return null // Sadece IPv4

    val ihl = (packet[0].toInt() and 0x0F) * 4
    if (ihl < 20 || length < ihl + 8) return null

    val protocol = packet[9].toInt() and 0xFF
    if (protocol != 17) return null // 17 = UDP

    val srcIp = packet.copyOfRange(12, 16)
    val dstIp = packet.copyOfRange(16, 20)

    val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
    val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
    if (dstPort != 53) return null

    val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
    val dnsOffset = ihl + 8
    val dnsLength = minOf(udpLen - 8, length - dnsOffset)
    if (dnsLength < 12) return null // DNS header en az 12 byte

    val dnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
    val flags = ((dnsPayload[2].toInt() and 0xFF) shl 8) or (dnsPayload[3].toInt() and 0xFF)
    val isResponse = (flags and 0x8000) != 0
    if (isResponse) return null

    val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
    if (qdCount < 1) return null

    // QNAME (Alan Adı) okuma
    var pos = 12
    val labels = mutableListOf<String>()
    while (pos < dnsPayload.size) {
      val labelLen = dnsPayload[pos].toInt() and 0xFF
      if (labelLen == 0) {
        pos++
        break
      }
      // Sıkıştırılmış pointer veya geçersiz uzunluk kontrolü
      if ((labelLen and 0xC0) != 0 || pos + 1 + labelLen > dnsPayload.size) {
        return null
      }
      val label = String(dnsPayload, pos + 1, labelLen, Charsets.UTF_8)
      labels.add(label)
      pos += 1 + labelLen
    }

    if (pos + 4 > dnsPayload.size || labels.isEmpty()) return null

    val qType = ((dnsPayload[pos].toInt() and 0xFF) shl 8) or (dnsPayload[pos + 1].toInt() and 0xFF)
    val questionEnd = pos + 4

    return ParsedDnsQueryPacket(
      ipHeaderLength = ihl,
      sourceIp = srcIp,
      destIp = dstIp,
      sourcePort = srcPort,
      destPort = dstPort,
      dnsPayload = dnsPayload,
      queriedDomain = labels.joinToString("."),
      qType = qType,
      questionEndOffset = questionEnd,
    )
  }

  /**
   * Engellenen reklam alan adları için yerel Blackhole (0.0.0.0 / NXDOMAIN) DNS yanıtı üretir.
   */
  fun buildBlackholeDnsResponse(query: ParsedDnsQueryPacket): ByteArray {
    val req = query.dnsPayload
    val out = ByteArrayOutputStream(128)

    // Transaction ID
    out.write(req[0].toInt())
    out.write(req[1].toInt())

    val rdBit = req[2].toInt() and 0x01
    if (query.qType == 1) {
      // QTYPE = A (IPv4) -> Standard Response, No Error, 0.0.0.0 döner
      out.write(0x81 or rdBit) // QR=1, Opcode=0, RD=rdBit
      out.write(0x80) // RA=1, RCODE=0 (NoError)
      // QDCOUNT = 1
      out.write(0x00)
      out.write(0x01)
      // ANCOUNT = 1
      out.write(0x00)
      out.write(0x01)
      // NSCOUNT = 0, ARCOUNT = 0
      out.write(byteArrayOf(0, 0, 0, 0))

      // Question section
      out.write(req, 12, query.questionEndOffset - 12)

      // Answer section: Name pointer to offset 12 (0xC00C)
      out.write(0xC0)
      out.write(0x0C)
      // Type A (1), Class IN (1)
      out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
      // TTL = 300 seconds
      out.write(byteArrayOf(0x00, 0x00, 0x01, 0x2C))
      // RDLENGTH = 4, RDATA = 0.0.0.0
      out.write(byteArrayOf(0x00, 0x04, 0x00, 0x00, 0x00, 0x00))
    } else {
      // AAAA veya diğer sorgular için NXDOMAIN (RCODE=3) döner
      out.write(0x81 or rdBit)
      out.write(0x83) // RA=1, RCODE=3 (NXDOMAIN)
      // QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
      out.write(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
      out.write(req, 12, query.questionEndOffset - 12)
    }

    return wrapDnsResponseInIpv4Udp(query, out.toByteArray())
  }

  /**
   * DNS yanıt yükünü (Blackhole veya gerçek DNS sunucusundan gelen yanıtı) IPv4 + UDP paketine sarar.
   */
  fun wrapDnsResponseInIpv4Udp(
    originalQuery: ParsedDnsQueryPacket,
    dnsResponsePayload: ByteArray,
  ): ByteArray {
    val udpLength = 8 + dnsResponsePayload.size
    val totalLength = 20 + udpLength
    val packet = ByteArray(totalLength)

    // IPv4 Header (20 bytes)
    packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
    packet[1] = 0x00.toByte()
    packet[2] = ((totalLength ushr 8) and 0xFF).toByte()
    packet[3] = (totalLength and 0xFF).toByte()
    // Identification = 0, Flags = Don't Fragment (0x4000)
    packet[4] = 0x00.toByte()
    packet[5] = 0x00.toByte()
    packet[6] = 0x40.toByte()
    packet[7] = 0x00.toByte()
    // TTL = 64, Protocol = 17 (UDP)
    packet[8] = 64.toByte()
    packet[9] = 17.toByte()
    // Header Checksum (şimdilik 0, aşağıda hesaplanacak)
    packet[10] = 0
    packet[11] = 0

    // Source IP = Orijinal paketin hedef IP'si (DNS sunucusu)
    System.arraycopy(originalQuery.destIp, 0, packet, 12, 4)
    // Dest IP = Orijinal paketin kaynak IP'si (TUN istemcisi)
    System.arraycopy(originalQuery.sourceIp, 0, packet, 16, 4)

    // IPv4 Checksum hesapla
    val ipChecksum = computeIpv4HeaderChecksum(packet, 20)
    packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
    packet[11] = (ipChecksum and 0xFF).toByte()

    // UDP Header (8 bytes)
    val srcPort = originalQuery.destPort
    val dstPort = originalQuery.sourcePort
    packet[20] = ((srcPort ushr 8) and 0xFF).toByte()
    packet[21] = (srcPort and 0xFF).toByte()
    packet[22] = ((dstPort ushr 8) and 0xFF).toByte()
    packet[23] = (dstPort and 0xFF).toByte()
    packet[24] = ((udpLength ushr 8) and 0xFF).toByte()
    packet[25] = (udpLength and 0xFF).toByte()
    // IPv4'te UDP checksum 0 (opsiyonel) bırakılabilir
    packet[26] = 0
    packet[27] = 0

    // DNS Payload
    System.arraycopy(dnsResponsePayload, 0, packet, 28, dnsResponsePayload.size)

    return packet
  }

  private fun computeIpv4HeaderChecksum(header: ByteArray, length: Int): Int {
    var sum = 0
    var i = 0
    while (i < length) {
      val word = ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
      sum += word
      i += 2
    }
    while ((sum ushr 16) != 0) {
      sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return sum.inv() and 0xFFFF
  }
}
