package com.doomblocker.app.domain

enum class TrackedApp(
  val packageName: String,
  val displayName: String,
  val isShortsOnly: Boolean,
) {
  INSTAGRAM(
    packageName = "com.instagram.android",
    displayName = "Instagram",
    isShortsOnly = false,
  ),
  TWITTER(
    packageName = "com.twitter.android",
    displayName = "X (Twitter)",
    isShortsOnly = false,
  ),
  REDDIT(
    packageName = "com.reddit.frontpage",
    displayName = "Reddit",
    isShortsOnly = false,
  ),
  YOUTUBE_SHORTS(
    packageName = "com.google.android.youtube",
    displayName = "YouTube Shorts",
    isShortsOnly = true,
  );

  companion object {
    fun fromPackageName(pkg: String): TrackedApp? = entries.find { it.packageName == pkg }
  }
}

enum class BlockReason {
  FOCUS_SCHEDULE_ACTIVE,
  PER_APP_LIMIT_REACHED,
  TOTAL_POOL_LIMIT_REACHED,
}

data class FocusScheduleConfig(
  val enabled: Boolean = false,
  val startHour: Int = 23,
  val startMinute: Int = 30,
  val endHour: Int = 8,
  val endMinute: Int = 0,
) {
  fun isInFocusWindow(currentHour: Int, currentMinute: Int): Boolean {
    if (!enabled) return false
    val nowMins = currentHour * 60 + currentMinute
    val startMins = startHour * 60 + startMinute
    val endMins = endHour * 60 + endMinute

    return if (startMins == endMins) {
      true // 24 saat aktif
    } else if (startMins < endMins) {
      // Aynı gün içi aralık (ör. 09:00 - 17:00)
      nowMins in startMins until endMins
    } else {
      // Gece yarısını aşan aralık (ör. 23:30 - 08:00)
      nowMins >= startMins || nowMins < endMins
    }
  }

  fun formattedRange(): String {
    return "%02d:%02d - %02d:%02d".format(startHour, startMinute, endHour, endMinute)
  }
}

data class LimitConfig(
  val windowSeconds: Int,
  val perAppLimitSeconds: Int,
  val totalLimitSeconds: Int,
  val warningThresholdSeconds: Int,
  val isTestMode: Boolean,
) {
  companion object {
    val NORMAL = LimitConfig(
      windowSeconds = 3600, // 60 dakika kayan pencere
      perAppLimitSeconds = 300, // Uygulama başı 5 dakika
      totalLimitSeconds = 420, // Toplam havuz 7 dakika
      warningThresholdSeconds = 60, // 1 dakika kala uyarı
      isTestMode = false,
    )

    val TEST_MODE = LimitConfig(
      windowSeconds = 60, // 60 saniye kayan pencere
      perAppLimitSeconds = 20, // Uygulama başı 20 saniye
      totalLimitSeconds = 30, // Toplam havuz 30 saniye
      warningThresholdSeconds = 10, // 10 saniye kala uyarı
      isTestMode = true,
    )
  }
}

data class AppUsageStatus(
  val app: TrackedApp,
  val usedSeconds: Int,
  val perAppLimitSeconds: Int,
  val appRemainingSeconds: Int,
  val effectiveRemainingSeconds: Int,
  val isBlocked: Boolean,
  val blockReason: BlockReason?,
  val secondsUntilUnblocked: Long,
  val counterStartEpochSec: Long? = null,
)

data class UsageSnapshot(
  val nowEpochSec: Long,
  val config: LimitConfig,
  val focusSchedule: FocusScheduleConfig,
  val isFocusScheduleCurrentlyActive: Boolean,
  val totalUsedSeconds: Int,
  val totalRemainingSeconds: Int,
  val appStatuses: Map<TrackedApp, AppUsageStatus>,
  val poolCounterStartEpochSec: Long? = null,
) {
  val isAnyAppBlocked: Boolean
    get() = appStatuses.values.any { it.isBlocked }
}

data class TickResult(
  val app: TrackedApp,
  val isBlocked: Boolean,
  val blockReason: BlockReason?,
  val shouldTriggerWarning: Boolean,
  val warningRemainingSeconds: Int,
  val snapshot: UsageSnapshot,
)

