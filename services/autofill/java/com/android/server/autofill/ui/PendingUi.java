/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.autofill.ui;

import android.annotation.NonNull;
import android.os.IBinder;
import android.util.DebugUtils;
import android.view.autofill.IAutoFillManagerClient;

/**
 * Helper class used to handle a pending Autofill UI such as the save UI.
 *
 * <p>This class is not thread safe.
 */
// NOTE: this class could be an interface implemented by Session, but that would make it harder
// to move the Autofill UI logic to a different process.
public final class PendingUi {

    public static final int STATE_CREATED = 1;
    public static final int STATE_PENDING = 2;
    public static final int STATE_FINISHED = 4;

    private final IBinder mToken;
    private int mState;
    public final int sessionId;
    public final IAutoFillManagerClient client;

    /**
     * Default constructor.
     *
     * @param token token used to identify this pending UI.
     */
    public PendingUi(@NonNull IBinder token, int sessionId,
            @NonNull IAutoFillManagerClient client) {
        mToken = token;
        mState = STATE_CREATED;
        this.sessionId = sessionId;
        this.client = client;
    }

    /**
     * Gets the token used to identify this pending UI.
     */
    @NonNull
    public IBinder getToken() {
        return mToken;
    }

    /**
     * Sets the current lifecycle state.
     */
    public void setState(int state) {
        mState = state;
    }

    /**
     * Gets the current lifecycle state.
     */
    public int getState() {
        return mState;
    }

    /**
     * Determines whether the given token matches the token used to identify this pending UI.
     */
    public boolean matches(IBinder token) {
        return mToken.equals(token);
    }

    @Override
    public String toString() {
        return "PendingUi: [token=" + mToken + ", sessionId=" + sessionId + ", state="
                + DebugUtils.flagsToString(PendingUi.class, "STATE_", mState) + "]";
    }
}
