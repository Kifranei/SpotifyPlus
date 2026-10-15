package dev.kifranei.spotifyplus.hook

import android.annotation.SuppressLint
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import dev.kifranei.spotifyplus.util.ModulePrefs
import dev.kifranei.spotifyplus.util.SpotifyPlusLog

/**
 * Restores Spotify like / play-mode controls on Xiaomi HyperOS media notifications.
 *
 * Halcyon publishes favorite + playback-mode as Media3 SessionCommand / CommandButton via
 * `MediaSession.setMediaButtonPreferences`, which the platform exposes as PlaybackState
 * CustomActions. Spotify already defines CustomActions (`ADD_TO_COLLECTION` /
 * `REMOVE_FROM_COLLECTION`, `com.spotify.toggle_shuffle`, `com.spotify.toggle_repeat`) but
 * HyperOS often hides "abnormal" or blacklisted CustomActions for Spotify.
 *
 * This hook mirrors Halcyon's intent inside the Spotify process: on every
 * [MediaSession.setPlaybackState], rebuild a well-formed PlaybackState that keeps Spotify's
 * own action ids (so the host callback still handles taps), ensures like + shuffle/repeat
 * CustomActions are present with non-empty names and valid icons, sets the standard
 * ACTION_SET_SHUFFLE_MODE / ACTION_SET_REPEAT_MODE / ACTION_SET_RATING bits, and marks
 * CustomActions with the Wear SHOW_ON_WEAR extra that OEMs use as a visibility hint.
 *
 * Device note: some HyperOS builds package-blacklist Spotify in SystemUI; fixing CustomActions
 * here is necessary but may not be sufficient on those builds without a SystemUI-side unblock.
 */
object MediaNotifActionsHook {
    // android.media.session.PlaybackState bits (API 24 / 31) — not always visible on all stubs
    private const val ACTION_SET_REPEAT_MODE = 1L shl 18
    private const val ACTION_SET_SHUFFLE_MODE = 1L shl 21

    const val ACTION_ADD = "ADD_TO_COLLECTION"
    const val ACTION_REMOVE = "REMOVE_FROM_COLLECTION"
    const val ACTION_ADD_FULL =
        "com.spotify.app.music.scopes.utils.action.player.NOTIFICATION_ADD_TO_COLLECTION"
    const val ACTION_REMOVE_FULL =
        "com.spotify.app.music.scopes.utils.action.player.NOTIFICATION_REMOVE_FROM_COLLECTION"
    const val ACTION_TOGGLE_SHUFFLE = "com.spotify.toggle_shuffle"
    const val ACTION_TOGGLE_REPEAT = "com.spotify.toggle_repeat"

    private const val EXTRA_SHOW_ON_WEAR =
        "android.support.wearable.media.extra.CUSTOM_ACTION_SHOW_ON_WEAR"

    private val LIKE_ACTIONS = setOf(ACTION_ADD, ACTION_REMOVE, ACTION_ADD_FULL, ACTION_REMOVE_FULL)
    private val SHUFFLE_ACTIONS = setOf(ACTION_TOGGLE_SHUFFLE, "toggle_shuffle")
    private val REPEAT_ACTIONS = setOf(ACTION_TOGGLE_REPEAT, "toggle_repeat")

    @Volatile private var installed = false
    @Volatile private var enabled = true

    fun install(prefs: ModulePrefs) {
        if (installed) return
        installed = true
        enabled = prefs.mediaNotifActions
        val n = ModernHookRuntime.hookAllMethods(
            MediaSession::class.java,
            "setPlaybackState",
            object : ModernHookRuntime.MethodHook() {
                override fun beforeHookedMethod(p: ModernHookRuntime.MethodHookParam) {
                    if (!enabled) return
                    val state = p.args.getOrNull(0) as? PlaybackState ?: return
                    runCatching { p.args[0] = enhance(state) }
                        .onFailure { SpotifyPlusLog.w("MediaNotifActionsHook enhance failed", it) }
                }
            },
        )
        SpotifyPlusLog.i("MediaNotifActionsHook installed hooks=$n")
    }

    fun setEnabled(v: Boolean) {
        enabled = v
    }

    @SuppressLint("WrongConstant")
    internal fun enhance(state: PlaybackState): PlaybackState {
        val existing = state.customActions.orEmpty()
        val hasLike = existing.any { it.action in LIKE_ACTIONS }
        val hasShuffle = existing.any { it.action in SHUFFLE_ACTIONS }
        val hasRepeat = existing.any { it.action in REPEAT_ACTIONS }

        val updateTime =
            if (state.lastPositionUpdateTime > 0L) state.lastPositionUpdateTime
            else SystemClock.elapsedRealtime()

        val fresh = PlaybackState.Builder()
            .setState(state.state, state.position, state.playbackSpeed, updateTime)
            .setActions(
                state.actions or
                    ACTION_SET_SHUFFLE_MODE or
                    ACTION_SET_REPEAT_MODE or
                    PlaybackState.ACTION_SET_RATING or
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO,
            )
            .setActiveQueueItemId(state.activeQueueItemId)
            .setBufferedPosition(state.bufferedPosition)

        state.extras?.let { fresh.setExtras(Bundle(it)) }
        state.errorMessage?.let { fresh.setErrorMessage(it) }

        val seen = mutableSetOf<String>()
        for (ca in existing) {
            val action = ca.action ?: continue
            if (!seen.add(action)) continue
            fresh.addCustomAction(normalize(ca))
        }

        if (!hasLike) {
            fresh.addCustomAction(
                normalize(
                    PlaybackState.CustomAction.Builder(
                        ACTION_ADD,
                        "收藏",
                        android.R.drawable.btn_star_big_off,
                    ).build(),
                ),
            )
        }
        if (!hasShuffle) {
            fresh.addCustomAction(
                normalize(
                    PlaybackState.CustomAction.Builder(
                        ACTION_TOGGLE_SHUFFLE,
                        "随机播放",
                        android.R.drawable.ic_menu_sort_by_size,
                    ).build(),
                ),
            )
        }
        if (!hasRepeat) {
            fresh.addCustomAction(
                normalize(
                    PlaybackState.CustomAction.Builder(
                        ACTION_TOGGLE_REPEAT,
                        "循环播放",
                        android.R.drawable.ic_menu_rotate,
                    ).build(),
                ),
            )
        }

        return fresh.build()
    }

    private fun normalize(ca: PlaybackState.CustomAction): PlaybackState.CustomAction {
        val name = ca.name?.takeIf { it.isNotBlank() } ?: (ca.action ?: "action")
        val icon = if (ca.icon != 0) ca.icon else android.R.drawable.ic_media_play
        val extras = Bundle(ca.extras ?: Bundle.EMPTY).apply {
            putBoolean(EXTRA_SHOW_ON_WEAR, true)
        }
        return PlaybackState.CustomAction.Builder(ca.action, name, icon)
            .setExtras(extras)
            .build()
    }
}
