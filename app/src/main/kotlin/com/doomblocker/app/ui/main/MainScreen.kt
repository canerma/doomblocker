package com.doomblocker.app.ui.main

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.doomblocker.app.adblock.DoomBlockerDnsVpnService
import com.doomblocker.app.data.DailyStats
import com.doomblocker.app.data.UsageRepository
import com.doomblocker.app.domain.AppUsageStatus
import com.doomblocker.app.domain.BlockReason
import com.doomblocker.app.domain.FocusScheduleConfig
import com.doomblocker.app.domain.TrackedApp
import com.doomblocker.app.domain.UsageSnapshot
import com.doomblocker.app.service.DoomBlockerAccessibilityService
import com.doomblocker.app.ui.block.formatSeconds
import com.doomblocker.app.ui.block.formatWindow
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  LaunchedEffect(Unit) {
    UsageRepository.init(context)
  }

  val snapshot by UsageRepository.snapshotFlow.collectAsStateWithLifecycle()
  val dailyStats by UsageRepository.dailyStatsFlow.collectAsStateWithLifecycle()
  val isProtectionEnabled by UsageRepository.isProtectionEnabled.collectAsStateWithLifecycle()
  val isMindfulPauseEnabled by UsageRepository.isMindfulPauseEnabled.collectAsStateWithLifecycle()
  val isSponsoredSkipEnabled by UsageRepository.isSponsoredSkipEnabled.collectAsStateWithLifecycle()
  val isDnsVpnRunning by UsageRepository.isDnsVpnRunning.collectAsStateWithLifecycle()
  val lastBlockedDomain by UsageRepository.lastBlockedDomain.collectAsStateWithLifecycle()
  val isServiceConnectedFlow by UsageRepository.isServiceConnected.collectAsStateWithLifecycle()

  var isAccessibilityEnabledInSettings by remember {
    mutableStateOf(isAccessibilityServiceEnabled(context))
  }
  var isBatteryOptimizationIgnored by remember {
    mutableStateOf(checkBatteryOptimizationIgnored(context))
  }
  var hasNotificationPermission by remember {
    mutableStateOf(checkNotificationPermission(context))
  }

  val notifPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    hasNotificationPermission = granted
  }

  val vpnPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      startDnsVpnService(context)
    }
  }

  LaunchedEffect(Unit) {
    while (true) {
      UsageRepository.refreshSnapshot()
      isAccessibilityEnabledInSettings = isAccessibilityServiceEnabled(context)
      isBatteryOptimizationIgnored = checkBatteryOptimizationIgnored(context)
      hasNotificationPermission = checkNotificationPermission(context)
      delay(1000L)
    }
  }

  val serviceActive = isAccessibilityEnabledInSettings || isServiceConnectedFlow

  var pendingStrictAction by remember { mutableStateOf<(() -> Unit)?>(null) }
  var pendingStrictActionTitle by remember { mutableStateOf("") }

  fun requestStrictAction(title: String, action: () -> Unit) {
    if (UsageRepository.hasActiveStrictBypass()) {
      action()
    } else {
      pendingStrictActionTitle = title
      pendingStrictAction = action
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Text(
            text = "🛡️ DoomBlocker",
            fontWeight = FontWeight.Bold,
          )
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = Color(0xFF151821),
          titleContentColor = Color.White,
        ),
      )
    },
    containerColor = Color(0xFF0F1117),
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // 1. Erişilebilirlik Servisi Durum Kartı
      ServiceStatusCard(
        isServiceActive = serviceActive,
        isProtectionEnabled = isProtectionEnabled,
        isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
        hasNotificationPermission = hasNotificationPermission,
        onOpenAccessibilitySettings = {
          val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          context.startActivity(intent)
        },
        onRequestNotificationPermission = {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          }
        },
        onRequestBatteryOptimization = {
          runCatching {
            val intent = Intent(
              Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
              Uri.parse("package:${context.packageName}"),
            )
            context.startActivity(intent)
          }
        },
      )

      // 2. Bugünün Özeti & Kurtarılan Zaman Kartı
      DailyStatsSummaryCard(dailyStats = dailyStats)

      // 3. 2 Katmanlı Hibrit AdBlocker Kartı (Blackhole DNS + Sponsorlu Reklam Atlayıcı)
      HybridAdBlockerCard(
        isDnsVpnRunning = isDnsVpnRunning,
        isSponsoredSkipEnabled = isSponsoredSkipEnabled,
        dnsAdsBlockedCount = dailyStats.dnsAdsBlockedCount,
        sponsoredAdsSkippedCount = dailyStats.sponsoredAdsSkippedCount,
        lastBlockedDomain = lastBlockedDomain,
        onToggleDnsVpn = { enable ->
          if (enable) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
              vpnPermissionLauncher.launch(prepareIntent)
            } else {
              startDnsVpnService(context)
            }
          } else {
            stopDnsVpnService(context)
          }
        },
        onToggleSponsoredSkip = { enabled ->
          UsageRepository.setSponsoredSkipEnabled(enabled)
        },
      )

      // 4. Son 60 Dakika Genel Havuz (Hibrit Model) Kartı
      HybridPoolSummaryCard(snapshot = snapshot)

      // 5. Gece / Derin Odak Saatleri & Bilinçli Duraksama Ayarları
      FocusAndMindfulCard(
        context = context,
        focusSchedule = snapshot.focusSchedule,
        isFocusCurrentlyActive = snapshot.isFocusScheduleCurrentlyActive,
        isMindfulPauseEnabled = isMindfulPauseEnabled,
        onUpdateFocusSchedule = { newConfig ->
          if (snapshot.isFocusScheduleCurrentlyActive) {
            requestStrictAction("Aktif Odak Saatlerini Değiştir") {
              UsageRepository.updateFocusSchedule(newConfig)
            }
          } else {
            UsageRepository.updateFocusSchedule(newConfig)
          }
        },
        onToggleMindfulPause = { enabled ->
          if (!enabled && snapshot.isAnyAppBlocked) {
            requestStrictAction("3 Sn Duraksamayı Kapat") {
              UsageRepository.setMindfulPauseEnabled(false)
            }
          } else {
            UsageRepository.setMindfulPauseEnabled(enabled)
          }
        },
      )

      // 6. Uygulama Bazlı Durum Kartları (Instagram, X, Reddit, YouTube Shorts)
      Text(
        text = "Takip Edilen Uygulamalar (4 Uygulama)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
      )

      TrackedApp.entries.forEach { app ->
        val status = snapshot.appStatuses.getValue(app)
        val todayUsed = dailyStats.todayAppUsedSeconds[app] ?: 0
        AppUsageCard(
          status = status,
          todayUsedSeconds = todayUsed,
          warningThresholdSeconds = snapshot.config.warningThresholdSeconds,
        )
      }

      // 7. Test Modu & Koruma Yönetimi (Strict Mode Korumalı)
      StrictControlsCard(
        snapshot = snapshot,
        isProtectionEnabled = isProtectionEnabled,
        onToggleTestMode = { targetTestMode ->
          if (!targetTestMode) {
            UsageRepository.setTestMode(false)
          } else {
            if (snapshot.isAnyAppBlocked) {
              requestStrictAction("Test Modunu Aç") {
                UsageRepository.setTestMode(true)
              }
            } else {
              UsageRepository.setTestMode(true)
            }
          }
        },
        onToggleProtection = { enable ->
          if (enable) {
            UsageRepository.setProtectionEnabled(true)
          } else {
            requestStrictAction("Korumayı Devre Dışı Bırak") {
              UsageRepository.setProtectionEnabled(false)
            }
          }
        },
        onResetUsage = {
          requestStrictAction("Kullanım Sürelerini Sıfırla") {
            UsageRepository.resetAllUsage()
          }
        },
      )

      Spacer(modifier = Modifier.height(16.dp))
    }
  }

  if (pendingStrictAction != null) {
    StrictModeChallengeDialog(
      actionTitle = pendingStrictActionTitle,
      onDismiss = { pendingStrictAction = null },
      onVerified = {
        UsageRepository.grantStrictBypassForSeconds(60L)
        val act = pendingStrictAction
        pendingStrictAction = null
        act?.invoke()
      },
    )
  }
}

