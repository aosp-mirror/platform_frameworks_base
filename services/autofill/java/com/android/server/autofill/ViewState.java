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

import android.annotation.Nullable;
import android.graphics.Rect;
import android.service.autofill.FillResponse;
import android.util.DebugUtils;
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
    /** Set only in the View that started a session . */
    public static final int STATE_STARTED_SESSION = 0x10;

    public final AutofillId id;
    private final Listener mListener;
    private final Session mSession;
    private FillResponse mResponse;

    private AutofillValue mCurrentValue;
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

    void setResponse(FillResponse response) {
        mResponse = response;
    }

    FillResponse getResponse() {
        return mResponse;
    }

    CharSequence getServiceName() {
        return mSession.getServiceName();
    }

    boolean isChanged() {
        return (mState & STATE_CHANGED) != 0;
    }

    int getState() {
        return mState;
    }

    String getStateAsString() {
        return DebugUtils.flagsToString(ViewState.class, "STATE_", mState);
    }

    void setCurrentValue(AutofillValue value) {
        mCurrentValue = value;
    }

    void setState(int state) {
        // TODO(b/33197203 , b/35707731): currently it's always setting one state, but once it
        // supports partitioning it will need to 'or' some of them..
        mState = state;
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
        // First try the current response associated with this View.
        if (mResponse != null) {
            if (mResponse.getDatasets() != null) {
                mListener.onFillReady(mResponse, this.id, mCurrentValue);
            }
            return;
        }
        // Then checks if the session has a response waiting authentication; if so, uses it instead.
        final FillResponse currentResponse = mSession.getCurrentResponse();
        if (currentResponse.getAuthentication() != null) {
            mListener.onFillReady(currentResponse, this.id, mCurrentValue);
        }
    }

    @Override
    public String toString() {
        return "ViewState: [id=" + id + ", currentValue=" + mCurrentValue
                + ", bounds=" + mVirtualBounds + ", state=" + getStateAsString() +"]";
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("id:" ); pw.println(this.id);
        pw.print(prefix); pw.print("state:" ); pw.println(getStateAsString());
        pw.print(prefix); pw.print("has response:" ); pw.println(mResponse != null);
        pw.print(prefix); pw.print("currentValue:" ); pw.println(mCurrentValue);
        pw.print(prefix); pw.print("virtualBounds:" ); pw.println(mVirtualBounds);
    }
}