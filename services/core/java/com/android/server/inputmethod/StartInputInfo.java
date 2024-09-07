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
import android.annotation.UserIdInt;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.StartInputReason;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Internal state snapshot when
 * {@link IInputMethod#startInput(IInputMethod.StartInputParams)} is about to be called.
 *
 * <p>Calling that IPC endpoint basically means that
 * {@link InputMethodService#doStartInput(InputConnection, EditorInfo, boolean)} will be called
 * back in the current IME process shortly, which will also affect what the current IME starts
 * receiving from {@link InputMethodService#getCurrentInputConnection()}. In other words, this
 * snapshot will be taken every time when {@link InputMethodManagerService} is initiating a new
 * logical input session between the client application and the current IME.</p>
 *
 * <p>Be careful to not keep strong references to this object forever, which can prevent
 * {@link StartInputInfo#mImeToken} and {@link StartInputInfo#mTargetWindow} from being GC-ed.
 * </p>
 */
final class StartInputInfo {
    private static final AtomicInteger sSequenceNumber = new AtomicInteger(0);

    final int mSequenceNumber;
    final long mTimestamp;
    final long mWallTime;
    @UserIdInt
    final int mImeUserId;
    @NonNull
    final IBinder mImeToken;
    final int mImeDisplayId;
    @NonNull
    final String mImeId;
    @StartInputReason
    final int mStartInputReason;
    final boolean mRestarting;
    @UserIdInt
    final int mTargetUserId;
    final int mTargetDisplayId;
    @Nullable
    final IBinder mTargetWindow;
    @NonNull
    final EditorInfo mEditorInfo;
    @WindowManager.LayoutParams.SoftInputModeFlags
    final int mTargetWindowSoftInputMode;
    final int mClientBindSequenceNumber;

    StartInputInfo(@UserIdInt int imeUserId, @NonNull IBinder imeToken, int imeDisplayId,
            @NonNull String imeId, @StartInputReason int startInputReason, boolean restarting,
            @UserIdInt int targetUserId, int targetDisplayId, @Nullable IBinder targetWindow,
            @NonNull EditorInfo editorInfo,
            @WindowManager.LayoutParams.SoftInputModeFlags int targetWindowSoftInputMode,
            int clientBindSequenceNumber) {
        mSequenceNumber = sSequenceNumber.getAndIncrement();
        mTimestamp = SystemClock.uptimeMillis();
        mWallTime = System.currentTimeMillis();
        mImeUserId = imeUserId;
        mImeToken = imeToken;
        mImeDisplayId = imeDisplayId;
        mImeId = imeId;
        mStartInputReason = startInputReason;
        mRestarting = restarting;
        mTargetUserId = targetUserId;
        mTargetDisplayId = targetDisplayId;
        mTargetWindow = targetWindow;
        mEditorInfo = editorInfo;
        mTargetWindowSoftInputMode = targetWindowSoftInputMode;
        mClientBindSequenceNumber = clientBindSequenceNumber;
    }
}
