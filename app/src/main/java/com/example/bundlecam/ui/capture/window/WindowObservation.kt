package com.example.bundlecam.ui.capture.window

import android.app.Activity
import android.os.Build
import android.view.Surface
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Pure integer rectangle in window-pixel coordinates. Kept independent of
 * `android.graphics.Rect` methods so pure-JVM unit tests run deterministically
 * without Android framework method stubs returning 0.
 */
data class IntRectPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    fun overlaps(other: IntRectPx): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    companion object {
        fun fromXYWH(x: Int, y: Int, width: Int, height: Int): IntRectPx =
            IntRectPx(x, y, x + width, y + height)
    }
}

/**
 * Window-pixel insets for a specific inset category (`safeDrawing` or `safeGestures`).
 * Combining two inset sets uses [maxUnion] (per-edge maximum), never addition, so
 * overlapping system bars and gesture zones are never subtracted twice.
 */
data class InsetsPx(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    fun maxUnion(other: InsetsPx): InsetsPx = InsetsPx(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    companion object {
        val Zero = InsetsPx(0, 0, 0, 0)
    }
}

enum class Posture {
    Flat,
    Tabletop,
    Book,
    Unknown,
}

enum class HingeOrientation {
    Vertical,
    Horizontal,
}

/**
 * Physical display surface classification.
 * - [Inner]: Confirmed unfolded internal foldable display.
 * - [Cover]: Confirmed external cover display on a foldable device.
 * - [Regular]: Confirmed non-foldable standard phone/tablet display.
 * - [Unknown]: Evidence is ambiguous or settling; must NOT be guessed as [Cover]
 *   merely because a `FoldingFeature` is absent.
 */
enum class SurfaceRole {
    Cover,
    Inner,
    Regular,
    Unknown,
}

data class HingeFeature(
    val boundsPx: IntRectPx,
    val isSeparating: Boolean,
    val isOccluding: Boolean,
    val orientation: HingeOrientation,
    val posture: Posture,
)

data class WindowObservation(
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val density: Float,
    val fontScale: Float = 1f,
    val safeDrawingInsetsPx: InsetsPx = InsetsPx.Zero,
    val safeGestureInsetsPx: InsetsPx = InsetsPx.Zero,
    val displayId: Int = 0,
    val displayRotation: Int = Surface.ROTATION_0,
    val hinge: HingeFeature? = null,
    val surfaceRole: SurfaceRole = SurfaceRole.Unknown,
    val isInMultiWindowMode: Boolean = false,
) {
    val safeContentInsetsPx: InsetsPx
        get() = safeDrawingInsetsPx.maxUnion(safeGestureInsetsPx)

    val effectivePosture: Posture
        get() = hinge?.posture ?: when (surfaceRole) {
            SurfaceRole.Inner, SurfaceRole.Cover, SurfaceRole.Regular -> Posture.Flat
            SurfaceRole.Unknown -> Posture.Unknown
        }
}

/**
 * Resolves [SurfaceRole] strictly from positive evidence:
 * 1. Explicit test/simulator injection ([injectedRole]).
 * 2. Presence of a hardware [HingeFeature] when not in split-screen ambiguity
 *    (WindowManager reports a [FoldingFeature] only on the internal foldable display).
 * 3. Explicit verified display-role mapping ([verifiedDisplayRoles]) supplied by a
 *    device integration or hinge-angle sensor adapter.
 *
 * Never infers [SurfaceRole.Cover] from a missing [HingeFeature], a display ID change,
 * or window dimensions/aspect ratios.
 */
fun interface SurfaceResolver {
    fun resolve(
        displayId: Int,
        hinge: HingeFeature?,
        injectedRole: SurfaceRole?,
    ): SurfaceRole
}

class DefaultSurfaceResolver(
    private val verifiedDisplayRoles: Map<Int, SurfaceRole> = emptyMap(),
) : SurfaceResolver {
    override fun resolve(
        displayId: Int,
        hinge: HingeFeature?,
        injectedRole: SurfaceRole?,
    ): SurfaceRole {
        if (injectedRole != null && injectedRole != SurfaceRole.Unknown) {
            return injectedRole
        }
        if (hinge != null) {
            return SurfaceRole.Inner
        }
        verifiedDisplayRoles[displayId]?.let { role ->
            if (role != SurfaceRole.Unknown) return role
        }
        return SurfaceRole.Unknown
    }
}

object WindowObservationAdapter {
    fun fromWindowLayoutInfo(
        activity: Activity,
        layoutInfo: WindowLayoutInfo?,
        safeDrawingInsetsPx: InsetsPx = InsetsPx.Zero,
        safeGestureInsetsPx: InsetsPx = InsetsPx.Zero,
        surfaceResolver: SurfaceResolver = DefaultSurfaceResolver(),
        injectedSurfaceRole: SurfaceRole? = null,
    ): WindowObservation {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        val bounds = metrics.bounds
        val resources = activity.resources
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density.takeIf { it > 0f } ?: 1f
        val fontScale = resources.configuration.fontScale.takeIf { it > 0f } ?: 1f

        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.display }.getOrNull() ?: activity.windowManager.defaultDisplay
        } else {
            activity.windowManager.defaultDisplay
        }
        val displayId = display?.displayId ?: 0
        val displayRotation = display?.rotation ?: Surface.ROTATION_0

        val foldingFeature = layoutInfo?.displayFeatures
            ?.filterIsInstance<FoldingFeature>()
            ?.firstOrNull()
        val hinge = foldingFeature?.toHingeFeature()
        val resolvedRole = surfaceResolver.resolve(
            displayId = displayId,
            hinge = hinge,
            injectedRole = injectedSurfaceRole,
        )

        return WindowObservation(
            windowWidthPx = (bounds.right - bounds.left).coerceAtLeast(0),
            windowHeightPx = (bounds.bottom - bounds.top).coerceAtLeast(0),
            density = density,
            fontScale = fontScale,
            safeDrawingInsetsPx = safeDrawingInsetsPx,
            safeGestureInsetsPx = safeGestureInsetsPx,
            displayId = displayId,
            displayRotation = displayRotation,
            hinge = hinge,
            surfaceRole = resolvedRole,
            isInMultiWindowMode = activity.isInMultiWindowMode,
        )
    }

    fun observeWindowLayoutInfo(activity: Activity): Flow<WindowLayoutInfo> =
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)

    private fun FoldingFeature.toHingeFeature(): HingeFeature {
        val rect = bounds
        val orientation = if (orientation == FoldingFeature.Orientation.VERTICAL) {
            HingeOrientation.Vertical
        } else {
            HingeOrientation.Horizontal
        }
        val posture = when (state) {
            FoldingFeature.State.FLAT -> Posture.Flat
            FoldingFeature.State.HALF_OPENED -> {
                if (orientation == HingeOrientation.Horizontal) Posture.Tabletop else Posture.Book
            }
            else -> Posture.Unknown
        }
        return HingeFeature(
            boundsPx = IntRectPx(rect.left, rect.top, rect.right, rect.bottom),
            isSeparating = isSeparating,
            isOccluding = occlusionType == FoldingFeature.OcclusionType.FULL,
            orientation = orientation,
            posture = posture,
        )
    }
}
