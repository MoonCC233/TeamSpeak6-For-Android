package com.mooncc.teamspeak9.screenshare.webrtc

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Coroutine adapters over WebRTC's callback-based SDP API.
 *
 * The native callbacks may fire on an internal signaling thread; each of these
 * resumes exactly once, so callers can `await` them like any suspend function.
 */
internal suspend fun PeerConnection.createOfferSuspend(
    constraints: MediaConstraints = MediaConstraints(),
): SessionDescription = suspendCancellableCoroutine { continuation ->
    createOffer(
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (continuation.isActive) continuation.resume(description)
            }

            override fun onCreateFailure(error: String?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("createOffer failed: ${error.orEmpty()}"),
                    )
                }
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        },
        constraints,
    )
}

internal suspend fun PeerConnection.createAnswerSuspend(
    constraints: MediaConstraints = MediaConstraints(),
): SessionDescription = suspendCancellableCoroutine { continuation ->
    createAnswer(
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (continuation.isActive) continuation.resume(description)
            }

            override fun onCreateFailure(error: String?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("createAnswer failed: ${error.orEmpty()}"),
                    )
                }
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        },
        constraints,
    )
}

internal suspend fun PeerConnection.setLocalDescriptionSuspend(
    description: SessionDescription,
): Unit = suspendCancellableCoroutine { continuation ->
    setLocalDescription(
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit

            override fun onSetSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onSetFailure(error: String?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("setLocalDescription failed: ${error.orEmpty()}"),
                    )
                }
            }
        },
        description,
    )
}

internal suspend fun PeerConnection.setRemoteDescriptionSuspend(
    description: SessionDescription,
): Unit = suspendCancellableCoroutine { continuation ->
    setRemoteDescription(
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit

            override fun onSetSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onSetFailure(error: String?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("setRemoteDescription failed: ${error.orEmpty()}"),
                    )
                }
            }
        },
        description,
    )
}
