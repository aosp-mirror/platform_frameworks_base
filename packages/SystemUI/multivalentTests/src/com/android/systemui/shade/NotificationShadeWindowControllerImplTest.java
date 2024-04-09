/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade;

import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_SENSITIVE_FOR_TRACING;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.platform.test.flag.junit.FlagsParameterization;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.communal.domain.interactor.CommunalInteractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.SceneContainerFlagParameterizationKt;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.scene.FakeWindowRootViewComponent;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;
import java.util.concurrent.Executor;

@RunWith(ParameterizedAndroidJunit4.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class NotificationShadeWindowControllerImplTest extends SysuiTestCase {
    @Mock private WindowManager mWindowManager;
    @Mock private DozeParameters mDozeParameters;
    @Spy private final NotificationShadeWindowView mNotificationShadeWindowView = spy(
            new NotificationShadeWindowView(mContext, null));
    @Mock private IActivityManager mActivityManager;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private KeyguardViewMediator mKeyguardViewMediator;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private SysuiColorExtractor mColorExtractor;
    @Mock private ColorExtractor.GradientColors mGradientColors;
    @Mock private DumpManager mDumpManager;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private AuthController mAuthController;
    @Mock private ShadeWindowLogger mShadeWindowLogger;
    @Mock private SelectedUserInteractor mSelectedUserInteractor;
    @Mock private UserTracker mUserTracker;
    @Captor private ArgumentCaptor<WindowManager.LayoutParams> mLayoutParameters;
    @Captor private ArgumentCaptor<StatusBarStateController.StateListener> mStateListener;

    private final Executor mMainExecutor = MoreExecutors.directExecutor();
    private final Executor mBackgroundExecutor = MoreExecutors.directExecutor();
    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);

    private NotificationShadeWindowControllerImpl mNotificationShadeWindowController;
    private float mPreferredRefreshRate = -1;
    private SysuiStatusBarStateController mStatusBarStateController;

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return SceneContainerFlagParameterizationKt.parameterizeSceneContainerFlag();
    }

    public NotificationShadeWindowControllerImplTest(FlagsParameterization flags) {
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        // Preferred refresh rate is equal to the first displayMode's refresh rate
        mPreferredRefreshRate = mContext.getDisplay().getSupportedModes()[0].getRefreshRate();
        overrideResource(
                R.integer.config_keyguardRefreshRate,
                (int) mPreferredRefreshRate
        );

        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        when(mColorExtractor.getNeutralColors()).thenReturn(mGradientColors);

        mStatusBarStateController = spy(mKosmos.getStatusBarStateController());

        mNotificationShadeWindowController = new NotificationShadeWindowControllerImpl(
                mContext,
                new FakeWindowRootViewComponent.Factory(mNotificationShadeWindowView),
                mWindowManager,
                mActivityManager,
                mDozeParameters,
                mStatusBarStateController,
                mConfigurationController,
                mKeyguardViewMediator,
                mKeyguardBypassController,
                mMainExecutor,
                mBackgroundExecutor,
                mColorExtractor,
                mDumpManager,
                mKeyguardStateController,
                mKosmos.getScreenOffAnimationController(),
                mAuthController,
                mKosmos::getShadeInteractor,
                mShadeWindowLogger,
                () -> mSelectedUserInteractor,
                mUserTracker,
                mKosmos::getCommunalInteractor) {
                    @Override
                    protected boolean isDebuggable() {
                        return false;
                    }
            };
        mNotificationShadeWindowController.setScrimsVisibilityListener((visibility) -> {});
        mNotificationShadeWindowController.fetchWindowRootView();

        mNotificationShadeWindowController.attach();
        verify(mWindowManager).addView(eq(mNotificationShadeWindowView), any());
        verify(mStatusBarStateController).addCallback(mStateListener.capture(), anyInt());
    }

    @Test
    public void testSetDozing_hidesSystemOverlays() {
        mNotificationShadeWindowController.setDozing(true);
        ArgumentCaptor<WindowManager.LayoutParams> captor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);
        verify(mWindowManager).updateViewLayout(any(), captor.capture());
        int flag = captor.getValue().privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        assertThat(flag).isNotEqualTo(0);

        reset(mWindowManager);
        mNotificationShadeWindowController.setDozing(false);
        verify(mWindowManager).updateViewLayout(any(), captor.capture());
        flag = captor.getValue().privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        assertThat(flag).isEqualTo(0);
    }

    @Test
    public void testOnThemeChanged_doesntCrash() {
        mNotificationShadeWindowController.onThemeChanged();
    }

    @Test
    public void testAdd_updatesVisibilityFlags() {
        verify(mNotificationShadeWindowView).setSystemUiVisibility(anyInt());
    }

    @Test
    public void testSetForcePluginOpen_beforeStatusBarInitialization() {
        mNotificationShadeWindowController.setForcePluginOpen(true, this);
    }

    @Test
    public void attach_visibleWithWallpaper() {
        clearInvocations(mWindowManager);
        when(mKeyguardViewMediator.isShowingAndNotOccluded()).thenReturn(true);
        mNotificationShadeWindowController.attach();

        verify(mNotificationShadeWindowView).setVisibility(eq(View.VISIBLE));
        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SHOW_WALLPAPER) != 0).isTrue();
    }

    @Test
    public void attach_lightScrimHidesWallpaper() {
        when(mKeyguardViewMediator.isShowingAndNotOccluded()).thenReturn(true);
        mNotificationShadeWindowController.attach();

        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.setLightRevealScrimOpaque(true);
        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SHOW_WALLPAPER) == 0).isTrue();
    }

    @Test
    public void attach_scrimDoesntHideWallpaper() {
        when(mKeyguardViewMediator.isShowingAndNotOccluded()).thenReturn(true);
        mNotificationShadeWindowController.attach();

        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.setScrimsVisibility(ScrimController.OPAQUE);
        // The scrim used to remove the wallpaper flag, but this causes a relayout.
        // Instead, we're not relying on SurfaceControl#setOpaque on
        // NotificationShadeDepthController.
        verify(mWindowManager, never()).updateViewLayout(any(), mLayoutParameters.capture());
    }

    @Test
    public void setScrimsVisibility_earlyReturn() {
        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.setScrimsVisibility(ScrimController.TRANSPARENT);
        // Abort early if value didn't change
        verify(mWindowManager, never()).updateViewLayout(any(), mLayoutParameters.capture());
    }

    @Test
    public void attach_animatingKeyguardAndSurface_wallpaperVisible() {
        clearInvocations(mWindowManager);
        when(mKeyguardViewMediator.isShowingAndNotOccluded()).thenReturn(true);
        when(mKeyguardViewMediator
                .isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe())
                .thenReturn(true);
        mNotificationShadeWindowController.attach();

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SHOW_WALLPAPER) != 0).isTrue();
    }

    @Test
    public void setBackgroundBlurRadius_expandedWithBlurs() {
        mNotificationShadeWindowController.setBackgroundBlurRadius(10);
        verify(mNotificationShadeWindowView).setVisibility(eq(View.VISIBLE));

        mNotificationShadeWindowController.setBackgroundBlurRadius(0);
        verify(mNotificationShadeWindowView).setVisibility(eq(View.INVISIBLE));
    }

    @Test
    public void setBouncerShowing_isFocusable_whenNeedsInput() {
        mNotificationShadeWindowController.setKeyguardNeedsInput(true);
        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.setBouncerShowing(true);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_NOT_FOCUSABLE) == 0).isTrue();
        assertThat((mLayoutParameters.getValue().flags & FLAG_ALT_FOCUSABLE_IM) == 0).isTrue();
    }

    @Test
    public void setKeyguardShowing_focusable_notAltFocusable_whenNeedsInput() {
        mNotificationShadeWindowController.setKeyguardShowing(true);
        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.setKeyguardNeedsInput(true);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_NOT_FOCUSABLE) == 0).isTrue();
        assertThat((mLayoutParameters.getValue().flags & FLAG_ALT_FOCUSABLE_IM) == 0).isTrue();
    }

    @Test
    public void setPanelExpanded_notFocusable_altFocusable_whenPanelIsOpen() {
        mNotificationShadeWindowController.onShadeOrQsExpanded(true);
        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.onShadeOrQsExpanded(true);
        verifyNoMoreInteractions(mWindowManager);
        mNotificationShadeWindowController.setNotificationShadeFocusable(true);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_NOT_FOCUSABLE) == 0).isTrue();
        assertThat((mLayoutParameters.getValue().flags & FLAG_ALT_FOCUSABLE_IM) != 0).isTrue();
    }

    @Test
    public void setCommunalVisible_userTimeout() {
        setKeyguardShowing();
        clearInvocations(mWindowManager);

        mNotificationShadeWindowController.onCommunalVisibleChanged(true);
        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat(mLayoutParameters.getValue().userActivityTimeout)
                .isEqualTo(CommunalInteractor.AWAKE_INTERVAL_MS);
        clearInvocations(mWindowManager);

        // Bouncer showing over communal overrides communal value
        mNotificationShadeWindowController.setBouncerShowing(true);
        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat(mLayoutParameters.getValue().userActivityTimeout)
                .isEqualTo(KeyguardViewMediator.AWAKE_INTERVAL_BOUNCER_MS);
    }

    @Test
    public void setKeyguardShowing_notFocusable_byDefault() {
        mNotificationShadeWindowController.setKeyguardShowing(false);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_NOT_FOCUSABLE) != 0).isTrue();
        assertThat((mLayoutParameters.getValue().flags & FLAG_ALT_FOCUSABLE_IM) == 0).isTrue();
    }

    @Test
    public void setKeyguardShowing_enablesSecureFlag() {
        mNotificationShadeWindowController.setBouncerShowing(true);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SECURE) != 0).isTrue();
        assertThat(
                (mLayoutParameters.getValue().inputFeatures & INPUT_FEATURE_SENSITIVE_FOR_TRACING)
                        != 0)
                .isTrue();
    }

    @Test
    public void setKeyguardNotShowing_disablesSecureFlag() {
        mNotificationShadeWindowController.setBouncerShowing(false);

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat((mLayoutParameters.getValue().flags & FLAG_SECURE) == 0).isTrue();
        assertThat(
                (mLayoutParameters.getValue().inputFeatures & INPUT_FEATURE_SENSITIVE_FOR_TRACING)
                        == 0)
                .isTrue();
    }

    @Test
    public void rotationBecameAllowed_layoutParamsUpdated() {
        mNotificationShadeWindowController.setKeyguardShowing(true);
        when(mKeyguardStateController.isKeyguardScreenRotationAllowed()).thenReturn(false);
        mNotificationShadeWindowController.onConfigChanged(new Configuration());
        clearInvocations(mWindowManager);

        when(mKeyguardStateController.isKeyguardScreenRotationAllowed()).thenReturn(true);
        mNotificationShadeWindowController.onConfigChanged(new Configuration());

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat(mLayoutParameters.getValue().screenOrientation)
                .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_USER);
    }

    @Test
    public void rotationBecameNotAllowed_layoutParamsUpdated() {
        mNotificationShadeWindowController.setKeyguardShowing(true);
        when(mKeyguardStateController.isKeyguardScreenRotationAllowed()).thenReturn(true);
        mNotificationShadeWindowController.onConfigChanged(new Configuration());
        clearInvocations(mWindowManager);

        when(mKeyguardStateController.isKeyguardScreenRotationAllowed()).thenReturn(false);
        mNotificationShadeWindowController.onConfigChanged(new Configuration());

        verify(mWindowManager).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat(mLayoutParameters.getValue().screenOrientation)
                .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
    }

    @Test
    public void batchApplyWindowLayoutParams_doesNotDispatchEvents() {
        mNotificationShadeWindowController.setForceDozeBrightness(true);
        verify(mWindowManager).updateViewLayout(any(), any());
        mNotificationShadeWindowController.setForceDozeBrightness(true);
        verifyNoMoreInteractions(mWindowManager);

        clearInvocations(mWindowManager);
        mNotificationShadeWindowController.batchApplyWindowLayoutParams(() -> {
            mNotificationShadeWindowController.setForceDozeBrightness(false);
            verify(mWindowManager, never()).updateViewLayout(any(), any());
        });
        verify(mWindowManager).updateViewLayout(any(), any());
    }

    @Test
    public void bouncerShowing_OrientationNoSensor() {
        mNotificationShadeWindowController.setKeyguardShowing(true);
        mNotificationShadeWindowController.setKeyguardOccluded(true);
        mNotificationShadeWindowController.setBouncerShowing(true);
        when(mKeyguardStateController.isKeyguardScreenRotationAllowed()).thenReturn(false);
        mNotificationShadeWindowController.onConfigChanged(new Configuration());

        verify(mWindowManager, atLeastOnce()).updateViewLayout(any(), mLayoutParameters.capture());
        assertThat(mLayoutParameters.getValue().screenOrientation)
                .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
    }

    @Test
    public void udfpsEnrolled_minAndMaxRefreshRateSetToPreferredRefreshRate() {
        // GIVEN optical udfps is enrolled
        when(mAuthController.isOpticalUdfpsEnrolled(anyInt())).thenReturn(true);

        // WHEN keyguard is showing
        setKeyguardShowing();

        // THEN min and max refresh rate is set to the preferredRefreshRate
        verify(mWindowManager, atLeastOnce()).updateViewLayout(any(), mLayoutParameters.capture());
        final List<WindowManager.LayoutParams> lpList = mLayoutParameters.getAllValues();
        final WindowManager.LayoutParams lp = lpList.get(lpList.size() - 1);
        assertThat(lp.preferredMaxDisplayRefreshRate).isEqualTo(mPreferredRefreshRate);
        assertThat(lp.preferredMinDisplayRefreshRate).isEqualTo(mPreferredRefreshRate);
    }

    @Test
    public void opticalUdfpsNotEnrolled_refreshRateUnset() {
        // GIVEN udfps is NOT enrolled
        when(mAuthController.isOpticalUdfpsEnrolled(anyInt())).thenReturn(false);

        // WHEN keyguard is showing
        setKeyguardShowing();

        // THEN min and max refresh rate aren't set (set to 0)
        verify(mWindowManager, atLeastOnce()).updateViewLayout(any(), mLayoutParameters.capture());
        final List<WindowManager.LayoutParams> lpList = mLayoutParameters.getAllValues();
        final WindowManager.LayoutParams lp = lpList.get(lpList.size() - 1);
        assertThat(lp.preferredMaxDisplayRefreshRate).isEqualTo(0);
        assertThat(lp.preferredMinDisplayRefreshRate).isEqualTo(0);
    }

    @Test
    public void keyguardNotShowing_refreshRateUnset() {
        // GIVEN optical UDFPS is enrolled
        when(mAuthController.isOpticalUdfpsEnrolled(anyInt())).thenReturn(true);

        // WHEN keyguard is NOT showing
        mNotificationShadeWindowController.setKeyguardShowing(false);

        // THEN min and max refresh rate aren't set (set to 0)
        verify(mWindowManager, atLeastOnce()).updateViewLayout(any(), mLayoutParameters.capture());
        final List<WindowManager.LayoutParams> lpList = mLayoutParameters.getAllValues();
        final WindowManager.LayoutParams lp = lpList.get(lpList.size() - 1);
        assertThat(lp.preferredMaxDisplayRefreshRate).isEqualTo(0);
        assertThat(lp.preferredMinDisplayRefreshRate).isEqualTo(0);
    }

    private void setKeyguardShowing() {
        mNotificationShadeWindowController.setKeyguardShowing(true);
        mNotificationShadeWindowController.setKeyguardGoingAway(false);
        mNotificationShadeWindowController.setKeyguardFadingAway(false);
        mStateListener.getValue().onStateChanged(StatusBarState.KEYGUARD);
    }
}
