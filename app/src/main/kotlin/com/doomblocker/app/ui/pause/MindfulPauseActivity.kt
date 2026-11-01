package com.doomblocker.app.ui.pause

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doomblocker.app.data.UsageRepository
import com.doomblocker.app.domain.TrackedApp
import com.doomblocker.app.ui.block.formatSeconds
import com.doomblocker.app.ui.theme.DoomBlockerTheme
import kotlinx.coroutines.delay

class MindfulPauseActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    UsageRepository.init(applicationContext)
    enableEdgeToEdge()

    val appNameKey = intent.getStringExtra(EXTRA_TRACKED_APP)
    val trackedApp = appNameKey?.let { runCatching { TrackedApp.valueOf(it) }.getOrNull() }
      ?: TrackedApp.INSTAGRAM

    setContent {
      DoomBlockerTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = Color(0xFF0E131A),
        ) {
          MindfulPauseContent(
            trackedApp = trackedApp,
            onQuitAndGoHome = {
              UsageRepository.recordMindfulQuit(trackedApp)
              navigateToHomeLauncher()
            },
            onContinueToApp = {
              UsageRepository.approveMindfulSession(trackedApp)
              finish()
            },
          )
        }
      }
    }
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
    private const val EXTRA_TRACKED_APP = "extra_tracked_app"

    fun createIntent(context: Context, trackedApp: TrackedApp): Intent {
      return Intent(context, MindfulPauseActivity::class.java).apply {
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        putExtra(EXTRA_TRACKED_APP, trackedApp.name)
      }
    }
  }
}

@Composable
private fun MindfulPauseContent(
  trackedApp: TrackedApp,
  onQuitAndGoHome: () -> Unit,
  onContinueToApp: () -> Unit,
) {
  BackHandler {
    onQuitAndGoHome()
  }

  val snapshot by UsageRepository.snapshotFlow.collectAsStateWithLifecycle()
  val appStatus = snapshot.appStatuses[trackedApp]
  val effectiveRemaining = appStatus?.effectiveRemainingSeconds ?: 0

  var countdownSeconds by remember { mutableIntStateOf(3) }

  LaunchedEffect(Unit) {
    while (countdownSeconds > 0) {
      delay(1000L)
      countdownSeconds--
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xFF0E131A))
      .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = "🧘",
        fontSize = 60.sp,
      )
      Spacer(modifier = Modifier.height(14.dp))

      Text(
        text = "Bilinçli Duraksama",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
      )

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = "${trackedApp.displayName} açılıyor...",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF60A5FA),
        fontWeight = FontWeight.SemiBold,
      )

      Spacer(modifier = Modifier.height(20.dp))

      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF18202C)),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Şu an gerçekten belirli bir şeye bakmak için mi giriyorsun, yoksa sadece el alışkanlığı mı?",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFE5E7EB),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Kalan Kullanılabilir Hakkın: ${formatSeconds(effectiveRemaining)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF38BDF8),
          )
        }
      }

      Spacer(modifier = Modifier.height(28.dp))

      Button(
        onClick = onQuitAndGoHome,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp),
      ) {
        Text(
          text = "✋ Vazgeç & Ana Ekrana Dön (+8 dk Kazan)",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      OutlinedButton(
        onClick = onContinueToApp,
        enabled = countdownSeconds == 0,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp),
      ) {
        Text(
          text = if (countdownSeconds > 0) {
            "Giriş için bekle ($countdownSeconds sn)..."
          } else {
            "Amacım Var, ${trackedApp.displayName}'a Devam Et →"
          },
          color = if (countdownSeconds > 0) Color(0xFF6B7280) else Color(0xFFD1D5DB),
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}
