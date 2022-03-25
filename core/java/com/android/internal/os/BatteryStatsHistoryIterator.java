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

/**
 * An iterator for {@link BatteryStats.HistoryItem}'s.
 */
public class BatteryStatsHistoryIterator {
    private static final boolean DEBUG = false;
    private static final String TAG = "BatteryStatsHistoryItr";
    private final BatteryStatsHistory mBatteryStatsHistory;
    private final BatteryStats.HistoryStepDetails mReadHistoryStepDetails =
            new BatteryStats.HistoryStepDetails();
    private final SparseArray<BatteryStats.HistoryTag> mHistoryTags = new SparseArray<>();

    public BatteryStatsHistoryIterator(@NonNull BatteryStatsHistory history) {
        mBatteryStatsHistory = history;
        mBatteryStatsHistory.startIteratingHistory();
    }

    /**
     * Retrieves the next HistoryItem from battery history, if available. Returns false if there
     * are no more items.
     */
    public boolean next(BatteryStats.HistoryItem out) {
        Parcel p = mBatteryStatsHistory.getNextParcel(out);
        if (p == null) {
            mBatteryStatsHistory.finishIteratingHistory();
            return false;
        }

        final long lastRealtimeMs = out.time;
        final long lastWalltimeMs = out.currentTime;
        readHistoryDelta(p, out);
        if (out.cmd != BatteryStats.HistoryItem.CMD_CURRENT_TIME
                && out.cmd != BatteryStats.HistoryItem.CMD_RESET && lastWalltimeMs != 0) {
            out.currentTime = lastWalltimeMs + (out.time - lastRealtimeMs);
        }
        return true;
    }

    void readHistoryDelta(Parcel src, BatteryStats.HistoryItem cur) {
        int firstToken = src.readInt();
        int deltaTimeToken = firstToken & BatteryStatsImpl.DELTA_TIME_MASK;
        cur.cmd = BatteryStats.HistoryItem.CMD_UPDATE;
        cur.numReadInts = 1;
        if (DEBUG) {
            Slog.i(TAG, "READ DELTA: firstToken=0x" + Integer.toHexString(firstToken)
                    + " deltaTimeToken=" + deltaTimeToken);
        }

        if (deltaTimeToken < BatteryStatsImpl.DELTA_TIME_ABS) {
            cur.time += deltaTimeToken;
        } else if (deltaTimeToken == BatteryStatsImpl.DELTA_TIME_ABS) {
            cur.readFromParcel(src);
            if (DEBUG) Slog.i(TAG, "READ DELTA: ABS time=" + cur.time);
            return;
        } else if (deltaTimeToken == BatteryStatsImpl.DELTA_TIME_INT) {
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
        if ((firstToken & BatteryStatsImpl.DELTA_BATTERY_LEVEL_FLAG) != 0) {
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

        if ((firstToken & BatteryStatsImpl.DELTA_STATE_FLAG) != 0) {
            int stateInt = src.readInt();
            cur.states = (firstToken & BatteryStatsImpl.DELTA_STATE_MASK) | (stateInt
                    & (~BatteryStatsImpl.STATE_BATTERY_MASK));
            cur.batteryStatus = (byte) ((stateInt >> BatteryStatsImpl.STATE_BATTERY_STATUS_SHIFT)
                    & BatteryStatsImpl.STATE_BATTERY_STATUS_MASK);
            cur.batteryHealth = (byte) ((stateInt >> BatteryStatsImpl.STATE_BATTERY_HEALTH_SHIFT)
                    & BatteryStatsImpl.STATE_BATTERY_HEALTH_MASK);
            cur.batteryPlugType = (byte) ((stateInt >> BatteryStatsImpl.STATE_BATTERY_PLUG_SHIFT)
                    & BatteryStatsImpl.STATE_BATTERY_PLUG_MASK);
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
            cur.states = (firstToken & BatteryStatsImpl.DELTA_STATE_MASK) | (cur.states
                    & (~BatteryStatsImpl.STATE_BATTERY_MASK));
        }

        if ((firstToken & BatteryStatsImpl.DELTA_STATE2_FLAG) != 0) {
            cur.states2 = src.readInt();
            if (DEBUG) {
                Slog.i(TAG, "READ DELTA: states2=0x"
                        + Integer.toHexString(cur.states2));
            }
        }

        if ((firstToken & BatteryStatsImpl.DELTA_WAKELOCK_FLAG) != 0) {
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

        if ((firstToken & BatteryStatsImpl.DELTA_EVENT_FLAG) != 0) {
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

        if ((batteryLevelInt & BatteryStatsImpl.BATTERY_DELTA_LEVEL_FLAG) != 0) {
            cur.stepDetails = mReadHistoryStepDetails;
            cur.stepDetails.readFromParcel(src);
        } else {
            cur.stepDetails = null;
        }

        if ((firstToken & BatteryStatsImpl.DELTA_BATTERY_CHARGE_FLAG) != 0) {
            cur.batteryChargeUah = src.readInt();
        }
        cur.modemRailChargeMah = src.readDouble();
        cur.wifiRailChargeMah = src.readDouble();
    }

    private boolean readHistoryTag(Parcel src, int index, BatteryStats.HistoryTag outTag) {
        if (index == 0xffff) {
            return false;
        }

        if ((index & BatteryStatsImpl.TAG_FIRST_OCCURRENCE_FLAG) != 0) {
            BatteryStats.HistoryTag tag = new BatteryStats.HistoryTag();
            tag.readFromParcel(src);
            tag.poolIdx = index & ~BatteryStatsImpl.TAG_FIRST_OCCURRENCE_FLAG;
            mHistoryTags.put(tag.poolIdx, tag);

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
        out.batteryVoltage = (char) ((batteryLevelInt & 0x00007ffe) >>> 1);
    }
}