@Composable
private fun HybridAdBlockerCard(
  isDnsVpnRunning: Boolean,
  isSponsoredSkipEnabled: Boolean,
  dnsAdsBlockedCount: Int,
  sponsoredAdsSkippedCount: Int,
  lastBlockedDomain: String?,
  onToggleDnsVpn: (Boolean) -> Unit,
  onToggleSponsoredSkip: (Boolean) -> Unit,
) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF161F2C)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "🛡️ 2 Katmanlı Hibrit AdBlocker",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
        Surface(
          color = Color(0xFF0EA5E9).copy(alpha = 0.2f),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text(
            text = "Bugün: ${dnsAdsBlockedCount + sponsoredAdsSkippedCount} Reklam",
            color = Color(0xFF38BDF8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      // Katman 1: Blackhole DNS (VpnService)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "🕳️ Katman 1: Blackhole DNS",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
          Text(
            text = "Reklam ve takipçi sunucularını 0.0.0.0 Blackhole'a gömer (Engellenen: $dnsAdsBlockedCount).",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9CA3AF),
          )
          if (!lastBlockedDomain.isNullOrBlank()) {
            Text(
              text = "Son engellenen: $lastBlockedDomain",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF38BDF8),
            )
          }
        }
        Switch(
          checked = isDnsVpnRunning,
          onCheckedChange = onToggleDnsVpn,
          colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF0EA5E9),
          ),
        )
      }

      // Katman 2: Instagram / X / Reddit Sponsorlu Gönderi Atlayıcı
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "⚡ Katman 2: 'Sponsorlu' Reklam Atlayıcı",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
          Text(
            text = "Instagram (Hikaye/Akış/Reels), X ve Reddit'teki 'Sponsorlu' reklamları otomatik kaydırır/atlar (Atlanan: $sponsoredAdsSkippedCount).",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9CA3AF),
          )
        }
        Switch(
          checked = isSponsoredSkipEnabled,
          onCheckedChange = onToggleSponsoredSkip,
          colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF10B981),
          ),
        )
      }
    }
  }
}

