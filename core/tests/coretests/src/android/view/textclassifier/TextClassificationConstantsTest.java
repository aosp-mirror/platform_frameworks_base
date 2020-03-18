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

import static com.google.common.truth.Truth.assertWithMessage;

import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationConstantsTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void testLoadFromDeviceConfig_booleanValue() throws Exception {
        // Saves config original value.
        final String originalValue = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                TextClassificationConstants.LOCAL_TEXT_CLASSIFIER_ENABLED);

        final TextClassificationConstants constants = new TextClassificationConstants();
        try {
            // Sets and checks different value.
            setDeviceConfig(TextClassificationConstants.LOCAL_TEXT_CLASSIFIER_ENABLED, "false");
            assertWithMessage(TextClassificationConstants.LOCAL_TEXT_CLASSIFIER_ENABLED)
                    .that(constants.isLocalTextClassifierEnabled()).isFalse();
        } finally {
            // Restores config original value.
            setDeviceConfig(TextClassificationConstants.LOCAL_TEXT_CLASSIFIER_ENABLED,
                    originalValue);
        }
    }

    @Test
    public void testLoadFromDeviceConfig_IntValue() throws Exception {
        // Saves config original value.
        final String originalValue = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                TextClassificationConstants.GENERATE_LINKS_MAX_TEXT_LENGTH);

        final TextClassificationConstants constants = new TextClassificationConstants();
        try {
            // Sets and checks different value.
            setDeviceConfig(TextClassificationConstants.GENERATE_LINKS_MAX_TEXT_LENGTH, "8");
            assertWithMessage(TextClassificationConstants.GENERATE_LINKS_MAX_TEXT_LENGTH)
                    .that(constants.getGenerateLinksMaxTextLength()).isEqualTo(8);
        } finally {
            // Restores config original value.
            setDeviceConfig(TextClassificationConstants.GENERATE_LINKS_MAX_TEXT_LENGTH,
                    originalValue);
        }
    }

    @Test
    public void testLoadFromDeviceConfig_StringValue() throws Exception {
        // Saves config original value.
        final String originalValue = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                TextClassificationConstants.TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE);

        final TextClassificationConstants constants = new TextClassificationConstants();
        try {
            // Sets and checks different value.
            final String testTextClassifier = "com.example.textclassifier";
            setDeviceConfig(TextClassificationConstants.TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE,
                    testTextClassifier);
            assertWithMessage(TextClassificationConstants.TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE)
                    .that(constants.getTextClassifierServicePackageOverride()).isEqualTo(
                    testTextClassifier);
        } finally {
            // Restores config original value.
            setDeviceConfig(TextClassificationConstants.TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE,
                    originalValue);
        }
    }

    private void setDeviceConfig(String key, String value) {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_TEXTCLASSIFIER, key,
                value, /* makeDefault */ false);
    }
}
