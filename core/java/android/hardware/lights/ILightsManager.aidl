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
  List<Light> getLights();
  LightState getLightState(int lightId);
  void openSession(in IBinder sessionToken, in int priority);
  void closeSession(in IBinder sessionToken);
  void setLightStates(in IBinder sessionToken, in int[] lightIds, in LightState[] states);
}
