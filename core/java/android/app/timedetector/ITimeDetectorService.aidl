/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.timedetector;

import android.app.time.ExternalTimeSuggestion;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.timedetector.GnssTimeSuggestion;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;

/**
 * System private API to communicate with time detector service.
 *
 * <p>Used by parts of the Android system with signals associated with the device's time to provide
 * information to the Time Detector Service.
 *
 * <p>Use the {@link android.app.timedetector.TimeDetector} class rather than going through
 * this Binder interface directly. See {@link android.app.timedetector.TimeDetectorService} for
 * more complete documentation.
 *
 *
 * {@hide}
 */
interface ITimeDetectorService {
  TimeCapabilitiesAndConfig getCapabilitiesAndConfig();
  boolean updateConfiguration(in TimeConfiguration timeConfiguration);

  void suggestExternalTime( in ExternalTimeSuggestion timeSuggestion);
  void suggestGnssTime(in GnssTimeSuggestion timeSuggestion);
  boolean suggestManualTime(in ManualTimeSuggestion timeSuggestion);
  void suggestNetworkTime(in NetworkTimeSuggestion timeSuggestion);
  void suggestTelephonyTime(in TelephonyTimeSuggestion timeSuggestion);
}
