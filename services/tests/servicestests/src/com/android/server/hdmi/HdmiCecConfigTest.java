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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings.Global;
import android.sysprop.HdmiProperties;
import android.util.Slog;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.cec.config.CecSettings;
import com.android.server.hdmi.cec.config.XmlParser;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.xml.datatype.DatatypeConfigurationException;

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
    public void getAllCecSettings_Empty() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getAllSettings()).isEmpty();
    }

    @Test
    public void getAllCecSettings_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"0\" />"
                + "      <value string-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"1\" />"
                + "  </setting>"
                + "  <setting name=\"send_standby_on_sleep\""
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
    public void getUserCecSettings_Empty() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getUserSettings()).isEmpty();
    }

    @Test
    public void getUserCecSettings_OnlyMasterXml() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"0\" />"
                + "      <value string-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"1\" />"
                + "  </setting>"
                + "  <setting name=\"send_standby_on_sleep\""
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
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"hdmi_cec_enabled\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"0\" />"
                + "      <value string-value=\"1\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"1\" />"
                + "  </setting>"
                + "  <setting name=\"send_standby_on_sleep\""
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
    public void getAllowedValues_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getAllowedValues("foo"));
    }

    @Test
    public void getAllowedValues_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getAllowedValues(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP))
                .containsExactly(HdmiControlManager.SEND_STANDBY_ON_SLEEP_TO_TV,
                                 HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST,
                                 HdmiControlManager.SEND_STANDBY_ON_SLEEP_NONE);
    }

    @Test
    public void getDefaultValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getDefaultValue("foo"));
    }

    @Test
    public void getDefaultValue_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getDefaultValue(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP))
                .isEqualTo(HdmiControlManager.SEND_STANDBY_ON_SLEEP_TO_TV);
    }

    @Test
    public void getValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.getValue("foo"));
    }

    @Test
    public void getValue_GlobalSetting_BasicSanity() {
        when(mStorageAdapter.retrieveGlobalSetting(mContext,
                  Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                  HdmiControlManager.SEND_STANDBY_ON_SLEEP_TO_TV))
            .thenReturn(HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getValue(
                    HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP))
                .isEqualTo(HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
    }

    @Test
    public void getValue_SystemProperty_BasicSanity() {
        when(mStorageAdapter.retrieveSystemProperty(
                  HdmiCecConfig.SYSPROP_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                  HdmiProperties.power_state_change_on_active_source_lost_values
                      .NONE.name().toLowerCase()))
                .thenReturn(HdmiProperties.power_state_change_on_active_source_lost_values
                       .STANDBY_NOW.name().toLowerCase());
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"power_state_change_on_active_source_lost\""
                + "           user-configurable=\"false\">"
                + "    <allowed-values>"
                + "      <value string-value=\"none\" />"
                + "      <value string-value=\"standby_now\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"none\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        assertThat(hdmiCecConfig.getValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST))
                .isEqualTo(HdmiProperties.power_state_change_on_active_source_lost_values
                        .STANDBY_NOW.name().toLowerCase());
    }

    @Test
    public void setValue_InvalidSetting() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "</cec-settings>", null);
        assertThrows(IllegalArgumentException.class,
                () -> hdmiCecConfig.setValue("foo", "bar"));
    }

    @Test
    public void setValue_NotConfigurable() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
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
                () -> hdmiCecConfig.setValue(
                        HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP,
                        HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST));
    }

    @Test
    public void setValue_InvalidValue() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
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
                () -> hdmiCecConfig.setValue(
                        HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP,
                        "bar"));
    }

    @Test
    public void setValue_GlobalSetting_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"send_standby_on_sleep\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"to_tv\" />"
                + "      <value string-value=\"broadcast\" />"
                + "      <value string-value=\"none\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"to_tv\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        hdmiCecConfig.setValue(HdmiControlManager.CEC_SETTING_NAME_SEND_STANDBY_ON_SLEEP,
                               HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
        verify(mStorageAdapter).storeGlobalSetting(mContext,
                  Global.HDMI_CONTROL_SEND_STANDBY_ON_SLEEP,
                  HdmiControlManager.SEND_STANDBY_ON_SLEEP_BROADCAST);
    }

    @Test
    public void setValue_SystemProperty_BasicSanity() {
        HdmiCecConfig hdmiCecConfig = createHdmiCecConfig(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<cec-settings>"
                + "  <setting name=\"power_state_change_on_active_source_lost\""
                + "           user-configurable=\"true\">"
                + "    <allowed-values>"
                + "      <value string-value=\"none\" />"
                + "      <value string-value=\"standby_now\" />"
                + "    </allowed-values>"
                + "    <default-value string-value=\"none\" />"
                + "  </setting>"
                + "</cec-settings>", null);
        hdmiCecConfig.setValue(
                  HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                  HdmiProperties.power_state_change_on_active_source_lost_values
                      .STANDBY_NOW.name().toLowerCase());
        verify(mStorageAdapter).storeSystemProperty(
                  HdmiCecConfig.SYSPROP_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                  HdmiProperties.power_state_change_on_active_source_lost_values
                      .STANDBY_NOW.name().toLowerCase());
    }

    private HdmiCecConfig createHdmiCecConfig(String productConfigXml, String vendorOverrideXml) {
        CecSettings productConfig = null;
        CecSettings vendorOverride = null;
        try {
            productConfig = XmlParser.read(new ByteArrayInputStream(productConfigXml.getBytes()));
            if (vendorOverrideXml != null) {
                vendorOverride = XmlParser.read(
                        new ByteArrayInputStream(vendorOverrideXml.getBytes()));
            }
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing CEC config strings", e);
        }
        return new HdmiCecConfig(mContext, mStorageAdapter, productConfig, vendorOverride);
    }
}
