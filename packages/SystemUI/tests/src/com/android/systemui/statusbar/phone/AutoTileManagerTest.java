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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContentResolver;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.qs.AutoAddTracker;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.HotspotController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AutoTileManagerTest extends SysuiTestCase {

    private static final String TEST_SETTING = "setting";
    private static final String TEST_SPEC = "spec";
    private static final String TEST_SETTING_COMPONENT = "setting_component";
    private static final String TEST_COMPONENT = "test_pkg/test_cls";
    private static final String TEST_CUSTOM_SPEC = "custom(" + TEST_COMPONENT + ")";
    private static final String SEPARATOR = AutoTileManager.SETTING_SEPARATOR;

    private static final int USER = 0;

    @Mock private QSTileHost mQsTileHost;
    @Mock private AutoAddTracker mAutoAddTracker;
    @Mock private CastController mCastController;
    @Mock private HotspotController mHotspotController;
    @Mock private DataSaverController mDataSaverController;
    @Mock private ManagedProfileController mManagedProfileController;
    @Mock private NightDisplayListener mNightDisplayListener;
    @Mock(answer = Answers.RETURNS_SELF)
    private AutoAddTracker.Builder mAutoAddTrackerBuilder;
    @Mock private Context mUserContext;

    private AutoTileManager mAutoTileManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_quickSettingsAutoAdd,
                new String[] {
                        TEST_SETTING + SEPARATOR + TEST_SPEC,
                        TEST_SETTING_COMPONENT + SEPARATOR + TEST_CUSTOM_SPEC
                }
        );
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_nightDisplayAvailable, true);

        when(mAutoAddTrackerBuilder.build()).thenReturn(mAutoAddTracker);
        when(mQsTileHost.getUserContext()).thenReturn(mUserContext);
        when(mUserContext.getUser()).thenReturn(UserHandle.of(USER));

        mAutoTileManager = createAutoTileManager(new MyContextWrapper(mContext));
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
            CastController castController) {
        return new AutoTileManager(context, autoAddTrackerBuilder, mQsTileHost,
                Handler.createAsync(TestableLooper.get(this).getLooper()),
                hotspotController,
                dataSaverController,
                managedProfileController,
                nightDisplayListener,
                castController);
    }

    private AutoTileManager createAutoTileManager(Context context) {
        return createAutoTileManager(context, mAutoAddTrackerBuilder, mHotspotController,
                mDataSaverController, mManagedProfileController, mNightDisplayListener,
                mCastController);
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

        AutoTileManager manager =
                createAutoTileManager(mock(Context.class), builder, hC, dSC, mPC, nDS, cC);

        verify(tracker, never()).initialize();
        verify(hC, never()).addCallback(any());
        verify(dSC, never()).addCallback(any());
        verify(mPC, never()).addCallback(any());
        verify(nDS, never()).setCallback(any());
        verify(cC, never()).addCallback(any());
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

        InOrder inOrderCast = inOrder(mCastController);
        inOrderCast.verify(mCastController).removeCallback(any());
        inOrderCast.verify(mCastController).addCallback(any());

        SecureSetting setting = mAutoTileManager.getSecureSettingForKey(TEST_SETTING);
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
        inOrderManagedProfile.verify(mManagedProfileController, never()).addCallback(any());

        if (ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            InOrder inOrderNightDisplay = inOrder(mNightDisplayListener);
            inOrderNightDisplay.verify(mNightDisplayListener).setCallback(isNull());
            inOrderNightDisplay.verify(mNightDisplayListener).setCallback(isNotNull());
        }

        InOrder inOrderCast = inOrder(mCastController);
        inOrderCast.verify(mCastController).removeCallback(any());
        inOrderCast.verify(mCastController, never()).addCallback(any());

        SecureSetting setting = mAutoTileManager.getSecureSettingForKey(TEST_SETTING);
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
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenDeactivated() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onActivated(false);
        verify(mQsTileHost, never()).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeTwilight() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                ColorDisplayManager.AUTO_MODE_TWILIGHT);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileAdded_whenNightModeCustom() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                ColorDisplayManager.AUTO_MODE_CUSTOM_TIME);
        verify(mQsTileHost).addTile("night");
    }

    @Test
    public void nightTileNotAdded_whenNightModeDisabled() {
        if (!ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            return;
        }
        mAutoTileManager.mNightDisplayCallback.onAutoModeChanged(
                ColorDisplayManager.AUTO_MODE_DISABLED);
        verify(mQsTileHost, never()).addTile("night");
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
        verify(mQsTileHost).addTile("cast");
    }

    @Test
    public void castTileNotAdded_whenDeviceIsNotCasting() {
        doReturn(buildFakeCastDevice(false)).when(mCastController).getCastDevices();
        mAutoTileManager.mCastCallback.onCastDevicesChanged();
        verify(mQsTileHost, never()).addTile("cast");
    }

    @Test
    public void testSettingTileAdded_onChanged() {
        changeValue(TEST_SETTING, 1);
        waitForIdleSync();
        verify(mAutoAddTracker).setTileAdded(TEST_SPEC);
        verify(mQsTileHost).addTile(TEST_SPEC);
    }

    @Test
    public void testSettingTileAddedComponentAtEnd_onChanged() {
        changeValue(TEST_SETTING_COMPONENT, 1);
        waitForIdleSync();
        verify(mAutoAddTracker).setTileAdded(TEST_CUSTOM_SPEC);
        verify(mQsTileHost).addTile(ComponentName.unflattenFromString(TEST_COMPONENT)
            , /* end */ true);
    }

    @Test
    public void testSettingTileAdded_onlyOnce() {
        changeValue(TEST_SETTING, 1);
        waitForIdleSync();
        TestableLooper.get(this).processAllMessages();
        changeValue(TEST_SETTING, 2);
        waitForIdleSync();
        verify(mAutoAddTracker).setTileAdded(TEST_SPEC);
        verify(mQsTileHost).addTile(TEST_SPEC);
    }

    @Test
    public void testSettingTileNotAdded_onChangedTo0() {
        changeValue(TEST_SETTING, 0);
        waitForIdleSync();
        verify(mAutoAddTracker, never()).setTileAdded(TEST_SPEC);
        verify(mQsTileHost, never()).addTile(TEST_SPEC);
    }

    @Test
    public void testSettingTileNotAdded_ifPreviouslyAdded() {
        when(mAutoAddTracker.isAdded(TEST_SPEC)).thenReturn(true);

        changeValue(TEST_SETTING, 1);
        waitForIdleSync();
        verify(mAutoAddTracker, never()).setTileAdded(TEST_SPEC);
        verify(mQsTileHost, never()).addTile(TEST_SPEC);
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
        SecureSetting s = mAutoTileManager.getSecureSettingForKey(key);
        Settings.Secure.putInt(mContext.getContentResolver(), key, value);
        if (s != null && s.isListening()) {
            s.onChange(false);
        }
    }

    class MyContextWrapper extends ContextWrapper {

        private TestableContentResolver mSpiedTCR;

        MyContextWrapper(TestableContext context) {
            super(context);
            mSpiedTCR = spy(context.getContentResolver());
            doNothing().when(mSpiedTCR).registerContentObserver(any(), anyBoolean(), any(),
                    anyInt());
            doNothing().when(mSpiedTCR).unregisterContentObserver(any());
        }

        @Override
        public ContentResolver getContentResolver() {
            return mSpiedTCR;
        }
    }
}
