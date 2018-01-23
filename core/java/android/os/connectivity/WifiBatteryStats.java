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

import android.os.BatteryStats;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * API for Wifi power stats
 *
 * @hide
 */
public final class WifiBatteryStats implements Parcelable {

  private long mLoggingDurationMs;
  private long mKernelActiveTimeMs;
  private long mNumPacketsTx;
  private long mNumBytesTx;
  private long mNumPacketsRx;
  private long mNumBytesRx;
  private long mSleepTimeMs;
  private long mScanTimeMs;
  private long mIdleTimeMs;
  private long mRxTimeMs;
  private long mTxTimeMs;
  private long mEnergyConsumedMaMs;
  private long mNumAppScanRequest;
  private long[] mTimeInStateMs;
  private long[] mTimeInSupplicantStateMs;
  private long[] mTimeInRxSignalStrengthLevelMs;

  public static final Parcelable.Creator<WifiBatteryStats> CREATOR = new
      Parcelable.Creator<WifiBatteryStats>() {
        public WifiBatteryStats createFromParcel(Parcel in) {
          return new WifiBatteryStats(in);
        }

        public WifiBatteryStats[] newArray(int size) {
          return new WifiBatteryStats[size];
        }
      };

  public WifiBatteryStats() {
    initialize();
  }

  public void writeToParcel(Parcel out, int flags) {
    out.writeLong(mLoggingDurationMs);
    out.writeLong(mKernelActiveTimeMs);
    out.writeLong(mNumPacketsTx);
    out.writeLong(mNumBytesTx);
    out.writeLong(mNumPacketsRx);
    out.writeLong(mNumBytesRx);
    out.writeLong(mSleepTimeMs);
    out.writeLong(mScanTimeMs);
    out.writeLong(mIdleTimeMs);
    out.writeLong(mRxTimeMs);
    out.writeLong(mTxTimeMs);
    out.writeLong(mEnergyConsumedMaMs);
    out.writeLong(mNumAppScanRequest);
    out.writeLongArray(mTimeInStateMs);
    out.writeLongArray(mTimeInRxSignalStrengthLevelMs);
    out.writeLongArray(mTimeInSupplicantStateMs);
  }

  public void readFromParcel(Parcel in) {
    mLoggingDurationMs = in.readLong();
    mKernelActiveTimeMs = in.readLong();
    mNumPacketsTx = in.readLong();
    mNumBytesTx = in.readLong();
    mNumPacketsRx = in.readLong();
    mNumBytesRx = in.readLong();
    mSleepTimeMs = in.readLong();
    mScanTimeMs = in.readLong();
    mIdleTimeMs = in.readLong();
    mRxTimeMs = in.readLong();
    mTxTimeMs = in.readLong();
    mEnergyConsumedMaMs = in.readLong();
    mNumAppScanRequest = in.readLong();
    in.readLongArray(mTimeInStateMs);
    in.readLongArray(mTimeInRxSignalStrengthLevelMs);
    in.readLongArray(mTimeInSupplicantStateMs);
  }

  public long getLoggingDurationMs() {
    return mLoggingDurationMs;
  }

  public long getKernelActiveTimeMs() {
    return mKernelActiveTimeMs;
  }

  public long getNumPacketsTx() {
    return mNumPacketsTx;
  }

  public long getNumBytesTx() {
    return mNumBytesTx;
  }

  public long getNumPacketsRx() {
    return mNumPacketsRx;
  }

  public long getNumBytesRx() {
    return mNumBytesRx;
  }

  public long getSleepTimeMs() {
    return mSleepTimeMs;
  }

  public long getScanTimeMs() {
    return mScanTimeMs;
  }

  public long getIdleTimeMs() {
    return mIdleTimeMs;
  }

  public long getRxTimeMs() {
    return mRxTimeMs;
  }

  public long getTxTimeMs() {
    return mTxTimeMs;
  }

  public long getEnergyConsumedMaMs() {
    return mEnergyConsumedMaMs;
  }

  public long getNumAppScanRequest() {
    return mNumAppScanRequest;
  }

  public long[] getTimeInStateMs() {
    return mTimeInStateMs;
  }

  public long[] getTimeInRxSignalStrengthLevelMs() {
    return mTimeInRxSignalStrengthLevelMs;
  }

  public long[] getTimeInSupplicantStateMs() {
    return mTimeInSupplicantStateMs;
  }

  public void setLoggingDurationMs(long t) {
    mLoggingDurationMs = t;
    return;
  }

  public void setKernelActiveTimeMs(long t) {
    mKernelActiveTimeMs = t;
    return;
  }

  public void setNumPacketsTx(long n) {
    mNumPacketsTx = n;
    return;
  }

  public void setNumBytesTx(long b) {
    mNumBytesTx = b;
    return;
  }

  public void setNumPacketsRx(long n) {
    mNumPacketsRx = n;
    return;
  }

  public void setNumBytesRx(long b) {
    mNumBytesRx = b;
    return;
  }

  public void setSleepTimeMs(long t) {
    mSleepTimeMs = t;
    return;
  }

  public void setScanTimeMs(long t) {
    mScanTimeMs = t;
    return;
  }

  public void setIdleTimeMs(long t) {
    mIdleTimeMs = t;
    return;
  }

  public void setRxTimeMs(long t) {
    mRxTimeMs = t;
    return;
  }

  public void setTxTimeMs(long t) {
    mTxTimeMs = t;
    return;
  }

  public void setEnergyConsumedMaMs(long e) {
    mEnergyConsumedMaMs = e;
    return;
  }

  public void setNumAppScanRequest(long n) {
    mNumAppScanRequest = n;
    return;
  }

  public void setTimeInStateMs(long[] t) {
    mTimeInStateMs = Arrays.copyOfRange(t, 0,
        Math.min(t.length, BatteryStats.NUM_WIFI_STATES));
    return;
  }

  public void setTimeInRxSignalStrengthLevelMs(long[] t) {
    mTimeInRxSignalStrengthLevelMs = Arrays.copyOfRange(t, 0,
        Math.min(t.length, BatteryStats.NUM_WIFI_SIGNAL_STRENGTH_BINS));
    return;
  }

  public void setTimeInSupplicantStateMs(long[] t) {
    mTimeInSupplicantStateMs = Arrays.copyOfRange(
        t, 0, Math.min(t.length, BatteryStats.NUM_WIFI_SUPPL_STATES));
    return;
  }

  public int describeContents() {
    return 0;
  }

  private WifiBatteryStats(Parcel in) {
    initialize();
    readFromParcel(in);
  }

  private void initialize() {
    mLoggingDurationMs = 0;
    mKernelActiveTimeMs = 0;
    mNumPacketsTx = 0;
    mNumBytesTx = 0;
    mNumPacketsRx = 0;
    mNumBytesRx = 0;
    mSleepTimeMs = 0;
    mScanTimeMs = 0;
    mIdleTimeMs = 0;
    mRxTimeMs = 0;
    mTxTimeMs = 0;
    mEnergyConsumedMaMs = 0;
    mNumAppScanRequest = 0;
    mTimeInStateMs = new long[BatteryStats.NUM_WIFI_STATES];
    Arrays.fill(mTimeInStateMs, 0);
    mTimeInRxSignalStrengthLevelMs = new long[BatteryStats.NUM_WIFI_SIGNAL_STRENGTH_BINS];
    Arrays.fill(mTimeInRxSignalStrengthLevelMs, 0);
    mTimeInSupplicantStateMs = new long[BatteryStats.NUM_WIFI_SUPPL_STATES];
    Arrays.fill(mTimeInSupplicantStateMs, 0);
    return;
  }
}