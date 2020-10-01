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

package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.util.Slog;

import com.android.internal.view.inline.IInlineContentCallback;
import com.android.internal.view.inline.IInlineContentProvider;
import com.android.server.FgThread;

/**
 * We create one instance of this class for each {@link android.view.inputmethod.InlineSuggestion}
 * instance. Each inline suggestion instance will only be sent to the remote IME process once. In
 * case of filtering and resending the suggestion when keyboard state changes between hide and
 * show, a new instance of this class will be created using {@link #copy()}, with the same backing
 * {@link RemoteInlineSuggestionUi}. When the
 * {@link #provideContent(int, int, IInlineContentCallback)} is called the first time (it's only
 * allowed to be called at most once), the passed in width/height is used to determine whether
 * the existing {@link RemoteInlineSuggestionUi} provided in the constructor can be reused, or a
 * new one should be created to suit the new size requirement for the view. In normal cases,
 * we should not expect the size requirement to change, although in theory the public API allows
 * the IME to do that.
 *
 * <p>This design is to enable us to be able to reuse the backing remote view while still keeping
 * the callbacks relatively well aligned. For example, if we allow multiple remote IME binder
 * callbacks to call into one instance of this class, then binder A may call in with width/height
 * X for which we create a view (i.e. {@link RemoteInlineSuggestionUi}) for it,
 *
 * See also {@link RemoteInlineSuggestionUi} for relevant information.
 */
final class InlineContentProviderImpl extends IInlineContentProvider.Stub {

    // TODO(b/153615023): consider not holding strong reference to heavy objects in this stub, to
    //  avoid memory leak in case the client app is holding the remote reference for a longer
    //  time than expected. Essentially we need strong reference in the system process to
    //  the member variables, but weak reference to them in the IInlineContentProvider.Stub.

    private static final String TAG = InlineContentProviderImpl.class.getSimpleName();

    private final Handler mHandler = FgThread.getHandler();;

    @NonNull
    private final RemoteInlineSuggestionViewConnector mRemoteInlineSuggestionViewConnector;
    @Nullable
    private RemoteInlineSuggestionUi mRemoteInlineSuggestionUi;

    private boolean mProvideContentCalled = false;

    InlineContentProviderImpl(
            @NonNull RemoteInlineSuggestionViewConnector remoteInlineSuggestionViewConnector,
            @Nullable RemoteInlineSuggestionUi remoteInlineSuggestionUi) {
        mRemoteInlineSuggestionViewConnector = remoteInlineSuggestionViewConnector;
        mRemoteInlineSuggestionUi = remoteInlineSuggestionUi;
    }

    /**
     * Returns a new instance of this class, with the same {@code mInlineSuggestionRenderer} and
     * {@code mRemoteInlineSuggestionUi}. The latter may or may not be reusable depending on the
     * size information provided when the client calls {@link #provideContent(int, int,
     * IInlineContentCallback)}.
     */
    @NonNull
    public InlineContentProviderImpl copy() {
        return new InlineContentProviderImpl(mRemoteInlineSuggestionViewConnector,
                mRemoteInlineSuggestionUi);
    }

    /**
     * Provides a SurfacePackage associated with the inline suggestion view to the IME. If such
     * view doesn't exit, then create a new one. This method should be called once per lifecycle
     * of this object. Any further calls to the method will be ignored.
     */
    @Override
    public void provideContent(int width, int height, IInlineContentCallback callback) {
        mHandler.post(() -> handleProvideContent(width, height, callback));
    }

    @Override
    public void requestSurfacePackage() {
        mHandler.post(this::handleGetSurfacePackage);
    }

    @Override
    public void onSurfacePackageReleased() {
        mHandler.post(this::handleOnSurfacePackageReleased);
    }

    private void handleProvideContent(int width, int height, IInlineContentCallback callback) {
        if (sVerbose) Slog.v(TAG, "handleProvideContent");
        if (mProvideContentCalled) {
            // This method should only be called once.
            return;
        }
        mProvideContentCalled = true;
        if (mRemoteInlineSuggestionUi == null || !mRemoteInlineSuggestionUi.match(width, height)) {
            mRemoteInlineSuggestionUi = new RemoteInlineSuggestionUi(
                    mRemoteInlineSuggestionViewConnector,
                    width, height, mHandler);
        }
        mRemoteInlineSuggestionUi.setInlineContentCallback(callback);
        mRemoteInlineSuggestionUi.requestSurfacePackage();
    }

    private void handleGetSurfacePackage() {
        if (sVerbose) Slog.v(TAG, "handleGetSurfacePackage");
        if (!mProvideContentCalled || mRemoteInlineSuggestionUi == null) {
            // provideContent should be called first, and remote UI should not be null.
            return;
        }
        mRemoteInlineSuggestionUi.requestSurfacePackage();
    }

    private void handleOnSurfacePackageReleased() {
        if (sVerbose) Slog.v(TAG, "handleOnSurfacePackageReleased");
        if (!mProvideContentCalled || mRemoteInlineSuggestionUi == null) {
            // provideContent should be called first, and remote UI should not be null.
            return;
        }
        mRemoteInlineSuggestionUi.surfacePackageReleased();
    }
}
