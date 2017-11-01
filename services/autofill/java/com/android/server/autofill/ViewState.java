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
import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

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
        void onFillReady(FillResponse fillResponse, AutofillId focusedId,
                @Nullable AutofillValue value);
    }

    private static final String TAG = "ViewState";

    // NOTE: state constants must be public because of flagstoString().
    public static final int STATE_UNKNOWN = 0x000;
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

    public final AutofillId id;

    private final Listener mListener;
    private final Session mSession;

    private FillResponse mResponse;
    private AutofillValue mCurrentValue;
    private AutofillValue mAutofilledValue;
    private Rect mVirtualBounds;
    private int mState;

    ViewState(Session session, AutofillId id, Listener listener, int state) {
        mSession = session;
        this.id = id;
        mListener = listener;
        mState = state;
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
    FillResponse getResponse() {
        return mResponse;
    }

    void setResponse(FillResponse response) {
        mResponse = response;
    }

    CharSequence getServiceName() {
        return mSession.getServiceName();
    }

    int getState() {
        return mState;
    }

    String getStateAsString() {
        return DebugUtils.flagsToString(ViewState.class, "STATE_", mState);
    }

    void setState(int state) {
        if (mState == STATE_INITIAL) {
            mState = state;
        } else {
            mState |= state;
        }
    }

    void resetState(int state) {
        mState &= ~state;
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
     * Listener#onFillReady(FillResponse, AutofillId, AutofillValue)} if the
     * fill UI is ready to be displayed (i.e. when response and bounds are set).
     */
    void maybeCallOnFillReady(int flags) {
        if ((mState & STATE_AUTOFILLED) != 0 && (flags & FLAG_MANUAL_REQUEST) == 0) {
            if (sDebug) Slog.d(TAG, "Ignoring UI for " + id + " on " + getStateAsString());
            return;
        }
        // First try the current response associated with this View.
        if (mResponse != null) {
            if (mResponse.getDatasets() != null || mResponse.getAuthentication() != null) {
                mListener.onFillReady(mResponse, this.id, mCurrentValue);
            }
        }
    }

    @Override
    public String toString() {
        return "ViewState: [id=" + id + ", currentValue=" + mCurrentValue
                + ", autofilledValue=" + mAutofilledValue
                + ", bounds=" + mVirtualBounds + ", state=" + getStateAsString() + "]";
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("id:" ); pw.println(this.id);
        pw.print(prefix); pw.print("state:" ); pw.println(getStateAsString());
        pw.print(prefix); pw.print("response:");
        if (mResponse == null) {
            pw.println("N/A");
        } else {
            if (sVerbose) {
                pw.println(mResponse);
            } else {
                pw.println(mResponse.getRequestId());
            }
        }
        pw.print(prefix); pw.print("currentValue:" ); pw.println(mCurrentValue);
        pw.print(prefix); pw.print("autofilledValue:" ); pw.println(mAutofilledValue);
        pw.print(prefix); pw.print("virtualBounds:" ); pw.println(mVirtualBounds);
    }
}