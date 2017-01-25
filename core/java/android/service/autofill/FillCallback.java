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

package android.service.autofill;

import static android.service.autofill.AutoFillService.DEBUG;

import android.annotation.Nullable;
import android.app.Activity;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.autofill.FillResponse;

import com.android.internal.util.Preconditions;

/**
 * Handles auto-fill requests from the {@link AutoFillService} into the {@link Activity} being
 * auto-filled.
 */
public final class FillCallback {

    private static final String TAG = "FillCallback";

    private final IAutoFillServerCallback mCallback;

    private boolean mReplied = false;

    /** @hide */
    FillCallback(IAutoFillServerCallback callback) {
        mCallback = callback;
    }

    /**
     * Notifies the Android System that an
     * {@link AutoFillService#onFillRequest(android.app.assist.AssistStructure, Bundle, android.os.CancellationSignal, FillCallback)}
     * was successfully fulfilled by the service.
     *
     * @param response auto-fill information for that activity, or {@code null} when the activity
     * cannot be auto-filled (for example, if it only contains read-only fields).
     *
     * @throws RuntimeException if an error occurred while calling the Android System.
     */
    public void onSuccess(@Nullable FillResponse response) {
        if (DEBUG) Log.d(TAG, "onSuccess(): respose=" + response);

        checkNotRepliedYet();

        try {
            mCallback.showResponse(response);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notifies the Android System that an
     * {@link AutoFillService#onFillRequest(android.app.assist.AssistStructure, Bundle, android.os.CancellationSignal, FillCallback)}
     * could not be fulfilled by the service.
     *
     * @param message error message to be displayed to the user.
     *
     * @throws RuntimeException if an error occurred while calling the Android System.
     */
    public void onFailure(CharSequence message) {
        if (DEBUG) Log.d(TAG, "onFailure(): message=" + message);

        checkNotRepliedYet();
        Preconditions.checkArgument(message != null, "message cannot be null");

        try {
            mCallback.showError(message.toString());
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    // There can be only one!!
    private void checkNotRepliedYet() {
        Preconditions.checkState(!mReplied, "already replied");
        mReplied = true;
    }
}
