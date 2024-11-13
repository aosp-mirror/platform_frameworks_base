/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.am;

import static android.app.AppProtoEnums.BROADCAST_TYPE_ORDERED;
import static android.app.AppProtoEnums.BROADCAST_TYPE_RESULT_TO;
import static android.app.AppProtoEnums.BROADCAST_TYPE_STICKY;

import static com.android.internal.util.FrameworkStatsLog.BROADCAST_SENT;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_SENT__RESULT__FAILED_STICKY_CANT_HAVE_PERMISSION;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_SENT__RESULT__FAILED_USER_STOPPED;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_SENT__RESULT__SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_SENT__RESULT__UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Intent;
import android.util.IntArray;

import com.android.internal.util.FrameworkStatsLog;

final class BroadcastSentEventRecord {
    @NonNull private Intent mIntent;
    private int mOriginalIntentFlags;
    private int mSenderUid;
    private int mRealSenderUid;
    private boolean mSticky;
    private boolean mOrdered;
    private boolean mResultRequested;
    private int mSenderProcState;
    private int mSenderUidState;
    @Nullable private BroadcastRecord mBroadcastRecord;
    private int mResult;

    public void setIntent(@NonNull Intent intent) {
        mIntent = intent;
    }

    public void setSenderUid(int uid) {
        mSenderUid = uid;
    }

    public void setRealSenderUid(int uid) {
        mRealSenderUid = uid;
    }

    public void setOriginalIntentFlags(int flags) {
        mOriginalIntentFlags = flags;
    }

    public void setSticky(boolean sticky) {
        mSticky = sticky;
    }

    public void setOrdered(boolean ordered) {
        mOrdered = ordered;
    }

    public void setResultRequested(boolean resultRequested) {
        mResultRequested = resultRequested;
    }

    public void setSenderProcState(int procState) {
        mSenderProcState = procState;
    }

    public void setSenderUidState(int procState) {
        mSenderUidState = procState;
    }

    public void setBroadcastRecord(@NonNull BroadcastRecord record) {
        mBroadcastRecord = record;
    }

    public void setResult(int result) {
        mResult = result;
    }

    public void logToStatsd() {
        if (Flags.logBroadcastSentEvent()) {
            int loggingResult = switch (mResult) {
                case ActivityManager.BROADCAST_SUCCESS ->
                        BROADCAST_SENT__RESULT__SUCCESS;
                case ActivityManager.BROADCAST_STICKY_CANT_HAVE_PERMISSION ->
                        BROADCAST_SENT__RESULT__FAILED_STICKY_CANT_HAVE_PERMISSION;
                case ActivityManager.BROADCAST_FAILED_USER_STOPPED ->
                        BROADCAST_SENT__RESULT__FAILED_USER_STOPPED;
                default -> BROADCAST_SENT__RESULT__UNKNOWN;
            };
            int[] types = calculateTypesForLogging();
            FrameworkStatsLog.write(BROADCAST_SENT, mIntent.getAction(), mIntent.getFlags(),
                    mOriginalIntentFlags, mSenderUid, mRealSenderUid, mIntent.getPackage() != null,
                    mIntent.getComponent() != null,
                    mBroadcastRecord != null ? mBroadcastRecord.receivers.size() : 0,
                    loggingResult,
                    mBroadcastRecord != null ? mBroadcastRecord.getDeliveryGroupPolicy() : 0,
                    ActivityManager.processStateAmToProto(mSenderProcState),
                    ActivityManager.processStateAmToProto(mSenderUidState), types);
        }
    }

    private int[] calculateTypesForLogging() {
        if (mBroadcastRecord != null) {
            return mBroadcastRecord.calculateTypesForLogging();
        } else {
            final IntArray types = new IntArray();
            if (mSticky) {
                types.add(BROADCAST_TYPE_STICKY);
            }
            if (mOrdered) {
                types.add(BROADCAST_TYPE_ORDERED);
            }
            if (mResultRequested) {
                types.add(BROADCAST_TYPE_RESULT_TO);
            }
            return types.toArray();
        }
    }
}
