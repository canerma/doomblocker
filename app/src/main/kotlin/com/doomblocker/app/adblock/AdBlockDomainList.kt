package com.doomblocker.app.adblock

/**
 * Yaygın mobil reklam ağları, takipçiler (trackers), telemetri ve sosyal medya reklam
 * sunucularını içeren yerleşik Blackhole DNS veri tabanı.
 * Hem tam alan adı hem de alt alan adı (subdomain) eşleşmesi yapar.
 */
object AdBlockDomainList {

  private val blockedRootDomains = setOf(
    // Google Ads / DoubleClick / AdMob
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "google-analytics.com",
    "googletagmanager.com",
    "googletagservices.com",
    "admob.com",
    "adservice.google.com",
    "pagead2.googlesyndication.com",
    "app-measurement.com",
    "firebaseinstallations.googleapis.com",

    // Meta / Instagram / Facebook Reklam & Takip Ağları (Ana içerik CDN'lerini bozmadan)
    "an.facebook.com",
    "pixel.facebook.com",
    "connect.facebook.net",
    "ads.facebook.com",
    "tr.facebook.com",
    "graph.instagram.com",

    // X (Twitter) Reklam & Analitik Sunucuları
    "ads-twitter.com",
    "ads-api.twitter.com",
    "analytics.twitter.com",
    "p.twitter.com",
    "scribe.twitter.com",

    // Reddit Reklam & Telemetri Sunucuları
    "events.redditmedia.com",
    "events.reddit.com",
    "alb.reddit.com",
    "d.reddit.com",
    "pixel.redditmedia.com",
    "w3-reporting.reddit.com",

    // Mobil Oyun & Uygulama İçi Reklam Ağları
    "unityads.unity3d.com",
    "ads.prd.ie.internal.unity3d.com",
    "applovin.com",
    "applvn.com",
    "ironsrc.com",
    "ironsource.mobi",
    "vungle.com",
    "chartboost.com",
    "inmobi.com",
    "tapjoy.com",
    "Tapjoyads.com",
    "startappservice.com",
    "adcolony.com",
    "fyber.com",
    "mintegral.com",
    "rayjump.com",
    "pangle.io",
    "byteoversea.com",

    // Web & Programatik Reklam / Takip Ağları
    "criteo.com",
    "criteo.net",
    "taboola.com",
    "outbrain.com",
    "adsrvr.org",
    "adnxs.com",
    "rubiconproject.com",
    "pubmatic.com",
    "openx.net",
    "moatads.com",
    "scorecardresearch.com",
    "quantserve.com",
    "casalemedia.com",
    "smartadserver.com",
    "teads.tv",
    "sharethrough.com",
    "indexexchange.com",
    "bidswitch.net",
    "demdex.net",
    "omtrdc.net",
    "2mdn.net",
    "flashtalking.com",
    "serving-sys.com",
    "adform.net",
    "advertising.com",
    "yandexadexchange.net",
    "mc.yandex.ru",

    // Mobil Attribution & Davranış Takipçileri (MMP)
    "adjust.com",
    "appsflyer.com",
    "Appsflyersdk.com",
    "branch.io",
    "app.link",
    "singular.net",
    "kochava.com",
    "clevertap-prod.com",
    "mixpanel.com",
    "amplitude.com",
    "segment.io",
    "segment.com",
    "braze.com",
    "iad-01.braze.com",
    "onesignal.com",
    "bugsnag.com",
    "sentry.io",
  )

  private val blockedKeywordPrefixes = listOf(
    "adserver.",
    "adservice.",
    "adsystem.",
    "adtrack.",
    "telemetry.",
    "metrics.",
    "analytics.",
    "app-ads.",
    "pagead.",
    "partnerad.",
    "banners.",
  )

  fun isBlocked(rawDomain: String): Boolean {
    val domain = rawDomain.trim().trimEnd('.').lowercase()
    if (domain.isEmpty()) return false

    if (blockedKeywordPrefixes.any { domain.startsWith(it) }) {
      return true
    }

    // Tam eşleşme veya alt alan adı (ör. googleads.g.doubleclick.net -> doubleclick.net) kontrolü
    var current = domain
    while (true) {
      if (current in blockedRootDomains) {
        return true
      }
      val dotIndex = current.indexOf('.')
      if (dotIndex == -1) break
      current = current.substring(dotIndex + 1)
    }

    return false
  }
}
