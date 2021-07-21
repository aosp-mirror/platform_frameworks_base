/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.timezonedetector;

import android.app.time.ITimeZoneDetectorListener;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;

/**
 * System private API to communicate with time zone detector service.
 *
 * <p>Used to provide information to the Time Zone Detector Service from other parts of the Android
 * system that have access to time zone-related signals, e.g. telephony.
 *
 * <p>Use the {@link android.app.timezonedetector.TimeZoneDetector} class rather than going through
 * this Binder interface directly. See {@link android.app.timezonedetector.TimeZoneDetectorService}
 * for more complete documentation.
 *
 * {@hide}
 */
interface ITimeZoneDetectorService {
  TimeZoneCapabilitiesAndConfig getCapabilitiesAndConfig();
  void addListener(ITimeZoneDetectorListener listener);
  void removeListener(ITimeZoneDetectorListener listener);

  boolean updateConfiguration(in TimeZoneConfiguration configuration);

  boolean suggestManualTimeZone(in ManualTimeZoneSuggestion timeZoneSuggestion);
  void suggestTelephonyTimeZone(in TelephonyTimeZoneSuggestion timeZoneSuggestion);
}
