package com.doomblocker.app.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.doomblocker.app.R
import com.doomblocker.app.data.UsageRepository
import com.doomblocker.app.domain.TrackedApp
import com.doomblocker.app.ui.block.BlockActivity
import com.doomblocker.app.ui.pause.MindfulPauseActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DoomBlockerAccessibilityService : AccessibilityService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val mainHandler = Handler(Looper.getMainLooper())

  private var currentPackageName: String? = null
  private var lastShortsBackActionTimeMs: Long = 0L
  private var lastBlockActivityLaunchTimeMs: Long = 0L
  private var lastMindfulPauseLaunchTimeMs: Long = 0L
  private var lastSponsoredAdSkipTimeMs: Long = 0L

  private val ignoredSystemPackages = setOf(
    "com.android.systemui",
    "com.google.android.inputmethod.latin",
    "com.samsung.android.honeyboard",
    "android",
  )

  override fun onServiceConnected() {
    super.onServiceConnected()
    UsageRepository.init(applicationContext)
    UsageRepository.setServiceConnected(true)
    createNotificationChannel()

    serviceScope.launch {
      while (isActive) {
        onSecondTick()
        delay(1000L)
      }
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    val pkg = event.packageName?.toString() ?: return

    if (pkg !in ignoredSystemPackages) {
      currentPackageName = pkg
    }

    if (!UsageRepository.isProtectionEnabled.value) return

    // Ayarlar (Anti-Tamper) kontrolü
    if (pkg == "com.android.settings" && !UsageRepository.hasActiveStrictBypass()) {
      val root = rootInActiveWindow
      if (isAttemptingToDisableDoomBlocker(root)) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastBlockActivityLaunchTimeMs > 1500L) {
          lastBlockActivityLaunchTimeMs = nowMs
          performGlobalAction(GLOBAL_ACTION_BACK)
          startActivity(BlockActivity.createIntent(this, isAntiTamper = true))
        }
        return
      }
    }

    val trackedApp = TrackedApp.fromPackageName(pkg) ?: return
    val snapshot = UsageRepository.refreshSnapshot()
    val status = snapshot.appStatuses[trackedApp] ?: return

    val rootNode = rootInActiveWindow

    if (trackedApp == TrackedApp.YOUTUBE_SHORTS) {
      if (!YouTubeShortsDetector.isShortsScreenActive(rootNode)) {
        return
      }
    }

    if (status.isBlocked) {
      if (trackedApp == TrackedApp.YOUTUBE_SHORTS) {
        exitYouTubeShortsWithNotice()
      } else {
        blockFullAppImmediately(trackedApp)
      }
      return
    }

    if (UsageRepository.shouldTriggerMindfulPause(trackedApp)) {
      launchMindfulPauseIfNeeded(trackedApp)
      return
    }

    // Instagram, X ve Reddit içinde "Sponsorlu" / "Promoted" reklamları otomatik atla
    if (!trackedApp.isShortsOnly && UsageRepository.isSponsoredSkipEnabled.value) {
      trySkipSponsoredAd(rootNode)
    }
  }

  private fun onSecondTick() {
    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isScreenInteractive = powerManager?.isInteractive ?: true
    if (!isScreenInteractive) {
      UsageRepository.refreshSnapshot()
      return
    }

    if (!UsageRepository.isProtectionEnabled.value) {
      UsageRepository.refreshSnapshot()
      return
    }

    val rootNode = rootInActiveWindow
    val activePkg = rootNode?.packageName?.toString()
      ?.takeIf { it !in ignoredSystemPackages }
      ?: currentPackageName

    if (activePkg == null || activePkg == packageName) {
      UsageRepository.refreshSnapshot()
      return
    }

    val trackedApp = TrackedApp.fromPackageName(activePkg)
    if (trackedApp == null) {
      UsageRepository.refreshSnapshot()
      return
    }

    if (trackedApp == TrackedApp.YOUTUBE_SHORTS) {
      val isShortsActive = YouTubeShortsDetector.isShortsScreenActive(rootNode)
      if (!isShortsActive) {
        UsageRepository.refreshSnapshot()
        return
      }
    }

    val currentSnapshot = UsageRepository.refreshSnapshot()
    val currentStatus = currentSnapshot.appStatuses.getValue(trackedApp)

    if (currentStatus.isBlocked) {
      if (trackedApp == TrackedApp.YOUTUBE_SHORTS) {
        exitYouTubeShortsWithNotice()
      } else {
        blockFullAppImmediately(trackedApp)
      }
      return
    }

    if (UsageRepository.shouldTriggerMindfulPause(trackedApp)) {
      launchMindfulPauseIfNeeded(trackedApp)
      return
    }

    if (!trackedApp.isShortsOnly && UsageRepository.isSponsoredSkipEnabled.value) {
      trySkipSponsoredAd(rootNode)
    }

    val tickResult = UsageRepository.recordTick(trackedApp)

    if (tickResult.shouldTriggerWarning) {
      showRemainingTimeWarning(trackedApp, tickResult.warningRemainingSeconds)
    }

    if (tickResult.isBlocked) {
      if (trackedApp == TrackedApp.YOUTUBE_SHORTS) {
        exitYouTubeShortsWithNotice()
      } else {
        blockFullAppImmediately(trackedApp)
      }
    }
  }

  private fun trySkipSponsoredAd(rootNode: AccessibilityNodeInfo?) {
    val nowMs = System.currentTimeMillis()
    if (nowMs - lastSponsoredAdSkipTimeMs < 1100L) return

    val metrics = resources.displayMetrics
    val screenWidth = metrics.widthPixels
    val screenHeight = metrics.heightPixels

    val adType = SponsoredAdSkipper.detectVisibleSponsoredAd(rootNode, screenHeight) ?: return
    lastSponsoredAdSkipTimeMs = nowMs

    val dispatched = SponsoredAdSkipper.performSkipGesture(
      service = this,
      adType = adType,
      screenWidth = screenWidth,
      screenHeight = screenHeight,
    )
    if (dispatched) {
      UsageRepository.recordSponsoredAdSkipped()
      mainHandler.post {
        Toast.makeText(
          applicationContext,
          "⚡ Sponsorlu reklam otomatik atlandı",
          Toast.LENGTH_SHORT,
        ).show()
      }
    }
  }

  private fun launchMindfulPauseIfNeeded(app: TrackedApp) {
    val nowMs = System.currentTimeMillis()
    if (nowMs - lastMindfulPauseLaunchTimeMs < 2000L) return
    lastMindfulPauseLaunchTimeMs = nowMs

    startActivity(MindfulPauseActivity.createIntent(this, app))
  }

  private fun blockFullAppImmediately(app: TrackedApp) {
    val nowMs = System.currentTimeMillis()
    if (nowMs - lastBlockActivityLaunchTimeMs < 1500L) return
    lastBlockActivityLaunchTimeMs = nowMs

    UsageRepository.recordBlockedAttempt()
    performGlobalAction(GLOBAL_ACTION_HOME)
    startActivity(BlockActivity.createIntent(this, trackedApp = app))
  }

  private fun exitYouTubeShortsWithNotice() {
    val nowMs = System.currentTimeMillis()
    if (nowMs - lastShortsBackActionTimeMs < 1500L) return
    lastShortsBackActionTimeMs = nowMs

    UsageRepository.recordBlockedAttempt()
    performGlobalAction(GLOBAL_ACTION_BACK)
    mainHandler.post {
      Toast.makeText(
        applicationContext,
        "🛑 YouTube Shorts engellendi! Normal videoları izlemeye devam edebilirsin.",
        Toast.LENGTH_LONG,
      ).show()
    }
    sendAlertNotification(
      title = "🛑 YouTube Shorts Engellendi",
      message = "Shorts kullanım hakkın doldu veya Odak Saatlerindesin. Normal YouTube videoları serbesttir.",
      notificationId = 2002,
    )
  }

  private fun showRemainingTimeWarning(app: TrackedApp, remainingSeconds: Int) {
    val timeText = if (remainingSeconds >= 60) {
      "${remainingSeconds / 60} dakika"
    } else {
      "$remainingSeconds saniye"
    }
    val msg = "⚠️ DoomBlocker: ${app.displayName} için son $timeText kaldı!"
    mainHandler.post {
      Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
    }
    sendAlertNotification(
      title = "⚠️ Süre Bitiyor (${app.displayName})",
      message = "Kullanım hakkının dolmasına son $timeText kaldı.",
      notificationId = 2001,
    )
  }

  private fun isAttemptingToDisableDoomBlocker(rootNode: AccessibilityNodeInfo?): Boolean {
    if (rootNode == null) return false
    var mentionsDoomBlocker = false
    var mentionsDangerAction = false

    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(rootNode)
    var visited = 0

    while (queue.isNotEmpty() && visited < 150) {
      val node = queue.removeFirst()
      visited++
      val text = node.text?.toString().orEmpty()
      val desc = node.contentDescription?.toString().orEmpty()
      val combined = "$text $desc"

      if (combined.contains("DoomBlocker", ignoreCase = true)) {
        mentionsDoomBlocker = true
      }
      if (combined.contains("Durdurmaya zorla", ignoreCase = true) ||
        combined.contains("Force stop", ignoreCase = true) ||
        combined.contains("Kaldır", ignoreCase = true) ||
        combined.contains("Uninstall", ignoreCase = true) ||
        combined.contains("Uygulamayı kullan", ignoreCase = true) ||
        combined.contains("Use DoomBlocker", ignoreCase = true) ||
        combined.contains("Use service", ignoreCase = true) ||
        combined.contains("Kısayol", ignoreCase = true)
      ) {
        mentionsDangerAction = true
      }

      if (mentionsDoomBlocker && mentionsDangerAction) return true

      for (i in 0 until node.childCount) {
        node.getChild(i)?.let { queue.add(it) }
      }
    }
    return false
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "DoomBlocker Süre Uyarıları",
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "Süre bitmeden 1 dakika önce ve süre dolduğunda bildirim gönderir."
      }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      nm?.createNotificationChannel(channel)
    }
  }

  private fun sendAlertNotification(title: String, message: String, notificationId: Int) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(message)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .build()
    runCatching { nm.notify(notificationId, notification) }
  }

  override fun onInterrupt() {
    UsageRepository.setServiceConnected(false)
  }

  override fun onDestroy() {
    UsageRepository.setServiceConnected(false)
    serviceScope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL_ID = "doomblocker_alerts"
  }
}
