package com.doomblocker.app.ui.block

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doomblocker.app.MainActivity
import com.doomblocker.app.data.UsageRepository
import com.doomblocker.app.domain.BlockReason
import com.doomblocker.app.domain.TrackedApp
import com.doomblocker.app.ui.theme.DoomBlockerTheme
import kotlinx.coroutines.delay

class BlockActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    UsageRepository.init(applicationContext)
    enableEdgeToEdge()

    val appNameKey = intent.getStringExtra(EXTRA_TRACKED_APP)
    val trackedApp = appNameKey?.let { runCatching { TrackedApp.valueOf(it) }.getOrNull() }
    val isAntiTamper = intent.getBooleanExtra(EXTRA_IS_ANTI_TAMPER, false)

    setContent {
      DoomBlockerTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = Color(0xFF121216),
        ) {
          BlockScreenContent(
            trackedApp = trackedApp,
            isAntiTamper = isAntiTamper,
            onGoHome = { navigateToHomeLauncher() },
            onOpenDoomBlocker = {
              val mainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
              }
              startActivity(mainIntent)
              finish()
            },
          )
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
  }

  private fun navigateToHomeLauncher() {
    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
      addCategory(Intent.CATEGORY_HOME)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(homeIntent)
    finish()
  }

  companion object {
    const val EXTRA_TRACKED_APP = "extra_tracked_app"
    const val EXTRA_IS_ANTI_TAMPER = "extra_is_anti_tamper"

    fun createIntent(
      context: Context,
      trackedApp: TrackedApp? = null,
      isAntiTamper: Boolean = false,
    ): Intent {
      return Intent(context, BlockActivity::class.java).apply {
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        trackedApp?.let { putExtra(EXTRA_TRACKED_APP, it.name) }
        putExtra(EXTRA_IS_ANTI_TAMPER, isAntiTamper)
      }
    }
  }
}

@Composable
private fun BlockScreenContent(
  trackedApp: TrackedApp?,
  isAntiTamper: Boolean,
  onGoHome: () -> Unit,
  onOpenDoomBlocker: () -> Unit,
) {
  BackHandler {
    onGoHome()
  }

  val snapshot by UsageRepository.snapshotFlow.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    while (true) {
      UsageRepository.refreshSnapshot()
      delay(1000L)
    }
  }

  val appStatus = trackedApp?.let { snapshot.appStatuses[it] }
  val waitSeconds = appStatus?.secondsUntilUnblocked ?: 0L
  val isFocusScheduleBlock = appStatus?.blockReason == BlockReason.FOCUS_SCHEDULE_ACTIVE ||
    snapshot.isFocusScheduleCurrentlyActive

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF121216))
      .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = when {
          isAntiTamper -> "🔒"
          isFocusScheduleBlock -> "🌙"
          else -> "🛑"
        },
        fontSize = 64.sp,
      )
      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = when {
          isAntiTamper -> "Strict Mode Koruması Aktif!"
          isFocusScheduleBlock -> "Derin Odak / Gece Modu Aktif"
          else -> "${trackedApp?.displayName ?: "Uygulama"} Kilitlendi"
        },
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center,
      )

      Spacer(modifier = Modifier.height(12.dp))

      val explanationText = when {
        isAntiTamper -> {
          "Aktif bir kısıtlama varken Erişilebilirlik ayarlarından DoomBlocker'ı doğrudan kapatamazsın.\n\n" +
            "Korumayı değiştirmek için önce DoomBlocker uygulamasında 30 saniyelik Strict Mode doğrulamasını tamamlamalısın."
        }
        isFocusScheduleBlock -> {
          "Belirlediğin Derin Odak / Gece saatleri (${snapshot.focusSchedule.formattedRange()}) içerisindesin.\n\n" +
            "Bu saat aralığında tüm sosyal medya uygulamaları tam kilit altındadır."
        }
        appStatus?.blockReason == BlockReason.PER_APP_LIMIT_REACHED -> {
          val limitMin = snapshot.config.perAppLimitSeconds / 60
          val limitSec = snapshot.config.perAppLimitSeconds % 60
          val limitLabel = if (limitMin > 0) "$limitMin dakikalık" else "$limitSec saniyelik"
          "Son ${formatWindow(snapshot.config.windowSeconds)} içindeki $limitLabel ${trackedApp.displayName} kullanım hakkın doldu.\n" +
            "(Genel havuzda kalan süren: ${formatSeconds(snapshot.totalRemainingSeconds)})"
        }
        else -> {
          val totalMin = snapshot.config.totalLimitSeconds / 60
          val totalSec = snapshot.config.totalLimitSeconds % 60
          val totalLabel = if (totalMin > 0) "$totalMin dakikalık" else "$totalSec saniyelik"
          "Son ${formatWindow(snapshot.config.windowSeconds)} içindeki $totalLabel genel toplam kullanım havuzun doldu."
        }
      }

      Text(
        text = explanationText,
        style = MaterialTheme.typography.bodyLarge,
        color = Color(0xFFD0D4DC),
        textAlign = TextAlign.Center,
      )

      Spacer(modifier = Modifier.height(28.dp))

      if (!isAntiTamper) {
        Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF1F222A)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            if (isFocusScheduleBlock) {
              Text(
                text = "Odak Saatleri Bitişi",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF9CA3AF),
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "%02d:%02d".format(snapshot.focusSchedule.endHour, snapshot.focusSchedule.endMinute),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF818CF8),
              )
            } else {
              Text(
                text = if (waitSeconds > 0) "Yeni Süre Açılmasına Kalan" else "Yeni Süre Açıldı!",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF9CA3AF),
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = if (waitSeconds > 0) formatSeconds(waitSeconds.toInt()) else "Hazır ✅",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = if (waitSeconds > 0) Color(0xFFFF6B6B) else Color(0xFF4ADE80),
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(28.dp))
      }

      Button(
        onClick = onGoHome,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(54.dp),
      ) {
        Text(
          text = "Telefon Ana Ekranına Dön",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedButton(
        onClick = onOpenDoomBlocker,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp),
      ) {
        Text(
          text = "DoomBlocker Durum Ekranını Aç",
          color = Color(0xFFE5E7EB),
        )
      }
    }
  }
}

internal fun formatSeconds(totalSeconds: Int): String {
  val clamped = totalSeconds.coerceAtLeast(0)
  val mins = clamped / 60
  val secs = clamped % 60
  return if (mins > 0) {
    "%d dk %02d sn".format(mins, secs)
  } else {
    "%d sn".format(secs)
  }
}

internal fun formatWindow(windowSeconds: Int): String {
  return if (windowSeconds >= 60) {
    "${windowSeconds / 60} dakika"
  } else {
    "$windowSeconds saniye"
  }
}
