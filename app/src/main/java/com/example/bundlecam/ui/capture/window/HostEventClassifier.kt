package com.example.bundlecam.ui.capture.window

/**
 * Raw host/window events fed into [HostEventClassifier].
 */
sealed interface RawHostEvent {
    data class WindowChanged(val observation: WindowObservation) : RawHostEvent
    data class ConfigurationChanged(val observation: WindowObservation) : RawHostEvent
    object ActivityPaused : RawHostEvent
    data class ActivityStopped(val isChangingConfigurations: Boolean) : RawHostEvent
    object RouteExited : RawHostEvent
}

/**
 * Classified semantic events consumed by `CaptureTakeCoordinator` and the UI shell.
 *
 * Priority hierarchy (highest to lowest):
 * 1. [Backgrounded] / [RouteExited] (priority 40)
 * 2. [StableSurfaceChanged] (priority 30)
 * 3. User Stop / Recorder Finalize / Error (handled in coordinator at priority 20)
 * 4. [PresentationUpdated] / [TransientPause] / [ConfigurationRecreating] (priority 10)
 */
sealed interface ClassifiedHostEvent {
    val priority: Int

    data class Backgrounded(val reason: String = "host_stopped") : ClassifiedHostEvent {
        override val priority: Int = PRIORITY_BACKGROUND
    }

    data class StableSurfaceChanged(
        val from: SurfaceRole,
        val to: SurfaceRole,
        val observation: WindowObservation,
    ) : ClassifiedHostEvent {
        override val priority: Int = PRIORITY_SURFACE_CHANGE
    }

    data class PresentationUpdated(
        val observation: WindowObservation,
        val lastConfirmedSurface: SurfaceRole,
    ) : ClassifiedHostEvent {
        override val priority: Int = PRIORITY_PRESENTATION
    }

    object TransientPause : ClassifiedHostEvent {
        override val priority: Int = PRIORITY_PRESENTATION
    }

    object ConfigurationRecreating : ClassifiedHostEvent {
        override val priority: Int = PRIORITY_PRESENTATION
    }

    companion object {
        const val PRIORITY_BACKGROUND = 40
        const val PRIORITY_SURFACE_CHANGE = 30
        const val PRIORITY_TAKE_TERMINATION = 20
        const val PRIORITY_PRESENTATION = 10
    }
}

/**
 * Stateful classifier that tracks the last confirmed physical foldable surface
 * ([SurfaceRole.Inner] or [SurfaceRole.Cover]) and distinguishes:
 * - ordinary rotation (0°/90°/180°/270°), multi-window resize, and same-surface
 *   posture transitions ([Posture.Flat] ↔ [Posture.Tabletop] ↔ [Posture.Book])
 *   → [ClassifiedHostEvent.PresentationUpdated] (recording continues!),
 * - confirmed [SurfaceRole.Inner] ↔ [SurfaceRole.Cover] transitions
 *   → [ClassifiedHostEvent.StableSurfaceChanged] (recording finalizes once!),
 * - `onPause` alone → [ClassifiedHostEvent.TransientPause] (never finalizes!),
 * - `onStop` with `!isChangingConfigurations` → [ClassifiedHostEvent.Backgrounded]
 *   (recording finalizes once!).
 */
class HostEventClassifier(
    initialSurface: SurfaceRole = SurfaceRole.Unknown,
) {
    var lastConfirmedSurface: SurfaceRole =
        if (initialSurface.isConfirmedFoldableSurface()) initialSurface else SurfaceRole.Unknown
        private set

    var lastObservation: WindowObservation? = null
        private set

    fun seed(observation: WindowObservation) {
        lastObservation = observation
        if (observation.surfaceRole.isConfirmedFoldableSurface()) {
            lastConfirmedSurface = observation.surfaceRole
        }
    }

    fun classify(event: RawHostEvent): ClassifiedHostEvent = when (event) {
        is RawHostEvent.WindowChanged -> classifyObservation(event.observation)
        is RawHostEvent.ConfigurationChanged -> classifyObservation(event.observation)
        RawHostEvent.ActivityPaused -> ClassifiedHostEvent.TransientPause
        is RawHostEvent.ActivityStopped -> {
            if (event.isChangingConfigurations) {
                ClassifiedHostEvent.ConfigurationRecreating
            } else {
                ClassifiedHostEvent.Backgrounded("activity_on_stop")
            }
        }
        RawHostEvent.RouteExited -> ClassifiedHostEvent.Backgrounded("capture_route_exited")
    }

    /**
     * Resolves the highest-priority event from a batch of concurrent/interleaved raw events.
     */
    fun classifyBatch(events: List<RawHostEvent>): List<ClassifiedHostEvent> {
        return events.map { classify(it) }
            .sortedByDescending { it.priority }
    }

    private fun classifyObservation(observation: WindowObservation): ClassifiedHostEvent {
        lastObservation = observation
        val incoming = observation.surfaceRole
        val previous = lastConfirmedSurface

        if (incoming.isConfirmedFoldableSurface()) {
            lastConfirmedSurface = incoming
            if (previous.isConfirmedFoldableSurface() && previous != incoming) {
                return ClassifiedHostEvent.StableSurfaceChanged(
                    from = previous,
                    to = incoming,
                    observation = observation,
                )
            }
        }

        return ClassifiedHostEvent.PresentationUpdated(
            observation = observation,
            lastConfirmedSurface = lastConfirmedSurface,
        )
    }

    fun reset(initialSurface: SurfaceRole = SurfaceRole.Unknown) {
        lastConfirmedSurface =
            if (initialSurface.isConfirmedFoldableSurface()) initialSurface else SurfaceRole.Unknown
        lastObservation = null
    }
}

private fun SurfaceRole.isConfirmedFoldableSurface(): Boolean =
    this == SurfaceRole.Inner || this == SurfaceRole.Cover
