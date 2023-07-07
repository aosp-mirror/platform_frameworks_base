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
import android.app.time.ITimeDetectorListener;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.time.TimeState;
import android.app.time.UnixEpochTime;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;

/**
 * Binder APIs to communicate with the time detector service.
 *
 * <p>Used to provide information to the Time Detector Service from other parts of the Android
 * system that have access to time-related signals, e.g. telephony. Over time, System APIs have
 * been added to support unbundled parts of the platform, e.g. SetUp Wizard.
 *
 * <p>Use the {@link android.app.timedetector.TimeDetector} (internal API) and
 * {@link android.app.time.TimeManager} (system API) classes rather than going through this Binder
 * interface directly. See {@link android.app.timedetector.TimeDetectorService} for more complete
 * documentation.
 *
 * {@hide}
 */
interface ITimeDetectorService {
  TimeCapabilitiesAndConfig getCapabilitiesAndConfig();
  void addListener(ITimeDetectorListener listener);
  void removeListener(ITimeDetectorListener listener);

  boolean updateConfiguration(in TimeConfiguration timeConfiguration);

  TimeState getTimeState();
  boolean confirmTime(in UnixEpochTime time);
  boolean setManualTime(in ManualTimeSuggestion timeZoneSuggestion);

  void suggestExternalTime(in ExternalTimeSuggestion timeSuggestion);
  boolean suggestManualTime(in ManualTimeSuggestion timeSuggestion);
  void suggestTelephonyTime(in TelephonyTimeSuggestion timeSuggestion);

  UnixEpochTime latestNetworkTime();
}
