/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.systemui.qs.dagger.QSFlagsModule.RBC_AVAILABLE;
import static com.android.systemui.statusbar.phone.AutoTileManager.DEVICE_CONTROLS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.AutoAddTracker;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.ReduceBrightColorsController;
import com.android.systemui.qs.SettingObserver;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DeviceControlsController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.SafetyController;
import com.android.systemui.statusbar.policy.WalletController;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Named;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AutoTileManagerTest extends SysuiTestCase {

    private static final String TEST_SETTING = "setting";
    private static final String TEST_SPEC = "spec";
    private static final String TEST_SETTING_COMPONENT = "setting_component";
    private static final String TEST_COMPONENT = "test_pkg/test_cls";
    private static final String TEST_CUSTOM_SPEC = "custom(" + TEST_COMPONENT + ")";
    private static final String TEST_CUSTOM_SAFETY_CLASS = "safety_cls";
    private static final String TEST_CUSTOM_SAFETY_PKG = "safety_pkg";
    private static final String TEST_CUSTOM_SAFETY_SPEC = CustomTile.toSpec(new ComponentName(
            TEST_CUSTOM_SAFETY_PKG, TEST_CUSTOM_SAFETY_CLASS));
    private static final String SEPARATOR = AutoTileManager.SETTING_SEPARATOR;

    private static final int USER = 0;

    @Mock private QSHost mQsHost;
    @Mock private AutoAddTracker mAutoAddTracker;
    @Mock private CastController mCastController;
    @Mock private HotspotController mHotspotController;
    @Mock private DataSaverController mDataSaverController;
    @Mock private ManagedProfileController mManagedProfileController;
    @Mock private NightDisplayListener mNightDisplayListener;
    @Mock private ReduceBrightColorsController mReduceBrightColorsController;
    @Mock private DeviceControlsController mDeviceControlsController;
    @Mock private WalletController mWalletController;
    @Mock private SafetyController mSafetyController;
    @Mock(answer = Answers.RETURNS_SELF)
    private AutoAddTracker.Builder mAutoAddTrackerBuilder;
    @Mock private Context mUserContext;
    @Spy private PackageManager mPackageManager;
    private final boolean mIsReduceBrightColorsAvailable = true;

    private AutoTileManager mAutoTileManager; // under test

    private SecureSettings mSecureSettings;
    private ManagedProfileController.Callback mManagedProfileCallback;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSecureSettings = new FakeSettings();

        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_quickSettingsAutoAdd,
                new String[] {
                        TEST_SETTING + SEPARATOR + TEST_SPEC,
                        TEST_SETTING_COMPONENT + SEPARATOR + TEST_CUSTOM_SPEC
                }
        );
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_nightDisplayAvailable, true);
        mContext.getOrCreateTestableResources().addOverride(
                R.string.safety_quick_settings_tile_class, TEST_CUSTOM_SAFETY_CLASS);

        when(mAutoAddTrackerBuilder.build()).thenReturn(mAutoAddTracker);
        when(mQsHost.getUserContext()).thenReturn(mUserContext);
        when(mUserContext.getUser()).thenReturn(UserHandle.of(USER));
        mPackageManager = Mockito.spy(mContext.getPackageManager());
        when(mPackageManager.getPermissionControllerPackageName())
                .thenReturn(TEST_CUSTOM_SAFETY_PKG);
        Context context = Mockito.spy(mContext);
        when(context.getPackageManager()).thenReturn(mPackageManager);

        mAutoTileManager = createAutoTileManager(context);
        mAutoTileManager.init();
    }

    @After
    public void tearDown() {
        mAutoTileManager.destroy();
    }

    private AutoTileManager createAutoTileManager(
            Context context,
            AutoAddTracker.Builder autoAddTrackerBuilder,
            HotspotController hotspotController,
            DataSaverController dataSaverController,
            ManagedProfileController managedProfileController,
            NightDisplayListener nightDisplayListener,
            CastController castController,
            ReduceBrightColorsController reduceBrightColorsController,
            DeviceControlsController deviceControlsController,
            WalletController walletController,
            SafetyController safetyController,
            @Named(RBC_AVAILABLE) boolean isReduceBrightColorsAvailable) {
        return new AutoTileManager(context, autoAddTrackerBuilder, mQsHost,
                Handler.createAsync(TestableLooper.get(this).getLooper()),
                mSecureSettings,
                hotspotController,
                dataSaverController,
                managedProfileController,
                nightDisplayListener,
                castController,
                reduceBrightColorsController,
                deviceControlsController,
                walletController,
                safetyController,
                isReduceBrightColorsAvailable);
    }

    private AutoTileManager createAutoTileManager(Context context) {
        return createAutoTileManager(context, mAutoAddTrackerBuilder, mHotspotController,
                mDataSaverController, mManagedProfileController, mNightDisplayListener,
                mCastController, mReduceBrightColorsController, mDeviceControlsController,
                mWalletController, mSafetyController, mIsReduceBrightColorsAvailable);
    }

    @Test
    public void testCreatedAutoTileManagerIsNotInitialized() {
        AutoAddTracker.Builder builder = mock(AutoAddTracker.Builder.class, Answers.RETURNS_SELF);
        AutoAddTracker tracker = mock(AutoAddTracker.class);
        when(builder.build()).thenReturn(tracker);
        HotspotController hC = mock(HotspotController.class);
        DataSaverController dSC = mock(DataSaverController.class);
        ManagedProfileController mPC = mock(ManagedProfileController.class);
        NightDisplayListener nDS = mock(NightDisplayListener.class);
        CastController cC = mock(CastController.class);
        ReduceBrightColorsController rBC = mock(ReduceBrightColorsController.class);
        DeviceControlsController dCC = mock(DeviceControlsController.class);
        WalletController wC = mock(WalletController.class);
        SafetyController sC = mock(SafetyController.class);

        AutoTileManager manager =
                createAutoTileManager(mock(Context.class), builder, hC, dSC, mPC, nDS, cC, rBC,
                        dCC, wC, sC, true);

        verify(tracker, never()).initialize();
        verify(hC, never()).addCallback(any());
        verify(dSC, never()).addCallback(any());
        verify(mPC, never()).addCallback(any());
        verify(nDS, never()).setCallback(any());
        verify(cC, never()).addCallback(any());
        verify(rBC, never()).addCallback(any());
        verify(dCC, never()).setCallback(any());
        verify(wC, never()).getWalletPosition();
        verify(sC, never()).addCallback(any());
        assertNull(manager.getSecureSettingForKey(TEST_SETTING));
        assertNull(manager.getSecureSettingForKey(TEST_SETTING_COMPONENT));
    }

    @Test
    public void testChangeUserWhenNotInitializedThrows() {
        AutoTileManager manager = createAutoTileManager(mock(Context.class));

        try {
            manager.changeUser(UserHandle.of(USER + 1));
            fail();
        } catch (Exception e) {
            // This should throw and take this path
        }
    }

    @Test
    public void testChangeUserCallbacksStoppedAndStarted() throws Exception {
        TestableLooper.get(this).runWithLooper(() ->
                mAutoTileManager.changeUser(UserHandle.of(USER + 1))
        );

        InOrder inOrderHotspot = inOrder(mHotspotController);
        inOrderHotspot.verify(mHotspotController).removeCallback(any());
        inOrderHotspot.verify(mHotspotController).addCallback(any());

        InOrder inOrderDataSaver = inOrder(mDataSaverController);
        inOrderDataSaver.verify(mDataSaverController).removeCallback(any());
        inOrderDataSaver.verify(mDataSaverController).addCallback(any());

        InOrder inOrderManagedProfile = inOrder(mManagedProfileController);
        inOrderManagedProfile.verify(mManagedProfileController).removeCallback(any());
        inOrderManagedProfile.verify(mManagedProfileController).addCallback(any());

        if (ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            InOrder inOrderNightDisplay = inOrder(mNightDisplayListener);
            inOrderNightDisplay.verify(mNightDisplayListener).setCallback(isNull());
            inOrderNightDisplay.verify(mNightDisplayListener).setCallback(isNotNull());
        }

        InOrder inOrderReduceBrightColors = inOrder(mReduceBrightColorsController);
        inOrderReduceBrightColors.verify(mReduceBrightColorsController).removeCallback(any());
        inOrderReduceBrightColors.verify(mReduceBrightColorsController).addCallback(any());

        InOrder inOrderCast = inOrder(mCastController);
        inOrderCast.verify(mCastController).removeCallback(any());
        inOrderCast.verify(mCastController).addCallback(any());

        InOrder inOrderDevices = inOrder(mDeviceControlsController);
        inOrderDevices.verify(mDeviceControlsController).removeCallback();
        inOrderDevices.verify(mDeviceControlsController).setCallback(any());

        verify(mWalletController, times(2)).getWalletPosition();

        InOrder inOrderSafety = inOrder(mSafetyController);
        inOrderSafety.verify(mSafetyController).removeCallback(any());
        inOrderSafety.verify(mSafetyController).addCallback(any());

        SettingObserver setting = mAutoTileManager.getSecureSettingForKey(TEST_SETTING);
        assertEquals(USER + 1, setting.getCurrentUser());
        assertTrue(setting.isListening());
    }

    @Test
    public void testChangeUserSomeCallbacksNotAdded() throws Exception {
        when(mAutoAddTracker.isAdded("hotspot")).thenReturn(true);
        when(mAutoAddTracker.isAdded("work")).thenReturn(true);
        when(mAutoAddTracker.isAdded("cast")).thenReturn(true);
        when(mAutoAddTracker.isAdded(TEST_SPEC)).thenReturn(true);

        TestableLooper.get(this).runWithLooper(() ->
                mAutoTileManager.changeUser(UserHandle.of(USER + 1))
        );

        verify(mAutoAddTracker).changeUser(UserHandle.of(USER + 1));

        InOrder inOrderHotspot = inOrder(mHotspotController);
        inOrderHotspot.verify(mHotspotController).removeCallback(any());
        inOrderHotspot.verify(mHotspotController, never()).addCallback(any());

        InOrder inOrderDataSaver = inOrder(mDataSaverController);
        inOrderDataSaver.verify(mDataSaverController).removeCallback(any());
        inOrderDataSaver.verify(mDataSaverController).addCallback(any());

        InOrder inOrderManagedProfile = inOrder(mManagedProfileController);
        inOrderManagedProfile.verify(mManagedProfileController).removeCallback(any());
        inOrderManagedProfile.verify(mManagedProfileController).addCallback(any());

        if (ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            InOrder inOrderNightDisplay = inOrder(mNightDisplayListener);
            inOrderNightDisplay.verify(mNightDisplayListener).setCallback(isNull());
            inOrderNightDisplay.verify(mNightDisplayListener).setCallback(isNotNull());
        }

        InOrder inOrderReduceBrightColors = inOrder(mReduceBrightColorsController);
        inOrderReduceBrightColors.verify(mReduceBrightColorsController).removeCallback(any());
        inOrderReduceBrightColors.verify(mReduceBrightColorsController).addCallback(any());

        InOrder inOrderCast = inOrder(mCastController);
        inOrderCast.verify(mCastController).removeCallback(any());
        inOrderCast.verify(mCastController, never()).addCallback(any());

        InOrder inOrderDevices = inOrder(mDeviceControlsController);
        inOrderDevices.verify(mDeviceControlsController).removeCallback();
        inOrderDevices.verify(mDeviceControlsController).setCallback(any());

        verify(mWalletController, times(2)).getWalletPosition();

        InOrder inOrderSafety = inOrder(mSafetyController);
        inOrderSafety.verify(mSafetyController).removeCallback(any());
        inOrderSafety.verify(mSafetyController).addCallback(any());

        SettingObserver setting = mAutoTileManager.getSecureSettingForKey(TEST_SETTING);
        assertEquals(USER + 1, setting.getCurrentUser());
        assertFalse(setting.isListening());
    }

    @Test
    public void testGetCurrentUserId() throws Exception {
        assertEquals(USER, mAutoTileManager.getCurrentUserId());

        TestableLooper.get(this).runWithLooper(() ->
                mAutoTileManager.changeUser(UserHandle.of(USER + 100))
        );

        assertEquals(USER + 100, mAutoTileManager.getCurrentUserId());
    }

    @Test
    public void nightTileAdded_whenActivated() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onActivated(true);
        verify(mQsHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenDeactivated() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onActivated(false);
        verify(mQsHost, never()).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeTwilight() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                ColorDisplayManager.AUTO_MODE_TWILIGHT);
        verify(mQsHost).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeCustom() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                ColorDisplayManager.AUTO_MODE_CUSTOM_TIME);
        verify(mQsHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenNightModeDisabled() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                ColorDisplayManager.AUTO_MODE_DISABLED);
        verify(mQsHost, never()).addTile("night");
    }

    @Test
    public void reduceBrightColorsTileAdded_whenActivated() {
        mAutoTileManager.mReduceBrightColorsCallback.onActivated(true);
        verify(mQsHost).addTile("reduce_brightness");
    }

    @Test
    public void reduceBrightColorsTileNotAdded_whenDeactivated() {
        mAutoTileManager.mReduceBrightColorsCallback.onActivated(false);
        verify(mQsHost, never()).addTile("reduce_brightness");
    }

    private static List<CastDevice> buildFakeCastDevice(boolean isCasting) {
        CastDevice cd = new CastDevice();
        cd.state = isCasting ? CastDevice.STATE_CONNECTED : CastDevice.STATE_DISCONNECTED;
        return Collections.singletonList(cd);
    }

    @Test
    public void castTileAdded_whenDeviceIsCasting() {
        doReturn(buildFakeCastDevice(true)).when(mCastController).getCastDevices();
        mAutoTileManager.mCastCallback.onCastDevicesChanged();
        verify(mQsHost).addTile("cast");
    }

    @Test
    public void castTileNotAdded_whenDeviceIsNotCasting() {
        doReturn(buildFakeCastDevice(false)).when(mCastController).getCastDevices();
        mAutoTileManager.mCastCallback.onCastDevicesChanged();
        verify(mQsHost, never()).addTile("cast");
    }

    @Test
    public void testSettingTileAdded_onChanged() {
        changeValue(TEST_SETTING, 1);
        verify(mAutoAddTracker).setTileAdded(TEST_SPEC);
        verify(mQsHost).addTile(TEST_SPEC);
    }

    @Test
    public void testSettingTileAddedComponentAtEnd_onChanged() {
        changeValue(TEST_SETTING_COMPONENT, 1);
        verify(mAutoAddTracker).setTileAdded(TEST_CUSTOM_SPEC);
        verify(mQsHost).addTile(ComponentName.unflattenFromString(TEST_COMPONENT)
            , /* end */ true);
    }

    @Test
    public void testSettingTileAdded_onlyOnce() {
        changeValue(TEST_SETTING, 1);
        changeValue(TEST_SETTING, 2);
        verify(mAutoAddTracker).setTileAdded(TEST_SPEC);
        verify(mQsHost).addTile(TEST_SPEC);
    }

    @Test
    public void testSettingTileNotAdded_onChangedTo0() {
        changeValue(TEST_SETTING, 0);
        verify(mAutoAddTracker, never()).setTileAdded(TEST_SPEC);
        verify(mQsHost, never()).addTile(TEST_SPEC);
    }

    @Test
    public void testSettingTileNotAdded_ifPreviouslyAdded() {
        when(mAutoAddTracker.isAdded(TEST_SPEC)).thenReturn(true);

        changeValue(TEST_SETTING, 1);
        verify(mAutoAddTracker, never()).setTileAdded(TEST_SPEC);
        verify(mQsHost, never()).addTile(TEST_SPEC);
    }

    @Test
    public void testSafetyTileNotAdded_ifPreviouslyAdded() {
        ComponentName safetyComponent = CustomTile.getComponentFromSpec(TEST_CUSTOM_SAFETY_SPEC);
        mAutoTileManager.init();
        verify(mQsHost, times(1)).addTile(safetyComponent, true);
        when(mAutoAddTracker.isAdded(TEST_CUSTOM_SAFETY_SPEC)).thenReturn(true);
        mAutoTileManager.init();
        verify(mQsHost, times(1)).addTile(safetyComponent, true);
    }

    @Test
    public void testSafetyTileAdded_onUserChange() {
        ComponentName safetyComponent = CustomTile.getComponentFromSpec(TEST_CUSTOM_SAFETY_SPEC);
        mAutoTileManager.init();
        verify(mQsHost, times(1)).addTile(safetyComponent, true);
        when(mAutoAddTracker.isAdded(TEST_CUSTOM_SAFETY_SPEC)).thenReturn(false);
        mAutoTileManager.changeUser(UserHandle.of(USER + 1));
        verify(mQsHost, times(2)).addTile(safetyComponent, true);
    }

    @Test
    public void testSafetyTileRemoved_onSafetyCenterDisable() {
        ComponentName safetyComponent = CustomTile.getComponentFromSpec(TEST_CUSTOM_SAFETY_SPEC);
        mAutoTileManager.init();
        when(mAutoAddTracker.isAdded(TEST_CUSTOM_SAFETY_SPEC)).thenReturn(true);
        mAutoTileManager.mSafetyCallback.onSafetyCenterEnableChanged(false);
        verify(mQsHost, times(1)).removeTile(TEST_CUSTOM_SAFETY_SPEC);
    }

    @Test
    public void testSafetyTileAdded_onSafetyCenterEnable() {
        ComponentName safetyComponent = CustomTile.getComponentFromSpec(TEST_CUSTOM_SAFETY_SPEC);
        mAutoTileManager.init();
        verify(mQsHost, times(1)).addTile(safetyComponent, true);
        mAutoTileManager.mSafetyCallback.onSafetyCenterEnableChanged(false);
        mAutoTileManager.mSafetyCallback.onSafetyCenterEnableChanged(true);
        verify(mQsHost, times(2)).addTile(safetyComponent, true);
    }

    @Test
    public void managedProfileAdded_tileAdded() {
        when(mAutoAddTracker.isAdded(eq("work"))).thenReturn(false);
        when(mAutoAddTracker.getRestoredTilePosition(eq("work"))).thenReturn(2);
        mAutoTileManager = createAutoTileManager(mContext);
        Mockito.doAnswer((Answer<Object>) invocation -> {
            mManagedProfileCallback = invocation.getArgument(0);
            return null;
        }).when(mManagedProfileController).addCallback(any());
        mAutoTileManager.init();
        when(mManagedProfileController.hasActiveProfile()).thenReturn(true);

        mManagedProfileCallback.onManagedProfileChanged();

        verify(mQsHost, times(1)).addTile(eq("work"), eq(2));
        verify(mAutoAddTracker, times(1)).setTileAdded(eq("work"));
    }

    @Test
    public void managedProfileRemoved_tileRemoved() {
        when(mAutoAddTracker.isAdded(eq("work"))).thenReturn(true);
        mAutoTileManager = createAutoTileManager(mContext);
        Mockito.doAnswer((Answer<Object>) invocation -> {
            mManagedProfileCallback = invocation.getArgument(0);
            return null;
        }).when(mManagedProfileController).addCallback(any());
        mAutoTileManager.init();
        when(mManagedProfileController.hasActiveProfile()).thenReturn(false);

        mManagedProfileCallback.onManagedProfileChanged();

        verify(mQsHost, times(1)).removeTile(eq("work"));
        verify(mAutoAddTracker, times(1)).setTileRemoved(eq("work"));
    }

    @Test
    public void testAddControlsTileIfNotPresent() {
        String spec = DEVICE_CONTROLS;
        when(mAutoAddTracker.isAdded(eq(spec))).thenReturn(false);
        when(mQsHost.getTiles()).thenReturn(new ArrayList<>());

        mAutoTileManager.init();
        ArgumentCaptor<DeviceControlsController.Callback> captor =
                ArgumentCaptor.forClass(DeviceControlsController.Callback.class);

        verify(mDeviceControlsController).setCallback(captor.capture());

        captor.getValue().onControlsUpdate(3);
        verify(mQsHost).addTile(spec, 3);
        verify(mAutoAddTracker).setTileAdded(spec);
    }

    @Test
    public void testDontAddControlsTileIfPresent() {
        String spec = DEVICE_CONTROLS;
        when(mAutoAddTracker.isAdded(eq(spec))).thenReturn(false);
        when(mQsHost.getTiles()).thenReturn(new ArrayList<>());

        mAutoTileManager.init();
        ArgumentCaptor<DeviceControlsController.Callback> captor =
                ArgumentCaptor.forClass(DeviceControlsController.Callback.class);

        verify(mDeviceControlsController).setCallback(captor.capture());

        captor.getValue().removeControlsAutoTracker();
        verify(mQsHost, never()).addTile(spec, 3);
        verify(mAutoAddTracker, never()).setTileAdded(spec);
        verify(mAutoAddTracker).setTileRemoved(spec);
    }

    @Test
    public void testRemoveControlsTileFromTrackerWhenRequested() {
        String spec = "controls";
        when(mAutoAddTracker.isAdded(eq(spec))).thenReturn(true);
        QSTile mockTile = mock(QSTile.class);
        when(mockTile.getTileSpec()).thenReturn(spec);
        when(mQsHost.getTiles()).thenReturn(List.of(mockTile));

        mAutoTileManager.init();
        ArgumentCaptor<DeviceControlsController.Callback> captor =
                ArgumentCaptor.forClass(DeviceControlsController.Callback.class);

        verify(mDeviceControlsController).setCallback(captor.capture());

        captor.getValue().onControlsUpdate(3);
        verify(mQsHost, never()).addTile(spec, 3);
        verify(mAutoAddTracker, never()).setTileAdded(spec);
    }


    @Test
    public void testEmptyArray_doesNotCrash() {
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_quickSettingsAutoAdd, new String[0]);
        createAutoTileManager(mContext).destroy();
    }

    @Test
    public void testMissingConfig_doesNotCrash() {
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_quickSettingsAutoAdd, null);
        createAutoTileManager(mContext).destroy();
    }

    // Will only notify if it's listening
    private void changeValue(String key, int value) {
        mSecureSettings.putIntForUser(key, value, USER);
        TestableLooper.get(this).processAllMessages();
    }
}
