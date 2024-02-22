/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.NonNull;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Parcel;
import android.util.Slog;
import android.util.SparseArray;

import java.util.Iterator;

/**
 * An iterator for {@link BatteryStats.HistoryItem}'s.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class BatteryStatsHistoryIterator implements Iterator<BatteryStats.HistoryItem>,
        AutoCloseable {
    private static final boolean DEBUG = false;
    private static final String TAG = "BatteryStatsHistoryItr";
    private final BatteryStatsHistory mBatteryStatsHistory;
    private final long mStartTimeMs;
    private final long mEndTimeMs;
    private final BatteryStats.HistoryStepDetails mReadHistoryStepDetails =
            new BatteryStats.HistoryStepDetails();
    private final SparseArray<BatteryStats.HistoryTag> mHistoryTags = new SparseArray<>();
    private final PowerStats.DescriptorRegistry mDescriptorRegistry =
            new PowerStats.DescriptorRegistry();
    private BatteryStats.HistoryItem mHistoryItem = new BatteryStats.HistoryItem();
    private boolean mNextItemReady;
    private boolean mTimeInitialized;
    private boolean mClosed;

    public BatteryStatsHistoryIterator(@NonNull BatteryStatsHistory history, long startTimeMs,
            long endTimeMs) {
        mBatteryStatsHistory = history;
        mStartTimeMs = startTimeMs;
        mEndTimeMs = (endTimeMs != MonotonicClock.UNDEFINED) ? endTimeMs : Long.MAX_VALUE;
        mHistoryItem.clear();
    }

    @Override
    public boolean hasNext() {
        if (!mNextItemReady) {
            advance();
        }

        return mHistoryItem != null;
    }

    /**
     * Retrieves the next HistoryItem from battery history, if available. Returns null if there
     * are no more items.
     */
    @Override
    public BatteryStats.HistoryItem next() {
        if (!mNextItemReady) {
            advance();
        }
        mNextItemReady = false;
        return mHistoryItem;
    }

    private void advance() {
        while (true) {
            Parcel p = mBatteryStatsHistory.getNextParcel(mStartTimeMs, mEndTimeMs);
            if (p == null) {
                break;
            }

            if (!mTimeInitialized) {
                mHistoryItem.time = mBatteryStatsHistory.getHistoryBufferStartTime(p);
                mTimeInitialized = true;
            }

            final long lastMonotonicTimeMs = mHistoryItem.time;
            final long lastWalltimeMs = mHistoryItem.currentTime;
            try {
                readHistoryDelta(p, mHistoryItem);
            } catch (Throwable t) {
                Slog.wtf(TAG, "Corrupted battery history", t);
                break;
            }
            if (mHistoryItem.cmd != BatteryStats.HistoryItem.CMD_CURRENT_TIME
                    && mHistoryItem.cmd != BatteryStats.HistoryItem.CMD_RESET
                    && lastWalltimeMs != 0) {
                mHistoryItem.currentTime =
                        lastWalltimeMs + (mHistoryItem.time - lastMonotonicTimeMs);
            }
            if (mEndTimeMs != 0 && mHistoryItem.time >= mEndTimeMs) {
                break;
            }
            if (mHistoryItem.time >= mStartTimeMs) {
                mNextItemReady = true;
                return;
            }
        }

        mHistoryItem = null;
        mNextItemReady = true;
        close();
    }

    private void readHistoryDelta(Parcel src, BatteryStats.HistoryItem cur) {
        int firstToken = src.readInt();
        int deltaTimeToken = firstToken & BatteryStatsHistory.DELTA_TIME_MASK;
        cur.cmd = BatteryStats.HistoryItem.CMD_UPDATE;
        cur.numReadInts = 1;
        if (DEBUG) {
            Slog.i(TAG, "READ DELTA: firstToken=0x" + Integer.toHexString(firstToken)
                    + " deltaTimeToken=" + deltaTimeToken);
        }

        if (deltaTimeToken < BatteryStatsHistory.DELTA_TIME_ABS) {
            cur.time += deltaTimeToken;
        } else if (deltaTimeToken == BatteryStatsHistory.DELTA_TIME_ABS) {
            cur.readFromParcel(src);
            if (DEBUG) Slog.i(TAG, "READ DELTA: ABS time=" + cur.time);
            return;
        } else if (deltaTimeToken == BatteryStatsHistory.DELTA_TIME_INT) {
            int delta = src.readInt();
            cur.time += delta;
            cur.numReadInts += 1;
            if (DEBUG) Slog.i(TAG, "READ DELTA: time delta=" + delta + " new time=" + cur.time);
        } else {
            long delta = src.readLong();
            if (DEBUG) Slog.i(TAG, "READ DELTA: time delta=" + delta + " new time=" + cur.time);
            cur.time += delta;
            cur.numReadInts += 2;
        }

        final int batteryLevelInt;
        if ((firstToken & BatteryStatsHistory.DELTA_BATTERY_LEVEL_FLAG) != 0) {
            batteryLevelInt = src.readInt();
            readBatteryLevelInt(batteryLevelInt, cur);
            cur.numReadInts += 1;
            if (DEBUG) {
                Slog.i(TAG, "READ DELTA: batteryToken=0x"
                        + Integer.toHexString(batteryLevelInt)
                        + " batteryLevel=" + cur.batteryLevel
                        + " batteryTemp=" + cur.batteryTemperature
                        + " batteryVolt=" + (int) cur.batteryVoltage);
            }
        } else {
            batteryLevelInt = 0;
        }

        if ((firstToken & BatteryStatsHistory.DELTA_STATE_FLAG) != 0) {
            int stateInt = src.readInt();
            cur.states = (firstToken & BatteryStatsHistory.DELTA_STATE_MASK) | (stateInt
                    & (~BatteryStatsHistory.STATE_BATTERY_MASK));
            cur.batteryStatus = (byte) ((stateInt >> BatteryStatsHistory.STATE_BATTERY_STATUS_SHIFT)
                    & BatteryStatsHistory.STATE_BATTERY_STATUS_MASK);
            cur.batteryHealth = (byte) ((stateInt >> BatteryStatsHistory.STATE_BATTERY_HEALTH_SHIFT)
                    & BatteryStatsHistory.STATE_BATTERY_HEALTH_MASK);
            cur.batteryPlugType = (byte) ((stateInt >> BatteryStatsHistory.STATE_BATTERY_PLUG_SHIFT)
                    & BatteryStatsHistory.STATE_BATTERY_PLUG_MASK);
            switch (cur.batteryPlugType) {
                case 1:
                    cur.batteryPlugType = BatteryManager.BATTERY_PLUGGED_AC;
                    break;
                case 2:
                    cur.batteryPlugType = BatteryManager.BATTERY_PLUGGED_USB;
                    break;
                case 3:
                    cur.batteryPlugType = BatteryManager.BATTERY_PLUGGED_WIRELESS;
                    break;
            }
            cur.numReadInts += 1;
            if (DEBUG) {
                Slog.i(TAG, "READ DELTA: stateToken=0x"
                        + Integer.toHexString(stateInt)
                        + " batteryStatus=" + cur.batteryStatus
                        + " batteryHealth=" + cur.batteryHealth
                        + " batteryPlugType=" + cur.batteryPlugType
                        + " states=0x" + Integer.toHexString(cur.states));
            }
        } else {
            cur.states = (firstToken & BatteryStatsHistory.DELTA_STATE_MASK) | (cur.states
                    & (~BatteryStatsHistory.STATE_BATTERY_MASK));
        }

        if ((firstToken & BatteryStatsHistory.DELTA_STATE2_FLAG) != 0) {
            cur.states2 = src.readInt();
            if (DEBUG) {
                Slog.i(TAG, "READ DELTA: states2=0x"
                        + Integer.toHexString(cur.states2));
            }
        }

        if ((firstToken & BatteryStatsHistory.DELTA_WAKELOCK_FLAG) != 0) {
            final int indexes = src.readInt();
            final int wakeLockIndex = indexes & 0xffff;
            final int wakeReasonIndex = (indexes >> 16) & 0xffff;
            if (readHistoryTag(src, wakeLockIndex, cur.localWakelockTag)) {
                cur.wakelockTag = cur.localWakelockTag;
            } else {
                cur.wakelockTag = null;
            }
            if (readHistoryTag(src, wakeReasonIndex, cur.localWakeReasonTag)) {
                cur.wakeReasonTag = cur.localWakeReasonTag;
            } else {
                cur.wakeReasonTag = null;
            }
            cur.numReadInts += 1;
        } else {
            cur.wakelockTag = null;
            cur.wakeReasonTag = null;
        }

        if ((firstToken & BatteryStatsHistory.DELTA_EVENT_FLAG) != 0) {
            cur.eventTag = cur.localEventTag;
            final int codeAndIndex = src.readInt();
            cur.eventCode = (codeAndIndex & 0xffff);
            final int index = ((codeAndIndex >> 16) & 0xffff);
            if (readHistoryTag(src, index, cur.localEventTag)) {
                cur.eventTag = cur.localEventTag;
            } else {
                cur.eventTag = null;
            }
            cur.numReadInts += 1;
            if (DEBUG) {
                Slog.i(TAG, "READ DELTA: event=" + cur.eventCode + " tag=#"
                        + cur.eventTag.poolIdx + " " + cur.eventTag.uid + ":"
                        + cur.eventTag.string);
            }
        } else {
            cur.eventCode = BatteryStats.HistoryItem.EVENT_NONE;
        }

        if ((batteryLevelInt & BatteryStatsHistory.BATTERY_LEVEL_DETAILS_FLAG) != 0) {
            cur.stepDetails = mReadHistoryStepDetails;
            cur.stepDetails.readFromParcel(src);
        } else {
            cur.stepDetails = null;
        }

        if ((firstToken & BatteryStatsHistory.DELTA_BATTERY_CHARGE_FLAG) != 0) {
            cur.batteryChargeUah = src.readInt();
        }
        cur.modemRailChargeMah = src.readDouble();
        cur.wifiRailChargeMah = src.readDouble();
        if ((cur.states2 & BatteryStats.HistoryItem.STATE2_EXTENSIONS_FLAG) != 0) {
            final int extensionFlags = src.readInt();
            if ((extensionFlags & BatteryStatsHistory.EXTENSION_POWER_STATS_DESCRIPTOR_FLAG) != 0) {
                PowerStats.Descriptor descriptor = PowerStats.Descriptor.readSummaryFromParcel(src);
                mDescriptorRegistry.register(descriptor);
            }
            if ((extensionFlags & BatteryStatsHistory.EXTENSION_POWER_STATS_FLAG) != 0) {
                cur.powerStats = PowerStats.readFromParcel(src, mDescriptorRegistry);
            } else {
                cur.powerStats = null;
            }
            if ((extensionFlags & BatteryStatsHistory.EXTENSION_PROCESS_STATE_CHANGE_FLAG) != 0) {
                cur.processStateChange = cur.localProcessStateChange;
                cur.processStateChange.readFromParcel(src);
            } else {
                cur.processStateChange = null;
            }
        } else {
            cur.powerStats = null;
            cur.processStateChange = null;
        }
    }

    private boolean readHistoryTag(Parcel src, int index, BatteryStats.HistoryTag outTag) {
        if (index == 0xffff) {
            return false;
        }

        if ((index & BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG) != 0) {
            BatteryStats.HistoryTag tag = new BatteryStats.HistoryTag();
            tag.readFromParcel(src);
            tag.poolIdx = index & ~BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG;
            if (tag.poolIdx < BatteryStatsHistory.HISTORY_TAG_INDEX_LIMIT) {
                mHistoryTags.put(tag.poolIdx, tag);
            } else {
                tag.poolIdx = BatteryStats.HistoryTag.HISTORY_TAG_POOL_OVERFLOW;
            }

            outTag.setTo(tag);
        } else {
            BatteryStats.HistoryTag historyTag = mHistoryTags.get(index);
            if (historyTag != null) {
                outTag.setTo(historyTag);
            } else {
                outTag.string = null;
                outTag.uid = 0;
            }
            outTag.poolIdx = index;
        }
        return true;
    }

    private static void readBatteryLevelInt(int batteryLevelInt, BatteryStats.HistoryItem out) {
        out.batteryLevel = (byte) ((batteryLevelInt & 0xfe000000) >>> 25);
        out.batteryTemperature = (short) ((batteryLevelInt & 0x01ff8000) >>> 15);
        int voltage = ((batteryLevelInt & 0x00007ffe) >>> 1);
        if (voltage == 0x3FFF) {
            voltage = -1;
        }

        out.batteryVoltage = (short) voltage;
    }

    /**
     * Should be called when iteration is complete.
     */
    @Override
    public void close() {
        if (!mClosed) {
            mClosed = true;
            mBatteryStatsHistory.iteratorFinished();
        }
    }
}
