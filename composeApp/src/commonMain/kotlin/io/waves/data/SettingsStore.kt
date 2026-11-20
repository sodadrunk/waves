package io.waves.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Per-device user preferences. Not synced (deliberately absent from SyncState).
 *
 * @param country Apple Podcasts store (ISO 3166-1 alpha-2, lowercase) for charts and search; null follows the
 *   device region (see [effectiveCountry]).
 * @param autoDownloadNewEpisodes placeholder: persisted only, nothing acts on it yet.
 * @param syncEnabled when false, no automatic or manual Google Drive sync runs.
 * @param autoDeleteDownloads the earlier on/off form of [deleteDownloadsAfterDays] (on: right after playing), read
 *   when that isn't set and kept in step with it, for a version that only knows this one.
 * @param deleteDownloadsAfterDays how many days after an episode is played its download is deleted: 0 right away,
 *   [NEVER] not at all, null as [autoDeleteDownloads] says (on: right away; off, or never chosen, which reads the
 *   same: [DEFAULT_DELETE_AFTER_DAYS]).
 */
@Serializable
data class UserSettings(
    val country: String? = null,
    val defaultSpeed: Float = 1.0f,
    val skipBackSeconds: Int = 15,
    val skipForwardSeconds: Int = 30,
    val autoDeleteDownloads: Boolean = false,
    val autoDownloadNewEpisodes: Boolean = false,
    val syncEnabled: Boolean = true,
    val deleteDownloadsAfterDays: Int? = null,
) {
    /** [country], or the device region when it follows the device. */
    val effectiveCountry: String get() = country ?: deviceRegion()

    /** Days after playing a download is deleted ([deleteDownloadsAfterDays]); [NEVER] for never. */
    val deleteAfterDays: Int get() = deleteDownloadsAfterDays ?: if (autoDeleteDownloads) 0 else DEFAULT_DELETE_AFTER_DAYS

    /** These settings deleting downloads [days] after playing ([NEVER] for never). */
    fun withDeleteAfterDays(days: Int): UserSettings = copy(deleteDownloadsAfterDays = days, autoDeleteDownloads = days >= 0)

    companion object {
        const val NEVER = -1
        const val DEFAULT_DELETE_AFTER_DAYS = 7
    }
}

/** Persistent [UserSettings]. [settings] always holds the current value (loaded at construction). */
interface SettingsStore {
    val settings: StateFlow<UserSettings>

    /** Atomically replace the settings with `transform(current)` and persist them. */
    suspend fun update(transform: (UserSettings) -> UserSettings)
}

/** Lowercase two-letter region of the device locale, or "us" when it has none. */
fun deviceRegion(): String = normalizeRegion(platformRegion()) ?: "us"

internal fun normalizeRegion(region: String?): String? =
    region?.trim()?.lowercase()?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }

/** Raw region code of the device's default locale (may be empty or a UN M.49 number). */
internal expect fun platformRegion(): String?
