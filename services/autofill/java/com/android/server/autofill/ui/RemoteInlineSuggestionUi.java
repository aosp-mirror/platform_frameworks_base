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

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.IInlineSuggestionUi;
import android.service.autofill.IInlineSuggestionUiCallback;
import android.service.autofill.ISurfacePackageResultCallback;
import android.util.Slog;
import android.view.SurfaceControlViewHost;

import com.android.internal.view.inline.IInlineContentCallback;

/**
 * The instance of this class lives in the system server, orchestrating the communication between
 * the remote process owning embedded view (i.e. ExtServices) and the remote process hosting the
 * embedded view (i.e. IME). It's also responsible for releasing the embedded view from the owning
 * process when it's not longer needed in the hosting process.
 *
 * <p>An instance of this class may be reused to associate with multiple instances of
 * {@link InlineContentProviderImpl}s, each of which wraps a callback from the IME. But at any
 * given time, there is only one active IME callback which this class will callback into.
 *
 * <p>This class is thread safe, because all the outside calls are piped into a single handler
 * thread to be processed.
 */
final class RemoteInlineSuggestionUi {

    private static final String TAG = RemoteInlineSuggestionUi.class.getSimpleName();

    // The delay time to release the remote inline suggestion view (in the renderer
    // process) after receiving a signal about the surface package being released due to being
    // detached from the window in the host app (in the IME process). The release will be
    // canceled if the host app reattaches the view to a window within this delay time.
    // TODO(b/154683107): try out using the Chroreographer to schedule the release right at the
    // next frame. Basically if the view is not re-attached to the window immediately in the next
    // frame after it was detached, then it will be released.
    private static final long RELEASE_REMOTE_VIEW_HOST_DELAY_MS = 200;

    @NonNull
    private final Handler mHandler;
    @NonNull
    private final RemoteInlineSuggestionViewConnector mRemoteInlineSuggestionViewConnector;
    private final int mWidth;
    private final int mHeight;
    @NonNull
    private final InlineSuggestionUiCallbackImpl mInlineSuggestionUiCallback;

    @Nullable
    private IInlineContentCallback mInlineContentCallback; // from IME

    /**
     * Remote inline suggestion view, backed by an instance of {@link SurfaceControlViewHost} in
     * the render service process. We takes care of releasing it when there is no remote
     * reference to it (from IME), and we will create a new instance of the view when it's needed
     * by IME again.
     */
    @Nullable
    private IInlineSuggestionUi mInlineSuggestionUi;
    private int mRefCount = 0;
    private boolean mWaitingForUiCreation = false;
    private int mActualWidth;
    private int mActualHeight;

    @Nullable
    private Runnable mDelayedReleaseViewRunnable;

    RemoteInlineSuggestionUi(
            @NonNull RemoteInlineSuggestionViewConnector remoteInlineSuggestionViewConnector,
            int width, int height, Handler handler) {
        mHandler = handler;
        mRemoteInlineSuggestionViewConnector = remoteInlineSuggestionViewConnector;
        mWidth = width;
        mHeight = height;
        mInlineSuggestionUiCallback = new InlineSuggestionUiCallbackImpl();
    }

    /**
     * Updates the callback from the IME process. It'll swap out the previous IME callback, and
     * all the subsequent callback events (onClick, onLongClick, touch event transfer, etc) will
     * be directed to the new callback.
     */
    void setInlineContentCallback(@NonNull IInlineContentCallback inlineContentCallback) {
        mHandler.post(() -> {
            mInlineContentCallback = inlineContentCallback;
        });
    }

    /**
     * Handles the request from the IME process to get a new surface package. May create a new
     * view in the renderer process if the existing view is already released.
     */
    void requestSurfacePackage() {
        mHandler.post(this::handleRequestSurfacePackage);
    }

    /**
     * Handles the signal from the IME process that the previously sent surface package has been
     * released.
     */
    void surfacePackageReleased() {
        mHandler.post(() -> handleUpdateRefCount(-1));
    }

    /**
     * Returns true if the provided size matches the remote view's size.
     */
    boolean match(int width, int height) {
        return mWidth == width && mHeight == height;
    }