@Composable
private fun DailyStatsSummaryCard(dailyStats: DailyStats) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF172030)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "📊 Bugünün Özeti",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
        Surface(
          color = Color(0xFF10B981).copy(alpha = 0.2f),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text(
            text = "⚡ ~${dailyStats.estimatedSavedMinutes} dk Kurtarıldı",
            color = Color(0xFF4ADE80),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StatMetricBox(
          title = "Bugün Süre",
          value = formatSeconds(dailyStats.todayTotalUsedSeconds),
          valueColor = Color(0xFF60A5FA),
          modifier = Modifier.weight(1f),
        )
        StatMetricBox(
          title = "Engel / Vazgeçiş",
          value = "${dailyStats.totalPreventedSessions} kez",
          valueColor = Color(0xFF4ADE80),
          modifier = Modifier.weight(1f),
        )
        StatMetricBox(
          title = "Reklam Engeli",
          value = "${dailyStats.totalAdsBlockedOrSkipped}",
          valueColor = Color(0xFF38BDF8),
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun StatMetricBox(
  title: String,
  value: String,
  valueColor: Color,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
      .padding(vertical = 10.dp, horizontal = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF9CA3AF),
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.ExtraBold,
        color = valueColor,
      )
    }
  }
}

@Composable
private fun FocusAndMindfulCard(
  context: Context,
  focusSchedule: FocusScheduleConfig,
  isFocusCurrentlyActive: Boolean,
  isMindfulPauseEnabled: Boolean,
  onUpdateFocusSchedule: (FocusScheduleConfig) -> Unit,
  onToggleMindfulPause: (Boolean) -> Unit,
) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D27)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // 1. Gece / Derin Odak Saatleri
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "🌙 Gece / Derin Odak Saatleri",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = Color.White,
            )
            if (isFocusCurrentlyActive) {
              Spacer(modifier = Modifier.padding(horizontal = 4.dp))
              Surface(
                color = Color(0xFF6366F1).copy(alpha = 0.25f),
                shape = RoundedCornerShape(6.dp),
              ) {
                Text(
                  text = "AKTİF",
                  color = Color(0xFF818CF8),
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
          }
          Text(
            text = "Belirlenen saatler arasında tüm uygulamaları sıfır süreyle tam kilitler.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9CA3AF),
          )
        }
        Switch(
          checked = focusSchedule.enabled,
          onCheckedChange = { checked ->
            onUpdateFocusSchedule(focusSchedule.copy(enabled = checked))
          },
          colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF6366F1),
          ),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = {
            TimePickerDialog(
              context,
              { _, hourOfDay, minute ->
                onUpdateFocusSchedule(
                  focusSchedule.copy(startHour = hourOfDay, startMinute = minute)
                )
              },
              focusSchedule.startHour,
              focusSchedule.startMinute,
              true,
            ).show()
          },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          Text(
            text = "Başlangıç: %02d:%02d".format(focusSchedule.startHour, focusSchedule.startMinute),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
          )
        }

        OutlinedButton(
          onClick = {
            TimePickerDialog(
              context,
              { _, hourOfDay, minute ->
                onUpdateFocusSchedule(
                  focusSchedule.copy(endHour = hourOfDay, endMinute = minute)
                )
              },
              focusSchedule.endHour,
              focusSchedule.endMinute,
              true,
            ).show()
          },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          Text(
            text = "Bitiş: %02d:%02d".format(focusSchedule.endHour, focusSchedule.endMinute),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }

      // 2. 3 Saniyelik Bilinçli Duraksama
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "🧘 3 Sn Bilinçli Duraksama",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
          Text(
            text = "Uygulamaları ilk açtığında 3 saniye 'Neden giriyorsun?' sorusu sorar.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9CA3AF),
          )
        }
        Switch(
          checked = isMindfulPauseEnabled,
          onCheckedChange = onToggleMindfulPause,
          colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF10B981),
          ),
        )
      }
    }
  }
}

