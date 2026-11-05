package com.example.bundlecam

import android.view.Surface
import com.example.bundlecam.ui.capture.window.ClassifiedHostEvent
import com.example.bundlecam.ui.capture.window.DefaultSurfaceResolver
import com.example.bundlecam.ui.capture.window.HingeFeature
import com.example.bundlecam.ui.capture.window.HingeOrientation
import com.example.bundlecam.ui.capture.window.HostEventClassifier
import com.example.bundlecam.ui.capture.window.IntRectPx
import com.example.bundlecam.ui.capture.window.Posture
import com.example.bundlecam.ui.capture.window.RawHostEvent
import com.example.bundlecam.ui.capture.window.SurfaceRole
import com.example.bundlecam.ui.capture.window.WindowObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostEventClassifierTest {

    private fun obs(
        w: Int = 960,
        h: Int = 720,
        rotation: Int = Surface.ROTATION_0,
        role: SurfaceRole = SurfaceRole.Inner,
        hinge: HingeFeature? = null,
    ) = WindowObservation(
        windowWidthPx = w,
        windowHeightPx = h,
        density = 1f,
        displayRotation = rotation,
        surfaceRole = role,
        hinge = hinge,
    )

    @Test
    fun rotation_and_resize_on_same_surface_are_presentation_updates() {
        val classifier = HostEventClassifier()
        classifier.seed(obs(960, 720, Surface.ROTATION_0, SurfaceRole.Inner))

        val cw90 = classifier.classify(
            RawHostEvent.ConfigurationChanged(obs(720, 960, Surface.ROTATION_90, SurfaceRole.Inner)),
        )
        assertTrue(cw90 is ClassifiedHostEvent.PresentationUpdated)
        assertEquals(SurfaceRole.Inner, classifier.lastConfirmedSurface)

        val halfTurn = classifier.classify(
            RawHostEvent.WindowChanged(obs(960, 720, Surface.ROTATION_180, SurfaceRole.Inner)),
        )
        assertTrue(halfTurn is ClassifiedHostEvent.PresentationUpdated)

        val splitResize = classifier.classify(
            RawHostEvent.WindowChanged(obs(480, 720, Surface.ROTATION_0, SurfaceRole.Inner)),
        )
        assertTrue(splitResize is ClassifiedHostEvent.PresentationUpdated)
    }

    @Test
    fun confirmed_inner_to_cover_and_cover_to_inner_trigger_stable_surface_change() {
        val classifier = HostEventClassifier()
        classifier.seed(obs(960, 720, Surface.ROTATION_0, SurfaceRole.Inner))

        // Inner -> Cover
        val folded = classifier.classify(
            RawHostEvent.WindowChanged(obs(360, 576, Surface.ROTATION_0, SurfaceRole.Cover)),
        )
        assertTrue(folded is ClassifiedHostEvent.StableSurfaceChanged)
        val foldEvent = folded as ClassifiedHostEvent.StableSurfaceChanged
        assertEquals(SurfaceRole.Inner, foldEvent.from)
        assertEquals(SurfaceRole.Cover, foldEvent.to)

        // Duplicate Cover observation does not trigger a second surface change
        val duplicateCover = classifier.classify(
            RawHostEvent.WindowChanged(obs(360, 576, Surface.ROTATION_0, SurfaceRole.Cover)),
        )
        assertTrue(duplicateCover is ClassifiedHostEvent.PresentationUpdated)

        // Cover -> Inner
        val unfolded = classifier.classify(
            RawHostEvent.WindowChanged(obs(960, 720, Surface.ROTATION_0, SurfaceRole.Inner)),
        )
        assertTrue(unfolded is ClassifiedHostEvent.StableSurfaceChanged)
        val unfoldEvent = unfolded as ClassifiedHostEvent.StableSurfaceChanged
        assertEquals(SurfaceRole.Cover, unfoldEvent.from)
        assertEquals(SurfaceRole.Inner, unfoldEvent.to)
    }

    @Test
    fun unknown_surface_retains_last_confirmed_surface_without_false_finalization() {
        val classifier = HostEventClassifier()
        classifier.seed(obs(960, 720, Surface.ROTATION_0, SurfaceRole.Inner))

        // Transient Unknown (e.g. split window missing FoldingFeature) must NOT guess Cover
        val unknownObs = classifier.classify(
            RawHostEvent.WindowChanged(obs(480, 720, Surface.ROTATION_0, SurfaceRole.Unknown)),
        )
        assertTrue(unknownObs is ClassifiedHostEvent.PresentationUpdated)
        assertEquals(SurfaceRole.Inner, classifier.lastConfirmedSurface)

        // Returning to Inner from transient Unknown is still Inner -> Inner (PresentationUpdated)
        val backToInner = classifier.classify(
            RawHostEvent.WindowChanged(obs(960, 720, Surface.ROTATION_0, SurfaceRole.Inner)),
        )
        assertTrue(backToInner is ClassifiedHostEvent.PresentationUpdated)
    }

    @Test
    fun surface_resolver_never_infers_cover_from_missing_hinge_alone() {
        val resolver = DefaultSurfaceResolver()
        val resolvedMissingHinge = resolver.resolve(
            displayId = 0,
            hinge = null,
            injectedRole = null,
        )
        assertEquals(SurfaceRole.Unknown, resolvedMissingHinge)

        val hinge = HingeFeature(
            boundsPx = IntRectPx(470, 0, 490, 720),
            isSeparating = false,
            isOccluding = false,
            orientation = HingeOrientation.Vertical,
            posture = Posture.Flat,
        )
        val resolvedWithHinge = resolver.resolve(
            displayId = 0,
            hinge = hinge,
            injectedRole = null,
        )
        assertEquals(SurfaceRole.Inner, resolvedWithHinge)
    }

    @Test
    fun onPause_is_transient_while_non_config_onStop_is_background() {
        val classifier = HostEventClassifier(SurfaceRole.Inner)

        val paused = classifier.classify(RawHostEvent.ActivityPaused)
        assertEquals(ClassifiedHostEvent.TransientPause, paused)

        val configStop = classifier.classify(RawHostEvent.ActivityStopped(isChangingConfigurations = true))
        assertEquals(ClassifiedHostEvent.ConfigurationRecreating, configStop)

        val realStop = classifier.classify(RawHostEvent.ActivityStopped(isChangingConfigurations = false))
        assertTrue(realStop is ClassifiedHostEvent.Backgrounded)
    }

    @Test
    fun batch_priority_orders_background_above_surface_change_above_presentation() {
        val classifier = HostEventClassifier()
        classifier.seed(obs(960, 720, Surface.ROTATION_0, SurfaceRole.Inner))

        val events = listOf(
            RawHostEvent.ConfigurationChanged(obs(720, 960, Surface.ROTATION_90, SurfaceRole.Inner)),
            RawHostEvent.WindowChanged(obs(360, 576, Surface.ROTATION_0, SurfaceRole.Cover)),
            RawHostEvent.ActivityStopped(isChangingConfigurations = false),
        )
        val ordered = classifier.classifyBatch(events)
        assertTrue(ordered[0] is ClassifiedHostEvent.Backgrounded)
        assertTrue(ordered[1] is ClassifiedHostEvent.StableSurfaceChanged)
        assertTrue(ordered[2] is ClassifiedHostEvent.PresentationUpdated)
    }
}
