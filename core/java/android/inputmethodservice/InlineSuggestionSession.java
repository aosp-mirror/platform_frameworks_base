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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.IInlineSuggestionsResponseCallback;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Maintains an active inline suggestion session with the autofill manager service.
 *
 * <p>
 * Each session corresponds to one {@link InlineSuggestionsRequest} and one {@link
 * IInlineSuggestionsResponseCallback}, but there may be multiple invocations of the response
 * callback for the same field or different fields in the same component.
 *
 * <p>
 * The data flow from IMS point of view is:
 * Before calling {@link InputMethodService#onStartInputView(EditorInfo, boolean)}, call the {@link
 * #notifyOnStartInputView(AutofillId)}
 * ->
 * [async] {@link IInlineSuggestionsRequestCallback#onInputMethodStartInputView(AutofillId)}
 * --- process boundary ---
 * ->
 * {@link com.android.server.inputmethod.InputMethodManagerService
 * .InlineSuggestionsRequestCallbackDecorator#onInputMethodStartInputView(AutofillId)}
 * ->
 * {@link com.android.server.autofill.InlineSuggestionSession
 * .InlineSuggestionsRequestCallbackImpl#onInputMethodStartInputView(AutofillId)}
 *
 * <p>
 * The data flow for {@link #notifyOnFinishInputView(AutofillId)} is similar.
 */
class InlineSuggestionSession {

    private static final String TAG = "ImsInlineSuggestionSession";

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
    private final Supplier<AutofillId> mClientAutofillIdSupplier;
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
            @NonNull Supplier<AutofillId> clientAutofillIdSupplier,
            @NonNull Supplier<InlineSuggestionsRequest> requestSupplier,
            @NonNull Supplier<IBinder> hostInputTokenSupplier,
            @NonNull Consumer<InlineSuggestionsResponse> responseConsumer,
            boolean inputViewStarted) {
        mComponentName = componentName;
        mCallback = callback;
        mResponseCallback = new InlineSuggestionsResponseCallbackImpl(this);
        mClientPackageNameSupplier = clientPackageNameSupplier;
        mClientAutofillIdSupplier = clientAutofillIdSupplier;
        mRequestSupplier = requestSupplier;
        mHostInputTokenSupplier = hostInputTokenSupplier;
        mResponseConsumer = responseConsumer;

        makeInlineSuggestionsRequest(inputViewStarted);
    }

    void notifyOnStartInputView(AutofillId imeFieldId) {
        if (DEBUG) Log.d(TAG, "notifyOnStartInputView");
        try {
            mCallback.onInputMethodStartInputView(imeFieldId);
        } catch (RemoteException e) {
            Log.w(TAG, "onInputMethodStartInputView() remote exception:" + e);
        }
    }

    void notifyOnFinishInputView(AutofillId imeFieldId) {
        if (DEBUG) Log.d(TAG, "notifyOnFinishInputView");
        try {
            mCallback.onInputMethodFinishInputView(imeFieldId);
        } catch (RemoteException e) {
            Log.w(TAG, "onInputMethodFinishInputView() remote exception:" + e);
        }
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
    private void makeInlineSuggestionsRequest(boolean inputViewStarted) {
        try {
            final InlineSuggestionsRequest request = mRequestSupplier.get();
            if (request == null) {
                if (DEBUG) {
                    Log.d(TAG, "onCreateInlineSuggestionsRequest() returned null request");
                }
                mCallback.onInlineSuggestionsUnsupported();
            } else {
                request.setHostInputToken(mHostInputTokenSupplier.get());
                mCallback.onInlineSuggestionsRequest(request, mResponseCallback,
                        mClientAutofillIdSupplier.get(), inputViewStarted);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "makeInlinedSuggestionsRequest() remote exception:" + e);
        }
    }

    private void handleOnInlineSuggestionsResponse(@NonNull AutofillId fieldId,
            @NonNull InlineSuggestionsResponse response) {
        if (mInvalidated) {
            if (DEBUG) {
                Log.d(TAG, "handleOnInlineSuggestionsResponse() called on invalid session");
            }
            return;
        }
        // The IME doesn't have information about the virtual view id for the child views in the
        // web view, so we are only comparing the parent view id here. This means that for cases
        // where there are two input fields in the web view, they will have the same view id
        // (although different virtual child id), and we will not be able to distinguish them.
        final AutofillId imeClientFieldId = mClientAutofillIdSupplier.get();
        if (!mComponentName.getPackageName().equals(mClientPackageNameSupplier.get())
                || imeClientFieldId == null
                || fieldId.getViewId() != imeClientFieldId.getViewId()) {
            if (DEBUG) {
                Log.d(TAG,
                        "handleOnInlineSuggestionsResponse() called on the wrong package/field "
                                + "name: " + mComponentName.getPackageName() + " v.s. "
                                + mClientPackageNameSupplier.get() + ", " + fieldId + " v.s. "
                                + mClientAutofillIdSupplier.get());
            }
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "IME receives response: " + response.getInlineSuggestions().size());
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
                        fieldId, response));
            }
        }
    }
}
