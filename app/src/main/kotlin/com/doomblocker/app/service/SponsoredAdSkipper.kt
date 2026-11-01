package com.doomblocker.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

enum class SponsoredAdType {
  STORY_AD,
  FEED_OR_REEL_AD,
}

object SponsoredAdSkipper {

  private val exactAdLabels = setOf(
    "sponsorlu",
    "sponsored",
    "promoted",
    "öne çıkarılan",
  )

  private val adViewIdMarkers = listOf(
    "sponsored_label",
    "promoted_badge",
    "ad_badge",
    "reel_viewer_subtitle",
    "row_feed_photo_profile_metalabel",
    "secondary_label",
  )

  fun detectVisibleSponsoredAd(
    rootNode: AccessibilityNodeInfo?,
    screenHeight: Int,
  ): SponsoredAdType? {
    if (rootNode == null || screenHeight <= 0) return null

    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(rootNode)
    var visited = 0
    val maxNodes = 220

    var isStoryViewerContainerPresent = false
    var detectedAdBounds: Rect? = null
    var detectedAdViewId: String = ""

    while (queue.isNotEmpty() && visited < maxNodes) {
      val node = queue.removeFirst()
      visited++

      if (node.isVisibleToUser) {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (viewId.contains("reel_viewer") || viewId.contains("story_viewer")) {
          isStoryViewerContainerPresent = true
        }

        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()

        val isExactMatch = text in exactAdLabels || desc in exactAdLabels
        val isSubtitleAdMatch = adViewIdMarkers.any { viewId.contains(it) } &&
          exactAdLabels.any { label -> text.startsWith(label) || desc.startsWith(label) }

        if (isExactMatch || isSubtitleAdMatch) {
          val rect = Rect()
          node.getBoundsInScreen(rect)
          // Ekranda görünür ana alanda mı kontrol et
          if (rect.top in 40..(screenHeight * 0.82).toInt() && rect.height() > 0) {
            detectedAdBounds = rect
            detectedAdViewId = viewId
            break
          }
        }
      }

      for (i in 0 until node.childCount) {
        node.getChild(i)?.let { queue.add(it) }
      }
    }

    val bounds = detectedAdBounds ?: return null

    // Instagram Story reklamlarında "Sponsorlu" etiketi üst başlıkta (ekranın üst %22'lik kısmında) yer alır
    return if (
      (isStoryViewerContainerPresent || detectedAdViewId.contains("reel_viewer")) &&
      bounds.top < (screenHeight * 0.24).toInt()
    ) {
      SponsoredAdType.STORY_AD
    } else {
      SponsoredAdType.FEED_OR_REEL_AD
    }
  }

  fun performSkipGesture(
    service: AccessibilityService,
    adType: SponsoredAdType,
    screenWidth: Int,
    screenHeight: Int,
  ): Boolean {
    if (screenWidth <= 0 || screenHeight <= 0) return false

    val path = Path()
    val stroke = when (adType) {
      SponsoredAdType.STORY_AD -> {
        // Hikaye reklamını atlamak için ekranın sağ kenarına hızlı dokunuş
        val tapX = screenWidth * 0.90f
        val tapY = screenHeight * 0.50f
        path.moveTo(tapX, tapY)
        GestureDescription.StrokeDescription(path, 0L, 45L)
      }
      SponsoredAdType.FEED_OR_REEL_AD -> {
        // Akış / Reels reklamını ekrandan uzaklaştırmak için yukarı hızlı kaydırma
        val centerX = screenWidth * 0.50f
        val startY = screenHeight * 0.78f
        val endY = screenHeight * 0.15f
        path.moveTo(centerX, startY)
        path.lineTo(centerX, endY)
        GestureDescription.StrokeDescription(path, 0L, 180L)
      }
    }

    val gesture = GestureDescription.Builder()
      .addStroke(stroke)
      .build()

    return service.dispatchGesture(gesture, null, null)
  }
}
