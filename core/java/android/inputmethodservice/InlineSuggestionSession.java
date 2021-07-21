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

package android.inputmethodservice;

import static android.inputmethodservice.InputMethodService.DEBUG;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.BinderThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.IInlineSuggestionsResponseCallback;
import com.android.internal.view.InlineSuggestionsRequestInfo;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Maintains an inline suggestion session with the autofill manager service.
 *
 * <p> Each session correspond to one request from the Autofill manager service to create an
 * {@link InlineSuggestionsRequest}. It's responsible for calling back to the Autofill manager
 * service with {@link InlineSuggestionsRequest} and receiving {@link InlineSuggestionsResponse}
 * from it.
 * <p>
 * TODO(b/151123764): currently the session may receive responses for different views on the same
 * screen, but we will fix it so each session corresponds to one view.
 *
 * <p> All the methods are expected to be called from the main thread, to ensure thread safety.
 */
class InlineSuggestionSession {
    private static final String TAG = "ImsInlineSuggestionSession";

    static final InlineSuggestionsResponse EMPTY_RESPONSE = new InlineSuggestionsResponse(
            Collections.emptyList());

    @NonNull
    private final Handler mMainThreadHandler;
    @NonNull
    private final InlineSuggestionSessionController mInlineSuggestionSessionController;
    @NonNull
    private final InlineSuggestionsRequestInfo mRequestInfo;
    @NonNull
    private final IInlineSuggestionsRequestCallback mCallback;
    @NonNull
    private final Function<Bundle, InlineSuggestionsRequest> mRequestSupplier;
    @NonNull
    private final Supplier<IBinder> mHostInputTokenSupplier;
    @NonNull
    private final Consumer<InlineSuggestionsResponse> mResponseConsumer;
    // Indicate whether the previous call to the mResponseConsumer is empty or not. If it hasn't
    // been called yet, the value would be null.
    @Nullable
    private Boolean mPreviousResponseIsEmpty;


    /**
     * Indicates whether {@link #makeInlineSuggestionRequestUncheck()} has been called or not,
     * because it should only be called at most once.
     */
    @Nullable
    private boolean mCallbackInvoked = false;
    @Nullable
    private InlineSuggestionsResponseCallbackImpl mResponseCallback;

    InlineSuggestionSession(@NonNull InlineSuggestionsRequestInfo requestInfo,
            @NonNull IInlineSuggestionsRequestCallback callback,
            @NonNull Function<Bundle, InlineSuggestionsRequest> requestSupplier,
            @NonNull Supplier<IBinder> hostInputTokenSupplier,
            @NonNull Consumer<InlineSuggestionsResponse> responseConsumer,
            @NonNull InlineSuggestionSessionController inlineSuggestionSessionController,
            @NonNull Handler mainThreadHandler) {
        mRequestInfo = requestInfo;
        mCallback = callback;
        mRequestSupplier = requestSupplier;
        mHostInputTokenSupplier = hostInputTokenSupplier;
        mResponseConsumer = responseConsumer;
        mInlineSuggestionSessionController = inlineSuggestionSessionController;
        mMainThreadHandler = mainThreadHandler;
    }

    @MainThread
    InlineSuggestionsRequestInfo getRequestInfo() {
        return mRequestInfo;
    }

    @MainThread
    IInlineSuggestionsRequestCallback getRequestCallback() {
        return mCallback;
    }

    /**
     * Returns true if the session should send Ime status updates to Autofill.
     *
     * <p> The session only starts to send Ime status updates to Autofill after the sending back
     * an {@link InlineSuggestionsRequest}.
     */
    @MainThread
    boolean shouldSendImeStatus() {
        return mResponseCallback != null;
    }

