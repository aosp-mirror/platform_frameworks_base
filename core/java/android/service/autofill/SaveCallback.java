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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.IntentSender;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.Preconditions;

/**
 * Handles save requests from the {@link AutofillService} into the {@link Activity} being
 * autofilled.
 */
public final class SaveCallback {

    private static final String TAG = "SaveCallback";

    private final ISaveCallback mCallback;
    private boolean mCalled;

    /** @hide */
    SaveCallback(ISaveCallback callback) {
        mCallback = callback;
    }

    /**
     * Notifies the Android System that an
     * {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)} was successfully handled
     * by the service.
     *
     * @throws IllegalStateException if this method, {@link #onSuccess(IntentSender)}, or
     * {@link #onFailure(CharSequence)} was already called.
     */
    public void onSuccess() {
        onSuccessInternal(null);
    }

    /**
     * Notifies the Android System that an
     * {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)} was successfully handled
     * by the service.
     *
     * <p>This method is useful when the service requires extra work&mdash;for example, launching an
     * activity asking the user to authenticate first &mdash;before it can process the request,
     * as the intent will be launched from the context of the activity being autofilled and hence
     * will be part of that activity's stack.
     *
     * @param intentSender intent that will be launched from the context of activity being
     * autofilled.
     *
     * @throws IllegalStateException if this method, {@link #onSuccess()},
     * or {@link #onFailure(CharSequence)} was already called.
     */
    public void onSuccess(@NonNull IntentSender intentSender) {
        onSuccessInternal(Preconditions.checkNotNull(intentSender));
    }

    private void onSuccessInternal(@Nullable IntentSender intentSender) {
        assertNotCalled();
        mCalled = true;
        try {
            mCallback.onSuccess(intentSender);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }




    /**
     * Notifies the Android System that an
     * {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)} could not be handled
     * by the service.
     *
     * <p>This method is just used for logging purposes, the Android System won't call the service
     * again in case of failures&mdash;if you need to recover from the failure, just save the
     * {@link SaveRequest} and try again later.
     *
     * <p><b>Note: </b>for apps targeting {@link android.os.Build.VERSION_CODES#Q} or higher, this
     * method just logs the message on {@code logcat}; for apps targetting older SDKs, it also
     * displays the message to user using a {@link android.widget.Toast}.
     *
     * @param message error message. <b>Note: </b> this message should <b>not</b> contain PII
     * (Personally Identifiable Information, such as username or email address).
     *
     * @throws IllegalStateException if this method, {@link #onSuccess()},
     * or {@link #onSuccess(IntentSender)} was already called.
     */
    public void onFailure(CharSequence message) {
        Log.w(TAG, "onFailure(): " + message);
        assertNotCalled();
        mCalled = true;
        try {
            mCallback.onFailure(message);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    private void assertNotCalled() {
        if (mCalled) {
            throw new IllegalStateException("Already called");
        }
    }
}
