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

package android.nfc;

import android.provider.Settings;

/**
 * @hide
 * TODO(b/303286040): Holds @hide API constants. Formalize these APIs.
 */
public final class Constants {
    private Constants() { }

    public static final String SETTINGS_SECURE_NFC_PAYMENT_FOREGROUND = "nfc_payment_foreground";
    public static final String SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT = "nfc_payment_default_component";
    public static final String FEATURE_NFC_ANY = "android.hardware.nfc.any";

    /**
     * @hide constant copied from {@link Settings.Global}
     * TODO(b/274636414): Migrate to official API in Android V.
     */
    public static final String SETTINGS_SATELLITE_MODE_RADIOS = "satellite_mode_radios";
    /**
     * @hide constant copied from {@link Settings.Global}
     * TODO(b/274636414): Migrate to official API in Android V.
     */
    public static final String SETTINGS_SATELLITE_MODE_ENABLED = "satellite_mode_enabled";
}