@Composable
private fun ServiceStatusCard(
  isServiceActive: Boolean,
  isProtectionEnabled: Boolean,
  isBatteryOptimizationIgnored: Boolean,
  hasNotificationPermission: Boolean,
  onOpenAccessibilitySettings: () -> Unit,
  onRequestNotificationPermission: () -> Unit,
  onRequestBatteryOptimization: () -> Unit,
) {
  val bgColor = when {
    !isServiceActive -> Color(0xFF3B181E)
    !isProtectionEnabled -> Color(0xFF382C14)
    else -> Color(0xFF132F23)
  }
  val accentColor = when {
    !isServiceActive -> Color(0xFFF87171)
    !isProtectionEnabled -> Color(0xFFFBBF24)
    else -> Color(0xFF4ADE80)
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = bgColor),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = when {
            !isServiceActive -> "⚠️ Erişilebilirlik İzni Gerekli"
            !isProtectionEnabled -> "⏸️ Koruma Duraklatıldı"
            else -> "✅ DoomBlocker Aktif"
          },
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = accentColor,
        )
      }

      Text(
        text = when {
          !isServiceActive -> {
            "Instagram, X, Reddit ve YouTube Shorts ekranlarını algılayabilmek için telefonun Erişilebilirlik ayarlarından DoomBlocker'ı aktif etmelisin."
          }
          !isProtectionEnabled -> {
            "Strict Mode doğrulamasıyla koruma geçici olarak kapatıldı."
          }
          else -> {
            "Arka plan servisi çalışıyor. Son 1 dakika kala uyarı verir, sponsorlu reklamları atlar ve süre dolunca otomatik engeller."
          }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFE5E7EB),
      )

      if (!isServiceActive) {
        Button(
          onClick = onOpenAccessibilitySettings,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "1. Adım: Erişilebilirlik İznini Ver",
            color = Color.White,
            fontWeight = FontWeight.Bold,
          )
        }
      }

      if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        OutlinedButton(
          onClick = onRequestNotificationPermission,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "🔔 1 Dk Kala Uyarı İçin Bildirim İzni Ver",
            color = Color.White,
          )
        }
      }

      if (!isBatteryOptimizationIgnored) {
        OutlinedButton(
          onClick = onRequestBatteryOptimization,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "🔋 Arka Planda Kapanmaması İçin Pil İznini Aç",
            color = Color.White,
          )
        }
      }
    }
  }
}

@Composable
private fun HybridPoolSummaryCard(snapshot: UsageSnapshot) {
  val config = snapshot.config
  val progress = (snapshot.totalUsedSeconds.toFloat() / config.totalLimitSeconds.toFloat()).coerceIn(0f, 1f)

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D27)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Genel Hibrit Havuz",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
        Surface(
          color = if (config.isTestMode) Color(0xFF4F46E5) else Color(0xFF2563EB),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text(
            text = if (config.isTestMode) "⚡ Test Modu (60 sn)" else "Normal Mod (60 dk)",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      Text(
        text = "Son ${formatWindow(config.windowSeconds)} içinde 4 uygulamanın toplam kullanımı:",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF9CA3AF),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
      ) {
        Text(
          text = "${formatSeconds(snapshot.totalUsedSeconds)} / ${formatSeconds(config.totalLimitSeconds)}",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.ExtraBold,
          color = Color.White,
        )
        Text(
          text = "Kalan Havuz: ${formatSeconds(snapshot.totalRemainingSeconds)}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = if (snapshot.totalRemainingSeconds == 0) Color(0xFFF87171) else Color(0xFF60A5FA),
        )
      }

      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
          .fillMaxWidth()
          .height(10.dp)
          .clip(RoundedCornerShape(5.dp)),
        color = if (progress >= 1f) Color(0xFFEF4444) else Color(0xFF3B82F6),
        trackColor = Color(0xFF2D3343),
      )

      Text(
        text = "Kural: Her uygulama en fazla ${formatSeconds(config.perAppLimitSeconds)}, 4 uygulama toplamda en fazla ${formatSeconds(config.totalLimitSeconds)} kullanılabilir.",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF9CA3AF),
      )
    }
  }
}

