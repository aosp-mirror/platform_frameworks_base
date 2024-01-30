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

package com.android.server.autofill;

import static android.service.autofill.FillRequest.FLAG_MANUAL_REQUEST;
import static android.service.autofill.FillRequest.FLAG_VIEW_REQUESTS_CREDMAN_SERVICE;

import static com.android.server.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.service.autofill.FillResponse;
import android.util.DebugUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import java.io.PrintWriter;

/**
 * State for a given view with a AutofillId.
 *
 * <p>This class holds state about a view and calls its listener when the fill UI is ready to
 * be displayed for the view.
 */
final class ViewState {
    interface Listener {
        /**
         * Called when the fill UI is ready to be shown for this view.
         */
        void onFillReady(@NonNull FillResponse fillResponse, @NonNull AutofillId focusedId,
                @Nullable AutofillValue value, int flags);
    }

    private static final String TAG = "ViewState";

    /** Initial state. */
    public static final int STATE_INITIAL = 0x001;
    /** View id is present in a dataset returned by the service. */
    public static final int STATE_FILLABLE = 0x002;
    /** View was autofilled after user selected a dataset. */
    public static final int STATE_AUTOFILLED = 0x004;
    /** View value was changed, but not by the service. */
    public static final int STATE_CHANGED = 0x008;
    /** Set only in the View that started a session. */
    public static final int STATE_STARTED_SESSION = 0x010;
    /** View that started a new partition when focused on. */
    public static final int STATE_STARTED_PARTITION = 0x020;
    /** User select a dataset in this view, but service must authenticate first. */
    public static final int STATE_WAITING_DATASET_AUTH = 0x040;
    /** Service does not care about this view. */
    public static final int STATE_IGNORED = 0x080;
    /** User manually request autofill in this view, after it was already autofilled. */
    public static final int STATE_RESTARTED_SESSION = 0x100;
    /** View is the URL bar of a package on compat mode. */
    public  static final int STATE_URL_BAR = 0x200;
    /** View was asked to autofill but failed to do so. */
    public static final int STATE_AUTOFILL_FAILED = 0x400;
    /** View has been autofilled at least once. */
    public static final int STATE_AUTOFILLED_ONCE = 0x800;
    /** View triggered the latest augmented autofill request. */
    public static final int STATE_TRIGGERED_AUGMENTED_AUTOFILL = 0x1000;
    /** Inline suggestions were shown for this View. */
    public static final int STATE_INLINE_SHOWN = 0x2000;
    /** A character was removed from the View value (not by the service). */
    public static final int STATE_CHAR_REMOVED = 0x4000;
    /** Showing inline suggestions is not allowed for this View. */
    public static final int STATE_INLINE_DISABLED = 0x8000;
    /** The View is waiting for an inline suggestions request from IME.*/
    public static final int STATE_PENDING_CREATE_INLINE_REQUEST = 0x10000;
    /** Fill dialog were shown for this View. */
    public static final int STATE_FILL_DIALOG_SHOWN = 0x20000;

    public final AutofillId id;

    private final Listener mListener;

    private final boolean mIsPrimaryCredential;

    /**
     * There are two sources of fill response. The fill response from the session's remote fill
     * service and the fill response from the secondary provider handler. Primary Fill Response
     * stores the fill response from the session's remote fill service.
     */
    private FillResponse mPrimaryFillResponse;

    /**
     * Secondary fill response stores the fill response from the secondary provider handler. Based
     * on whether the user focuses on a credential view or an autofill view, the relevant fill
     * response will be used to show the autofill suggestions.
     */
    private FillResponse mSecondaryFillResponse;
    private AutofillValue mCurrentValue;
    private AutofillValue mAutofilledValue;
    private AutofillValue mSanitizedValue;
    private Rect mVirtualBounds;
    private int mState;
    private String mDatasetId;

    ViewState(AutofillId id, Listener listener, int state, boolean isPrimaryCredential) {
        this.id = id;
        mListener = listener;
        mState = state;
        mIsPrimaryCredential = isPrimaryCredential;
    }

    /**
     * Gets the boundaries of the virtual view, or {@code null} if the the view is not virtual.
     */
    @Nullable
    Rect getVirtualBounds() {
        return mVirtualBounds;
    }

    /**
     * Gets the current value of the view.
     */
    @Nullable
    AutofillValue getCurrentValue() {
        return mCurrentValue;
    }

    void setCurrentValue(AutofillValue value) {
        mCurrentValue = value;
    }

    @Nullable
    AutofillValue getAutofilledValue() {
        return mAutofilledValue;
    }

    void setAutofilledValue(@Nullable AutofillValue value) {
        mAutofilledValue = value;
    }

    @Nullable
    AutofillValue getSanitizedValue() {
        return mSanitizedValue;
    }

    void setSanitizedValue(@Nullable AutofillValue value) {
        mSanitizedValue = value;
    }