    /**
     * Returns true if {@link #makeInlineSuggestionRequestUncheck()} is called. It doesn't not
     * necessarily mean an {@link InlineSuggestionsRequest} was sent, because it may call {@link
     * IInlineSuggestionsRequestCallback#onInlineSuggestionsUnsupported()}.
     *
     * <p> The callback should be invoked at most once for each session.
     */
    @MainThread
    boolean isCallbackInvoked() {
        return mCallbackInvoked;
    }

    /**
     * Invalidates the current session so it doesn't process any further {@link
     * InlineSuggestionsResponse} from Autofill.
     *
     * <p> This method should be called when the session is de-referenced from the {@link
     * InlineSuggestionSessionController}.
     */
    @MainThread
    void invalidate() {
        try {
            mCallback.onInlineSuggestionsSessionInvalidated();
        } catch (RemoteException e) {
            Log.w(TAG, "onInlineSuggestionsSessionInvalidated() remote exception", e);
        }
        if (mResponseCallback != null) {
            consumeInlineSuggestionsResponse(EMPTY_RESPONSE);
            mResponseCallback.invalidate();
            mResponseCallback = null;
        }
    }

    /**
     * Gets the {@link InlineSuggestionsRequest} from IME and send it back to the Autofill if it's
     * not null.
     *
     * <p>Calling this method implies that the input is started on the view corresponding to the
     * session.
     */
    @MainThread
    void makeInlineSuggestionRequestUncheck() {
        if (mCallbackInvoked) {
            return;
        }
        try {
            final InlineSuggestionsRequest request = mRequestSupplier.apply(
                    mRequestInfo.getUiExtras());
            if (request == null) {
                if (DEBUG) {
                    Log.d(TAG, "onCreateInlineSuggestionsRequest() returned null request");
                }
                mCallback.onInlineSuggestionsUnsupported();
            } else {
                request.setHostInputToken(mHostInputTokenSupplier.get());
                request.filterContentTypes();
                mResponseCallback = new InlineSuggestionsResponseCallbackImpl(this);
                mCallback.onInlineSuggestionsRequest(request, mResponseCallback);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "makeInlinedSuggestionsRequest() remote exception:" + e);
        }
        mCallbackInvoked = true;
    }

    @MainThread
    void handleOnInlineSuggestionsResponse(@NonNull AutofillId fieldId,
            @NonNull InlineSuggestionsResponse response) {
        if (!mInlineSuggestionSessionController.match(fieldId)) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "IME receives response: " + response.getInlineSuggestions().size());
        }
        consumeInlineSuggestionsResponse(response);
    }

    @MainThread
    void consumeInlineSuggestionsResponse(@NonNull InlineSuggestionsResponse response) {
        boolean isResponseEmpty = response.getInlineSuggestions().isEmpty();
        if (isResponseEmpty && Boolean.TRUE.equals(mPreviousResponseIsEmpty)) {
            // No-op if both the previous response and current response are empty.
            return;
        }
        mPreviousResponseIsEmpty = isResponseEmpty;
        mResponseConsumer.accept(response);
    }

    /**
     * Internal implementation of {@link IInlineSuggestionsResponseCallback}.
     */
    private static final class InlineSuggestionsResponseCallbackImpl extends
            IInlineSuggestionsResponseCallback.Stub {
        private final WeakReference<InlineSuggestionSession> mSession;
        private volatile boolean mInvalid = false;

        private InlineSuggestionsResponseCallbackImpl(InlineSuggestionSession session) {
            mSession = new WeakReference<>(session);
        }

        void invalidate() {
            mInvalid = true;
        }

        @BinderThread
        @Override
        public void onInlineSuggestionsResponse(AutofillId fieldId,
                InlineSuggestionsResponse response) {
            if (mInvalid) {
                return;
            }
            final InlineSuggestionSession session = mSession.get();
            if (session != null) {
                session.mMainThreadHandler.sendMessage(
                        obtainMessage(InlineSuggestionSession::handleOnInlineSuggestionsResponse,
                                session, fieldId, response));
            }
        }
    }
}
