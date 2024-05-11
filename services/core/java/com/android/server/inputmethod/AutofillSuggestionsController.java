/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InputMethodInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IInlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.IInlineSuggestionsResponseCallback;
import com.android.internal.inputmethod.InlineSuggestionsRequestInfo;

/**
 * A controller managing autofill suggestion requests.
 */
final class AutofillSuggestionsController {
    private static final boolean DEBUG = false;
    private static final String TAG = AutofillSuggestionsController.class.getSimpleName();

    @NonNull private final InputMethodManagerService mService;

    /**
     * The host input token of the input method that is currently associated with this controller.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    private IBinder mCurHostInputToken;

    private static final class CreateInlineSuggestionsRequest {
        @NonNull final InlineSuggestionsRequestInfo mRequestInfo;
        @NonNull final IInlineSuggestionsRequestCallback mCallback;
        @NonNull final String mPackageName;

        CreateInlineSuggestionsRequest(
                @NonNull InlineSuggestionsRequestInfo requestInfo,
                @NonNull IInlineSuggestionsRequestCallback callback,
                @NonNull String packageName) {
            mRequestInfo = requestInfo;
            mCallback = callback;
            mPackageName = packageName;
        }
    }

    /**
     * If a request to create inline autofill suggestions comes in while the IME is unbound
     * due to {@link InputMethodManagerService#mPreventImeStartupUnlessTextEditor},
     * this is where it is stored, so that it may be fulfilled once the IME rebinds.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    private CreateInlineSuggestionsRequest mPendingInlineSuggestionsRequest;

    /**
     * A callback into the autofill service obtained from the latest call to
     * {@link #onCreateInlineSuggestionsRequest}, which can be used to invalidate an
     * autofill session in case the IME process dies.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    private IInlineSuggestionsRequestCallback mInlineSuggestionsRequestCallback;

    AutofillSuggestionsController(@NonNull InputMethodManagerService service) {
        mService = service;
    }

    @GuardedBy("ImfLock.class")
    void onResetSystemUi() {
        mCurHostInputToken = null;
    }

    @Nullable
    @GuardedBy("ImfLock.class")
    IBinder getCurHostInputToken() {
        return mCurHostInputToken;
    }

    @GuardedBy("ImfLock.class")
    void onCreateInlineSuggestionsRequest(@UserIdInt int userId,
            InlineSuggestionsRequestInfo requestInfo, IInlineSuggestionsRequestCallback callback,
            boolean touchExplorationEnabled) {
        clearPendingInlineSuggestionsRequest();
        mInlineSuggestionsRequestCallback = callback;
        final InputMethodInfo imi = mService.queryInputMethodForCurrentUserLocked(
                mService.getSelectedMethodIdLocked());
        try {
            if (userId == mService.getCurrentImeUserIdLocked()
                    && imi != null && isInlineSuggestionsEnabled(imi, touchExplorationEnabled)) {
                mPendingInlineSuggestionsRequest = new CreateInlineSuggestionsRequest(
                        requestInfo, callback, imi.getPackageName());
                if (mService.getCurMethodLocked() != null) {
                    // In the normal case when the IME is connected, we can make the request here.
                    performOnCreateInlineSuggestionsRequest();
                } else {
                    // Otherwise, the next time the IME connection is established,
                    // InputMethodBindingController.mMainConnection#onServiceConnected() will call
                    // into #performOnCreateInlineSuggestionsRequestLocked() to make the request.
                    if (DEBUG) {
                        Slog.d(TAG, "IME not connected. Delaying inline suggestions request.");
                    }
                }
            } else {
                callback.onInlineSuggestionsUnsupported();
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException calling onCreateInlineSuggestionsRequest(): " + e);
        }
    }

    @GuardedBy("ImfLock.class")
    void performOnCreateInlineSuggestionsRequest() {
        if (mPendingInlineSuggestionsRequest == null) {
            return;
        }
        IInputMethodInvoker curMethod = mService.getCurMethodLocked();
        if (DEBUG) {
            Slog.d(TAG, "Performing onCreateInlineSuggestionsRequest. mCurMethod = " + curMethod);
        }
        if (curMethod != null) {
            final IInlineSuggestionsRequestCallback callback =
                    new InlineSuggestionsRequestCallbackDecorator(
                            mPendingInlineSuggestionsRequest.mCallback,
                            mPendingInlineSuggestionsRequest.mPackageName,
                            mService.getCurTokenDisplayIdLocked(),
                            mService.getCurTokenLocked());
            curMethod.onCreateInlineSuggestionsRequest(
                    mPendingInlineSuggestionsRequest.mRequestInfo, callback);
        } else {
            Slog.w(TAG, "No IME connected! Abandoning inline suggestions creation request.");
        }
        clearPendingInlineSuggestionsRequest();
    }

    @GuardedBy("ImfLock.class")
    private void clearPendingInlineSuggestionsRequest() {
        mPendingInlineSuggestionsRequest = null;
    }

    private static boolean isInlineSuggestionsEnabled(InputMethodInfo imi,
            boolean touchExplorationEnabled) {
        return imi.isInlineSuggestionsEnabled()
                && (!touchExplorationEnabled
                || imi.supportsInlineSuggestionsWithTouchExploration());
    }

    @GuardedBy("ImfLock.class")
    void invalidateAutofillSession() {
        if (mInlineSuggestionsRequestCallback != null) {
            try {
                mInlineSuggestionsRequestCallback.onInlineSuggestionsSessionInvalidated();
            } catch (RemoteException e) {
                Slog.e(TAG, "Cannot invalidate autofill session.", e);
            }
        }
    }

    /**
     * The decorator which validates the host package name in the
     * {@link InlineSuggestionsRequest} argument to make sure it matches the IME package name.
     */
    private final class InlineSuggestionsRequestCallbackDecorator
            extends IInlineSuggestionsRequestCallback.Stub {
        @NonNull private final IInlineSuggestionsRequestCallback mCallback;
        @NonNull private final String mImePackageName;
        private final int mImeDisplayId;
        @NonNull private final IBinder mImeToken;

        InlineSuggestionsRequestCallbackDecorator(
                @NonNull IInlineSuggestionsRequestCallback callback, @NonNull String imePackageName,
                int displayId, @NonNull IBinder imeToken) {
            mCallback = callback;
            mImePackageName = imePackageName;
            mImeDisplayId = displayId;
            mImeToken = imeToken;
        }

        @Override
        public void onInlineSuggestionsUnsupported() throws RemoteException {
            mCallback.onInlineSuggestionsUnsupported();
        }

        @Override
        public void onInlineSuggestionsRequest(InlineSuggestionsRequest request,
                IInlineSuggestionsResponseCallback callback)
                throws RemoteException {
            if (!mImePackageName.equals(request.getHostPackageName())) {
                throw new SecurityException(
                        "Host package name in the provide request=[" + request.getHostPackageName()
                                + "] doesn't match the IME package name=[" + mImePackageName
                                + "].");
            }
            request.setHostDisplayId(mImeDisplayId);
            synchronized (ImfLock.class) {
                final IBinder curImeToken = mService.getCurTokenLocked();
                if (mImeToken == curImeToken) {
                    mCurHostInputToken = request.getHostInputToken();
                }
            }
            mCallback.onInlineSuggestionsRequest(request, callback);
        }

        @Override
        public void onInputMethodStartInput(AutofillId imeFieldId) throws RemoteException {
            mCallback.onInputMethodStartInput(imeFieldId);
        }

        @Override
        public void onInputMethodShowInputRequested(boolean requestResult) throws RemoteException {
            mCallback.onInputMethodShowInputRequested(requestResult);
        }

        @Override
        public void onInputMethodStartInputView() throws RemoteException {
            mCallback.onInputMethodStartInputView();
        }

        @Override
        public void onInputMethodFinishInputView() throws RemoteException {
            mCallback.onInputMethodFinishInputView();
        }

        @Override
        public void onInputMethodFinishInput() throws RemoteException {
            mCallback.onInputMethodFinishInput();
        }

        @Override
        public void onInlineSuggestionsSessionInvalidated() throws RemoteException {
            mCallback.onInlineSuggestionsSessionInvalidated();
        }
    }
}
