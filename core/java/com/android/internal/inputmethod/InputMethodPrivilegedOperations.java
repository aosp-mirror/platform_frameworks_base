/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.inputmethod;

import android.annotation.AnyThread;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;

import java.util.Objects;

/**
 * A utility class to take care of boilerplate code around IPCs.
 */
public final class InputMethodPrivilegedOperations {
    private static final String TAG = "InputMethodPrivilegedOperations";

    private static final class OpsHolder {
        @Nullable
        @GuardedBy("this")
        private IInputMethodPrivilegedOperations mPrivOps;

        /**
         * Sets {@link IInputMethodPrivilegedOperations}.
         *
         * <p>This method can be called only once.</p>
         *
         * @param privOps Binder interface to be set
         */
        @AnyThread
        public synchronized void set(@NonNull IInputMethodPrivilegedOperations privOps) {
            if (mPrivOps != null) {
                throw new IllegalStateException(
                        "IInputMethodPrivilegedOperations must be set at most once."
                                + " privOps=" + privOps);
            }
            mPrivOps = privOps;
        }

        /**
         * A simplified version of {@link android.os.Debug#getCaller()}.
         *
         * @return method name of the caller.
         */
        @AnyThread
        private static String getCallerMethodName() {
            final StackTraceElement[] callStack = Thread.currentThread().getStackTrace();
            if (callStack.length <= 4) {
                return "<bottom of call stack>";
            }
            return callStack[4].getMethodName();
        }

        @AnyThread
        @Nullable
        public synchronized IInputMethodPrivilegedOperations getAndWarnIfNull() {
            if (mPrivOps == null) {
                Log.e(TAG, getCallerMethodName() + " is ignored."
                        + " Call it within attachToken() and InputMethodService.onDestroy()");
            }
            return mPrivOps;
        }
    }
    private final OpsHolder mOps = new OpsHolder();

