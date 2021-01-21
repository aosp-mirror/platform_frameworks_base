/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.util.Slog;

import com.android.server.hdmi.cec.config.CecSettings;
import com.android.server.hdmi.cec.config.XmlParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Fake class which loads default system configuration with user-configurable
 * settings (useful for testing).
 */
final class FakeHdmiCecConfig extends HdmiCecConfig {
    private static final String TAG = "FakeHdmiCecConfig";

    private static final String SYSTEM_CONFIG_XML =
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
                    + "  <setting name=\"power_state_change_on_active_source_lost\""
                    + "           value-type=\"string\""
                    + "           user-configurable=\"true\">"
                    + "    <allowed-values>"
                    + "      <value string-value=\"none\" />"
                    + "      <value string-value=\"standby_now\" />"
                    + "    </allowed-values>"
                    + "    <default-value string-value=\"none\" />"
                    + "  </setting>"
                    + "  <setting name=\"hdmi_cec_enabled\""
                    + "           value-type=\"int\""
                    + "           user-configurable=\"true\">"
                    + "    <allowed-values>"
                    + "      <value int-value=\"0\" />"
                    + "      <value int-value=\"1\" />"
                    + "    </allowed-values>"
                    + "    <default-value int-value=\"1\" />"
                    + "  </setting>"
                    + "  <setting name=\"hdmi_cec_version\""
                    + "           value-type=\"int\""
                    + "           user-configurable=\"true\">"
                    + "    <allowed-values>"
                    + "      <value int-value=\"0x05\" />"
                    + "      <value int-value=\"0x06\" />"
                    + "    </allowed-values>"
                    + "    <default-value int-value=\"0x05\" />"
                    + "  </setting>"
                    + "  <setting name=\"system_audio_mode_muting\""
                    + "           value-type=\"int\""
                    + "           user-configurable=\"true\">"
                    + "    <allowed-values>"
                    + "      <value int-value=\"0\" />"
                    + "      <value int-value=\"1\" />"
                    + "    </allowed-values>"
                    + "    <default-value int-value=\"1\" />"
                    + "  </setting>"
                    + "  <setting name=\"hdmi_cec_enabled\""
                    + "           value-type=\"int\""
                    + "           user-configurable=\"true\">"
                    + "    <allowed-values>"
                    + "      <value int-value=\"0\" />"
                    + "      <value int-value=\"1\" />"
                    + "    </allowed-values>"
                    + "    <default-value int-value=\"1\" />"
                    + "  </setting>"
                    + "  <setting name=\"volume_control_enabled\""
                    + "           value-type=\"int\""
                    + "           user-configurable=\"true\">"
                    + "    <allowed-values>"
                    + "      <value int-value=\"0\" />"
                    + "      <value int-value=\"1\" />"
                    + "    </allowed-values>"
                    + "    <default-value int-value=\"1\" />"
                    + "  </setting>"
                    + "  <setting name=\"tv_wake_on_one_touch_play\""
                    + "           value-type=\"int\""
                    + "           user-configurable=\"true\">"
                    + "    <allowed-values>"
                    + "      <value int-value=\"0\" />"
                    + "      <value int-value=\"1\" />"
                    + "    </allowed-values>"
                    + "    <default-value int-value=\"1\" />"
                    + "  </setting>"
                    + "</cec-settings>";

    FakeHdmiCecConfig(@NonNull Context context) {
        super(context, new StorageAdapter(context), parseFromString(SYSTEM_CONFIG_XML), null);
    }

    private static CecSettings parseFromString(@NonNull String configXml) {
        CecSettings config = null;
        try {
            config = XmlParser.read(
                    new ByteArrayInputStream(configXml.getBytes()));
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing CEC config strings", e);
        }
        return config;
    }
}
