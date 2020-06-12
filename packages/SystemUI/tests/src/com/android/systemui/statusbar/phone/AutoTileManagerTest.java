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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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

    @Mock private QSTileHost mQsTileHost;
    @Mock private AutoAddTracker mAutoAddTracker;
    @Mock private CastController mCastController;

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

        mAutoTileManager = createAutoTileManager();
    }

    private AutoTileManager createAutoTileManager() {
        return new AutoTileManager(mContext, mAutoAddTracker, mQsTileHost,
                Handler.createAsync(TestableLooper.get(this).getLooper()),
                mock(HotspotController.class),
                mock(DataSaverController.class),
                mock(ManagedProfileController.class),
                mock(NightDisplayListener.class),
                mCastController);
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
    public void testSettingTileAddedComponent_onChanged() {
        changeValue(TEST_SETTING_COMPONENT, 1);
        waitForIdleSync();
        verify(mAutoAddTracker).setTileAdded(TEST_CUSTOM_SPEC);
        verify(mQsTileHost).addTile(ComponentName.unflattenFromString(TEST_COMPONENT));
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
        createAutoTileManager();
    }

    @Test
    public void testMissingConfig_doesNotCrash() {
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_quickSettingsAutoAdd, null);
        createAutoTileManager();
    }

    // Will only notify if it's listening
    private void changeValue(String key, int value) {
        SecureSetting s = mAutoTileManager.getSecureSettingForKey(key);
        Settings.Secure.putInt(mContext.getContentResolver(), key, value);
        if (s != null && s.isListening()) {
            s.onChange(false);
        }
    }
}
