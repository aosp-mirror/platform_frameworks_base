/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.StatusBarManager;
import android.os.PowerManager;
import android.os.Vibrator;
import android.testing.AndroidTestingRunner;
import android.view.InsetsVisibilities;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.view.AppearanceRegion;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.shade.CameraLauncher;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DisableFlagsLogger;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.Optional;

import dagger.Lazy;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CentralSurfacesCommandQueueCallbacksTest extends SysuiTestCase {

    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private ShadeController mShadeController;
    @Mock private CommandQueue mCommandQueue;
    @Mock private NotificationPanelViewController mNotificationPanelViewController;
    @Mock private RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;
    private final MetricsLogger mMetricsLogger = new FakeMetricsLogger();
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private AssistManager mAssistManager;
    @Mock private DozeServiceHost mDozeServiceHost;
    @Mock private NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    @Mock private PowerManager mPowerManager;
    @Mock private VibratorHelper mVibratorHelper;
    @Mock private Vibrator mVibrator;
    @Mock private StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;
    @Mock private SystemBarAttributesListener mSystemBarAttributesListener;
    @Mock private Lazy<CameraLauncher> mCameraLauncherLazy;

    CentralSurfacesCommandQueueCallbacks mSbcqCallbacks;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mSbcqCallbacks = new CentralSurfacesCommandQueueCallbacks(
                mCentralSurfaces,
                mContext,
                mContext.getResources(),
                mShadeController,
                mCommandQueue,
                mNotificationPanelViewController,
                mRemoteInputQuickSettingsDisabler,
                mMetricsLogger,
                mKeyguardUpdateMonitor,
                mKeyguardStateController,
                mHeadsUpManager,
                mWakefulnessLifecycle,
                mDeviceProvisionedController,
                mStatusBarKeyguardViewManager,
                mAssistManager,
                mDozeServiceHost,
                mNotificationStackScrollLayoutController,
                mStatusBarHideIconsForBouncerManager,
                mPowerManager,
                mVibratorHelper,
                Optional.of(mVibrator),
                new DisableFlagsLogger(),
                DEFAULT_DISPLAY,
                mSystemBarAttributesListener,
                mCameraLauncherLazy);

        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        when(mRemoteInputQuickSettingsDisabler.adjustDisableFlags(anyInt()))
                .thenAnswer((Answer<Integer>) invocation -> invocation.getArgument(0));
    }

    @Test
    public void testDisableNotificationShade() {
        when(mCentralSurfaces.getDisabled1()).thenReturn(StatusBarManager.DISABLE_NONE);
        when(mCentralSurfaces.getDisabled2()).thenReturn(StatusBarManager.DISABLE_NONE);
        when(mCommandQueue.panelsEnabled()).thenReturn(false);
        mSbcqCallbacks.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NOTIFICATION_SHADE, false);

        verify(mCentralSurfaces).updateQsExpansionEnabled();
        verify(mShadeController).animateCollapseShade();

        // Trying to open it does nothing.
        mSbcqCallbacks.animateExpandNotificationsPanel();
        verify(mNotificationPanelViewController, never()).expandShadeToNotifications();
        mSbcqCallbacks.animateExpandSettingsPanel(null);
        verify(mNotificationPanelViewController, never()).expand(anyBoolean());
    }

    @Test
    public void testEnableNotificationShade() {
        when(mCentralSurfaces.getDisabled1()).thenReturn(StatusBarManager.DISABLE_NONE);
        when(mCentralSurfaces.getDisabled2())
                .thenReturn(StatusBarManager.DISABLE2_NOTIFICATION_SHADE);
        when(mCommandQueue.panelsEnabled()).thenReturn(true);
        mSbcqCallbacks.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NONE, false);
        verify(mCentralSurfaces).updateQsExpansionEnabled();
        verify(mShadeController, never()).animateCollapseShade();

        // Can now be opened.
        mSbcqCallbacks.animateExpandNotificationsPanel();
        verify(mNotificationPanelViewController).expandShadeToNotifications();
        mSbcqCallbacks.animateExpandSettingsPanel(null);
        verify(mNotificationPanelViewController).expandWithQs();
    }

    @Test
    public void testSuppressAmbientDisplay_suppress() {
        mSbcqCallbacks.suppressAmbientDisplay(true);
        verify(mDozeServiceHost).setAlwaysOnSuppressed(true);
    }

    @Test
    public void testSuppressAmbientDisplay_unsuppress() {
        mSbcqCallbacks.suppressAmbientDisplay(false);
        verify(mDozeServiceHost).setAlwaysOnSuppressed(false);
    }

    @Test
    public void onSystemBarAttributesChanged_forwardsToSysBarAttrsListener() {
        int displayId = DEFAULT_DISPLAY;
        int appearance = 123;
        AppearanceRegion[] appearanceRegions = new AppearanceRegion[]{};
        boolean navbarColorManagedByIme = true;
        int behavior = 456;
        InsetsVisibilities requestedVisibilities = new InsetsVisibilities();
        String packageName = "test package name";
        LetterboxDetails[] letterboxDetails = new LetterboxDetails[]{};

        mSbcqCallbacks.onSystemBarAttributesChanged(
                displayId,
                appearance,
                appearanceRegions,
                navbarColorManagedByIme,
                behavior,
                requestedVisibilities,
                packageName,
                letterboxDetails);

        verify(mSystemBarAttributesListener).onSystemBarAttributesChanged(
                displayId,
                appearance,
                appearanceRegions,
                navbarColorManagedByIme,
                behavior,
                requestedVisibilities,
                packageName,
                letterboxDetails
        );
    }

    @Test
    public void onSystemBarAttributesChanged_differentDisplayId_doesNotForwardToAttrsListener() {
        int appearance = 123;
        AppearanceRegion[] appearanceRegions = new AppearanceRegion[]{};
        boolean navbarColorManagedByIme = true;
        int behavior = 456;
        InsetsVisibilities requestedVisibilities = new InsetsVisibilities();
        String packageName = "test package name";
        LetterboxDetails[] letterboxDetails = new LetterboxDetails[]{};

        mSbcqCallbacks.onSystemBarAttributesChanged(
                DEFAULT_DISPLAY + 1,
                appearance,
                appearanceRegions,
                navbarColorManagedByIme,
                behavior,
                requestedVisibilities,
                packageName,
                letterboxDetails);

        verifyZeroInteractions(mSystemBarAttributesListener);
    }
}
