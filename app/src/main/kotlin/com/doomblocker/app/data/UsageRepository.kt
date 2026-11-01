package com.doomblocker.app.data

import android.content.Context
import android.content.SharedPreferences
import com.doomblocker.app.domain.FocusScheduleConfig
import com.doomblocker.app.domain.LimitConfig
import com.doomblocker.app.domain.TickResult
import com.doomblocker.app.domain.TrackedApp
import com.doomblocker.app.domain.UsageEngine
import com.doomblocker.app.domain.UsageSnapshot
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

data class DailyStats(
  val dateKey: String,
  val todayAppUsedSeconds: Map<TrackedApp, Int>,
  val blockedAttemptsCount: Int,
  val mindfulQuitCount: Int,
) {
  val todayTotalUsedSeconds: Int
    get() = todayAppUsedSeconds.values.sum()

  val totalPreventedSessions: Int
    get() = blockedAttemptsCount + mindfulQuitCount

  // Araştırmalara göre engellenen/vazgeçilen her doomscroll oturumu ortalama ~8 dakika kurtarır
  val estimatedSavedMinutes: Int
    get() = totalPreventedSessions * 8
}

object UsageRepository {
  private const val PREFS_NAME = "doom_blocker_prefs"
  private const val KEY_TEST_MODE = "is_test_mode"
  private const val KEY_PROTECTION_ENABLED = "is_protection_enabled"
  private const val KEY_MINDFUL_PAUSE_ENABLED = "is_mindful_pause_enabled"
  private const val KEY_STRICT_BYPASS_UNTIL = "strict_bypass_until_epoch_sec"
  private const val KEY_PREFIX_TIMESTAMPS = "timestamps_"

  // Focus Schedule keys
  private const val KEY_FOCUS_ENABLED = "focus_schedule_enabled"
  private const val KEY_FOCUS_START_HOUR = "focus_start_hour"
  private const val KEY_FOCUS_START_MIN = "focus_start_min"
  private const val KEY_FOCUS_END_HOUR = "focus_end_hour"
  private const val KEY_FOCUS_END_MIN = "focus_end_min"

  // Daily stats keys
  private const val KEY_STATS_DATE = "daily_stats_date"
  private const val KEY_STATS_BLOCKED_COUNT = "daily_stats_blocked_count"
  private const val KEY_STATS_MINDFUL_QUIT_COUNT = "daily_stats_mindful_quit_count"
  private const val KEY_PREFIX_DAILY_APP_SEC = "daily_stats_app_sec_"

  private const val MINDFUL_SESSION_TIMEOUT_SEC = 20L

  private var prefs: SharedPreferences? = null
  private val engine = UsageEngine(LimitConfig.NORMAL)

  private val approvedSessionLastSeenSec: MutableMap<TrackedApp, Long> = mutableMapOf()

  private val _snapshotFlow = MutableStateFlow(engine.getSnapshot(currentEpochSec(), false))
  val snapshotFlow: StateFlow<UsageSnapshot> = _snapshotFlow.asStateFlow()

  private val _dailyStatsFlow = MutableStateFlow(
    DailyStats(
      dateKey = todayDateKey(),
      todayAppUsedSeconds = TrackedApp.entries.associateWith { 0 },
      blockedAttemptsCount = 0,
      mindfulQuitCount = 0,
    )
  )
  val dailyStatsFlow: StateFlow<DailyStats> = _dailyStatsFlow.asStateFlow()

  private val _isProtectionEnabled = MutableStateFlow(true)
  val isProtectionEnabled: StateFlow<Boolean> = _isProtectionEnabled.asStateFlow()

  private val _isMindfulPauseEnabled = MutableStateFlow(true)
  val isMindfulPauseEnabled: StateFlow<Boolean> = _isMindfulPauseEnabled.asStateFlow()

  private val _isServiceConnected = MutableStateFlow(false)
  val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

  private val strictSentences = listOf(
    "Odaklanmak yerine vaktimi boşa harcamak için korumayı devre dışı bırakıyorum",
    "Hedeflerimi erteleyip sonsuz kaydırma tuzağına bilerek geri dönüyorum",
    "Zamanımı sosyal medya algoritmalarına teslim etmeyi kendi irademle seçiyorum",
    "Dikkatimi dağıtacak uygulamalara girmek için kendi kurallarımı bozuyorum",
  )

