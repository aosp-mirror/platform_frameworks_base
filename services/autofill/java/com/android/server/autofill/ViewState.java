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

import static com.android.server.autofill.Helper.DEBUG;

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
    public static final int STATE_UNKNOWN = 0x00;
    /** Initial state. */
    public static final int STATE_INITIAL = 0x01;
    /** View id is present in a dataset returned by the service. */
    public static final int STATE_FILLABLE = 0x02;
    /** View was autofilled after user selected a dataset. */
    public static final int STATE_AUTOFILLED = 0x04;
    /** View value was changed, but not by the service. */
    public static final int STATE_CHANGED = 0x08;
    /** Set only in the View that started a session. */
    public static final int STATE_STARTED_SESSION = 0x10;
    /** View that started a new partition when focused on. */
    public static final int STATE_STARTED_PARTITION = 0x20;
    /** User select a dataset in this view, but service must authenticate first. */
    public static final int STATE_WAITING_DATASET_AUTH = 0x40;

    public final AutofillId id;
    private final Listener mListener;
    private final Session mSession;
    private FillResponse mResponse;

    private AutofillValue mInitialValue;
    private AutofillValue mCurrentValue;
    private AutofillValue mAutofilledValue;
    private Rect mVirtualBounds;

    private int mState;

    ViewState(Session session, AutofillId id, AutofillValue value, Listener listener, int state) {
        mSession = session;
        this.id = id;
        mInitialValue = value;
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

    void setAutofilledValue(AutofillValue value) {
        mAutofilledValue = value;
    }

    @Nullable
    AutofillValue getInitialValue() {
        return mInitialValue;
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

    // TODO(b/33197203): need to refactor / rename / document this method to make it clear that
    // it can change  the value and update the UI; similarly, should replace code that
    // directly sets mAutoFilLValue to use encapsulation.
    void update(@Nullable AutofillValue autofillValue, @Nullable Rect virtualBounds) {
        if (autofillValue != null) {
            mCurrentValue = autofillValue;
        }
        if (virtualBounds != null) {
            mVirtualBounds = virtualBounds;
        }

        maybeCallOnFillReady();
    }

    /**
     * Calls {@link
     * Listener#onFillReady(FillResponse, AutofillId, AutofillValue)} if the
     * fill UI is ready to be displayed (i.e. when response and bounds are set).
     */
    void maybeCallOnFillReady() {
        if ((mState & (STATE_AUTOFILLED | STATE_WAITING_DATASET_AUTH)) != 0) {
            if (DEBUG) {
                Slog.d(TAG, "Ignoring UI for " + id + " on " + getStateAsString());
            }
            return;
        }
        // First try the current response associated with this View.
        if (mResponse != null) {
            if (mResponse.getDatasets() != null) {
                mListener.onFillReady(mResponse, this.id, mCurrentValue);
            }
            return;
        }
        // Then checks if the session has a response waiting authentication; if so, uses it instead.
        final FillResponse responseWaitingAuth = mSession.getResponseWaitingAuth();
        if (responseWaitingAuth != null) {
            mListener.onFillReady(responseWaitingAuth, this.id, mCurrentValue);
        }
    }

    @Override
    public String toString() {
        return "ViewState: [id=" + id + ", initialValue=" + mInitialValue
                + ", currentValue=" + mCurrentValue + ", autofilledValue=" + mAutofilledValue
                + ", bounds=" + mVirtualBounds + ", state=" + getStateAsString() + "]";
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("id:" ); pw.println(this.id);
        pw.print(prefix); pw.print("state:" ); pw.println(getStateAsString());
        pw.print(prefix); pw.print("has response:" ); pw.println(mResponse != null);
        pw.print(prefix); pw.print("initialValue:" ); pw.println(mInitialValue);
        pw.print(prefix); pw.print("currentValue:" ); pw.println(mCurrentValue);
        pw.print(prefix); pw.print("autofilledValue:" ); pw.println(mAutofilledValue);
        pw.print(prefix); pw.print("virtualBounds:" ); pw.println(mVirtualBounds);
    }
}