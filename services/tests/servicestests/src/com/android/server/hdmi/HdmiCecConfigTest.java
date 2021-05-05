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
package com.android.server.hdmi;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.hdmi.HdmiControlManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings.Global;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public final class HdmiCecConfigTest {
    private static final String TAG = "HdmiCecConfigTest";

    private static final int TIMEOUT_CONTENT_CHANGE_SEC = 3;

    private final TestLooper mTestLooper = new TestLooper();

    private Context mContext;

    @Mock private HdmiCecConfig.StorageAdapter mStorageAdapter;
    @Mock private HdmiCecConfig.SettingChangeListener mSettingChangeListener;

    private void setBooleanResource(int resId, boolean value) {
        Resources resources = mContext.getResources();
        doReturn(value).when(resources).getBoolean(resId);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = FakeHdmiCecConfig.buildContext(InstrumentationRegistry.getTargetContext());
    }

    @Test
    public void getAllCecSettings_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getAllSettings())
                .containsExactly(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                    HdmiControlManager.CEC_SETTING_NAME_ROUTING_CONTROL,
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                    HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                    HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL,
                    HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                    HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                    HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                    HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_TV,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
                    HdmiControlManager
                        .CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU);
    }

    @Test
    public void getUserCecSettings_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getUserSettings())
                .containsExactly(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                    HdmiControlManager.CEC_SETTING_NAME_ROUTING_CONTROL,
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                    HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                    HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL,
                    HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                    HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                    HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                    HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_TV,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
                    HdmiControlManager
                        .CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU);
    }

    @Test
    public void getUserCecSettings_WithOverride() {
        setBooleanResource(R.bool.config_cecHdmiCecEnabled_userConfigurable, false);
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getUserSettings())
                .containsExactly(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                    HdmiControlManager.CEC_SETTING_NAME_ROUTING_CONTROL,
                    HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                    HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL,
                    HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                    HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                    HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                    HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_TV,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU,
                    HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
                    HdmiControlManager
                        .CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU);
    }

    @Test
    public void isStringValueType_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.isStringValueType("foo"));
    }

    @Test
    public void isStringValueType_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertTrue(hdmiCecConfig.isStringValueType(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE));
    }

    @Test
    public void isIntValueType_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.isIntValueType("foo"));
    }

    @Test
    public void isIntValueType_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertTrue(hdmiCecConfig.isIntValueType(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED));
    }

    @Test
    public void getAllowedStringValues_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedStringValues("foo"));
    }

    @Test
    public void getAllowedStringValues_InvalidValueType() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedStringValues(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED));
    }

    @Test
    public void getAllowedStringValues_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getAllowedStringValues(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE))
                .containsExactly(HdmiControlManager.POWER_CONTROL_MODE_TV,
                                 HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM,
                                 HdmiControlManager.POWER_CONTROL_MODE_BROADCAST,
                                 HdmiControlManager.POWER_CONTROL_MODE_NONE);
    }

    @Test
    public void getAllowedStringValues_WithOverride() {
        setBooleanResource(R.bool.config_cecPowerControlModeNone_allowed, false);
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getAllowedStringValues(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE))
                .containsExactly(HdmiControlManager.POWER_CONTROL_MODE_TV,
                                 HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM,
                                 HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
    }

    @Test
    public void getAllowedIntValues_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedIntValues("foo"));
    }

    @Test
    public void getAllowedIntValues_InvalidValueType() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedIntValues(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE));
    }

    @Test
    public void getAllowedIntValues_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getAllowedIntValues(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .containsExactly(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED,
                                 HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
    }

    @Test
    public void getAllowedIntValues_WithOverride() {
        setBooleanResource(R.bool.config_cecHdmiCecControlDisabled_allowed, false);
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getAllowedIntValues(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .containsExactly(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
    }

    @Test
    public void getDefaultStringValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultStringValue("foo"));
    }

    @Test
    public void getDefaultStringValue_InvalidValueType() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED));
    }

    @Test
    public void getDefaultStringValue_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getDefaultStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE))
                .isEqualTo(HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM);
    }

    @Test
    public void getDefaultStringValue_WithOverride() {
        setBooleanResource(R.bool.config_cecPowerControlModeTvAndAudioSystem_default, false);
        setBooleanResource(R.bool.config_cecPowerControlModeBroadcast_default, true);
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getDefaultStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE))
                .isEqualTo(HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
    }

    @Test
    public void getDefaultStringValue_MultipleDefaults() {
        setBooleanResource(R.bool.config_cecPowerControlModeBroadcast_default, true);
        assertThrows(RuntimeException.class,
                () -> new HdmiCecConfig(mContext, mStorageAdapter));
    }

    @Test
    public void getDefaultStringValue_NoDefault() {
        setBooleanResource(R.bool.config_cecPowerControlModeTvAndAudioSystem_default, false);
        assertThrows(RuntimeException.class,
                () -> new HdmiCecConfig(mContext, mStorageAdapter));
    }

    @Test
    public void getDefaultIntValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultIntValue("foo"));
    }

    @Test
    public void getDefaultIntValue_InvalidValueType() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE));
    }

    @Test
    public void getDefaultIntValue_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getDefaultIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .isEqualTo(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
    }

    @Test
    public void getDefaultIntValue_WithOverride() {
        setBooleanResource(R.bool.config_cecHdmiCecControlEnabled_default, false);
        setBooleanResource(R.bool.config_cecHdmiCecControlDisabled_default, true);
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getDefaultIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .isEqualTo(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
    }

    @Test
    public void getStringValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getStringValue("foo"));
    }

    @Test
    public void getStringValue_InvalidType() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED));
    }

    @Test
    public void getStringValue_GlobalSetting_BasicSanity() {
        when(mStorageAdapter.retrieveGlobalSetting(
                  Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                  HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM))
            .thenReturn(HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE))
                .isEqualTo(HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
    }

    @Test
    public void getStringValue_SharedPref_BasicSanity() {
        when(mStorageAdapter.retrieveSharedPref(
                  HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                  HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE))
                .thenReturn(
                        HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST))
                .isEqualTo(HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
    }

    @Test
    public void getIntValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getIntValue("foo"));
    }

    @Test
    public void getIntValue_InvalidType() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE));
    }

    @Test
    public void getIntValue_GlobalSetting_BasicSanity() {
        when(mStorageAdapter.retrieveGlobalSetting(
                  Global.HDMI_CONTROL_ENABLED,
                  Integer.toString(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED)))
            .thenReturn(Integer.toString(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED));
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .isEqualTo(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
    }

    @Test
    public void getIntValue_SharedPref_BasicSanity() {
        when(mStorageAdapter.retrieveSharedPref(
                  HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                  Integer.toString(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_ENABLED)))
                .thenReturn(Integer.toString(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED));
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThat(hdmiCecConfig.getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING))
                .isEqualTo(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED);
    }

    @Test
    public void setStringValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setStringValue("foo", "bar"));
    }

    @Test
    public void setStringValue_NotConfigurable() {
        setBooleanResource(R.bool.config_cecPowerControlMode_userConfigurable, false);
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setStringValue(
                        HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                        HdmiControlManager.POWER_CONTROL_MODE_BROADCAST));
    }

    @Test
    public void setStringValue_InvalidValue() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setStringValue(
                        HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                        "bar"));
    }

    @Test
    public void setStringValue_GlobalSetting_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        hdmiCecConfig.setStringValue(HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                               HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        verify(mStorageAdapter).storeGlobalSetting(
                  Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                  HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
    }

    @Test
    public void setStringValue_SharedPref_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        hdmiCecConfig.setStringValue(
                  HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                  HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        verify(mStorageAdapter).storeSharedPref(
                  HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                  HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
    }

    @Test
    public void setIntValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setIntValue("foo", 0));
    }

    @Test
    public void setIntValue_NotConfigurable() {
        setBooleanResource(R.bool.config_cecHdmiCecEnabled_userConfigurable, false);
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setIntValue(
                        HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                        HdmiControlManager.HDMI_CEC_CONTROL_DISABLED));
    }

    @Test
    public void setIntValue_InvalidValue() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setIntValue(
                        HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                        123));
    }

    @Test
    public void setIntValue_GlobalSetting_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        hdmiCecConfig.setIntValue(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                                  HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        verify(mStorageAdapter).storeGlobalSetting(
                  Global.HDMI_CONTROL_ENABLED,
                  Integer.toString(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED));
    }

    @Test
    public void setIntValue_SharedPref_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        hdmiCecConfig.setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED);
        verify(mStorageAdapter).storeSharedPref(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                Integer.toString(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED));
    }

    @Test
    public void registerChangeListener_SharedPref_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        hdmiCecConfig.registerChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                mSettingChangeListener);
        hdmiCecConfig.setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED);
        verify(mSettingChangeListener).onChange(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING);
    }

    @Test
    public void removeChangeListener_SharedPref_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
        hdmiCecConfig.registerChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                mSettingChangeListener);
        hdmiCecConfig.removeChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                mSettingChangeListener);
        hdmiCecConfig.setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED);
        verify(mSettingChangeListener, never()).onChange(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING);
    }

    /**
     * Externally modified Global Settings still need to be supported. This test verifies that
     * setting change notification is being forwarded to listeners registered via HdmiCecConfig.
     */
    @Test
    @Ignore("b/175381065")
    public void globalSettingObserver_BasicSanity() throws Exception {
        CountDownLatch notifyLatch = new CountDownLatch(1);
        // Get current value of the setting in the system.
        String originalValue = Global.getString(mContext.getContentResolver(),
                Global.HDMI_CONTROL_ENABLED);
        try {
            HdmiCecConfig hdmiCecConfig = new HdmiCecConfig(mContext, mStorageAdapter);
            hdmiCecConfig.registerGlobalSettingsObserver(mTestLooper.getLooper());
            HdmiCecConfig.SettingChangeListener latchUpdateListener =
                    new HdmiCecConfig.SettingChangeListener() {
                        @Override
                        public void onChange(
                                @NonNull @HdmiControlManager.CecSettingName String setting) {
                            notifyLatch.countDown();
                            assertThat(setting).isEqualTo(
                                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED);
                        }
                    };
            hdmiCecConfig.registerChangeListener(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                    latchUpdateListener);

            // Flip the value of the setting.
            String valueToSet = ((originalValue == null || originalValue.equals("1")) ? "0" : "1");
            Global.putString(mContext.getContentResolver(), Global.HDMI_CONTROL_ENABLED,
                    valueToSet);
            assertThat(Global.getString(mContext.getContentResolver(),
                    Global.HDMI_CONTROL_ENABLED)).isEqualTo(valueToSet);
            mTestLooper.dispatchAll();

            if (!notifyLatch.await(TIMEOUT_CONTENT_CHANGE_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for the notify callback");
            }
            hdmiCecConfig.unregisterGlobalSettingsObserver();
        } finally {
            // Restore the previous value of the setting in the system.
            Global.putString(mContext.getContentResolver(), Global.HDMI_CONTROL_ENABLED,
                    originalValue);
        }
    }
}