    /**
     * Sets {@link IInputMethodPrivilegedOperations}.
     *
     * <p>This method can be called only once.</p>
     *
     * @param privOps Binder interface to be set
     */
    @AnyThread
    public void set(@NonNull IInputMethodPrivilegedOperations privOps) {
        Objects.requireNonNull(privOps, "privOps must not be null");
        mOps.set(privOps);
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#setImeWindowStatusAsync(int, int)}.
     *
     * @param vis visibility flags
     * @param backDisposition disposition flags
     * @see android.inputmethodservice.InputMethodService#IME_ACTIVE
     * @see android.inputmethodservice.InputMethodService#IME_VISIBLE
     * @see android.inputmethodservice.InputMethodService#IME_INVISIBLE
     * @see android.inputmethodservice.InputMethodService#BACK_DISPOSITION_DEFAULT
     * @see android.inputmethodservice.InputMethodService#BACK_DISPOSITION_ADJUST_NOTHING
     */
    @AnyThread
    public void setImeWindowStatusAsync(int vis, int backDisposition) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.setImeWindowStatusAsync(vis, backDisposition);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#reportStartInputAsync(IBinder)}.
     *
     * @param startInputToken {@link IBinder} token to distinguish startInput session
     */
    @AnyThread
    public void reportStartInputAsync(IBinder startInputToken) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.reportStartInputAsync(startInputToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#createInputContentUriToken(Uri, String,
     * AndroidFuture)}.
     *
     * @param contentUri Content URI to which a temporary read permission should be granted
     * @param packageName Indicates what package needs to have a temporary read permission
     * @return special Binder token that should be set to
     *         {@link android.view.inputmethod.InputContentInfo#setUriToken(IInputContentUriToken)}
     */
    @AnyThread
    public IInputContentUriToken createInputContentUriToken(Uri contentUri, String packageName) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return null;
        }
        try {
            final AndroidFuture<IBinder> future = new AndroidFuture<>();
            ops.createInputContentUriToken(contentUri, packageName, future);
            return IInputContentUriToken.Stub.asInterface(CompletableFutureUtil.getResult(future));
        } catch (RemoteException e) {
            // For historical reasons, this error was silently ignored.
            // Note that the caller already logs error so we do not need additional Log.e() here.
            // TODO(team): Check if it is safe to rethrow error here.
            return null;
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#reportFullscreenModeAsync(boolean)}.
     *
     * @param fullscreen {@code true} if the IME enters full screen mode
     */
    @AnyThread
    public void reportFullscreenModeAsync(boolean fullscreen) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.reportFullscreenModeAsync(fullscreen);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#updateStatusIconAsync(String, int)}.
     *
     * @param packageName package name from which the status icon should be loaded
     * @param iconResId resource ID of the icon to be loaded
     */
    @AnyThread
    public void updateStatusIconAsync(String packageName, @DrawableRes int iconResId) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.updateStatusIconAsync(packageName, iconResId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#setInputMethod(String, AndroidFuture)}.
     *
     * @param id IME ID of the IME to switch to
     * @see android.view.inputmethod.InputMethodInfo#getId()
     */
    @AnyThread
    public void setInputMethod(String id) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            final AndroidFuture<Void> future = new AndroidFuture<>();
            ops.setInputMethod(id, future);
            CompletableFutureUtil.getResult(future);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#setInputMethodAndSubtype(String,
     * InputMethodSubtype, AndroidFuture)}
     *
     * @param id IME ID of the IME to switch to
     * @param subtype {@link InputMethodSubtype} to switch to
     * @see android.view.inputmethod.InputMethodInfo#getId()
     */
    @AnyThread
    public void setInputMethodAndSubtype(String id, InputMethodSubtype subtype) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            final AndroidFuture<Void> future = new AndroidFuture<>();
            ops.setInputMethodAndSubtype(id, subtype, future);
            CompletableFutureUtil.getResult(future);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#hideMySoftInput(int, int, AndroidFuture)}
     *
     * @param reason the reason to hide soft input
     */
    @AnyThread
    public void hideMySoftInput(@InputMethodManager.HideFlags int flags,
            @SoftInputShowHideReason int reason) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            final AndroidFuture<Void> future = new AndroidFuture<>();
            ops.hideMySoftInput(flags, reason, future);
            CompletableFutureUtil.getResult(future);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#showMySoftInput(int, AndroidFuture)}
     */
    @AnyThread
    public void showMySoftInput(@InputMethodManager.ShowFlags int flags) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            final AndroidFuture<Void> future = new AndroidFuture<>();
            ops.showMySoftInput(flags, future);
            CompletableFutureUtil.getResult(future);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#switchToPreviousInputMethod(AndroidFuture)}
     *
     * @return {@code true} if handled
     */
    @AnyThread
    public boolean switchToPreviousInputMethod() {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return false;
        }
        try {
            final AndroidFuture<Boolean> value = new AndroidFuture<>();
            ops.switchToPreviousInputMethod(value);
            return CompletableFutureUtil.getResult(value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#switchToNextInputMethod(boolean,
     * AndroidFuture)}
     *
     * @param onlyCurrentIme {@code true} to switch to a {@link InputMethodSubtype} within the same
     *                       IME
     * @return {@code true} if handled
     */
    @AnyThread
    public boolean switchToNextInputMethod(boolean onlyCurrentIme) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return false;
        }
        try {
            final AndroidFuture<Boolean> future = new AndroidFuture<>();
            ops.switchToNextInputMethod(onlyCurrentIme, future);
            return CompletableFutureUtil.getResult(future);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#shouldOfferSwitchingToNextInputMethod(
     * AndroidFuture)}
     *
     * @return {@code true} if the IEM should offer a way to globally switch IME
     */
    @AnyThread
    public boolean shouldOfferSwitchingToNextInputMethod() {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return false;
        }
        try {
            final AndroidFuture<Boolean> future = new AndroidFuture<>();
            ops.shouldOfferSwitchingToNextInputMethod(future);
            return CompletableFutureUtil.getResult(future);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#notifyUserActionAsync()}
     */
    @AnyThread
    public void notifyUserActionAsync() {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.notifyUserActionAsync();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#applyImeVisibilityAsync(IBinder, boolean,
     * ImeTracker.Token)}.
     *
     * @param showOrHideInputToken placeholder token that maps to window requesting
     *        {@link android.view.inputmethod.InputMethodManager#showSoftInput(View, int)} or
     *        {@link android.view.inputmethod.InputMethodManager#hideSoftInputFromWindow(IBinder,
     *        int)}
     * @param setVisible {@code true} to set IME visible, else hidden.
     * @param statsToken the token tracking the current IME request or {@code null} otherwise.
     */
    @AnyThread
    public void applyImeVisibilityAsync(IBinder showOrHideInputToken, boolean setVisible,
            @Nullable ImeTracker.Token statsToken) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            ImeTracker.forLogging().onFailed(statsToken,
                    ImeTracker.PHASE_IME_APPLY_VISIBILITY_INSETS_CONSUMER);
            return;
        }
        ImeTracker.forLogging().onProgress(statsToken,
                ImeTracker.PHASE_IME_APPLY_VISIBILITY_INSETS_CONSUMER);
        try {
            ops.applyImeVisibilityAsync(showOrHideInputToken, setVisible, statsToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#onStylusHandwritingReady(int, int)}
     */
    @AnyThread
    public void onStylusHandwritingReady(int requestId, int pid) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.onStylusHandwritingReady(requestId, pid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * IME notifies that the current handwriting session should be closed.
     * @param requestId
     */
    @AnyThread
    public void resetStylusHandwriting(int requestId) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.resetStylusHandwriting(requestId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IInputMethodPrivilegedOperations#switchKeyboardLayoutAsync(int)}.
     */
    @AnyThread
    public void switchKeyboardLayoutAsync(int direction) {
        final IInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.switchKeyboardLayoutAsync(direction);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
