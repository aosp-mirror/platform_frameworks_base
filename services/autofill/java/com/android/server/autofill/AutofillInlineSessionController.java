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
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.annotations.GuardedBy;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.util.Collections;
import java.util.Optional;
import java.util.function.Consumer;


/**
 * Controls the interaction with the IME for the inline suggestion sessions.
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

    @GuardedBy("mLock")
    private AutofillInlineSuggestionsRequestSession mSession;

    AutofillInlineSessionController(InputMethodManagerInternal inputMethodManagerInternal,
            int userId, ComponentName componentName, Handler handler, Object lock) {
        mInputMethodManagerInternal = inputMethodManagerInternal;
        mUserId = userId;
        mComponentName = componentName;
        mHandler = handler;
        mLock = lock;
    }


    /**
     * Requests the IME to create an {@link InlineSuggestionsRequest} for {@code autofillId}.
     *
     * @param autofillId      the Id of the field for which the request is for.
     * @param requestConsumer the callback which will be invoked when IME responded or if it times
     *                        out waiting for IME response.
     */
    @GuardedBy("mLock")
    void onCreateInlineSuggestionsRequestLocked(@NonNull AutofillId autofillId,
            @NonNull Consumer<InlineSuggestionsRequest> requestConsumer, @NonNull Bundle uiExtras) {
        // TODO(b/151123764): rename the method to better reflect what it does.
        if (mSession != null) {
            // Send an empty response to IME and destroy the existing session.
            mSession.onInlineSuggestionsResponseLocked(mSession.getAutofillIdLocked(),
                    new InlineSuggestionsResponse(Collections.EMPTY_LIST));
            mSession.destroySessionLocked();
        }
        // TODO(b/151123764): consider reusing the same AutofillInlineSession object for the
        // same field.
        mSession = new AutofillInlineSuggestionsRequestSession(mInputMethodManagerInternal, mUserId,
                mComponentName, mHandler, mLock, autofillId, requestConsumer, uiExtras);
        mSession.onCreateInlineSuggestionsRequestLocked();

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
     * to the IME.
     */
    @GuardedBy("mLock")
    boolean hideInlineSuggestionsUiLocked(@NonNull AutofillId autofillId) {
        if (mSession != null) {
            return mSession.onInlineSuggestionsResponseLocked(autofillId,
                    new InlineSuggestionsResponse(Collections.EMPTY_LIST));
        }
        return false;
    }

    /**
     * Requests showing the inline suggestion in the IME when the IME becomes visible and is focused
     * on the {@code autofillId}.
     *
     * @return false if there is no session, or if the IME callback is not available in the session.
     */
    @GuardedBy("mLock")
    boolean onInlineSuggestionsResponseLocked(@NonNull AutofillId autofillId,
            @NonNull InlineSuggestionsResponse inlineSuggestionsResponse) {
        // TODO(b/151123764): rename the method to better reflect what it does.
        if (mSession != null) {
            return mSession.onInlineSuggestionsResponseLocked(autofillId,
                    inlineSuggestionsResponse);
        }
        return false;
    }
}