@Composable
private fun AppUsageCard(
  status: AppUsageStatus,
  todayUsedSeconds: Int,
  warningThresholdSeconds: Int,
) {
  val progress = (status.usedSeconds.toFloat() / status.perAppLimitSeconds.toFloat()).coerceIn(0f, 1f)
  val isWarning = !status.isBlocked && status.effectiveRemainingSeconds <= warningThresholdSeconds

  val statusBadgeColor = when {
    status.isBlocked -> Color(0xFFEF4444)
    isWarning -> Color(0xFFF59E0B)
    else -> Color(0xFF10B981)
  }

  val statusBadgeText = when {
    status.blockReason == BlockReason.FOCUS_SCHEDULE_ACTIVE -> "ODAK KİLİDİ 🌙"
    status.isBlocked -> "KİLİTLİ 🔒"
    isWarning -> "SON ${formatSeconds(status.effectiveRemainingSeconds)} ⚠️"
    else -> "SERBEST"
  }

  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF181B24)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            text = status.app.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
          Text(
            text = "Bugün toplam: ${formatSeconds(todayUsedSeconds)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF9CA3AF),
          )
        }
        Surface(
          color = statusBadgeColor.copy(alpha = 0.2f),
          shape = RoundedCornerShape(6.dp),
        ) {
          Text(
            text = statusBadgeText,
            color = statusBadgeColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }

      if (status.app.isShortsOnly) {
        Text(
          text = "Normal YouTube videoları serbesttir; sadece Shorts ekranı sayılır ve engellenir.",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF9CA3AF),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "Son Pencere: ${formatSeconds(status.usedSeconds)} / ${formatSeconds(status.perAppLimitSeconds)}",
          style = MaterialTheme.typography.bodyMedium,
          color = Color(0xFFD1D5DB),
        )
        Text(
          text = "Kullanılabilir: ${formatSeconds(status.effectiveRemainingSeconds)}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = Color.White,
        )
      }

      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
          .fillMaxWidth()
          .height(8.dp)
          .clip(RoundedCornerShape(4.dp)),
        color = statusBadgeColor,
        trackColor = Color(0xFF2A2F3D),
      )

      if (status.isBlocked) {
        val reasonText = when (status.blockReason) {
          BlockReason.FOCUS_SCHEDULE_ACTIVE -> "Gece / Derin Odak saatleri aktif olduğu için kilitli."
          BlockReason.PER_APP_LIMIT_REACHED ->
            "Uygulama başı limit (${formatSeconds(status.perAppLimitSeconds)}) doldu. Yeni hak: ${formatSeconds(status.secondsUntilUnblocked.toInt())}"
          else ->
            "Genel toplam havuz limiti doldu. Yeni hak: ${formatSeconds(status.secondsUntilUnblocked.toInt())}"
        }
        Text(
          text = reasonText,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.SemiBold,
          color = Color(0xFFF87171),
        )
      }
    }
  }
}

@Composable
private fun StrictControlsCard(
  snapshot: UsageSnapshot,
  isProtectionEnabled: Boolean,
  onToggleTestMode: (Boolean) -> Unit,
  onToggleProtection: (Boolean) -> Unit,
  onResetUsage: () -> Unit,
) {
  val isTestMode = snapshot.config.isTestMode

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D27)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "⚙️ Test Modu & Strict Mode Ayarları",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
      )

      Text(
        text = if (isTestMode) {
          "Şu an Hızlandırılmış Test Modu aktif: Son 60 saniyede uygulama başı 20 sn, toplam 30 sn limit (son 10 sn kala uyarı)."
        } else {
          "Şu an Normal Mod aktif: Son 60 dakikada uygulama başı 5 dk, toplam 7 dk limit (son 1 dk kala uyarı)."
        },
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF9CA3AF),
      )

      Button(
        onClick = { onToggleTestMode(!isTestMode) },
        colors = ButtonDefaults.buttonColors(
          containerColor = if (isTestMode) Color(0xFF2563EB) else Color(0xFF4F46E5),
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = if (isTestMode) {
            "Normal Moda Geç (60 dk: 5 dk / 7 dk)"
          } else {
            "⚡ Hızlandırılmış Test Moduna Geç (60 sn: 20 sn / 30 sn)"
          },
          color = Color.White,
          fontWeight = FontWeight.SemiBold,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = { onToggleProtection(!isProtectionEnabled) },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          Text(
            text = if (isProtectionEnabled) "🔒 Korumayı Kapat" else "▶️ Korumayı Aç",
            color = Color.White,
          )
        }

        OutlinedButton(
          onClick = { onResetUsage() },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          Text(
            text = "🔒 Süreleri Sıfırla",
            color = Color.White,
          )
        }
      }
    }
  }
}

