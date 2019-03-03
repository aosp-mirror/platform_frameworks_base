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
package android.os.connectivity;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.location.gnssmetrics.GnssMetrics;

import java.util.Arrays;

/**
 * API for GPS power stats
 *
 * @hide
 */
public final class GpsBatteryStats implements Parcelable {

  private long mLoggingDurationMs;
  private long mEnergyConsumedMaMs;
  private long[] mTimeInGpsSignalQualityLevel;

  public static final @android.annotation.NonNull Parcelable.Creator<GpsBatteryStats> CREATOR = new
      Parcelable.Creator<GpsBatteryStats>() {
        public GpsBatteryStats createFromParcel(Parcel in) {
          return new GpsBatteryStats(in);
        }

        public GpsBatteryStats[] newArray(int size) {
          return new GpsBatteryStats[size];
        }
      };

  public GpsBatteryStats() {
    initialize();
  }

  @Override
  public void writeToParcel(Parcel out, int flags) {
    out.writeLong(mLoggingDurationMs);
    out.writeLong(mEnergyConsumedMaMs);
    out.writeLongArray(mTimeInGpsSignalQualityLevel);
  }

  public void readFromParcel(Parcel in) {
    mLoggingDurationMs = in.readLong();
    mEnergyConsumedMaMs = in.readLong();
    in.readLongArray(mTimeInGpsSignalQualityLevel);
  }

  public long getLoggingDurationMs() {
    return mLoggingDurationMs;
  }

  public long getEnergyConsumedMaMs() {
    return mEnergyConsumedMaMs;
  }

  public long[] getTimeInGpsSignalQualityLevel() {
    return mTimeInGpsSignalQualityLevel;
  }

  public void setLoggingDurationMs(long t) {
    mLoggingDurationMs = t;
    return;
  }

  public void setEnergyConsumedMaMs(long e) {
    mEnergyConsumedMaMs = e;
    return;
  }

  public void setTimeInGpsSignalQualityLevel(long[] t) {
    mTimeInGpsSignalQualityLevel = Arrays.copyOfRange(t, 0,
        Math.min(t.length, GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS));
    return;
  }

  @Override
  public int describeContents() {
    return 0;
  }

  private GpsBatteryStats(Parcel in) {
    initialize();
    readFromParcel(in);
  }

  private void initialize() {
    mLoggingDurationMs = 0;
    mEnergyConsumedMaMs = 0;
    mTimeInGpsSignalQualityLevel = new long[GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS];
    return;
  }
}