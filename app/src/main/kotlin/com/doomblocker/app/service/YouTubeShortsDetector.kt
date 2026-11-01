package com.doomblocker.app.service

import android.view.accessibility.AccessibilityNodeInfo

object YouTubeShortsDetector {

  private val shortsViewIdMarkers = listOf(
    "reel_player_page_container",
    "reel_recycler",
    "reel_watch_fragment_root",
    "shorts_player",
    "reel_progress_bar",
    "reel_rhs",
    "reel_dyn_like",
    "reel_dyn_remix",
    "reel_pivot_button",
    "reel_time_bar",
  )

  fun isShortsScreenActive(rootNode: AccessibilityNodeInfo?): Boolean {
    if (rootNode == null) return false

    var visitedCount = 0
    val maxNodesToInspect = 250
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(rootNode)

    var hasRemixAction = false
    var hasRightSideEngagementBar = false

    while (queue.isNotEmpty() && visitedCount < maxNodesToInspect) {
      val node = queue.removeFirst()
      visitedCount++

      if (node.isVisibleToUser) {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (viewId.isNotEmpty() && isShortsViewId(viewId)) {
          return true
        }

        // Alt menüde "Shorts" sekmesi seçiliyse (isSelected == true)
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (node.isSelected && (text.equals("Shorts", ignoreCase = true) || desc.equals("Shorts", ignoreCase = true))) {
          return true
        }

        // Obfuscated YouTube sürümleri için yedek kontrol:
        if (text.equals("Remix", ignoreCase = true) ||
          text.equals("Remiksle", ignoreCase = true) ||
          desc.contains("Remix", ignoreCase = true) ||
          desc.contains("Remiksle", ignoreCase = true)
        ) {
          hasRemixAction = true
        }
        if (desc.contains("Shorts videosunu beğen", ignoreCase = true) ||
          desc.contains("like this short", ignoreCase = true) ||
          desc.contains("Bu sesi kullan", ignoreCase = true) ||
          desc.contains("Use this sound", ignoreCase = true)
        ) {
          hasRightSideEngagementBar = true
        }

        if (hasRemixAction && hasRightSideEngagementBar) {
          return true
        }
      }

      val childCount = node.childCount
      for (i in 0 until childCount) {
        node.getChild(i)?.let { queue.add(it) }
      }
    }

    return hasRightSideEngagementBar
  }

  internal fun isShortsViewId(viewIdLowercase: String): Boolean {
    return shortsViewIdMarkers.any { marker -> viewIdLowercase.contains(marker) }
  }
}
