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

package com.android.server.inputmethod;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.StartInputReason;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A ring buffer to store the history of {@link StartInputInfo}.
 */
final class StartInputHistory {
    /**
     * Entry size for non low-RAM devices.
     *
     * <p>TODO: Consider to follow what other system services have been doing to manage
     * constants (e.g. {@link android.provider.Settings.Global#ACTIVITY_MANAGER_CONSTANTS}).</p>
     */
    private static final int ENTRY_SIZE_FOR_HIGH_RAM_DEVICE = 32;

    /**
     * Entry size for low-RAM devices.
     *
     * <p>TODO: Consider to follow what other system services have been doing to manage
     * constants (e.g. {@link android.provider.Settings.Global#ACTIVITY_MANAGER_CONSTANTS}).</p>
     */
    private static final int ENTRY_SIZE_FOR_LOW_RAM_DEVICE = 5;

    private static int getEntrySize() {
        if (ActivityManager.isLowRamDeviceStatic()) {
            return ENTRY_SIZE_FOR_LOW_RAM_DEVICE;
        } else {
            return ENTRY_SIZE_FOR_HIGH_RAM_DEVICE;
        }
    }

    /**
     * Backing store for the ring buffer.
     */
    private final Entry[] mEntries = new Entry[getEntrySize()];

    /**
     * An index of {@link #mEntries}, to which next
     * {@link #addEntry(StartInputInfo)} should
     * write.
     */
    private int mNextIndex = 0;

    /**
     * Recyclable entry to store the information in {@link StartInputInfo}.
     */
    private static final class Entry {
        int mSequenceNumber;
        long mTimestamp;
        long mWallTime;
        @UserIdInt
        int mImeUserId;
        @NonNull
        String mImeTokenString;
        int mImeDisplayId;
        @NonNull
        String mImeId;
        @StartInputReason
        int mStartInputReason;
        boolean mRestarting;
        @UserIdInt
        int mTargetUserId;
        int mTargetDisplayId;
        @NonNull
        String mTargetWindowString;
        @NonNull
        EditorInfo mEditorInfo;
        @WindowManager.LayoutParams.SoftInputModeFlags
        int mTargetWindowSoftInputMode;
        int mClientBindSequenceNumber;

        Entry(@NonNull StartInputInfo original) {
            set(original);
        }

        void set(@NonNull StartInputInfo original) {
            mSequenceNumber = original.mSequenceNumber;
            mTimestamp = original.mTimestamp;
            mWallTime = original.mWallTime;
            mImeUserId = original.mImeUserId;
            // Intentionally convert to String so as not to keep a strong reference to a Binder
            // object.
            mImeTokenString = String.valueOf(original.mImeToken);
            mImeDisplayId = original.mImeDisplayId;
            mImeId = original.mImeId;
            mStartInputReason = original.mStartInputReason;
            mRestarting = original.mRestarting;
            mTargetUserId = original.mTargetUserId;
            mTargetDisplayId = original.mTargetDisplayId;
            // Intentionally convert to String so as not to keep a strong reference to a Binder
            // object.
            mTargetWindowString = String.valueOf(original.mTargetWindow);
            mEditorInfo = original.mEditorInfo;
            mTargetWindowSoftInputMode = original.mTargetWindowSoftInputMode;
            mClientBindSequenceNumber = original.mClientBindSequenceNumber;
        }
    }

    /**
     * Add a new entry and discard the oldest entry as needed.
     *
     * @param info {@link StartInputInfo} to be added.
     */
    void addEntry(@NonNull StartInputInfo info) {
        final int index = mNextIndex;
        if (mEntries[index] == null) {
            mEntries[index] = new Entry(info);
        } else {
            mEntries[index].set(info);
        }
        mNextIndex = (mNextIndex + 1) % mEntries.length;
    }

    void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        final DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        .withZone(ZoneId.systemDefault());

        for (int i = 0; i < mEntries.length; ++i) {
            final Entry entry = mEntries[(i + mNextIndex) % mEntries.length];
            if (entry == null) {
                continue;
            }
            pw.print(prefix);
            pw.println("StartInput #" + entry.mSequenceNumber + ":");

            pw.print(prefix);
            pw.println("  time=" + formatter.format(Instant.ofEpochMilli(entry.mWallTime))
                    + " (timestamp=" + entry.mTimestamp + ")"
                    + " reason="
                    + InputMethodDebug.startInputReasonToString(entry.mStartInputReason)
                    + " restarting=" + entry.mRestarting);

            pw.print(prefix);
            pw.print("  imeToken=" + entry.mImeTokenString + " [" + entry.mImeId + "]");
            pw.print(" imeUserId=" + entry.mImeUserId);
            pw.println(" imeDisplayId=" + entry.mImeDisplayId);

            pw.print(prefix);
            pw.println("  targetWin=" + entry.mTargetWindowString
                    + " [" + entry.mEditorInfo.packageName + "]"
                    + " targetUserId=" + entry.mTargetUserId
                    + " targetDisplayId=" + entry.mTargetDisplayId
                    + " clientBindSeq=" + entry.mClientBindSequenceNumber);

            pw.print(prefix);
            pw.println("  softInputMode=" + InputMethodDebug.softInputModeToString(
                    entry.mTargetWindowSoftInputMode));

            pw.print(prefix);
            pw.println("  inputType=0x" + Integer.toHexString(entry.mEditorInfo.inputType)
                    + " imeOptions=0x" + Integer.toHexString(entry.mEditorInfo.imeOptions)
                    + " fieldId=0x" + Integer.toHexString(entry.mEditorInfo.fieldId)
                    + " fieldName=" + entry.mEditorInfo.fieldName
                    + " actionId=" + entry.mEditorInfo.actionId
                    + " actionLabel=" + entry.mEditorInfo.actionLabel);
        }
    }
}
