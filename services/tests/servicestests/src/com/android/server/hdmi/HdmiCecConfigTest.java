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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings.Global;
import android.sysprop.HdmiProperties;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public final class HdmiCecConfigTest {
    private static final String TAG = "HdmiCecConfigTest";

    private Context mContext;

    @Mock private HdmiCecConfig.StorageAdapter mStorageAdapter;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void getAllCecSettings_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThat(hdmiCecConfig.getAllSettings()).isEmpty();
    }

    @Test
    public void getAllCecSettings_Empty() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getAllSettings()).isEmpty();
    }

    @Test
    public void getAllCecSettings_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"false\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getAllSettings())
                .containsExactly(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                                 HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP);
    }

    @Test
    public void getUserCecSettings_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThat(hdmiCecConfig.getUserSettings()).isEmpty();
    }

    @Test
    public void getUserCecSettings_Empty() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getUserSettings()).isEmpty();
    }

    @Test
    public void getUserCecSettings_OnlyMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getUserSettings())
                .containsExactly(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                                 HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP);
    }

    @Test
    public void getUserCecSettings_WithOverride() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>",
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"false\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>");
        assertThat(hdmiCecConfig.getUserSettings())
                .containsExactly(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED);
    }

    @Test
    public void isStringValueType_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.isStringValueType("foo"));
    }

    @Test
    public void isStringValueType_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.isStringValueType("foo"));
    }

    @Test
    public void isStringValueType_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertTrue(hdmiCecConfig.isStringValueType(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP));
    }

    @Test
    public void isIntValueType_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.isIntValueType("foo"));
    }

    @Test
    public void isIntValueType_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.isIntValueType("foo"));
    }

    @Test
    public void isIntValueType_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertTrue(hdmiCecConfig.isIntValueType(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED));
    }

    @Test
    public void getAllowedStringValues_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedStringValues("foo"));
    }

    @Test
    public void getAllowedStringValues_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedStringValues("foo"));
    }

    @Test
    public void getAllowedStringValues_InvalidValueType() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedStringValues(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED));
    }

    @Test
    public void getAllowedStringValues_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getAllowedStringValues(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP))
                .containsExactly(HdmiControlManager.SEND_STANDBY_ON_SLEEP_TO_TV,
                                 HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST,
                                 HdmiControlManager.SEND_STANDBY_ON_SLEEP_NONE);
    }

    @Test
    public void getAllowedIntValues_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedIntValues("foo"));
    }

    @Test
    public void getAllowedIntValues_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedIntValues("foo"));
    }

    @Test
    public void getAllowedIntValues_InvalidValueType() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedIntValues(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP));
    }

    @Test
    public void getAllowedIntValues_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getAllowedIntValues(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .containsExactly(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED,
                                 HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
    }

    @Test
    public void getAllowedIntValues_HexValues() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0x00\" />"
                + "      <value int-value=\"0x01\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"0x01\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getAllowedIntValues(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .containsExactly(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED,
                                 HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
    }

    @Test
    public void getDefaultStringValue_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultStringValue("foo"));
    }

    @Test
    public void getDefaultStringValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultStringValue("foo"));
    }

    @Test
    public void getDefaultStringValue_InvalidValueType() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED));
    }

    @Test
    public void getDefaultStringValue_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getDefaultStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP))
                .isEqualTo(HdmiControlManager.SEND_STANDBY_ON_SLEEP_TO_TV);
    }

    @Test
    public void getDefaultIntValue_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultIntValue("foo"));
    }

    @Test
    public void getDefaultIntValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultIntValue("foo"));
    }

    @Test
    public void getDefaultIntValue_InvalidValueType() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP));
    }

    @Test
    public void getDefaultIntValue_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getDefaultIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .isEqualTo(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
    }

    @Test
    public void getDefaultIntValue_HexValue() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0x00\" />"
                + "      <value int-value=\"0x01\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"0x01\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getDefaultIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .isEqualTo(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
    }

    @Test
    public void getStringValue_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getStringValue("foo"));
    }

    @Test
    public void getStringValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getStringValue("foo"));
    }

    @Test
    public void getStringValue_InvalidType() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED));
    }

    @Test
    public void getStringValue_GlobalSetting_BasicSanity() {
        when(mStorageAdapter.retrieveGlobalSetting(mContext,
                  Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                  HdmiControlManager.SEND_STANDBY_ON_SLEEP_TO_TV))
            .thenReturn(HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP))
                .isEqualTo(HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
    }

    @Test
    public void getStringValue_SystemProperty_BasicSanity() {
        when(mStorageAdapter.retrieveSystemProperty(
                  HdmiCecConfig.SYSPROP_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                  HdmiProperties.power_state_change_on_active_source_lost_values
                      .NONE.name().toLowerCase()))
                .thenReturn(HdmiProperties.power_state_change_on_active_source_lost_values
                       .STANDBY_NOW.name().toLowerCase());
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"power_state_change_on_active_source_lost\""
                + "           value-type=\"string\""
                + "           user-configurable=\"false\">"
                + "    <allowed-values>"
                + "      <value string-value=\"none\" />"
                + "      <value string-value=\"standby_now\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"none\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST))
                .isEqualTo(HdmiProperties.power_state_change_on_active_source_lost_values
                        .STANDBY_NOW.name().toLowerCase());
    }

    @Test
    public void getIntValue_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getIntValue("foo"));
    }

    @Test
    public void getIntValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getIntValue("foo"));
    }

    @Test
    public void getIntValue_InvalidType() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP));
    }

    @Test
    public void getIntValue_GlobalSetting_BasicSanity() {
        when(mStorageAdapter.retrieveGlobalSetting(mContext,
                  Global.HDMI_CONTROL_ENABLED,
                  Integer.toString(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED)))
            .thenReturn(Integer.toString(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED));
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .isEqualTo(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
    }

    @Test
    public void getIntValue_GlobalSetting_HexValue() {
        when(mStorageAdapter.retrieveGlobalSetting(mContext,
                  Global.HDMI_CONTROL_ENABLED,
                  Integer.toHexString(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED)))
            .thenReturn(Integer.toString(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED));
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0x0\" />"
                + "      <value int-value=\"0x1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"0x1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED))
                .isEqualTo(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
    }

    @Test
    public void getIntValue_SystemProperty_BasicSanity() {
        when(mStorageAdapter.retrieveSystemProperty(
                  HdmiCecConfig.SYSPROP_SYSTEM_AUDIO_MODE_MUTING,
                  Integer.toString(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_ENABLED)))
                .thenReturn(Integer.toString(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED));
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"system_audio_mode_muting\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING))
                .isEqualTo(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED);
    }

    @Test
    public void setStringValue_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setStringValue("foo", "bar"));
    }

    @Test
    public void setStringValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setStringValue("foo", "bar"));
    }

    @Test
    public void setStringValue_NotConfigurable() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"false\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setStringValue(
                        HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP,
                        HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST));
    }

    @Test
    public void setStringValue_InvalidValue() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setStringValue(
                        HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP,
                        "bar"));
    }

    @Test
    public void setStringValue_GlobalSetting_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        hdmiCecConfig.setStringValue(HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP,
                               HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
        verify(mStorageAdapter).storeGlobalSetting(mContext,
                  Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                  HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
    }

    @Test
    public void setStringValue_SystemProperty_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"power_state_change_on_active_source_lost\""
                + "           value-type=\"string\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"none\" />"
                + "      <value string-value=\"standby_now\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"none\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        hdmiCecConfig.setStringValue(
                  HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                  HdmiProperties.power_state_change_on_active_source_lost_values
                      .STANDBY_NOW.name().toLowerCase());
        verify(mStorageAdapter).storeSystemProperty(
                  HdmiCecConfig.SYSPROP_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                  HdmiProperties.power_state_change_on_active_source_lost_values
                      .STANDBY_NOW.name().toLowerCase());
    }

    @Test
    public void setIntValue_NoMasterXml() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setIntValue("foo", 0));
    }

    @Test
    public void setIntValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setIntValue("foo", 0));
    }

    @Test
    public void setIntValue_NotConfigurable() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"false\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setIntValue(
                        HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                        HdmiControlManager.HDMI_CEC_CONTROL_DISABLED));
    }

    @Test
    public void setIntValue_InvalidValue() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setIntValue(
                        HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                        123));
    }

    @Test
    public void setIntValue_GlobalSetting_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        hdmiCecConfig.setIntValue(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                                  HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        verify(mStorageAdapter).storeGlobalSetting(mContext,
                  Global.HDMI_CONTROL_ENABLED,
                  Integer.toString(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED));
    }

    @Test
    public void setIntValue_GlobalSetting_HexValue() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0x0\" />"
                + "      <value int-value=\"0x1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"0x1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        hdmiCecConfig.setIntValue(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                                  HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        verify(mStorageAdapter).storeGlobalSetting(mContext,
                  Global.HDMI_CONTROL_ENABLED,
                  Integer.toString(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED));
    }

    @Test
    public void setIntValue_SystemProperty_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = HdmiCecConfig.createFromStrings(
                mContext, mStorageAdapter,
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"system_audio_mode_muting\""
                + "           value-type=\"int\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value int-value=\"0\" />"
                + "      <value int-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value int-value=\"1\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        hdmiCecConfig.setIntValue(
                  HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                  HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED);
        verify(mStorageAdapter).storeSystemProperty(
                  HdmiCecConfig.SYSPROP_SYSTEM_AUDIO_MODE_MUTING,
                  Integer.toString(HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED));
    }
}