@Composable
private fun StrictModeChallengeDialog(
  actionTitle: String,
  onDismiss: () -> Unit,
  onVerified: () -> Unit,
) {
  var remainingWaitSeconds by remember { mutableIntStateOf(30) }
  val targetPhrase = remember { UsageRepository.generateStrictChallenge() }
  var typedText by remember { mutableStateOf("") }
  var pasteError by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    while (remainingWaitSeconds > 0) {
      delay(1000L)
      remainingWaitSeconds--
    }
  }

  val isMatch = typedText == targetPhrase && remainingWaitSeconds == 0

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFF1A1D27),
    titleContentColor = Color.White,
    textContentColor = Color(0xFFE5E7EB),
    title = {
      Text(
        text = "🔒 Strict Mode: $actionTitle",
        fontWeight = FontWeight.Bold,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (remainingWaitSeconds > 0) {
          Text(
            text = "Anlık dürtüyle korumayı kapatmanı önlemek için lütfen bekle:",
            style = MaterialTheme.typography.bodyMedium,
          )
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color(0xFF262B3A), RoundedCornerShape(12.dp))
              .padding(16.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "$remainingWaitSeconds saniye",
              style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.ExtraBold,
              color = Color(0xFFFBBF24),
            )
          }
          Text(
            text = "Bu pencereyi kapatırsan sayaç baştan başlar.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9CA3AF),
          )
        } else {
          Text(
            text = "Devam etmek için aşağıdaki cümleyi kopyala-yapıştır yapmadan, büyük/küçük harflere dikkat ederek birebir yaz:",
            style = MaterialTheme.typography.bodySmall,
          )
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color(0xFF262B3A), RoundedCornerShape(8.dp))
              .padding(12.dp),
          ) {
            Text(
              text = targetPhrase,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = Color(0xFFFCA5A5),
            )
          }

          OutlinedTextField(
            value = typedText,
            onValueChange = { newValue ->
              if (newValue.length - typedText.length > 2) {
                pasteError = true
              } else {
                pasteError = false
                typedText = newValue
              }
            },
            label = { Text("Cümleyi buraya tek tek yaz") },
            modifier = Modifier.fillMaxWidth(),
          )

          if (pasteError) {
            Text(
              text = "⚠️ Kopyala-yapıştır veya otomatik tamamlama kullanılamaz! Harfleri tek tek yazmalısın.",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFFF87171),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onVerified,
        enabled = isMatch,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
      ) {
        Text("Onayla", color = Color.White)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Vazgeç (Korumada Kal)", color = Color(0xFF60A5FA))
      }
    },
  )
}

private fun startDnsVpnService(context: Context) {
  val intent = Intent(context, DoomBlockerDnsVpnService::class.java).apply {
    action = DoomBlockerDnsVpnService.ACTION_START_VPN
  }
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    context.startForegroundService(intent)
  } else {
    context.startService(intent)
  }
}

private fun stopDnsVpnService(context: Context) {
  val intent = Intent(context, DoomBlockerDnsVpnService::class.java).apply {
    action = DoomBlockerDnsVpnService.ACTION_STOP_VPN
  }
  context.startService(intent)
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
  val expectedComponentName = ComponentName(context, DoomBlockerAccessibilityService::class.java)
  val enabledServicesSetting = Settings.Secure.getString(
    context.contentResolver,
    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
  ) ?: return false

  return enabledServicesSetting.split(':')
    .mapNotNull { ComponentName.unflattenFromString(it) }
    .any { it == expectedComponentName }
}

private fun checkBatteryOptimizationIgnored(context: Context): Boolean {
  val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
  return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun checkNotificationPermission(context: Context): Boolean {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
  } else {
    true
  }
}
