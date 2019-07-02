/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.view.textclassifier;

import static com.google.common.truth.Truth.assertThat;

import android.provider.DeviceConfig;
import android.support.test.uiautomator.UiDevice;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.function.Supplier;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ConfigParserTest {
    private static final Supplier<String> SETTINGS =
            () -> "int=42,float=12.3,boolean=true,string=abc";
    private static final String CLEAR_DEVICE_CONFIG_KEY_CMD =
            "device_config delete " + DeviceConfig.NAMESPACE_TEXTCLASSIFIER;
    private static final String[] DEVICE_CONFIG_KEYS = new String[]{
            "boolean",
            "string",
            "int",
            "float"
    };

    private ConfigParser mConfigParser;

    @Before
    public void setup() throws IOException {
        mConfigParser = new ConfigParser(SETTINGS);
        clearDeviceConfig();
    }

    @After
    public void tearDown() throws IOException {
        clearDeviceConfig();
    }

    @Test
    public void getBoolean_deviceConfig() {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                "boolean",
                "false",
                false);
        boolean value = mConfigParser.getBoolean("boolean", true);
        assertThat(value).isFalse();
    }

    @Test
    public void getBoolean_settings() {
        boolean value = mConfigParser.getBoolean(
                "boolean",
                false);
        assertThat(value).isTrue();
    }

    @Test
    public void getInt_deviceConfig() {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                "int",
                "1",
                false);
        int value = mConfigParser.getInt("int", 0);
        assertThat(value).isEqualTo(1);
    }

    @Test
    public void getInt_settings() {
        int value = mConfigParser.getInt("int", 0);
        assertThat(value).isEqualTo(42);
    }

    @Test
    public void getFloat_deviceConfig() {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                "float",
                "3.14",
                false);
        float value = mConfigParser.getFloat("float", 0);
        assertThat(value).isWithin(0.0001f).of(3.14f);
    }

    @Test
    public void getFloat_settings() {
        float value = mConfigParser.getFloat("float", 0);
        assertThat(value).isWithin(0.0001f).of(12.3f);
    }

    @Test
    public void getString_deviceConfig() {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                "string",
                "hello",
                false);
        String value = mConfigParser.getString("string", "");
        assertThat(value).isEqualTo("hello");
    }

    @Test
    public void getString_settings() {
        String value = mConfigParser.getString("string", "");
        assertThat(value).isEqualTo("abc");
    }

    private static void clearDeviceConfig() throws IOException {
        UiDevice uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for (String key : DEVICE_CONFIG_KEYS) {
            uiDevice.executeShellCommand(CLEAR_DEVICE_CONFIG_KEY_CMD + " " + key);
        }
    }
}