class UsageEngine(
  private var config: LimitConfig = LimitConfig.NORMAL,
) {
  private val usageTimestamps: Map<TrackedApp, MutableSet<Long>> =
    TrackedApp.entries.associateWith { linkedSetOf() }

  private val warnedAtRemainingForApp: MutableMap<TrackedApp, Long> = mutableMapOf()
  private var focusSchedule: FocusScheduleConfig = FocusScheduleConfig()

  @Synchronized
  fun getConfig(): LimitConfig = config

  @Synchronized
  fun setConfig(newConfig: LimitConfig) {
    config = newConfig
    warnedAtRemainingForApp.clear()
  }

  @Synchronized
  fun getFocusSchedule(): FocusScheduleConfig = focusSchedule

  @Synchronized
  fun setFocusSchedule(newSchedule: FocusScheduleConfig) {
    focusSchedule = newSchedule
  }

  @Synchronized
  fun clearAllUsage() {
    usageTimestamps.values.forEach { it.clear() }
    warnedAtRemainingForApp.clear()
  }

  @Synchronized
  fun pruneOldRecords(nowEpochSec: Long) {
    val cutoff = nowEpochSec - config.windowSeconds
    usageTimestamps.values.forEach { set ->
      set.removeAll { it <= cutoff }
    }
  }

  @Synchronized
  fun getSnapshot(
    nowEpochSec: Long,
    isFocusActiveNow: Boolean = false,
  ): UsageSnapshot {
    pruneOldRecords(nowEpochSec)

    val sortedPerApp = usageTimestamps.mapValues { (_, set) -> set.sorted() }
    val allSorted = sortedPerApp.values.flatten().sorted()

    val totalUsed = allSorted.size
    val totalRemaining = (config.totalLimitSeconds - totalUsed).coerceAtLeast(0)

    val totalWaitSeconds = if (totalUsed >= config.totalLimitSeconds) {
      val excessTotal = totalUsed - config.totalLimitSeconds + 1
      val targetTimestamp = allSorted.getOrNull(excessTotal - 1) ?: nowEpochSec
      ((targetTimestamp + config.windowSeconds) - nowEpochSec).coerceAtLeast(1L)
    } else {
      0L
    }

    val statuses = TrackedApp.entries.associateWith { app ->
      val appTimes = sortedPerApp[app].orEmpty()
      val used = appTimes.size
      val appRemaining = (config.perAppLimitSeconds - used).coerceAtLeast(0)
      val effectiveRemaining = if (isFocusActiveNow) 0 else minOf(appRemaining, totalRemaining)

      val reachedAppLimit = used >= config.perAppLimitSeconds
      val reachedTotalLimit = totalUsed >= config.totalLimitSeconds
      val isBlocked = isFocusActiveNow || reachedAppLimit || reachedTotalLimit

      val reason = when {
        isFocusActiveNow -> BlockReason.FOCUS_SCHEDULE_ACTIVE
        reachedAppLimit -> BlockReason.PER_APP_LIMIT_REACHED
        reachedTotalLimit -> BlockReason.TOTAL_POOL_LIMIT_REACHED
        else -> null
      }

      val appWaitSeconds = if (reachedAppLimit) {
        val excessApp = used - config.perAppLimitSeconds + 1
        val targetTimestamp = appTimes.getOrNull(excessApp - 1) ?: nowEpochSec
        ((targetTimestamp + config.windowSeconds) - nowEpochSec).coerceAtLeast(1L)
      } else {
        0L
      }

      val secondsUntilUnblocked = if (reachedAppLimit || reachedTotalLimit) {
        maxOf(appWaitSeconds, totalWaitSeconds)
      } else {
        0L
      }

      AppUsageStatus(
        app = app,
        usedSeconds = used,
        perAppLimitSeconds = config.perAppLimitSeconds,
        appRemainingSeconds = appRemaining,
        effectiveRemainingSeconds = effectiveRemaining,
        isBlocked = isBlocked,
        blockReason = reason,
        secondsUntilUnblocked = secondsUntilUnblocked,
        counterStartEpochSec = appTimes.firstOrNull(),
      )
    }

    return UsageSnapshot(
      nowEpochSec = nowEpochSec,
      config = config,
      focusSchedule = focusSchedule,
      isFocusScheduleCurrentlyActive = isFocusActiveNow,
      totalUsedSeconds = totalUsed,
      totalRemainingSeconds = totalRemaining,
      appStatuses = statuses,
      poolCounterStartEpochSec = allSorted.firstOrNull(),
    )
  }

  @Synchronized
  fun recordUsageTick(
    app: TrackedApp,
    nowEpochSec: Long,
    isFocusActiveNow: Boolean = false,
  ): TickResult {
    val beforeSnapshot = getSnapshot(nowEpochSec, isFocusActiveNow)
    val beforeStatus = beforeSnapshot.appStatuses.getValue(app)

    if (beforeStatus.isBlocked) {
      return TickResult(
        app = app,
        isBlocked = true,
        blockReason = beforeStatus.blockReason,
        shouldTriggerWarning = false,
        warningRemainingSeconds = 0,
        snapshot = beforeSnapshot,
      )
    }

    usageTimestamps.getValue(app).add(nowEpochSec)

    val afterSnapshot = getSnapshot(nowEpochSec, isFocusActiveNow)
    val afterStatus = afterSnapshot.appStatuses.getValue(app)

    val shouldWarn = if (
      !afterStatus.isBlocked &&
      afterStatus.effectiveRemainingSeconds == config.warningThresholdSeconds &&
      warnedAtRemainingForApp[app] != nowEpochSec
    ) {
      warnedAtRemainingForApp[app] = nowEpochSec
      true
    } else {
      false
    }

    return TickResult(
      app = app,
      isBlocked = afterStatus.isBlocked,
      blockReason = afterStatus.blockReason,
      shouldTriggerWarning = shouldWarn,
      warningRemainingSeconds = afterStatus.effectiveRemainingSeconds,
      snapshot = afterSnapshot,
    )
  }

  @Synchronized
  fun exportState(): Map<TrackedApp, List<Long>> {
    return usageTimestamps.mapValues { (_, set) -> set.toList() }
  }

  @Synchronized
  fun importState(state: Map<TrackedApp, List<Long>>, nowEpochSec: Long) {
    usageTimestamps.values.forEach { it.clear() }
    state.forEach { (app, timestamps) ->
      usageTimestamps[app]?.addAll(timestamps)
    }
    pruneOldRecords(nowEpochSec)
  }
}
