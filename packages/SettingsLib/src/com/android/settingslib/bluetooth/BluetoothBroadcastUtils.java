/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

public final class BluetoothBroadcastUtils {

    /**
     * The fragment tag specified to FragmentManager for container activities to manage fragments.
     */
    public static final String TAG_FRAGMENT_QR_CODE_SCANNER = "qr_code_scanner_fragment";

    /**
     * Action for launching qr code scanner activity.
     */
    public static final String ACTION_BLUETOOTH_LE_AUDIO_QR_CODE_SCANNER =
            "android.settings.BLUETOOTH_LE_AUDIO_QR_CODE_SCANNER";

    /**
     * Extra for {@link android.bluetooth.BluetoothDevice}.
     */
    public static final String EXTRA_BLUETOOTH_DEVICE_SINK = "bluetooth_device_sink";

    /**
     * Extra for checking the {@link android.bluetooth.BluetoothLeBroadcastAssistant} should perform
     * this operation for all coordinated set members throughout one session or not.
     */
    public static final String EXTRA_BLUETOOTH_SINK_IS_GROUP = "bluetooth_sink_is_group";

    /**
     * Bluetooth scheme.
     */
    public static final String SCHEME_BT_BROADCAST_METADATA = "BLUETOOTH:UUID:184F;";
}
