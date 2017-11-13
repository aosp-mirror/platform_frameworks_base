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

import android.telephony.ModemActivityInfo;
import android.telephony.SignalStrength;

import java.util.Arrays;

/**
 * API for Cellular power stats
 *
 * @hide
 */
public final class CellularBatteryStats implements Parcelable {

  private long mLoggingDurationMs;
  private long mKernelActiveTimeMs;
  private long mNumPacketsTx;
  private long mNumBytesTx;
  private long mNumPacketsRx;
  private long mNumBytesRx;
  private long mSleepTimeMs;
  private long mIdleTimeMs;
  private long mRxTimeMs;
  private long mEnergyConsumedMaMs;
  private long[] mTimeInRatMs;
  private long[] mTimeInRxSignalStrengthLevelMs;
  private long[] mTxTimeMs;

  public static final Parcelable.Creator<CellularBatteryStats> CREATOR = new
      Parcelable.Creator<CellularBatteryStats>() {
        public CellularBatteryStats createFromParcel(Parcel in) {
          return new CellularBatteryStats(in);
        }

        public CellularBatteryStats[] newArray(int size) {
          return new CellularBatteryStats[size];
        }
      };

  public CellularBatteryStats() {
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
    out.writeLong(mIdleTimeMs);
    out.writeLong(mRxTimeMs);
    out.writeLong(mEnergyConsumedMaMs);
    out.writeLongArray(mTimeInRatMs);
    out.writeLongArray(mTimeInRxSignalStrengthLevelMs);
    out.writeLongArray(mTxTimeMs);
  }

  public void readFromParcel(Parcel in) {
    mLoggingDurationMs = in.readLong();
    mKernelActiveTimeMs = in.readLong();
    mNumPacketsTx = in.readLong();
    mNumBytesTx = in.readLong();
    mNumPacketsRx = in.readLong();
    mNumBytesRx = in.readLong();
    mSleepTimeMs = in.readLong();
    mIdleTimeMs = in.readLong();
    mRxTimeMs = in.readLong();
    mEnergyConsumedMaMs = in.readLong();
    in.readLongArray(mTimeInRatMs);
    in.readLongArray(mTimeInRxSignalStrengthLevelMs);
    in.readLongArray(mTxTimeMs);
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

  public long getIdleTimeMs() {
    return mIdleTimeMs;
  }

  public long getRxTimeMs() {
    return mRxTimeMs;
  }

  public long getEnergyConsumedMaMs() {
    return mEnergyConsumedMaMs;
  }

  public long[] getTimeInRatMs() {
    return mTimeInRatMs;
  }

  public long[] getTimeInRxSignalStrengthLevelMs() {
    return mTimeInRxSignalStrengthLevelMs;
  }

  public long[] getTxTimeMs() {
    return mTxTimeMs;
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

  public void setIdleTimeMs(long t) {
    mIdleTimeMs = t;
    return;
  }

  public void setRxTimeMs(long t) {
    mRxTimeMs = t;
    return;
  }

  public void setEnergyConsumedMaMs(long e) {
    mEnergyConsumedMaMs = e;
    return;
  }

  public void setTimeInRatMs(long[] t) {
    mTimeInRatMs = Arrays.copyOfRange(t, 0,
        Math.min(t.length, BatteryStats.NUM_DATA_CONNECTION_TYPES));
    return;
  }

  public void setTimeInRxSignalStrengthLevelMs(long[] t) {
    mTimeInRxSignalStrengthLevelMs = Arrays.copyOfRange(t, 0,
        Math.min(t.length, SignalStrength.NUM_SIGNAL_STRENGTH_BINS));
    return;
  }

  public void setTxTimeMs(long[] t) {
    mTxTimeMs = Arrays.copyOfRange(t, 0, Math.min(t.length, ModemActivityInfo.TX_POWER_LEVELS));
    return;
  }

  public int describeContents() {
    return 0;
  }

  private CellularBatteryStats(Parcel in) {
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
    mIdleTimeMs = 0;
    mRxTimeMs = 0;
    mEnergyConsumedMaMs = 0;
    mTimeInRatMs = new long[BatteryStats.NUM_DATA_CONNECTION_TYPES];
    Arrays.fill(mTimeInRatMs, 0);
    mTimeInRxSignalStrengthLevelMs = new long[SignalStrength.NUM_SIGNAL_STRENGTH_BINS];
    Arrays.fill(mTimeInRxSignalStrengthLevelMs, 0);
    mTxTimeMs = new long[ModemActivityInfo.TX_POWER_LEVELS];
    Arrays.fill(mTxTimeMs, 0);
    return;
  }
}