    @Nullable
    FillResponse getResponse() {
        return mPrimaryFillResponse;
    }

    void setResponse(FillResponse response) {
        setResponse(response, /* isPrimary= */ true);
    }

    void setResponse(@Nullable FillResponse response, boolean isPrimary) {
        if (isPrimary) {
            mPrimaryFillResponse = response;
        } else {
            mSecondaryFillResponse = response;
        }
    }

    int getState() {
        return mState;
    }

    String getStateAsString() {
        return getStateAsString(mState);
    }

    static String getStateAsString(int state) {
        return DebugUtils.flagsToString(ViewState.class, "STATE_", state);
    }

    void setState(int state) {
        if (mState == STATE_INITIAL) {
            mState = state;
        } else {
            mState |= state;
        }
        if (state == STATE_AUTOFILLED) {
            mState |= STATE_AUTOFILLED_ONCE;
        }
    }

    void resetState(int state) {
        mState &= ~state;
    }

    @Nullable
    String getDatasetId() {
        return mDatasetId;
    }

    void setDatasetId(String datasetId) {
        mDatasetId = datasetId;
    }

    // TODO: refactor / rename / document this method (and maybeCallOnFillReady) to make it clear
    // that it can change the value and update the UI; similarly, should replace code that
    // directly sets mAutofillValue to use encapsulation.
    void update(@Nullable AutofillValue autofillValue, @Nullable Rect virtualBounds, int flags) {
        if (autofillValue != null) {
            mCurrentValue = autofillValue;
        }
        if (virtualBounds != null) {
            mVirtualBounds = virtualBounds;
        }

        maybeCallOnFillReady(flags);
    }

    /**
     * Calls {@link
     * Listener#onFillReady(FillResponse, AutofillId, AutofillValue, int)} if the
     * fill UI is ready to be displayed (i.e. when response and bounds are set).
     */
    void maybeCallOnFillReady(int flags) {
        if ((mState & STATE_AUTOFILLED) != 0 && (flags & FLAG_MANUAL_REQUEST) == 0) {
            if (sDebug) Slog.d(TAG, "Ignoring UI for " + id + " on " + getStateAsString());
            return;
        }
        // First try the current response associated with this View.
        FillResponse requestedResponse = requestingPrimaryResponse(flags)
                ? mPrimaryFillResponse : mSecondaryFillResponse;
        if (requestedResponse != null) {
            if (requestedResponse.getDatasets() != null
                    || requestedResponse.getAuthentication() != null) {
                mListener.onFillReady(requestedResponse, this.id, mCurrentValue, flags);
            }
        }
    }

    private boolean requestingPrimaryResponse(int flags) {
        if (mIsPrimaryCredential) {
            return (flags & FLAG_VIEW_REQUESTS_CREDMAN_SERVICE) != 0;
        } else {
            return (flags & FLAG_VIEW_REQUESTS_CREDMAN_SERVICE) == 0;
        }
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("ViewState: [id=").append(id);
        if (mDatasetId != null) {
            builder.append(", datasetId:" ).append(mDatasetId);
        }
        builder.append(", state:").append(getStateAsString());
        if (mCurrentValue != null) {
            builder.append(", currentValue:" ).append(mCurrentValue);
        }
        if (mAutofilledValue != null) {
            builder.append(", autofilledValue:" ).append(mAutofilledValue);
        }
        if (mSanitizedValue != null) {
            builder.append(", sanitizedValue:" ).append(mSanitizedValue);
        }
        if (mVirtualBounds != null) {
            builder.append(", virtualBounds:" ).append(mVirtualBounds);
        }
        builder.append("]");
        return builder.toString();
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("id:" ); pw.println(id);
        if (mDatasetId != null) {
            pw.print(prefix); pw.print("datasetId:" ); pw.println(mDatasetId);
        }
        pw.print(prefix); pw.print("state:" ); pw.println(getStateAsString());
        pw.print(prefix); pw.print("is primary credential:"); pw.println(mIsPrimaryCredential);
        if (mPrimaryFillResponse != null) {
            pw.print(prefix); pw.print("primary response id:");
            pw.println(mPrimaryFillResponse.getRequestId());
        }
        if (mSecondaryFillResponse != null) {
            pw.print(prefix); pw.print("secondary response id:");
            pw.println(mSecondaryFillResponse.getRequestId());
        }
        if (mCurrentValue != null) {
            pw.print(prefix); pw.print("currentValue:" ); pw.println(mCurrentValue);
        }
        if (mAutofilledValue != null) {
            pw.print(prefix); pw.print("autofilledValue:" ); pw.println(mAutofilledValue);
        }
        if (mSanitizedValue != null) {
            pw.print(prefix); pw.print("sanitizedValue:" ); pw.println(mSanitizedValue);
        }
        if (mVirtualBounds != null) {
            pw.print(prefix); pw.print("virtualBounds:" ); pw.println(mVirtualBounds);
        }
    }
}