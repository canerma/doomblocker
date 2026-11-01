package com.doomblocker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEngineTest {

  @Test
  fun `per-app 5 minute limit blocks that app but leaves 2 minutes for other apps including Reddit`() {
    val engine = UsageEngine(LimitConfig.NORMAL)
    val startSec = 10_000L

    var warnedCount = 0
    for (i in 0 until 300) {
      val res = engine.recordUsageTick(TrackedApp.INSTAGRAM, startSec + i)
      if (res.shouldTriggerWarning) {
        warnedCount++
        assertEquals(60, res.warningRemainingSeconds)
      }
      if (i < 299) {
        assertFalse(res.isBlocked)
      } else {
        assertTrue(res.isBlocked)
        assertEquals(BlockReason.PER_APP_LIMIT_REACHED, res.blockReason)
      }
    }

    assertEquals(1, warnedCount)

    val snapshot = engine.getSnapshot(startSec + 299)
    val igStatus = snapshot.appStatuses.getValue(TrackedApp.INSTAGRAM)
    val xStatus = snapshot.appStatuses.getValue(TrackedApp.TWITTER)
    val redditStatus = snapshot.appStatuses.getValue(TrackedApp.REDDIT)
    val shortsStatus = snapshot.appStatuses.getValue(TrackedApp.YOUTUBE_SHORTS)

    assertTrue(igStatus.isBlocked)
    assertEquals(0, igStatus.effectiveRemainingSeconds)

    // Diğer uygulamalar (X, Reddit, YouTube Shorts) için 7 - 5 = 2 dakika (120 saniye) ek havuz kalmalı!
    assertFalse(xStatus.isBlocked)
    assertEquals(120, xStatus.effectiveRemainingSeconds)
    assertFalse(redditStatus.isBlocked)
    assertEquals(120, redditStatus.effectiveRemainingSeconds)
    assertFalse(shortsStatus.isBlocked)
    assertEquals(120, shortsStatus.effectiveRemainingSeconds)
  }

  @Test
  fun `hybrid 7 minute total pool blocks all 4 apps even when no single app reached 5 minutes`() {
    val engine = UsageEngine(LimitConfig.NORMAL)
    var now = 20_000L

    // 120 sn (2 dk) Instagram
    repeat(120) {
      engine.recordUsageTick(TrackedApp.INSTAGRAM, now++)
    }
    // 120 sn (2 dk) Twitter
    repeat(120) {
      engine.recordUsageTick(TrackedApp.TWITTER, now++)
    }
    // 60 sn (1 dk) Reddit
    repeat(60) {
      engine.recordUsageTick(TrackedApp.REDDIT, now++)
    }
    // 120 sn (2 dk) YouTube Shorts -> Toplam 420 sn (7 dk)
    var warnedOnShorts = false
    repeat(120) { idx ->
      val res = engine.recordUsageTick(TrackedApp.YOUTUBE_SHORTS, now++)
      if (res.shouldTriggerWarning) {
        warnedOnShorts = true
      }
      if (idx == 119) {
        assertTrue(res.isBlocked)
        assertEquals(BlockReason.TOTAL_POOL_LIMIT_REACHED, res.blockReason)
      }
    }

    assertTrue("Toplam havuzda son 1 dk kala uyarı vermeli", warnedOnShorts)

    val snapshot = engine.getSnapshot(now - 1)
    assertEquals(420, snapshot.totalUsedSeconds)
    assertEquals(0, snapshot.totalRemainingSeconds)
    assertTrue(snapshot.appStatuses.getValue(TrackedApp.INSTAGRAM).isBlocked)
    assertTrue(snapshot.appStatuses.getValue(TrackedApp.TWITTER).isBlocked)
    assertTrue(snapshot.appStatuses.getValue(TrackedApp.REDDIT).isBlocked)
    assertTrue(snapshot.appStatuses.getValue(TrackedApp.YOUTUBE_SHORTS).isBlocked)
  }

  @Test
  fun `focus schedule correctly detects overnight and daytime windows`() {
    val overnight = FocusScheduleConfig(
      enabled = true,
      startHour = 23,
      startMinute = 30,
      endHour = 8,
      endMinute = 0,
    )
    assertTrue(overnight.isInFocusWindow(23, 45))
    assertTrue(overnight.isInFocusWindow(0, 15))
    assertTrue(overnight.isInFocusWindow(7, 59))
    assertFalse(overnight.isInFocusWindow(8, 0))
    assertFalse(overnight.isInFocusWindow(14, 30))
    assertFalse(overnight.isInFocusWindow(23, 29))

    val daytime = FocusScheduleConfig(
      enabled = true,
      startHour = 9,
      startMinute = 0,
      endHour = 17,
      endMinute = 0,
    )
    assertTrue(daytime.isInFocusWindow(9, 0))
    assertTrue(daytime.isInFocusWindow(12, 30))
    assertFalse(daytime.isInFocusWindow(17, 0))
    assertFalse(daytime.isInFocusWindow(22, 0))
  }

  @Test
  fun `rolling 60 minute window frees up quota as old usage falls out of window`() {
    val engine = UsageEngine(LimitConfig.NORMAL)
    val baseTime = 50_000L

    for (i in 0 until 300) {
      engine.recordUsageTick(TrackedApp.REDDIT, baseTime + i)
    }

    val blockedAt299 = engine.getSnapshot(baseTime + 299).appStatuses.getValue(TrackedApp.REDDIT)
    assertTrue(blockedAt299.isBlocked)
    assertEquals(3301L, blockedAt299.secondsUntilUnblocked)

    val snapshotAt3600 = engine.getSnapshot(baseTime + 3600)
    val redditAt3600 = snapshotAt3600.appStatuses.getValue(TrackedApp.REDDIT)
    assertFalse(redditAt3600.isBlocked)
    assertEquals(299, redditAt3600.usedSeconds)
    assertEquals(1, redditAt3600.effectiveRemainingSeconds)
  }
}
