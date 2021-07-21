/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;

import com.android.internal.annotations.GuardedBy;
import com.android.server.autofill.ui.InlineFillUi;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Controls the interaction with the IME for the {@link AutofillInlineSuggestionsRequestSession}s.
 *
 * <p>The class maintains the inline suggestion session with the autofill service. There is at most
 * one active inline suggestion session at any given  corresponding to one focused view. New
 * sessions are created only when {@link #onCreateInlineSuggestionsRequestLocked} is called.</p>
 *
 * <p>The class manages the interaction between the {@link com.android.server.autofill.Session} and
 * the inline suggestion session whenever inline suggestions can be provided. All calls to the
 * inline suggestion session must be made through this controller.</p>
 */
final class AutofillInlineSessionController {
    @NonNull
    private final InputMethodManagerInternal mInputMethodManagerInternal;
    private final int mUserId;
    @NonNull
    private final ComponentName mComponentName;
    @NonNull
    private final Object mLock;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final InlineFillUi.InlineUiEventCallback mUiCallback;

    @Nullable
    @GuardedBy("mLock")
    private AutofillInlineSuggestionsRequestSession mSession;
    @Nullable
    @GuardedBy("mLock")
    private InlineFillUi mInlineFillUi;

    AutofillInlineSessionController(InputMethodManagerInternal inputMethodManagerInternal,
            int userId, ComponentName componentName, Handler handler, Object lock,
            InlineFillUi.InlineUiEventCallback callback) {
        mInputMethodManagerInternal = inputMethodManagerInternal;
        mUserId = userId;
        mComponentName = componentName;
        mHandler = handler;
        mLock = lock;
        mUiCallback = callback;
    }

    /**
     * Requests the IME to create an {@link InlineSuggestionsRequest} for {@code autofillId}.
     *
     * @param autofillId      the Id of the field for which the request is for.
     * @param requestConsumer the callback to be invoked when the IME responds. Note that this is
     *                        never invoked if the IME doesn't respond.
     */
    @GuardedBy("mLock")
    void onCreateInlineSuggestionsRequestLocked(@NonNull AutofillId autofillId,
            @NonNull Consumer<InlineSuggestionsRequest> requestConsumer, @NonNull Bundle uiExtras) {
        if (mSession != null) {
            // Destroy the existing session.
            mSession.destroySessionLocked();
        }
        mInlineFillUi = null;
        // TODO(b/151123764): consider reusing the same AutofillInlineSession object for the
        // same field.
        mSession = new AutofillInlineSuggestionsRequestSession(mInputMethodManagerInternal, mUserId,
                mComponentName, mHandler, mLock, autofillId, requestConsumer, uiExtras,
                mUiCallback);
        mSession.onCreateInlineSuggestionsRequestLocked();
    }

    /**
     * Destroys the current session. May send an empty response to IME to clear the suggestions if
     * the focus didn't change to a different field.
     *
     * @param autofillId the currently focused view from the autofill session
     */
    @GuardedBy("mLock")
    void destroyLocked(@NonNull AutofillId autofillId) {
        if (mSession != null) {
            mSession.onInlineSuggestionsResponseLocked(InlineFillUi.emptyUi(autofillId));
            mSession.destroySessionLocked();
            mSession = null;
        }
        mInlineFillUi = null;
    }

    /**
     * Returns the {@link InlineSuggestionsRequest} provided by IME for the last request.
     *
     * <p> The caller is responsible for making sure Autofill hears back from IME before calling
     * this method, using the {@code requestConsumer} provided when calling {@link
     * #onCreateInlineSuggestionsRequestLocked(AutofillId, Consumer, Bundle)}.
     */
    @GuardedBy("mLock")
    Optional<InlineSuggestionsRequest> getInlineSuggestionsRequestLocked() {
        if (mSession != null) {
            return mSession.getInlineSuggestionsRequestLocked();
        }
        return Optional.empty();
    }

    /**
     * Requests the IME to hide the current suggestions, if any. Returns true if the message is sent
     * to the IME. This only hides the UI temporarily. For example if user starts typing/deleting
     * characters, new filterText will kick in and may revive the suggestion UI.
     */
    @GuardedBy("mLock")
    boolean hideInlineSuggestionsUiLocked(@NonNull AutofillId autofillId) {
        if (mSession != null) {
            return mSession.onInlineSuggestionsResponseLocked(InlineFillUi.emptyUi(autofillId));
        }
        return false;
    }

    /**
     * Disables prefix/regex based filtering. Other filtering rules (see {@link
     * android.service.autofill.Dataset}) still apply.
     */
    @GuardedBy("mLock")
    void disableFilterMatching(@NonNull AutofillId autofillId) {
        if (mInlineFillUi != null && mInlineFillUi.getAutofillId().equals(autofillId)) {
            mInlineFillUi.disableFilterMatching();
        }
    }

    /**
     * Clear the locally cached inline fill UI, but don't clear the suggestion in the IME.
     *
     * <p>This is called to invalid the locally cached inline suggestions so we don't resend them
     * to the IME, while assuming that the IME will clean up suggestion on their own when the input
     * connection is finished. We don't send an empty response to IME so that it doesn't cause UI
     * flicker on the IME side if it arrives before the input view is finished on the IME.
     */
    @GuardedBy("mLock")
    void resetInlineFillUiLocked() {
        mInlineFillUi = null;
        if (mSession != null) {
            mSession.resetInlineFillUiLocked();
        }
    }

    /**
     * Updates the inline fill UI with the filter text. It'll send updated inline suggestions to
     * the IME.
     */
    @GuardedBy("mLock")
    boolean filterInlineFillUiLocked(@NonNull AutofillId autofillId, @Nullable String filterText) {
        if (mInlineFillUi != null && mInlineFillUi.getAutofillId().equals(autofillId)) {
            mInlineFillUi.setFilterText(filterText);
            return requestImeToShowInlineSuggestionsLocked();
        }
        return false;
    }

    /**
     * Set the current inline fill UI. It'll request the IME to show the inline suggestions when
     * the IME becomes visible and is focused on the {@code autofillId}.
     *
     * @return false if the suggestions are not sent to IME because there is no session, or if the
     * IME callback is not available in the session.
     */
    @GuardedBy("mLock")
    boolean setInlineFillUiLocked(@NonNull InlineFillUi inlineFillUi) {
        mInlineFillUi = inlineFillUi;
        return requestImeToShowInlineSuggestionsLocked();
    }

    /**
     * Sends the suggestions from the current inline fill UI to the IME.
     *
     * @return false if the suggestions are not sent to IME because there is no session, or if the
     * IME callback is not available in the session.
     */
    @GuardedBy("mLock")
    private boolean requestImeToShowInlineSuggestionsLocked() {
        if (mSession != null && mInlineFillUi != null) {
            return mSession.onInlineSuggestionsResponseLocked(mInlineFillUi);
        }
        return false;
    }
}
