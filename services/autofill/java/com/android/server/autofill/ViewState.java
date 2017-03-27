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
import android.content.Intent;
import android.graphics.Rect;
import android.service.autofill.FillResponse;
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

    final AutofillId mId;
    private final Listener mListener;
    // TODO(b/33197203): would not need a reference to response and session if it was an inner
    // class of Session...
    private final Session mSession;
    private FillResponse mResponse;
    private Intent mAuthIntent;

    // TODO(b/33197203): encapsulate access so it's not called by UI
    AutofillValue mAutofillValue;

    // TODO(b/33197203): encapsulate access so it's not called by UI
    // Bounds if a virtual view, null otherwise
    Rect mVirtualBounds;

    boolean mValueUpdated;

    ViewState(Session session, AutofillId id, Listener listener) {
        mSession = session;
        mId = id;
        mListener = listener;
    }

    /**
     * Response should only be set once.
     */
    void setResponse(FillResponse response) {
        mResponse = response;
        maybeCallOnFillReady();
    }

    /**
     * Used when a {@link FillResponse} requires authentication to be unlocked.
     */
    void setResponse(FillResponse response, Intent authIntent) {
        mAuthIntent = authIntent;
        setResponse(response);
    }

    CharSequence getServiceName() {
        return mSession.getServiceName();
    }

    // TODO(b/33197203): need to refactor / rename / document this method to make it clear that
    // it can change  the value and update the UI; similarly, should replace code that
    // directly sets mAutoFilLValue to use encapsulation.
    void update(@Nullable AutofillValue autofillValue, @Nullable Rect virtualBounds) {
        if (autofillValue != null) {
            mAutofillValue = autofillValue;
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
        if (mResponse != null && (mResponse.getAuthentication() != null
                || mResponse.getDatasets() != null)) {
            mListener.onFillReady(mResponse, mId, mAutofillValue);
        }
    }

    @Override
    public String toString() {
        return "ViewState: [id=" + mId + ", value=" + mAutofillValue + ", bounds=" + mVirtualBounds
                + ", updated = " + mValueUpdated + "]";
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("id:" ); pw.println(mId);
        pw.print(prefix); pw.print("value:" ); pw.println(mAutofillValue);
        pw.print(prefix); pw.print("updated:" ); pw.println(mValueUpdated);
        pw.print(prefix); pw.print("virtualBounds:" ); pw.println(mVirtualBounds);
        pw.print(prefix); pw.print("authIntent:" ); pw.println(mAuthIntent);
    }
}