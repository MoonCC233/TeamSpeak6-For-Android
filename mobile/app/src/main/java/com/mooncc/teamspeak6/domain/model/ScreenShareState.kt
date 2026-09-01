package com.mooncc.teamspeak6.domain.model

/**
 * Connection mode for a screen share.
 *
 * [P2P] sends a separate stream to every viewer; [SERVER] uploads once and lets a
 * relay fan it out. Mirrors the desktop client's wording, but note that neither
 * mode interoperates with the official client — see `docs/screenshare-protocol.md`.
 */
enum class ScreenShareMode {
    P2P,
    SERVER,
}

/** Who may watch a share. */
enum class ScreenSharePrivacy {
    PUBLIC,
    CONTACTS,
    PRIVATE,
}

/** Presets offered in the share dialog, matching the desktop client. */
enum class ScreenShareResolution(val targetHeight: Int, val label: String) {
    P360(360, "360"),
    P480(480, "480"),
    P720(720, "720"),
    P1080(1080, "1080"),
    P1440(1440, "1440"),
    SOURCE(Int.MAX_VALUE, "源"),
}

/** What the user picked before starting a share. */
data class ScreenShareConfig(
    val mode: ScreenShareMode = ScreenShareMode.P2P,
    val privacy: ScreenSharePrivacy = ScreenSharePrivacy.PUBLIC,
    val resolution: ScreenShareResolution = ScreenShareResolution.P720,
    val fps: Int = 30,
    val videoBitrateKbps: Int = 2500,
    val captureAudio: Boolean = false,
    val audioBitrateKbps: Int = 128,
    val viewerLimit: Int = 0,
)

/** How the signaling connection is doing, surfaced so the UI can explain failures. */
enum class ScreenShareSignalingState {
    OFFLINE,
    CONNECTING,
    ONLINE,
    RECONNECTING,
    FAILED,
}

/** A share someone in the room is offering. */
data class RemoteShare(
    val publisherId: String,
    val nickname: String,
    val mode: ScreenShareMode,
    val hasAudio: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    /** True once a peer connection for this share has media flowing. */
    val isWatching: Boolean = false,
    val isConnecting: Boolean = false,
) {
    val resolutionLabel: String get() = if (width > 0 && height > 0) "${width}x$height" else ""
}

/** Someone asking to watch our share while [ScreenSharePrivacy.PRIVATE] is set. */
data class ViewerRequest(
    val peerId: String,
    val nickname: String,
    val clientUid: String,
)

/** Aggregate screen-share state for the UI. */
data class ScreenShareState(
    val signaling: ScreenShareSignalingState = ScreenShareSignalingState.OFFLINE,
    val signalingUrl: String = "",
    val roomId: String = "",
    val serverModeAvailable: Boolean = false,
    val isSharing: Boolean = false,
    val isStarting: Boolean = false,
    val config: ScreenShareConfig = ScreenShareConfig(),
    val remoteShares: List<RemoteShare> = emptyList(),
    val viewerCount: Int = 0,
    val pendingViewerRequests: List<ViewerRequest> = emptyList(),
    val errorMessage: String? = null,
) {
    val isConfigured: Boolean get() = signalingUrl.isNotBlank()
    val hasRemoteShares: Boolean get() = remoteShares.isNotEmpty()
}
