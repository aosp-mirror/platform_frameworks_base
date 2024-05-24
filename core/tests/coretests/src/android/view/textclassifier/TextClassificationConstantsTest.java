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
 * limitations under the License.
 */

package android.view.textclassifier;

import static com.google.common.truth.Truth.assertThat;

import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;
import java.util.function.Predicate;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationConstantsTest {

    @Before
    public void setup() {
        TextClassificationConstants.resetMemoizedValues();
    }

    @Test
    public void booleanSettings() {
        assertSettings(
                TextClassificationConstants.LOCAL_TEXT_CLASSIFIER_ENABLED,
                "false",
                settings -> assertThat(settings.isLocalTextClassifierEnabled()).isFalse());
    }

    @Test
    public void intSettings() {
        assertSettings(
                TextClassificationConstants.GENERATE_LINKS_MAX_TEXT_LENGTH,
                "128",
                settings -> assertThat(settings.getGenerateLinksMaxTextLength()).isEqualTo(128));
    }

    @Test
    public void stringSettings() {
        assertSettings(
                TextClassificationConstants.TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE,
                "com.example.textclassifier",
                settings -> assertThat(
                        settings.getTextClassifierServicePackageOverride())
                        .isEqualTo("com.example.textclassifier"));
    }

    @Test
    public void longSettings() {
        assertSettings(
                TextClassificationConstants.SYSTEM_TEXT_CLASSIFIER_API_TIMEOUT_IN_SECOND,
                "1",
                settings -> assertThat(
                        settings.getSystemTextClassifierApiTimeoutInSecond())
                        .isEqualTo(1));
    }

    @Test
    public void runtimeMutableSettings() {
        assertOverride(
                TextClassificationConstants.SYSTEM_TEXT_CLASSIFIER_ENABLED,
                settings -> settings.isSystemTextClassifierEnabled());
    }

    private static void assertSettings(
            String key, String value, Consumer<TextClassificationConstants> settingsConsumer) {
        final String originalValue =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_TEXTCLASSIFIER, key);
        TextClassificationConstants settings = new TextClassificationConstants();
        try {
            setDeviceConfig(key, value);
            settingsConsumer.accept(settings);
        } finally {
            setDeviceConfig(key, originalValue);
        }
    }

    private static void assertOverride(
            String key, Predicate<TextClassificationConstants> settingsPredicate) {
        final String originalValue =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_TEXTCLASSIFIER, key);
        TextClassificationConstants settings = new TextClassificationConstants();
        try {
            setDeviceConfig(key, "true");
            assertThat(settingsPredicate.test(settings)).isTrue();
            setDeviceConfig(key, "false");
            assertThat(settingsPredicate.test(settings)).isFalse();
        } finally {
            setDeviceConfig(key, originalValue);
        }
    }

    private static void setDeviceConfig(String key, String value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER, key, value, /* makeDefault */ false);
    }
}