  @Synchronized
  fun init(context: Context) {
    if (prefs != null) return
    val sharedPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs = sharedPrefs

    val isTestMode = sharedPrefs.getBoolean(KEY_TEST_MODE, false)
    val protectionEnabled = sharedPrefs.getBoolean(KEY_PROTECTION_ENABLED, true)
    val mindfulEnabled = sharedPrefs.getBoolean(KEY_MINDFUL_PAUSE_ENABLED, true)
    _isProtectionEnabled.value = protectionEnabled
    _isMindfulPauseEnabled.value = mindfulEnabled

    val focusConfig = FocusScheduleConfig(
      enabled = sharedPrefs.getBoolean(KEY_FOCUS_ENABLED, false),
      startHour = sharedPrefs.getInt(KEY_FOCUS_START_HOUR, 23),
      startMinute = sharedPrefs.getInt(KEY_FOCUS_START_MIN, 30),
      endHour = sharedPrefs.getInt(KEY_FOCUS_END_HOUR, 8),
      endMinute = sharedPrefs.getInt(KEY_FOCUS_END_MIN, 0),
    )
    engine.setFocusSchedule(focusConfig)
    engine.setConfig(if (isTestMode) LimitConfig.TEST_MODE else LimitConfig.NORMAL)

    val now = currentEpochSec()
    val loadedState = TrackedApp.entries.associateWith { app ->
      val raw = sharedPrefs.getString(KEY_PREFIX_TIMESTAMPS + app.name, "").orEmpty()
      if (raw.isBlank()) {
        emptyList()
      } else {
        raw.split(",").mapNotNull { it.toLongOrNull() }
      }
    }
    engine.importState(loadedState, now)
    loadOrRolloverDailyStats()
    refreshSnapshot(now)
  }

  fun setServiceConnected(connected: Boolean) {
    _isServiceConnected.value = connected
  }

  @Synchronized
  fun refreshSnapshot(nowEpochSec: Long = currentEpochSec()): UsageSnapshot {
    loadOrRolloverDailyStats()
    val nowTime = LocalTime.now()
    val isFocusActive = engine.getFocusSchedule().isInFocusWindow(nowTime.hour, nowTime.minute)
    val snap = engine.getSnapshot(nowEpochSec, isFocusActive)
    _snapshotFlow.value = snap
    return snap
  }

  @Synchronized
  fun recordTick(app: TrackedApp, nowEpochSec: Long = currentEpochSec()): TickResult {
    loadOrRolloverDailyStats()
    val nowTime = LocalTime.now()
    val isFocusActive = engine.getFocusSchedule().isInFocusWindow(nowTime.hour, nowTime.minute)
    val result = engine.recordUsageTick(app, nowEpochSec, isFocusActive)
    saveTimestampsForApp(app)

    if (!result.isBlocked || result.snapshot.appStatuses[app]?.usedSeconds != null) {
      // Aktif kullanım saniyesini günlük istatistiğe işle ve oturumu canlı tut
      approvedSessionLastSeenSec[app] = nowEpochSec
      incrementDailyAppUsageSecond(app)
    }
    _snapshotFlow.value = result.snapshot
    return result
  }

  @Synchronized
  fun shouldTriggerMindfulPause(app: TrackedApp, nowEpochSec: Long = currentEpochSec()): Boolean {
    if (!_isMindfulPauseEnabled.value) return false
    val lastSeen = approvedSessionLastSeenSec[app] ?: return true
    return (nowEpochSec - lastSeen) > MINDFUL_SESSION_TIMEOUT_SEC
  }

  @Synchronized
  fun approveMindfulSession(app: TrackedApp, nowEpochSec: Long = currentEpochSec()) {
    // Kullanıcı 3 saniyelik ekranı onayladı; uygulamaya geçiş için ek tolerans tanı
    approvedSessionLastSeenSec[app] = nowEpochSec + 10L
  }

  @Synchronized
  fun clearMindfulSession(app: TrackedApp) {
    approvedSessionLastSeenSec.remove(app)
  }

  @Synchronized
  fun setMindfulPauseEnabled(enabled: Boolean) {
    _isMindfulPauseEnabled.value = enabled
    prefs?.edit()?.putBoolean(KEY_MINDFUL_PAUSE_ENABLED, enabled)?.apply()
  }

  @Synchronized
  fun updateFocusSchedule(config: FocusScheduleConfig) {
    engine.setFocusSchedule(config)
    prefs?.edit()
      ?.putBoolean(KEY_FOCUS_ENABLED, config.enabled)
      ?.putInt(KEY_FOCUS_START_HOUR, config.startHour)
      ?.putInt(KEY_FOCUS_START_MIN, config.startMinute)
      ?.putInt(KEY_FOCUS_END_HOUR, config.endHour)
      ?.putInt(KEY_FOCUS_END_MIN, config.endMinute)
      ?.apply()
    refreshSnapshot()
  }

  @Synchronized
  fun recordBlockedAttempt() {
    loadOrRolloverDailyStats()
    val current = _dailyStatsFlow.value
    val updated = current.copy(blockedAttemptsCount = current.blockedAttemptsCount + 1)
    _dailyStatsFlow.value = updated
    prefs?.edit()?.putInt(KEY_STATS_BLOCKED_COUNT, updated.blockedAttemptsCount)?.apply()
  }

