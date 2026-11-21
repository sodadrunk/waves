package io.waves.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.waves.net.ResponseCache
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The update check with answers kept ([ResponseCache]), as the app runs it. */
class UpdateCheckCacheTest {
    private val dir: File = Files.createTempDirectory("waves-responses").toFile()
    private var apiAnswers = true

    @BeforeTest fun setUp() = runBlocking<Unit> { ResponseCache.install(dir.path, now = 0) }

    @AfterTest fun tearDown() {
        ResponseCache.uninstall()
        dir.deleteRecursively()
    }

    private val releases = GitHubReleases(
        HttpClient(MockEngine { req ->
            when (req.url.encodedPath) {
                "/repos/sodadrunk/waves/releases/latest" ->
                    if (apiAnswers) respond("""{"tag_name":"v1.4.1","assets":[]}""")
                    else respond("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden)
                "/sodadrunk/waves/releases/latest" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://github.com/sodadrunk/waves/releases/tag/v1.5.0"))
                "/sodadrunk/waves/releases/tag/v1.5.0" -> respond("")
                "/sodadrunk/waves/releases/expanded_assets/v1.5.0" -> respond("")
                else -> error("Unexpected request ${req.url}")
            }
        }),
    )

    @Test
    fun aKeptAnswerPastItsTimeDoesNotStandInWhileTheApiIsCapped() = runBlocking<Unit> {
        // Checked earlier, when 1.4.1 was the latest: kept.
        assertEquals(Version(1, 4, 1), releases.latest()!!.version)
        // Over half an hour later, 1.5.0 is out but the API's cap is used up: the website says so.
        apiAnswers = false
        ResponseCache.uninstall()
        dir.listFiles()!!.forEach { file -> // the kept answer an hour old
            val lines = file.readText().split('\n', limit = 3)
            file.writeText(listOf(lines[0], (lines[1].toLong() - 3_600_000).toString(), lines[2]).joinToString("\n"))
        }
        ResponseCache.install(dir.path, now = 0)
        assertEquals(Version(1, 5, 0), releases.latest()!!.version)
    }
}
