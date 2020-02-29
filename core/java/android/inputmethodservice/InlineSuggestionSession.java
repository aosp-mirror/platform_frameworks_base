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

import android.annotation.NonNull;
import android.content.ComponentName;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.IInlineSuggestionsResponseCallback;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Maintains an active inline suggestion session.
 *
 * <p>
 * Each session corresponds to one inline suggestion request, but there may be multiple callbacks
 * with the inline suggestions response.
 */
class InlineSuggestionSession {

    private static final String TAG = InlineSuggestionSession.class.getSimpleName();

    private final Handler mHandler = new Handler(Looper.getMainLooper(), null, true);

    @NonNull
    private final ComponentName mComponentName;
    @NonNull
    private final IInlineSuggestionsRequestCallback mCallback;
    @NonNull
    private final InlineSuggestionsResponseCallbackImpl mResponseCallback;
    @NonNull
    private final Supplier<String> mClientPackageNameSupplier;
    @NonNull
    private final Supplier<InlineSuggestionsRequest> mRequestSupplier;
    @NonNull
    private final Supplier<IBinder> mHostInputTokenSupplier;
    @NonNull
    private final Consumer<InlineSuggestionsResponse> mResponseConsumer;

    private volatile boolean mInvalidated = false;

    InlineSuggestionSession(@NonNull ComponentName componentName,
            @NonNull IInlineSuggestionsRequestCallback callback,
            @NonNull Supplier<String> clientPackageNameSupplier,
            @NonNull Supplier<InlineSuggestionsRequest> requestSupplier,
            @NonNull Supplier<IBinder> hostInputTokenSupplier,
            @NonNull Consumer<InlineSuggestionsResponse> responseConsumer) {
        mComponentName = componentName;
        mCallback = callback;
        mResponseCallback = new InlineSuggestionsResponseCallbackImpl(this);
        mClientPackageNameSupplier = clientPackageNameSupplier;
        mRequestSupplier = requestSupplier;
        mHostInputTokenSupplier = hostInputTokenSupplier;
        mResponseConsumer = responseConsumer;

        makeInlineSuggestionsRequest();
    }

    /**
     * This needs to be called before creating a new session, such that the later response callbacks
     * will be discarded.
     */
    void invalidateSession() {
        mInvalidated = true;
    }

    /**
     * Sends an {@link InlineSuggestionsRequest} obtained from {@cocde supplier} to the current
     * Autofill Session through
     * {@link IInlineSuggestionsRequestCallback#onInlineSuggestionsRequest}.
     */
    private void makeInlineSuggestionsRequest() {
        try {
            final InlineSuggestionsRequest request = mRequestSupplier.get();
            if (request == null) {
                if (DEBUG) {
                    Log.d(TAG, "onCreateInlineSuggestionsRequest() returned null request");
                }
                mCallback.onInlineSuggestionsUnsupported();
            } else {
                request.setHostInputToken(mHostInputTokenSupplier.get());
                mCallback.onInlineSuggestionsRequest(request, mResponseCallback);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "makeInlinedSuggestionsRequest() remote exception:" + e);
        }
    }

    private void handleOnInlineSuggestionsResponse(@NonNull InlineSuggestionsResponse response) {
        if (mInvalidated) {
            if (DEBUG) {
                Log.d(TAG, "handleOnInlineSuggestionsResponse() called on invalid session");
            }
            return;
        }
        // TODO(b/149522488): checking the current focused input field to make sure we don't send
        //  inline responses for previous input field
        if (!mComponentName.getPackageName().equals(mClientPackageNameSupplier.get())) {
            if (DEBUG) {
                Log.d(TAG, "handleOnInlineSuggestionsResponse() called on the wrong package name");
            }
            return;
        }
        mResponseConsumer.accept(response);
    }

    /**
     * Internal implementation of {@link IInlineSuggestionsResponseCallback}.
     */
    static final class InlineSuggestionsResponseCallbackImpl
            extends IInlineSuggestionsResponseCallback.Stub {
        private final WeakReference<InlineSuggestionSession> mInlineSuggestionSession;

        private InlineSuggestionsResponseCallbackImpl(
                InlineSuggestionSession inlineSuggestionSession) {
            mInlineSuggestionSession = new WeakReference<>(inlineSuggestionSession);
        }

        @Override
        public void onInlineSuggestionsResponse(AutofillId fieldId,
                InlineSuggestionsResponse response) {
            final InlineSuggestionSession session = mInlineSuggestionSession.get();
            if (session != null) {
                session.mHandler.sendMessage(obtainMessage(
                        InlineSuggestionSession::handleOnInlineSuggestionsResponse, session,
                        response));
            }
        }
    }
}
