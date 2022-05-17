/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.os;

import android.os.SystemProperties;
import android.sysprop.CryptoProperties;
import android.sysprop.HdmiProperties;

/**
 * This is a cache of various ro.* properties so that they can be read just once
 * at class init time.
 */
public class RoSystemProperties {
    public static final boolean DEBUGGABLE =
            SystemProperties.getInt("ro.debuggable", 0) == 1;
    public static final int FACTORYTEST =
            SystemProperties.getInt("ro.factorytest", 0);
    public static final String CONTROL_PRIVAPP_PERMISSIONS =
            SystemProperties.get("ro.control_privapp_permissions");

    // ------ ro.hdmi.* -------- //
    /**
     * Property to indicate if a CEC audio device should forward volume keys when system audio
     * mode is off.
     */
    public static final boolean CEC_AUDIO_DEVICE_FORWARD_VOLUME_KEYS_SYSTEM_AUDIO_MODE_OFF =
            HdmiProperties.forward_volume_keys_when_system_audio_mode_off().orElse(false);

    // ------ ro.config.* -------- //
    public static final boolean CONFIG_AVOID_GFX_ACCEL =
            SystemProperties.getBoolean("ro.config.avoid_gfx_accel", false);
    public static final boolean CONFIG_LOW_RAM =
            SystemProperties.getBoolean("ro.config.low_ram", false);
    public static final boolean CONFIG_SMALL_BATTERY =
            SystemProperties.getBoolean("ro.config.small_battery", false);

    // ------ ro.fw.* ------------ //
    public static final boolean FW_SYSTEM_USER_SPLIT =
            SystemProperties.getBoolean("ro.fw.system_user_split", false);
    public static final boolean MULTIUSER_HEADLESS_SYSTEM_USER =
            SystemProperties.getBoolean("ro.fw.mu.headless_system_user", false);

    // ------ ro.crypto.* -------- //
    public static final CryptoProperties.state_values CRYPTO_STATE =
            CryptoProperties.state().orElse(CryptoProperties.state_values.UNSUPPORTED);
    public static final CryptoProperties.type_values CRYPTO_TYPE =
            CryptoProperties.type().orElse(CryptoProperties.type_values.NONE);
    // These are pseudo-properties
    public static final boolean CRYPTO_ENCRYPTED =
            CRYPTO_STATE == CryptoProperties.state_values.ENCRYPTED;
    public static final boolean CRYPTO_FILE_ENCRYPTED =
            CRYPTO_TYPE == CryptoProperties.type_values.FILE;

    public static final boolean CONTROL_PRIVAPP_PERMISSIONS_LOG =
            "log".equalsIgnoreCase(CONTROL_PRIVAPP_PERMISSIONS);
    public static final boolean CONTROL_PRIVAPP_PERMISSIONS_ENFORCE =
            "enforce".equalsIgnoreCase(CONTROL_PRIVAPP_PERMISSIONS);
    public static final boolean CONTROL_PRIVAPP_PERMISSIONS_DISABLE =
            !CONTROL_PRIVAPP_PERMISSIONS_LOG && !CONTROL_PRIVAPP_PERMISSIONS_ENFORCE;

}
