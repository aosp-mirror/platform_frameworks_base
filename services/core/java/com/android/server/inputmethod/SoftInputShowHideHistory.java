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
import android.annotation.Nullable;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.SoftInputShowHideReason;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

final class SoftInputShowHideHistory {
    private static final AtomicInteger sSequenceNumber = new AtomicInteger(0);

    private final Entry[] mEntries = new Entry[16];
    private int mNextIndex = 0;

    static final class Entry {
        final int mSequenceNumber = sSequenceNumber.getAndIncrement();
        @Nullable
        final ClientState mClientState;
        @WindowManager.LayoutParams.SoftInputModeFlags
        final int mFocusedWindowSoftInputMode;
        @SoftInputShowHideReason
        final int mReason;
        // The timing of handling showCurrentInputLocked() or hideCurrentInputLocked().
        final long mTimestamp;
        final long mWallTime;
        final boolean mInFullscreenMode;
        @NonNull
        final String mFocusedWindowName;
        @Nullable
        final EditorInfo mEditorInfo;
        @NonNull
        final String mRequestWindowName;
        @Nullable
        final String mImeControlTargetName;
        @Nullable
        final String mImeTargetNameFromWm;
        @Nullable
        final String mImeSurfaceParentName;

        Entry(ClientState client, EditorInfo editorInfo,
                String focusedWindowName,
                @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode,
                @SoftInputShowHideReason int reason,
                boolean inFullscreenMode, String requestWindowName,
                @Nullable String imeControlTargetName, @Nullable String imeTargetName,
                @Nullable String imeSurfaceParentName) {
            mClientState = client;
            mEditorInfo = editorInfo;
            mFocusedWindowName = focusedWindowName;
            mFocusedWindowSoftInputMode = softInputMode;
            mReason = reason;
            mTimestamp = SystemClock.uptimeMillis();
            mWallTime = System.currentTimeMillis();
            mInFullscreenMode = inFullscreenMode;
            mRequestWindowName = requestWindowName;
            mImeControlTargetName = imeControlTargetName;
            mImeTargetNameFromWm = imeTargetName;
            mImeSurfaceParentName = imeSurfaceParentName;
        }
    }

    void addEntry(@NonNull Entry entry) {
        final int index = mNextIndex;
        mEntries[index] = entry;
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
            pw.println("SoftInputShowHide #" + entry.mSequenceNumber + ":");

            pw.print(prefix);
            pw.println("  time=" + formatter.format(Instant.ofEpochMilli(entry.mWallTime))
                    + " (timestamp=" + entry.mTimestamp + ")");

            pw.print(prefix);
            pw.print("  reason=" + InputMethodDebug.softInputDisplayReasonToString(
                    entry.mReason));
            pw.println(" inFullscreenMode=" + entry.mInFullscreenMode);

            pw.print(prefix);
            pw.println("  requestClient=" + entry.mClientState);

            pw.print(prefix);
            pw.println("  focusedWindowName=" + entry.mFocusedWindowName);

            pw.print(prefix);
            pw.println("  requestWindowName=" + entry.mRequestWindowName);

            pw.print(prefix);
            pw.println("  imeControlTargetName=" + entry.mImeControlTargetName);

            pw.print(prefix);
            pw.println("  imeTargetNameFromWm=" + entry.mImeTargetNameFromWm);

            pw.print(prefix);
            pw.println("  imeSurfaceParentName=" + entry.mImeSurfaceParentName);

            pw.print(prefix);
            pw.print("  editorInfo:");
            if (entry.mEditorInfo != null) {
                pw.print(" inputType=" + entry.mEditorInfo.inputType);
                pw.print(" privateImeOptions=" + entry.mEditorInfo.privateImeOptions);
                pw.println(" fieldId (viewId)=" + entry.mEditorInfo.fieldId);
            } else {
                pw.println(" null");
            }

            pw.print(prefix);
            pw.println("  focusedWindowSoftInputMode=" + InputMethodDebug.softInputModeToString(
                    entry.mFocusedWindowSoftInputMode));
        }
    }
}
