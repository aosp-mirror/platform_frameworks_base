/**
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

package android.hardware.lights;

import android.hardware.lights.Light;
import android.hardware.lights.LightState;

/**
 * API to lights manager service.
 *
 * {@hide}
 */
interface ILightsManager {
  @EnforcePermission("CONTROL_DEVICE_LIGHTS")
  List<Light> getLights();
  @EnforcePermission("CONTROL_DEVICE_LIGHTS")
  LightState getLightState(int lightId);
  @EnforcePermission("CONTROL_DEVICE_LIGHTS")
  void openSession(in IBinder sessionToken, in int priority);
  @EnforcePermission("CONTROL_DEVICE_LIGHTS")
  void closeSession(in IBinder sessionToken);
  @EnforcePermission("CONTROL_DEVICE_LIGHTS")
  void setLightStates(in IBinder sessionToken, in int[] lightIds, in LightState[] states);
}
