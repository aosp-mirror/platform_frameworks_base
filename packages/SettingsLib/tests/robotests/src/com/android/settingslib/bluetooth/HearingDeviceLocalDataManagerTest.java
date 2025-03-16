/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSettings;

import java.util.HashMap;
import java.util.Map;

/** Tests for {@link HearingDeviceLocalDataManager}. */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {HearingDeviceLocalDataManagerTest.ShadowGlobal.class})
public class HearingDeviceLocalDataManagerTest {

    private static final String TEST_ADDRESS = "XX:XX:XX:XX:11:22";
    private static final int TEST_AMBIENT = 10;
    private static final int TEST_GROUP_AMBIENT = 20;
    private static final boolean TEST_AMBIENT_CONTROL_EXPANDED = true;
    private static final int TEST_UPDATED_AMBIENT = 30;
    private static final int TEST_UPDATED_GROUP_AMBIENT = 40;
    private static final boolean TEST_UPDATED_AMBIENT_CONTROL_EXPANDED = false;

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private BluetoothDevice mDevice;
    @Mock
    private HearingDeviceLocalDataManager.OnDeviceLocalDataChangeListener mListener;

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private HearingDeviceLocalDataManager mLocalDataManager;

    @Before
    public void setUp() {
        prepareTestDataInSettings();
        mLocalDataManager = new HearingDeviceLocalDataManager(mContext);
        mLocalDataManager.start();
        mLocalDataManager.setOnDeviceLocalDataChangeListener(mListener,
                mContext.getMainExecutor());

        when(mDevice.getAnonymizedAddress()).thenReturn(TEST_ADDRESS);
    }

    @Test
    public void stop_verifyDataIsSaved() {
        mLocalDataManager.updateAmbient(mDevice, TEST_UPDATED_AMBIENT);
        mLocalDataManager.stop();

        String settings = Settings.Global.getStringForUser(mContext.getContentResolver(),
                Settings.Global.HEARING_DEVICE_LOCAL_AMBIENT_VOLUME, UserHandle.USER_SYSTEM);
        String expectedSettings = generateSettingsString(TEST_ADDRESS, TEST_UPDATED_AMBIENT,
                TEST_GROUP_AMBIENT, TEST_AMBIENT_CONTROL_EXPANDED);
        assertThat(settings).isEqualTo(expectedSettings);
    }

    @Test
    public void get_correctDataFromSettings() {
        HearingDeviceLocalDataManager.Data data = mLocalDataManager.get(mDevice);

        assertThat(data.ambient()).isEqualTo(TEST_AMBIENT);
        assertThat(data.groupAmbient()).isEqualTo(TEST_GROUP_AMBIENT);
        assertThat(data.ambientControlExpanded()).isEqualTo(TEST_AMBIENT_CONTROL_EXPANDED);
    }

    @Test
    public void updateAmbient_correctValue_listenerCalled() {
        HearingDeviceLocalDataManager.Data oldData = mLocalDataManager.get(mDevice);
        assertThat(oldData.ambient()).isEqualTo(TEST_AMBIENT);

        mLocalDataManager.updateAmbient(mDevice, TEST_UPDATED_AMBIENT);

        HearingDeviceLocalDataManager.Data newData = mLocalDataManager.get(mDevice);
        assertThat(newData.ambient()).isEqualTo(TEST_UPDATED_AMBIENT);
        verify(mListener).onDeviceLocalDataChange(TEST_ADDRESS, newData);
    }

    @Test
    public void updateAmbient_sameValue_listenerNotCalled() {
        HearingDeviceLocalDataManager.Data oldData = mLocalDataManager.get(mDevice);
        assertThat(oldData.ambient()).isEqualTo(TEST_AMBIENT);

        mLocalDataManager.updateAmbient(mDevice, TEST_AMBIENT);

        HearingDeviceLocalDataManager.Data newData = mLocalDataManager.get(mDevice);
        assertThat(newData.ambient()).isEqualTo(TEST_AMBIENT);
        verify(mListener, never()).onDeviceLocalDataChange(any(), any());
    }

    @Test
    public void updateGroupAmbient_correctValue_listenerCalled() {
        HearingDeviceLocalDataManager.Data oldData = mLocalDataManager.get(mDevice);
        assertThat(oldData.groupAmbient()).isEqualTo(TEST_GROUP_AMBIENT);

        mLocalDataManager.updateGroupAmbient(mDevice, TEST_UPDATED_GROUP_AMBIENT);

        HearingDeviceLocalDataManager.Data newData = mLocalDataManager.get(mDevice);
        assertThat(newData.groupAmbient()).isEqualTo(TEST_UPDATED_GROUP_AMBIENT);
        verify(mListener).onDeviceLocalDataChange(TEST_ADDRESS, newData);
    }

