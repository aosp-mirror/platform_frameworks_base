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

package com.android.server.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.os.IBinder;
import android.view.inputmethod.InputMethodInfo;

/**
 * A controller managing the state of the input method binding.
 */
final class InputMethodBindingController {
    static final boolean DEBUG = false;
    private static final String TAG = InputMethodBindingController.class.getSimpleName();

    private final InputMethodManagerService mService;

    private long mLastBindTime;
    private boolean mHasConnection;
    @Nullable private String mCurId;
    @Nullable private String mSelectedMethodId;
    @Nullable private Intent mCurIntent;
    private IBinder mCurToken;
    private int mCurSeq;


    InputMethodBindingController(@NonNull InputMethodManagerService service) {
        mService = service;
    }

    /**
     * Time that we last initiated a bind to the input method, to determine
     * if we should try to disconnect and reconnect to it.
     */
    long getLastBindTime() {
        return mLastBindTime;
    }

    void setLastBindTime(long lastBindTime) {
        mLastBindTime = lastBindTime;
    }

    /**
     * Set to true if our ServiceConnection is currently actively bound to
     * a service (whether or not we have gotten its IBinder back yet).
     */
    boolean hasConnection() {
        return mHasConnection;
    }

    void setHasConnection(boolean hasConnection) {
        mHasConnection = hasConnection;
    }

    /**
     * Id obtained with {@link InputMethodInfo#getId()} for the input method that we are currently
     * connected to or in the process of connecting to.
     *
     * <p>This can be {@code null} when no input method is connected.</p>
     *
     * @see #getSelectedMethodId()
     */
    @Nullable
    String getCurId() {
        return mCurId;
    }

    void setCurId(@Nullable String curId) {
        mCurId = curId;
    }

    /**
     * Id obtained with {@link InputMethodInfo#getId()} for the currently selected input method.
     * This is to be synchronized with the secure settings keyed with
     * {@link android.provider.Settings.Secure#DEFAULT_INPUT_METHOD}.
     *
     * <p>This can be transiently {@code null} when the system is re-initializing input method
     * settings, e.g., the system locale is just changed.</p>
     *
     * <p>Note that {@link #getCurId()} is used to track which IME is being connected to
     * {@link com.android.server.inputmethod.InputMethodManagerService}.</p>
     *
     * @see #getCurId()
     */
    @Nullable
    String getSelectedMethodId() {
        return mSelectedMethodId;
    }

    void setSelectedMethodId(@Nullable String selectedMethodId) {
        mSelectedMethodId = selectedMethodId;
    }

    /**
     * The token we have made for the currently active input method, to
     * identify it in the future.
     */
    IBinder getCurToken() {
        return mCurToken;
    }

    void setCurToken(IBinder curToken) {
        mCurToken = curToken;
    }

    /**
     * The Intent used to connect to the current input method.
     */
    @Nullable
    Intent getCurIntent() {
        return mCurIntent;
    }

    void setCurIntent(@Nullable Intent curIntent) {
        mCurIntent = curIntent;
    }

    /**
     * The current binding sequence number, incremented every time there is
     * a new bind performed.
     */
    int getSequenceNumber() {
        return mCurSeq;
    }

    /**
     * Increase the current binding sequence number by one.
     * Reset to 1 on overflow.
     */
    void advanceSequenceNumber() {
        mCurSeq += 1;
        if (mCurSeq <= 0) {
            mCurSeq = 1;
        }
    }
}
