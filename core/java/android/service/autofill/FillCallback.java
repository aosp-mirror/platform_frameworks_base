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

import android.annotation.Nullable;
import android.app.Activity;
import android.os.RemoteException;
import android.util.Log;

/**
 * <p><code>FillCallback</code> handles autofill requests from the {@link AutofillService} into
 * the {@link Activity} being autofilled.
 *
 * <p>To learn about using Autofill services in your app, read
 * <a href="/guide/topics/text/autofill-services">Build autofill services</a>.
 */
public final class FillCallback {

    private static final String TAG = "FillCallback";

    private final IFillCallback mCallback;
    private final int mRequestId;
    private boolean mCalled;

    /** @hide */
    public FillCallback(IFillCallback callback, int requestId) {
        mCallback = callback;
        mRequestId = requestId;
    }

    /**
     * Notifies the Android System that a fill request
     * ({@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal,
     * FillCallback)}) was successfully fulfilled by the service.
     *
     * <p>This method should always be called, even if the service doesn't have the heuristics to
     * fulfill the request (in which case it should be called with {@code null}).
     *
     * <p>See the main {@link AutofillService} documentation for more details and examples.
     *
     * @param response autofill information for that activity, or {@code null} when the service
     * cannot autofill the activity.
     *
     * @throws IllegalStateException if this method or {@link #onFailure(CharSequence)} was already
     * called.
     */
    public void onSuccess(@Nullable FillResponse response) {
        assertNotCalled();
        mCalled = true;

        if (response != null) {
            response.setRequestId(mRequestId);
        }

        try {
            mCallback.onSuccess(response);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notifies the Android System that a fill request (
     * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal,
     * FillCallback)}) could not be fulfilled by the service (for example, because the user data was
     * not available yet), so the request could be retried later.
     *
     * <p><b>Note: </b>this method should not be used when the service didn't have the heursitics to
     * fulfill the request; in this case, the service should call {@link #onSuccess(FillResponse)
     * onSuccess(null)} instead.
     *
     * <p><b>Note: </b>prior to {@link android.os.Build.VERSION_CODES#Q}, this
     * method was not working as intended and the service should always call
     * {@link #onSuccess(FillResponse) onSuccess(null)} instead.
     *
     * <p><b>Note: </b>for apps targeting {@link android.os.Build.VERSION_CODES#Q} or higher, this
     * method just logs the message on {@code logcat}; for apps targetting older SDKs, it also
     * displays the message to user using a {@link android.widget.Toast}. Generally speaking, you
     * should not display an error to the user if the request failed, unless the request had the
     * {@link FillRequest#FLAG_MANUAL_REQUEST} flag.
     *
     * @param message error message. <b>Note: </b> this message should <b>not</b> contain PII
     * (Personally Identifiable Information, such as username or email address).
     *
     * @throws IllegalStateException if this method or {@link #onSuccess(FillResponse)} was already
     * called.
     */
    public void onFailure(@Nullable CharSequence message) {
        Log.w(TAG, "onFailure(): " + message);
        assertNotCalled();
        mCalled = true;
        try {
            mCallback.onFailure(mRequestId, message);
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