  @Synchronized
  fun recordMindfulQuit(app: TrackedApp) {
    clearMindfulSession(app)
    loadOrRolloverDailyStats()
    val current = _dailyStatsFlow.value
    val updated = current.copy(mindfulQuitCount = current.mindfulQuitCount + 1)
    _dailyStatsFlow.value = updated
    prefs?.edit()?.putInt(KEY_STATS_MINDFUL_QUIT_COUNT, updated.mindfulQuitCount)?.apply()
  }

  @Synchronized
  fun setTestMode(enabled: Boolean) {
    val newConfig = if (enabled) LimitConfig.TEST_MODE else LimitConfig.NORMAL
    engine.setConfig(newConfig)
    prefs?.edit()?.putBoolean(KEY_TEST_MODE, enabled)?.apply()
    refreshSnapshot()
  }

  @Synchronized
  fun setProtectionEnabled(enabled: Boolean) {
    _isProtectionEnabled.value = enabled
    prefs?.edit()?.putBoolean(KEY_PROTECTION_ENABLED, enabled)?.apply()
    refreshSnapshot()
  }

  @Synchronized
  fun resetAllUsage() {
    engine.clearAllUsage()
    approvedSessionLastSeenSec.clear()
    val editor = prefs?.edit()
    TrackedApp.entries.forEach { app ->
      editor?.remove(KEY_PREFIX_TIMESTAMPS + app.name)
    }
    editor?.apply()
    refreshSnapshot()
  }

  @Synchronized
  fun grantStrictBypassForSeconds(durationSeconds: Long = 60L) {
    val until = currentEpochSec() + durationSeconds
    prefs?.edit()?.putLong(KEY_STRICT_BYPASS_UNTIL, until)?.apply()
  }

  @Synchronized
  fun hasActiveStrictBypass(nowEpochSec: Long = currentEpochSec()): Boolean {
    val until = prefs?.getLong(KEY_STRICT_BYPASS_UNTIL, 0L) ?: 0L
    return nowEpochSec < until
  }

  fun generateStrictChallenge(): String {
    val sentence = strictSentences.random()
    val code = Random.nextInt(1000, 9999)
    return "$sentence - Kod: $code"
  }

  private fun loadOrRolloverDailyStats() {
    val sharedPrefs = prefs ?: return
    val today = todayDateKey()
    val savedDate = sharedPrefs.getString(KEY_STATS_DATE, "")

    if (savedDate != today) {
      val edit = sharedPrefs.edit()
      edit.putString(KEY_STATS_DATE, today)
      edit.putInt(KEY_STATS_BLOCKED_COUNT, 0)
      edit.putInt(KEY_STATS_MINDFUL_QUIT_COUNT, 0)
      TrackedApp.entries.forEach { app ->
        edit.putInt(KEY_PREFIX_DAILY_APP_SEC + app.name, 0)
      }
      edit.apply()
      _dailyStatsFlow.value = DailyStats(
        dateKey = today,
        todayAppUsedSeconds = TrackedApp.entries.associateWith { 0 },
        blockedAttemptsCount = 0,
        mindfulQuitCount = 0,
      )
    } else {
      val appMap = TrackedApp.entries.associateWith { app ->
        sharedPrefs.getInt(KEY_PREFIX_DAILY_APP_SEC + app.name, 0)
      }
      _dailyStatsFlow.value = DailyStats(
        dateKey = today,
        todayAppUsedSeconds = appMap,
        blockedAttemptsCount = sharedPrefs.getInt(KEY_STATS_BLOCKED_COUNT, 0),
        mindfulQuitCount = sharedPrefs.getInt(KEY_STATS_MINDFUL_QUIT_COUNT, 0),
      )
    }
  }

  private fun incrementDailyAppUsageSecond(app: TrackedApp) {
    val sharedPrefs = prefs ?: return
    val current = _dailyStatsFlow.value
    val newSec = (current.todayAppUsedSeconds[app] ?: 0) + 1
    val newMap = current.todayAppUsedSeconds.toMutableMap().apply { put(app, newSec) }
    _dailyStatsFlow.value = current.copy(todayAppUsedSeconds = newMap)
    sharedPrefs.edit().putInt(KEY_PREFIX_DAILY_APP_SEC + app.name, newSec).apply()
  }

  private fun saveTimestampsForApp(app: TrackedApp) {
    val state = engine.exportState()[app].orEmpty()
    prefs?.edit()
      ?.putString(KEY_PREFIX_TIMESTAMPS + app.name, state.joinToString(","))
      ?.apply()
  }

  private fun todayDateKey(): String = LocalDate.now().toString()

  fun currentEpochSec(): Long = System.currentTimeMillis() / 1000L
}
