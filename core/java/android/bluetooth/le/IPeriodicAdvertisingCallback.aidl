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
package android.bluetooth.le;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.PeriodicAdvertisingReport;

/**
 * Callback definitions for interacting with Periodic Advertising
 * @hide
 */
oneway interface IPeriodicAdvertisingCallback {

  void onSyncEstablished(in int syncHandle, in BluetoothDevice device, in int advertisingSid,
                         in int skip, in int timeout, in int status);
  void onPeriodicAdvertisingReport(in PeriodicAdvertisingReport report);
  void onSyncLost(in int syncHandle);
}
