/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.bluetooth.devicesettings;

import com.android.settingslib.bluetooth.devicesettings.DeviceInfo;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsProviderServiceStatus;
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsListener;

interface IDeviceSettingsProviderService {
   DeviceSettingsProviderServiceStatus getServiceStatus();
   oneway void registerDeviceSettingsListener(in DeviceInfo device, in IDeviceSettingsListener callback);
   oneway void unregisterDeviceSettingsListener(in DeviceInfo device, in IDeviceSettingsListener callback);
   oneway void updateDeviceSettings(in DeviceInfo device, in DeviceSettingState params);
}