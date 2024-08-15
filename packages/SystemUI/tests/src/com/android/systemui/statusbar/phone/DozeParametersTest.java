/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.res.Resources;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.doze.DozeScreenState;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.domain.interactor.DozeInteractor;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.unfold.FoldAodAnimationController;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DozeParametersTest extends SysuiTestCase {
    private DozeParameters mDozeParameters;

    @Mock Handler mHandler;
    @Mock Resources mResources;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock private AlwaysOnDisplayPolicy mAlwaysOnDisplayPolicy;
    @Mock private PowerManager mPowerManager;
    @Mock private TunerService mTunerService;
    @Mock private BatteryController mBatteryController;
    @Mock private DumpManager mDumpManager;
    @Mock private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock private FoldAodAnimationController mFoldAodAnimationController;
    @Mock private SysUIUnfoldComponent mSysUIUnfoldComponent;
    @Mock private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private UserTracker mUserTracker;
    @Mock private DozeInteractor mDozeInteractor;
    @Captor private ArgumentCaptor<BatteryStateChangeCallback> mBatteryStateChangeCallback;

    /**
     * The current value of PowerManager's dozeAfterScreenOff property.
     *
     * This property controls whether System UI is controlling the screen off animation. If it's
     * false (PowerManager should not doze after screen off) then System UI is controlling the
     * animation. If true, we're not controlling it and PowerManager will doze immediately.
     */
    private boolean mPowerManagerDozeAfterScreenOff;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        // Save the current value set for dozeAfterScreenOff so we can make assertions. This method
        // is only called if the value changes, which makes it difficult to check that it was set
        // correctly in tests.
        doAnswer(invocation -> {
            mPowerManagerDozeAfterScreenOff = invocation.getArgument(0);
            return mPowerManagerDozeAfterScreenOff;
        }).when(mPowerManager).setDozeAfterScreenOff(anyBoolean());

        when(mSysUIUnfoldComponent.getFoldAodAnimationController())
                .thenReturn(mFoldAodAnimationController);
        when(mUserTracker.getUserId()).thenReturn(ActivityManager.getCurrentUser());

        SecureSettings secureSettings = new FakeSettings();
        mDozeParameters = new DozeParameters(
            mContext,
            mHandler,
            mResources,
            mAmbientDisplayConfiguration,
            mAlwaysOnDisplayPolicy,
            mPowerManager,
            mBatteryController,
            mTunerService,
            mDumpManager,
            mScreenOffAnimationController,
            Optional.of(mSysUIUnfoldComponent),
            mUnlockedScreenOffAnimationController,
            mKeyguardUpdateMonitor,
            mConfigurationController,
            mStatusBarStateController,
            mUserTracker,
            mDozeInteractor,
            secureSettings
        );

        verify(mBatteryController).addCallback(mBatteryStateChangeCallback.capture());

        setAodEnabledForTest(true);
        setShouldControlUnlockedScreenOffForTest(true);
        setDisplayNeedsBlankingForTest(false);

        // Default to false here (with one test to make sure that when it returns true, we respect
        // that). We'll test the specific conditions for this to return true/false in the
        // UnlockedScreenOffAnimationController's tests.
        when(mUnlockedScreenOffAnimationController.shouldPlayUnlockedScreenOffAnimation())
                .thenReturn(false);
    }

    @Test
    public void testSetControlScreenOffAnimation_setsDozeAfterScreenOff_correctly() {
        // If we want to control screen off, we do NOT want PowerManager to doze after screen off.
        // Obviously.
        mDozeParameters.setControlScreenOffAnimation(true);
        assertFalse(mPowerManagerDozeAfterScreenOff);

        // If we don't want to control screen off, PowerManager is free to doze after screen off if
        // that's what'll make it happy.
        mDozeParameters.setControlScreenOffAnimation(false);
        assertTrue(mPowerManagerDozeAfterScreenOff);
    }

    @Test
    public void testGetWallpaperAodDuration_when_shouldControlScreenOff() {
        mDozeParameters.setControlScreenOffAnimation(true);
        Assert.assertEquals(
                "wallpaper hides faster when controlling screen off",
                mDozeParameters.getWallpaperAodDuration(),
                DozeScreenState.ENTER_DOZE_HIDE_WALLPAPER_DELAY);
    }

    @Test
    public void testGetAlwaysOn() {
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "1");

        assertThat(mDozeParameters.getAlwaysOn()).isTrue();
    }

    @Test
    public void testGetAlwaysOn_whenBatterySaver() {
        when(mBatteryController.isAodPowerSave()).thenReturn(true);
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "1");

        verify(mScreenOffAnimationController).onAlwaysOnChanged(false);
        assertThat(mDozeParameters.getAlwaysOn()).isFalse();
    }

    @Test
    public void testGetAlwaysOn_whenBatterySaverCallback() {
        reset(mDozeInteractor);
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        when(mBatteryController.isAodPowerSave()).thenReturn(true);

        // Both lines should trigger an event
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "1");
        mBatteryStateChangeCallback.getValue().onPowerSaveChanged(true);

        verify(mDozeInteractor, times(2)).setAodAvailable(anyBoolean());
        verify(mScreenOffAnimationController, times(2)).onAlwaysOnChanged(false);
        assertThat(mDozeParameters.getAlwaysOn()).isFalse();

        reset(mScreenOffAnimationController);
        reset(mDozeInteractor);
        when(mBatteryController.isAodPowerSave()).thenReturn(false);
        mBatteryStateChangeCallback.getValue().onPowerSaveChanged(true);

        verify(mDozeInteractor).setAodAvailable(anyBoolean());
        verify(mScreenOffAnimationController).onAlwaysOnChanged(true);
        assertThat(mDozeParameters.getAlwaysOn()).isTrue();
    }

    /**
     * PowerManager.setDozeAfterScreenOff(true) means we are not controlling screen off, and calling
     * it with false means we are. Confusing, but sure - make sure that we call PowerManager with
     * the correct value depending on whether we want to control screen off.
     */
    @Test
    public void testControlUnlockedScreenOffAnimation_dozeAfterScreenOff_false() {
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(true);

        // If AOD is disabled, we shouldn't want to control screen off. Also, let's double check
        // that when that value is updated, we called through to PowerManager.
        setAodEnabledForTest(false);

        assertFalse(mDozeParameters.shouldControlScreenOff());
        assertTrue(mPowerManagerDozeAfterScreenOff);

        // And vice versa...
        setAodEnabledForTest(true);
        assertTrue(mDozeParameters.shouldControlScreenOff());
        assertFalse(mPowerManagerDozeAfterScreenOff);
    }

    @Test
    public void propagatesAnimateScreenOff_noAlwaysOn() {
        setAodEnabledForTest(false);
        setDisplayNeedsBlankingForTest(false);

        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertFalse(mDozeParameters.shouldControlScreenOff());
    }

    @Test
    public void propagatesAnimateScreenOff_alwaysOn() {
        setAodEnabledForTest(true);
        setDisplayNeedsBlankingForTest(false);
        setShouldControlUnlockedScreenOffForTest(false);

        // Take over when the keyguard is visible.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(true);
        assertTrue(mDozeParameters.shouldControlScreenOff());

        // Do not animate screen-off when keyguard isn't visible.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertFalse(mDozeParameters.shouldControlScreenOff());
    }


    @Test
    public void neverAnimateScreenOff_whenNotSupported() {
        setDisplayNeedsBlankingForTest(true);

        // Never animate if display doesn't support it.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(true);
        assertFalse(mDozeParameters.shouldControlScreenOff());
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertFalse(mDozeParameters.shouldControlScreenOff());
    }


    @Test
    public void controlScreenOffTrueWhenKeyguardNotShowingAndControlUnlockedScreenOff() {
        setShouldControlUnlockedScreenOffForTest(true);

        // Tell doze that keyguard is not visible.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(
                false /* showing */);

        // Since we're controlling the unlocked screen off animation, verify that we've asked to
        // control the screen off animation despite being unlocked.
        assertTrue(mDozeParameters.shouldControlScreenOff());
    }


    @Test
    public void keyguardVisibility_changesControlScreenOffAnimation() {
        setShouldControlUnlockedScreenOffForTest(false);

        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertFalse(mDozeParameters.shouldControlScreenOff());
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(true);
        assertTrue(mDozeParameters.shouldControlScreenOff());
    }

    @Test
    public void keyguardVisibility_changesControlScreenOffAnimation_respectsUnlockedScreenOff() {
        setShouldControlUnlockedScreenOffForTest(true);

        // Even if the keyguard is gone, we should control screen off if we can control unlocked
        // screen off.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertTrue(mDozeParameters.shouldControlScreenOff());

        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(true);
        assertTrue(mDozeParameters.shouldControlScreenOff());
    }

    private void setDisplayNeedsBlankingForTest(boolean needsBlanking) {
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_displayBlanksAfterDoze)).thenReturn(
                        needsBlanking);
    }

    private void setAodEnabledForTest(boolean enabled) {
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(enabled);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "");
    }

    private void setShouldControlUnlockedScreenOffForTest(boolean shouldControl) {
        when(mUnlockedScreenOffAnimationController.shouldPlayUnlockedScreenOffAnimation())
                .thenReturn(shouldControl);
    }
}
