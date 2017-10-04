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

import android.app.Activity;
import android.os.RemoteException;

/**
 * Handles save requests from the {@link AutofillService} into the {@link Activity} being
 * autofilled.
 */
public final class SaveCallback {
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
     * <p>If the service could not handle the request right away&mdash;for example, because it must
     * launch an activity asking the user to authenticate first or because the network is
     * down&mdash;it should still call {@link #onSuccess()}.
     *
     * @throws RuntimeException if an error occurred while calling the Android System.
     */
    public void onSuccess() {
        assertNotCalled();
        mCalled = true;
        try {
            mCallback.onSuccess();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notifies the Android System that an
     * {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)} could not be handled
     * by the service.
     *
     * <p>This method should only be called when the service could not handle the request right away
     * and could not recover or retry it. If the service could retry or recover, it could keep
     * the {@link SaveRequest} and call {@link #onSuccess()} instead.
     *
     * <p><b>Note:</b> The Android System displays an UI with the supplied error message; if
     * you prefer to show your own message, call {@link #onSuccess()} instead.
     *
     * @param message error message to be displayed to the user.
     *
     * @throws RuntimeException if an error occurred while calling the Android System.
     */
    public void onFailure(CharSequence message) {
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
