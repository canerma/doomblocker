package com.doomblocker.app.adblock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.doomblocker.app.MainActivity
import com.doomblocker.app.R
import com.doomblocker.app.data.UsageRepository
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DoomBlockerDnsVpnService : VpnService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var vpnInterface: ParcelFileDescriptor? = null
  private var readerJob: Job? = null
  private val writeLock = Any()

  override fun onCreate() {
    super.onCreate()
    UsageRepository.init(applicationContext)
    createVpnNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_VPN) {
      stopVpnTunnel()
      stopSelf()
      return START_NOT_STICKY
    }

    startVpnTunnel()
    return START_STICKY
  }

  private fun startVpnTunnel() {
    if (vpnInterface != null) return

    runCatching {
      val builder = Builder()
        .setSession("DoomBlocker Blackhole DNS")
        .setMtu(1500)
        .addAddress(TUN_CLIENT_IP, 30)
        .addDnsServer(TUN_DNS_SERVER_IP)
        // Sadece sanal DNS IP'sine (10.215.173.2/32) giden DNS paketlerini yakala;
        // normal internet trafiği doğrudan tam hızda Wi-Fi / Mobil Veri üzerinden akar.
        .addRoute(TUN_DNS_SERVER_IP, 32)
        .setBlocking(true)

      val pfd = builder.establish() ?: return
      vpnInterface = pfd
      UsageRepository.setDnsVpnRunning(true)

      val openAppIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

      val notification = NotificationCompat.Builder(this, VPN_CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("🕳️ DoomBlocker Blackhole DNS Aktif")
        .setContentText("Reklam ve takipçi alan adları 0.0.0.0 Blackhole'a yönlendiriliyor.")
        .setContentIntent(openAppIntent)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

      runCatching {
        startForeground(VPN_NOTIFICATION_ID, notification)
      }

      readerJob = serviceScope.launch {
        runDnsLoop(pfd)
      }
    }
  }

  private fun runDnsLoop(pfd: ParcelFileDescriptor) {
    val inputStream = FileInputStream(pfd.fileDescriptor)
    val outputStream = FileOutputStream(pfd.fileDescriptor)
    val buffer = ByteArray(32767)

    try {
      while (serviceScope.isActive && vpnInterface != null) {
        val length = inputStream.read(buffer)
        if (length <= 0) continue

        val packetCopy = buffer.copyOfRange(0, length)
        val parsedQuery = DnsPacketHandler.parseIpv4UdpDnsQuery(packetCopy, length) ?: continue

        if (AdBlockDomainList.isBlocked(parsedQuery.queriedDomain)) {
          // BLACKHOLE: Reklam/takipçi alan adını anında 0.0.0.0 / NXDOMAIN ile yanıtla!
          val blackholePacket = DnsPacketHandler.buildBlackholeDnsResponse(parsedQuery)
          synchronized(writeLock) {
            runCatching { outputStream.write(blackholePacket) }
          }
          UsageRepository.recordDnsAdBlocked(parsedQuery.queriedDomain)
        } else {
          // Normal alan adı -> Korunmuş soket ile gerçek DNS sunucusuna (1.1.1.1 / 8.8.8.8) ilet
          serviceScope.launch {
            val dnsReply = forwardDnsQueryToUpstream(parsedQuery.dnsPayload)
            if (dnsReply != null) {
              val wrappedPacket = DnsPacketHandler.wrapDnsResponseInIpv4Udp(parsedQuery, dnsReply)
              synchronized(writeLock) {
                runCatching { outputStream.write(wrappedPacket) }
              }
            }
          }
        }
      }
    } catch (_: Exception) {
      // TUN kapatıldığında döngü sonlanır
    } finally {
      UsageRepository.setDnsVpnRunning(false)
    }
  }

  private fun forwardDnsQueryToUpstream(dnsPayload: ByteArray): ByteArray? {
    for (upstreamIp in UPSTREAM_DNS_SERVERS) {
      var socket: DatagramSocket? = null
      try {
        socket = DatagramSocket()
        protect(socket) // VPN döngüsüne girmemesi için soketi koru
        socket.soTimeout = 2500

        val address = InetAddress.getByName(upstreamIp)
        val requestPacket = DatagramPacket(dnsPayload, dnsPayload.size, address, 53)
        socket.send(requestPacket)

        val responseBuffer = ByteArray(4096)
        val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
        socket.receive(responsePacket)

        return responseBuffer.copyOfRange(0, responsePacket.length)
      } catch (_: Exception) {
        // İlk DNS yanıt vermezse yedek DNS'i dene
      } finally {
        runCatching { socket?.close() }
      }
    }
    return null
  }

  private fun stopVpnTunnel() {
    UsageRepository.setDnsVpnRunning(false)
    readerJob?.cancel()
    readerJob = null
    runCatching { vpnInterface?.close() }
    vpnInterface = null
    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
  }

  override fun onRevoke() {
    stopVpnTunnel()
    super.onRevoke()
  }

  override fun onDestroy() {
    stopVpnTunnel()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun createVpnNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        VPN_CHANNEL_ID,
        "DoomBlocker Blackhole DNS",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Yerel Blackhole DNS reklam engelleyici durum bildirimi."
      }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      nm?.createNotificationChannel(channel)
    }
  }

  companion object {
    const val ACTION_START_VPN = "com.doomblocker.app.START_DNS_VPN"
    const val ACTION_STOP_VPN = "com.doomblocker.app.STOP_DNS_VPN"

    private const val TUN_CLIENT_IP = "10.215.173.1"
    private const val TUN_DNS_SERVER_IP = "10.215.173.2"
    private val UPSTREAM_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")

    private const val VPN_CHANNEL_ID = "doomblocker_dns_vpn"
    private const val VPN_NOTIFICATION_ID = 3001
  }
}
