/*
 * SPDX-FileCopyrightText: The PerleOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.phone.ScrimController.OPAQUE;
import static com.android.systemui.statusbar.phone.ScrimController.SEMI_TRANSPARENT;

import static com.google.common.truth.Truth.assertThat;

import static kotlinx.coroutines.flow.FlowKt.emptyFlow;
import static kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.graphics.Color;
import android.os.Handler;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.TestWithLooperRule;
import android.testing.TestableContext;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.DejankUtils;
import com.android.systemui.Flags;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.ui.transitions.BlurConfig;
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToGoneTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToDreamingTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToDreamingTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel;
import com.android.systemui.res.R;
import com.android.systemui.scrim.ScrimView;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.shade.ui.ShadeColors;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor;

import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.flow.Flow;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/** Opacity of the scrims with the fallback colors, which the shade uses without window blur. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
@EnableFlags({Flags.FLAG_BOUNCER_UI_REVAMP, Flags.FLAG_NOTIFICATION_SHADE_BLUR})
public class ScrimControllerFallbackColorsTest {

    private static final int PANEL_COLOR = 0xBF000000;
    private static final int NOTIFICATIONS_COLOR = 0x4D000000;
    private static final float PANEL_ALPHA = 0xBF / 255f;
    private static final float NOTIFICATIONS_ALPHA = 0x4D / 255f;
    private static final float TOLERANCE = 0.001f;

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext());

    // Must be the innermost rule.
    @Rule(order = Integer.MAX_VALUE)
    public final TestWithLooperRule mLooperRule = new TestWithLooperRule();

    private ScrimController mScrimController;
    private ScrimView mScrimBehind;
    private ScrimView mNotificationsScrim;
    private ScrimView mScrimInFront;
    private int mScrimVisibility;
    private TestableLooper mLooper;

    @Before
    public void setUp() {
        mLooper = TestableLooper.get(this);
        DejankUtils.setImmediate(true);
    }

    @After
    public void tearDown() {
        DejankUtils.setImmediate(false);
        TestableLooper.remove(this);
    }

    @AfterClass
    public static void clearMocks() {
        Mockito.framework().clearInlineMocks();
    }

    @Test
    public void unlocked_shadeExpanded_usesAlphaOfColors() {
        createController(PANEL_COLOR, NOTIFICATIONS_COLOR, /* blurSupported= */ false);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);

        expandShade(1f);

        assertScrimAlphas(PANEL_ALPHA, NOTIFICATIONS_ALPHA);
        assertThat(mScrimVisibility).isEqualTo(SEMI_TRANSPARENT);
    }

    @Test
    public void unlocked_shadeHalfExpanded_appliesAlphaOfColorsOnce() {
        createController(PANEL_COLOR, NOTIFICATIONS_COLOR, /* blurSupported= */ false);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);

        expandShade(0.5f);

        assertScrimAlphas(0.5f * PANEL_ALPHA, 0.5f * NOTIFICATIONS_ALPHA);
    }

    @Test
    public void keyguard_toFullShade_matchesShadeLocked() {
        createController(PANEL_COLOR, NOTIFICATIONS_COLOR, /* blurSupported= */ false);

        mScrimController.setTransitionToFullShadeProgress(1f, 1f);
        finishAnimations();
        assertScrimAlphas(PANEL_ALPHA, NOTIFICATIONS_ALPHA);

        mScrimController.legacyTransitionTo(ScrimState.SHADE_LOCKED);
        finishAnimations();
        assertScrimAlphas(PANEL_ALPHA, NOTIFICATIONS_ALPHA);
    }

    @Test
    public void keyguard_quickSettingsExpanded_usesAlphaOfColors() {
        createController(PANEL_COLOR, NOTIFICATIONS_COLOR, /* blurSupported= */ false);

        mScrimController.setQsPosition(1f, 0);
        finishAnimations();

        assertScrimAlphas(PANEL_ALPHA, NOTIFICATIONS_ALPHA);
    }

    @Test
    public void bouncer_isOpaque() {
        createController(PANEL_COLOR, NOTIFICATIONS_COLOR, /* blurSupported= */ false);

        mScrimController.legacyTransitionTo(ScrimState.BOUNCER);
        finishAnimations();

        assertScrimAlphas(1f, 0f);
        assertThat(mScrimVisibility).isEqualTo(OPAQUE);
    }

    @Test
    public void unlocked_opaqueColors_isOpaque() {
        createController(Color.BLACK, Color.BLACK, /* blurSupported= */ false);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);

        expandShade(1f);

        assertScrimAlphas(1f, 1f);
        assertThat(mScrimVisibility).isEqualTo(OPAQUE);
    }

    @Test
    public void unlocked_blurSupported_ignoresFallbackColors() {
        createController(PANEL_COLOR, NOTIFICATIONS_COLOR, /* blurSupported= */ true);
        mScrimController.legacyTransitionTo(ScrimState.UNLOCKED);

        expandShade(1f);

        assertScrimAlphas(
                Color.alpha(ShadeColors.shadePanel(mContext, true, true)) / 255f,
                Color.alpha(ShadeColors.notificationScrim(mContext, true)) / 255f);
    }

    @Test
    public void keyguard_quickSettingsExpanded_blurSupported_ignoresFallbackColors() {
        createController(PANEL_COLOR, NOTIFICATIONS_COLOR, /* blurSupported= */ true);

        mScrimController.setQsPosition(1f, 0);
        finishAnimations();

        assertScrimAlphas(Color.alpha(ShadeColors.shadePanel(mContext, true, true)) / 255f, 1f);
    }

    /** Creates the controller in the KEYGUARD state. */
    private void createController(int panelColor, int notificationsColor, boolean blurSupported) {
        mContext.getOrCreateTestableResources().addOverride(
                R.color.shade_panel_fallback, panelColor);
        mContext.getOrCreateTestableResources().addOverride(
                R.color.notification_scrim_fallback, notificationsColor);
        WindowRootViewBlurInteractor blurInteractor = mock(WindowRootViewBlurInteractor.class);
        when(blurInteractor.isBlurCurrentlySupported()).thenReturn(MutableStateFlow(blurSupported));
        DelayedWakeLock.Factory wakeLockFactory = mock(DelayedWakeLock.Factory.class);
        when(wakeLockFactory.create(any(String.class))).thenReturn(mock(DelayedWakeLock.class));

        mScrimBehind = new ScrimView(mContext);
        mNotificationsScrim = new ScrimView(mContext);
        mScrimInFront = new ScrimView(mContext);

        mScrimController = new ScrimController(
                mock(LightBarController.class),
                mock(DozeParameters.class),
                mock(KeyguardStateController.class),
                wakeLockFactory,
                new Handler(mLooper.getLooper()),
                mock(KeyguardUpdateMonitor.class),
                mock(DockManager.class),
                mock(ConfigurationController.class),
                mock(ScreenOffAnimationController.class),
                mock(KeyguardUnlockAnimationController.class),
                mock(StatusBarKeyguardViewManager.class),
                mockWithEmptyFlows(PrimaryBouncerToDreamingTransitionViewModel.class),
                mockWithEmptyFlows(PrimaryBouncerToGoneTransitionViewModel.class),
                mockWithEmptyFlows(AlternateBouncerToGoneTransitionViewModel.class),
                mockWithEmptyFlows(LockscreenToDreamingTransitionViewModel.class),
                mockWithEmptyFlows(KeyguardTransitionInteractor.class),
                mock(KeyguardInteractor.class),
                Dispatchers.getUnconfined(),
                new LinearInterpolator(),
                new BlurConfig(0.0f, 0.0f),
                mContext,
                () -> blurInteractor);
        mScrimController.setScrimVisibleListener(visible -> mScrimVisibility = visible);
        mScrimController.attachViews(mScrimBehind, mNotificationsScrim, mScrimInFront);

        mScrimController.legacyTransitionTo(ScrimState.KEYGUARD);
        finishAnimations();
    }

    private void expandShade(float fraction) {
        mScrimController.setRawPanelExpansionFraction(0f);
        mScrimController.setRawPanelExpansionFraction(fraction);
        finishAnimations();
    }

    private void finishAnimations() {
        mScrimController.onPreDraw();
        mLooper.processAllMessages();
        endAnimation(mNotificationsScrim);
        endAnimation(mScrimBehind);
        endAnimation(mScrimInFront);
    }

    private static void endAnimation(ScrimView scrim) {
        Animator animator = (Animator) scrim.getTag(ScrimController.TAG_KEY_ANIM);
        if (animator != null) {
            animator.end();
        }
    }

    private static <T> T mockWithEmptyFlows(Class<T> classToMock) {
        return mock(classToMock, invocation ->
                Flow.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? emptyFlow() : RETURNS_DEFAULTS.answer(invocation));
    }

    private void assertScrimAlphas(float behind, float notifications) {
        assertThat(mScrimBehind.getViewAlpha()).isWithin(TOLERANCE).of(behind);
        assertThat(mNotificationsScrim.getViewAlpha()).isWithin(TOLERANCE).of(notifications);
    }

    private static class LinearInterpolator implements LargeScreenShadeInterpolator {
        @Override
        public float getBehindScrimAlpha(float fraction) {
            return fraction;
        }

        @Override
        public float getNotificationScrimAlpha(float fraction) {
            return fraction;
        }

        @Override
        public float getNotificationContentAlpha(float fraction) {
            return fraction;
        }

        @Override
        public float getNotificationFooterAlpha(float fraction) {
            return fraction;
        }

        @Override
        public float getQsAlpha(float fraction) {
            return fraction;
        }
    }
}