    @Test
    public void updateGroupAmbient_sameValue_listenerNotCalled() {
        HearingDeviceLocalDataManager.Data oldData = mLocalDataManager.get(mDevice);
        assertThat(oldData.groupAmbient()).isEqualTo(TEST_GROUP_AMBIENT);

        mLocalDataManager.updateGroupAmbient(mDevice, TEST_GROUP_AMBIENT);

        HearingDeviceLocalDataManager.Data newData = mLocalDataManager.get(mDevice);
        assertThat(newData.groupAmbient()).isEqualTo(TEST_GROUP_AMBIENT);
        verify(mListener, never()).onDeviceLocalDataChange(any(), any());
    }

    @Test
    public void updateAmbientControlExpanded_correctValue_listenerCalled() {
        HearingDeviceLocalDataManager.Data oldData = mLocalDataManager.get(mDevice);
        assertThat(oldData.ambientControlExpanded()).isEqualTo(TEST_AMBIENT_CONTROL_EXPANDED);

        mLocalDataManager.updateAmbientControlExpanded(mDevice,
                TEST_UPDATED_AMBIENT_CONTROL_EXPANDED);

        HearingDeviceLocalDataManager.Data newData = mLocalDataManager.get(mDevice);
        assertThat(newData.ambientControlExpanded()).isEqualTo(
                TEST_UPDATED_AMBIENT_CONTROL_EXPANDED);
        verify(mListener).onDeviceLocalDataChange(TEST_ADDRESS, newData);
    }

    @Test
    public void updateAmbientControlExpanded_sameValue_listenerNotCalled() {
        HearingDeviceLocalDataManager.Data oldData = mLocalDataManager.get(mDevice);
        assertThat(oldData.ambientControlExpanded()).isEqualTo(TEST_AMBIENT_CONTROL_EXPANDED);

        mLocalDataManager.updateAmbientControlExpanded(mDevice, TEST_AMBIENT_CONTROL_EXPANDED);

        HearingDeviceLocalDataManager.Data newData = mLocalDataManager.get(mDevice);
        assertThat(newData.ambientControlExpanded()).isEqualTo(TEST_AMBIENT_CONTROL_EXPANDED);
        verify(mListener, never()).onDeviceLocalDataChange(any(), any());
    }

    @Test
    public void getLocalDataFromSettings_dataChanged_correctValue_listenerCalled() {
        HearingDeviceLocalDataManager.Data oldData = mLocalDataManager.get(mDevice);
        assertThat(oldData.ambient()).isEqualTo(TEST_AMBIENT);
        assertThat(oldData.groupAmbient()).isEqualTo(TEST_GROUP_AMBIENT);
        assertThat(oldData.ambientControlExpanded()).isEqualTo(TEST_AMBIENT_CONTROL_EXPANDED);

        prepareUpdatedDataInSettings();
        mLocalDataManager.getLocalDataFromSettings();

        HearingDeviceLocalDataManager.Data newData = mLocalDataManager.get(mDevice);
        assertThat(newData.ambient()).isEqualTo(TEST_UPDATED_AMBIENT);
        assertThat(newData.groupAmbient()).isEqualTo(TEST_UPDATED_GROUP_AMBIENT);
        assertThat(newData.ambientControlExpanded()).isEqualTo(
                TEST_UPDATED_AMBIENT_CONTROL_EXPANDED);
        verify(mListener).onDeviceLocalDataChange(TEST_ADDRESS, newData);
    }

    private void prepareTestDataInSettings() {
        String data = generateSettingsString(TEST_ADDRESS, TEST_AMBIENT, TEST_GROUP_AMBIENT,
                TEST_AMBIENT_CONTROL_EXPANDED);
        Settings.Global.putStringForUser(mContext.getContentResolver(),
                Settings.Global.HEARING_DEVICE_LOCAL_AMBIENT_VOLUME, data,
                UserHandle.USER_SYSTEM);
    }

    private void prepareUpdatedDataInSettings() {
        String data = generateSettingsString(TEST_ADDRESS, TEST_UPDATED_AMBIENT,
                TEST_UPDATED_GROUP_AMBIENT, TEST_UPDATED_AMBIENT_CONTROL_EXPANDED);
        Settings.Global.putStringForUser(mContext.getContentResolver(),
                Settings.Global.HEARING_DEVICE_LOCAL_AMBIENT_VOLUME, data,
                UserHandle.USER_SYSTEM);
    }

    private String generateSettingsString(String addr, int ambient, int groupAmbient,
            boolean ambientControlExpanded) {
        return "addr=" + addr + ",ambient=" + ambient + ",group_ambient=" + groupAmbient
                + ",control_expanded=" + ambientControlExpanded + ";";
    }

    @Implements(value = Settings.Global.class)
    public static class ShadowGlobal extends ShadowSettings.ShadowGlobal {
        private static final Map<ContentResolver, Map<String, String>> sDataMap = new HashMap<>();

        @Implementation
        protected static boolean putStringForUser(
                ContentResolver cr, String name, String value, int userHandle) {
            get(cr).put(name, value);
            return true;
        }

        @Implementation
        protected static String getStringForUser(ContentResolver cr, String name, int userHandle) {
            return get(cr).get(name);
        }

        private static Map<String, String> get(ContentResolver cr) {
            return sDataMap.computeIfAbsent(cr, k -> new HashMap<>());
        }
    }
}
