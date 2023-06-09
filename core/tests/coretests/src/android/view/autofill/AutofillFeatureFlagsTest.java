/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.view.autofill;

import static com.google.common.truth.Truth.assertThat;

import android.provider.DeviceConfig;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link AutofillFeatureFlags}
 *
 * run: atest FrameworksCoreTests:android.view.autofill.AutofillFeatureFlagsTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AutofillFeatureFlagsTest {

    @Test
    public void testGetFillDialogEnabledHintsEmpty() {
        setFillDialogHints("");
        String[] fillDialogHints = AutofillFeatureFlags.getFillDialogEnabledHints();
        assertThat(fillDialogHints).isEmpty();
    }

    @Test
    public void testGetFillDialogEnabledHintsTwoValues() {
        setFillDialogHints("password:creditCardNumber");
        String[] fillDialogHints = AutofillFeatureFlags.getFillDialogEnabledHints();
        assertThat(fillDialogHints.length).isEqualTo(2);
        assertThat(fillDialogHints[0]).isEqualTo("password");
        assertThat(fillDialogHints[1]).isEqualTo("creditCardNumber");
    }

    @Test
    public void testIsCredentialManagerEnabled() {
        setCredentialManagerEnabled(false);
        assertThat(AutofillFeatureFlags.isCredentialManagerEnabled()).isFalse();
        setCredentialManagerEnabled(true);
        assertThat(AutofillFeatureFlags.isCredentialManagerEnabled()).isTrue();
    }

    private static void setFillDialogHints(String value) {
        setDeviceConfig(
                AutofillFeatureFlags.DEVICE_CONFIG_AUTOFILL_DIALOG_HINTS,
                value);
    }

    private static void setCredentialManagerEnabled(boolean value) {
        setDeviceConfig(
                AutofillFeatureFlags.DEVICE_CONFIG_AUTOFILL_CREDENTIAL_MANAGER_ENABLED,
                String.valueOf(value));
    }

    private static void setDeviceConfig(String key, String value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_AUTOFILL, key, value, /* makeDefault */ false);
    }
}