    private void handleRequestSurfacePackage() {
        cancelPendingReleaseViewRequest();

        if (mInlineSuggestionUi == null) {
            if (mWaitingForUiCreation) {
                // This could happen in the following case: the remote embedded view was released
                // when previously detached from window. An event after that to re-attached to
                // the window will cause us calling the renderSuggestion again. Now, before the
                // render call returns a new surface package, if the view is detached and
                // re-attached to the window, causing this method to be called again, we will get
                // to this state. This request will be ignored and the surface package will still
                // be sent back once the view is rendered.
                if (sDebug) Slog.d(TAG, "Inline suggestion ui is not ready");
            } else {
                mRemoteInlineSuggestionViewConnector.renderSuggestion(mWidth, mHeight,
                        mInlineSuggestionUiCallback);
                mWaitingForUiCreation = true;
            }
        } else {
            try {
                mInlineSuggestionUi.getSurfacePackage(new ISurfacePackageResultCallback.Stub() {
                    @Override
                    public void onResult(SurfaceControlViewHost.SurfacePackage result) {
                        mHandler.post(() -> {
                            if (sVerbose) Slog.v(TAG, "Sending refreshed SurfacePackage to IME");
                            try {
                                mInlineContentCallback.onContent(result, mActualWidth,
                                        mActualHeight);
                                handleUpdateRefCount(1);
                            } catch (RemoteException e) {
                                Slog.w(TAG, "RemoteException calling onContent");
                            }
                        });
                    }
                });
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException calling getSurfacePackage.");
            }
        }
    }

    private void handleUpdateRefCount(int delta) {
        cancelPendingReleaseViewRequest();
        mRefCount += delta;
        if (mRefCount <= 0) {
            mDelayedReleaseViewRunnable = () -> {
                if (mInlineSuggestionUi != null) {
                    try {
                        if (sVerbose) Slog.v(TAG, "releasing the host");
                        mInlineSuggestionUi.releaseSurfaceControlViewHost();
                        mInlineSuggestionUi = null;
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException calling releaseSurfaceControlViewHost");
                    }
                }
                mDelayedReleaseViewRunnable = null;
            };
            mHandler.postDelayed(mDelayedReleaseViewRunnable, RELEASE_REMOTE_VIEW_HOST_DELAY_MS);
        }
    }

    private void cancelPendingReleaseViewRequest() {
        if (mDelayedReleaseViewRunnable != null) {
            mHandler.removeCallbacks(mDelayedReleaseViewRunnable);
            mDelayedReleaseViewRunnable = null;
        }
    }

    /**
     * This is called when a new inline suggestion UI is inflated from the ext services.
     */
    private void handleInlineSuggestionUiReady(IInlineSuggestionUi content,
            SurfaceControlViewHost.SurfacePackage surfacePackage, int width, int height) {
        mInlineSuggestionUi = content;
        mRefCount = 0;
        mWaitingForUiCreation = false;
        mActualWidth = width;
        mActualHeight = height;
        if (mInlineContentCallback != null) {
            try {
                if (sVerbose) Slog.v(TAG, "Sending new UI content to IME");
                handleUpdateRefCount(1);
                mInlineContentCallback.onContent(surfacePackage, mActualWidth, mActualHeight);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException calling onContent");
            }
        }
        if (surfacePackage != null) {
            surfacePackage.release();
        }
    }

    private void handleOnClick() {
        // Autofill the value
        mRemoteInlineSuggestionViewConnector.onClick();

        // Notify the remote process (IME) that hosts the embedded UI that it's clicked
        if (mInlineContentCallback != null) {
            try {
                mInlineContentCallback.onClick();
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException calling onClick");
            }
        }
    }

    private void handleOnLongClick() {
        // Notify the remote process (IME) that hosts the embedded UI that it's long clicked
        if (mInlineContentCallback != null) {
            try {
                mInlineContentCallback.onLongClick();
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException calling onLongClick");
            }
        }
    }

    private void handleOnError() {
        mRemoteInlineSuggestionViewConnector.onError();
    }

    private void handleOnTransferTouchFocusToImeWindow(IBinder sourceInputToken, int displayId) {
        mRemoteInlineSuggestionViewConnector.onTransferTouchFocusToImeWindow(sourceInputToken,
                displayId);
    }

    private void handleOnStartIntentSender(IntentSender intentSender) {
        mRemoteInlineSuggestionViewConnector.onStartIntentSender(intentSender);
    }

    /**
     * Responsible for communicating with the inline suggestion view owning process.
     */
    private class InlineSuggestionUiCallbackImpl extends IInlineSuggestionUiCallback.Stub {

        @Override
        public void onClick() {
            mHandler.post(RemoteInlineSuggestionUi.this::handleOnClick);
        }

        @Override
        public void onLongClick() {
            mHandler.post(RemoteInlineSuggestionUi.this::handleOnLongClick);
        }

        @Override
        public void onContent(IInlineSuggestionUi content,
                SurfaceControlViewHost.SurfacePackage surface, int width, int height) {
            mHandler.post(() -> handleInlineSuggestionUiReady(content, surface, width, height));
        }

        @Override
        public void onError() {
            mHandler.post(RemoteInlineSuggestionUi.this::handleOnError);
        }

        @Override
        public void onTransferTouchFocusToImeWindow(IBinder sourceInputToken, int displayId) {
            mHandler.post(() -> handleOnTransferTouchFocusToImeWindow(sourceInputToken, displayId));
        }

        @Override
        public void onStartIntentSender(IntentSender intentSender) {
            mHandler.post(() -> handleOnStartIntentSender(intentSender));
        }
    }
}
