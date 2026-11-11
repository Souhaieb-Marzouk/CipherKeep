package dev.cipherkeep.app.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var cache: FakeCache

    private class FakeCache(
        var etagValue: String? = null,
        var bodyValue: String? = null,
    ) : UpdateCache {
        override suspend fun etag(): String? = etagValue
        override suspend fun cachedBody(): String? = bodyValue
        override suspend fun save(etag: String, body: String) {
            etagValue = etag
            bodyValue = body
        }
    }

    private val releaseJson = """
        {
          "tag_name": "v0.2.0",
          "published_at": "2026-11-11T00:00:00Z",
          "body": "<b>Changes</b><script>alert(1)</script>",
          "html_url": "https://github.com/Souhaieb-Marzouk/CipherKeep/releases/tag/v0.2.0",
          "assets": [{"size": 100}, {"size": 200}]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cache = FakeCache()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun checker(): UpdateChecker = UpdateChecker(
        currentVersion = "0.1.0",
        client = OkHttpClient(),
        cache = cache,
        url = server.url("/latest").toString(),
    )

    @Test
    fun parsesNewerReleaseAndCachesEtag() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"abc\"")
                .setBody(releaseJson),
        )
        val outcome = checker().check()
        assertTrue(outcome is UpdateOutcome.Available)
        outcome as UpdateOutcome.Available
        assertEquals("v0.2.0", outcome.latestVersion)
        assertEquals(300L, outcome.sizeBytes)
        assertEquals(false, outcome.notes.contains("<script>"))
        assertEquals("\"abc\"", cache.etagValue)
    }

    @Test
    fun notModifiedUsesCachedBodyAndSendsIfNoneMatch() = runBlocking {
        cache.etagValue = "\"abc\""
        cache.bodyValue = releaseJson
        server.enqueue(MockResponse().setResponseCode(304))

        val outcome = checker().check()
        assertTrue(outcome is UpdateOutcome.Available)
        val request = server.takeRequest()
        assertEquals("\"abc\"", request.getHeader("If-None-Match"))
    }

    @Test
    fun rateLimitIsFriendly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("rate limited"))
        assertEquals(UpdateOutcome.RateLimited, checker().check())
    }

    @Test
    fun missingReleaseIsFriendly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertEquals(UpdateOutcome.NoRelease, checker().check())
    }

    @Test
    fun malformedJsonFailsClosed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not json"))
        assertTrue(checker().check() is UpdateOutcome.Failed)
    }

    @Test
    fun olderReleaseIsUpToDate() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(releaseJson.replace("v0.2.0", "v0.0.9")),
        )
        assertTrue(checker().check() is UpdateOutcome.UpToDate)
    }
}
