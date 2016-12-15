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
import static android.util.DebugUtils.flagsToString;

import android.annotation.Nullable;
import android.app.Activity;
import android.os.Bundle;
import android.os.RemoteException;
import android.service.autofill.CallbackHelper.Dumpable;
import android.service.autofill.CallbackHelper.Finalizer;
import android.util.Log;
import android.view.autofill.Dataset;
import android.view.autofill.FillResponse;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;

/**
 * Handles auto-fill requests from the {@link AutoFillService} into the {@link Activity} being
 * auto-filled.
 *
 * <p>This class is thread safe.
 */
public final class FillCallback implements Dumpable {

    private static final String TAG = "FillCallback";

    // NOTE: constants below are public so they can be used by flagsToString()
    /** @hide */ public static final int STATE_INITIAL = 1 << 0;
    /** @hide */ public static final int STATE_WAITING_FILL_RESPONSE_AUTH_RESPONSE = 1 << 1;
    /** @hide */ public static final int STATE_WAITING_DATASET_AUTH_RESPONSE = 1 << 2;
    /** @hide */ public static final int STATE_FINISHED_OK = 1 << 3;
    /** @hide */ public static final int STATE_FINISHED_FAILURE = 1 << 4;
    /** @hide */ public static final int STATE_FINISHED_ERROR = 1 << 5;
    /** @hide */ public static final int STATE_FINISHED_AUTHENTICATED = 1 << 6;

    private final IAutoFillServerCallback mCallback;

    @GuardedBy("mCallback")
    private int mState = STATE_INITIAL;

    @GuardedBy("mCallback")
    private Finalizer mFinalizer;

    /** @hide */
    FillCallback(IAutoFillServerCallback callback) {
        mCallback = callback;
    }

    /**
     * Notifies the Android System that an
     * {@link AutoFillService#onFillRequest(android.app.assist.AssistStructure, Bundle,
     * android.os.CancellationSignal, FillCallback)} was successfully fulfilled by the service.
     *
     * @param response auto-fill information for that activity, or {@code null} when the activity
     * cannot be auto-filled (for example, if it only contains read-only fields). See
     * {@link FillResponse} for examples.
     */
    public void onSuccess(@Nullable FillResponse response) {
        final boolean authRequired = response != null && response.isAuthRequired();

        if (DEBUG) Log.d(TAG, "onSuccess(): authReq= " + authRequired + ", resp=" + response);

        synchronized (mCallback) {
            if (authRequired) {
                assertOnStateLocked(STATE_INITIAL);
            } else {
                assertOnStateLocked(STATE_INITIAL | STATE_WAITING_FILL_RESPONSE_AUTH_RESPONSE
                        | STATE_WAITING_DATASET_AUTH_RESPONSE);
            }

            try {
                mCallback.showResponse(response);
                if (authRequired) {
                    mState = STATE_WAITING_FILL_RESPONSE_AUTH_RESPONSE;
                } else {
                    // Check if at least one dataset requires authentication.
                    boolean waitingAuth = false;
                    if (response != null) {
                        for (Dataset dataset : response.getDatasets()) {
                            if (dataset.isAuthRequired()) {
                                waitingAuth = true;
                                break;
                            }
                        }
                    }
                    if (waitingAuth) {
                        mState = STATE_WAITING_DATASET_AUTH_RESPONSE;
                    } else {
                        setFinalStateLocked(STATE_FINISHED_OK);
                    }
                }
            } catch (RemoteException e) {
                setFinalStateLocked(STATE_FINISHED_ERROR);
                e.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Notifies the Android System that an
     * {@link AutoFillService#onFillRequest(android.app.assist.AssistStructure,
     * Bundle, android.os.CancellationSignal, FillCallback)}
     * could not be fulfilled by the service.
     *
     * @param message error message to be displayed to the user.
     */
    public void onFailure(CharSequence message) {
        if (DEBUG) Log.d(TAG, "onFailure(): message=" + message);

        Preconditions.checkArgument(message != null, "message cannot be null");

        synchronized (mCallback) {
            assertOnStateLocked(STATE_INITIAL | STATE_WAITING_FILL_RESPONSE_AUTH_RESPONSE
                    | STATE_WAITING_DATASET_AUTH_RESPONSE);

            try {
                mCallback.showError(message);
                setFinalStateLocked(STATE_FINISHED_FAILURE);
            } catch (RemoteException e) {
                setFinalStateLocked(STATE_FINISHED_ERROR);
                e.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Notifies the Android System when the user authenticated a {@link FillResponse} previously
     * passed to {@link #onSuccess(FillResponse)}.
     *
     * @param flags must contain either
     * {@link android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_ERROR} or
     * {@link android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_SUCCESS}.
     */
    public void onFillResponseAuthentication(int flags) {
        if (DEBUG) Log.d(TAG, "onFillResponseAuthentication(): flags=" + flags);

        synchronized (mCallback) {
            assertOnStateLocked(STATE_WAITING_FILL_RESPONSE_AUTH_RESPONSE);

            try {
                mCallback.unlockFillResponse(flags);
                setFinalStateLocked(STATE_FINISHED_AUTHENTICATED);
            } catch (RemoteException e) {
                setFinalStateLocked(STATE_FINISHED_ERROR);
                e.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Notifies the Android System when the user authenticated a {@link Dataset} previously passed
     * to {@link #onSuccess(FillResponse)}.
     *
     * @param dataset values to fill the activity with in case of successful authentication of a
     * previously locked (and empty) dataset).
     * @param flags must contain either
     * {@link android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_ERROR} or
     * {@link android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_SUCCESS}.
     */
    public void onDatasetAuthentication(@Nullable Dataset dataset, int flags) {
        if (DEBUG) Log.d(TAG, "onDatasetAuthentication(): dataset=" + dataset + ", flags=" + flags);

        synchronized (mCallback) {
            assertOnStateLocked(STATE_WAITING_DATASET_AUTH_RESPONSE);

            try {
                mCallback.unlockDataset(dataset, flags);
                setFinalStateLocked(STATE_FINISHED_AUTHENTICATED);
            } catch (RemoteException e) {
                setFinalStateLocked(STATE_FINISHED_ERROR);
                e.rethrowAsRuntimeException();
            }
        }
    }

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        return "FillCallback: [mState = " + mState + "]";
    }

    /** @hide */
    @Override
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("FillCallback: mState="); pw.println(mState);
    }

    /** @hide */
    @Override
    public void setFinalizer(Finalizer f) {
        synchronized (mCallback) {
            mFinalizer = f;
        }
    }

    /**
     * Sets a final state (where the callback cannot be used anymore) and notifies the
     * {@link Finalizer} (if any).
     */
    private void setFinalStateLocked(int state) {
        if (DEBUG) Log.d(TAG, "setFinalState(): " + state);
        mState = state;

        if (mFinalizer != null) {
            mFinalizer.gone();
        }
    }

    // TODO(b/33197203): move and/or re-add state check logic on server side to avoid malicious app
    // calling the callback on wrong state.

    // Make sure callback method is called during the proper lifecycle state.
    private void assertOnStateLocked(int flags) {
        if (DEBUG) Log.d(TAG, "assertOnState(): current=" + mState + ", required=" + flags);

        Preconditions.checkState((flags & mState) != 0,
                "invalid state: required " + flagsToString(FillCallback.class, "STATE_", flags)
                + ", current is " + flagsToString(FillCallback.class, "STATE_", mState));
    }
}
