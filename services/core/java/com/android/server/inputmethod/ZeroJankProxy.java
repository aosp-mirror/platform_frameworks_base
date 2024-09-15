/*
 * Copyright (C) 2024 The Android Open Source Project
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

/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.server.inputmethod.InputMethodManagerService.TAG;

import android.Manifest;
import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.os.Binder;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IBooleanListener;
import com.android.internal.inputmethod.IConnectionlessHandwritingCallback;
import com.android.internal.inputmethod.IImeTracker;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.InputMethodInfoSafeList;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.view.IInputMethodManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * A proxy that processes all {@link IInputMethodManager} calls asynchronously.
 */
final class ZeroJankProxy implements IInputMethodManagerImpl.Callback {

    interface Callback extends IInputMethodManagerImpl.Callback {
        @GuardedBy("ImfLock.class")
        ClientState getClientStateLocked(IInputMethodClient client);
        @GuardedBy("ImfLock.class")
        boolean isInputShownLocked();
    }

    private final Callback mInner;
    private final Executor mExecutor;

    ZeroJankProxy(Executor executor, Callback inner) {
        mInner = inner;
        mExecutor = executor;
    }

    private void offload(ThrowingRunnable r) {
        offloadInner(r);
    }

    private void offload(Runnable r) {
        offloadInner(r);
    }

    private void offloadInner(Runnable r) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mExecutor.execute(() -> {
                final long inner = Binder.clearCallingIdentity();
                // Restoring calling identity, so we can still do permission checks on caller.
                Binder.restoreCallingIdentity(identity);
                try {
                    try {
                        r.run();
                    } catch (Exception e) {
                        Slog.e(TAG, "Error in async IMMS call", e);
                    }
                } finally {
                    Binder.restoreCallingIdentity(inner);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void addClient(IInputMethodClient client, IRemoteInputConnection inputConnection,
            int selfReportedDisplayId) {
        offload(() -> mInner.addClient(client, inputConnection, selfReportedDisplayId));
    }

    @Override
    public InputMethodInfo getCurrentInputMethodInfoAsUser(int userId) {
        return mInner.getCurrentInputMethodInfoAsUser(userId);
    }

    @Override
    public InputMethodInfoSafeList getInputMethodList(
            int userId, @DirectBootAwareness int directBootAwareness) {
        return mInner.getInputMethodList(userId, directBootAwareness);
    }

    @Override
    public InputMethodInfoSafeList getEnabledInputMethodList(int userId) {
        return mInner.getEnabledInputMethodList(userId);
    }

    @Override
    public List<InputMethodInfo> getInputMethodListLegacy(
            int userId, @DirectBootAwareness int directBootAwareness) {
        return mInner.getInputMethodListLegacy(userId, directBootAwareness);
    }

    @Override
    public List<InputMethodInfo> getEnabledInputMethodListLegacy(int userId) {
        return mInner.getEnabledInputMethodListLegacy(userId);
    }

    @Override
    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String imiId,
            boolean allowsImplicitlyEnabledSubtypes, int userId) {
        return mInner.getEnabledInputMethodSubtypeList(imiId, allowsImplicitlyEnabledSubtypes,
                userId);
    }

    @Override
    public InputMethodSubtype getLastInputMethodSubtype(int userId) {
        return mInner.getLastInputMethodSubtype(userId);
    }

    @Override
    public boolean showSoftInput(IInputMethodClient client, IBinder windowToken,
            @Nullable ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            @MotionEvent.ToolType int lastClickToolType, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        offload(
                () -> {
                    if (!mInner.showSoftInput(
                            client,
                            windowToken,
                            statsToken,
                            flags,
                            lastClickToolType,
                            resultReceiver,
                            reason)) {
                        sendResultReceiverFailure(resultReceiver);
                    }
                });
        return true;
    }

    @Override
    public boolean hideSoftInput(IInputMethodClient client, IBinder windowToken,
            @Nullable ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        offload(
                () -> {
                    if (!mInner.hideSoftInput(
                            client, windowToken, statsToken, flags, resultReceiver, reason)) {
                        sendResultReceiverFailure(resultReceiver);
                    }
                });
        return true;
    }

    private void sendResultReceiverFailure(@Nullable ResultReceiver resultReceiver) {
        if (resultReceiver == null) {
            return;
        }
        final boolean isInputShown;
        synchronized (ImfLock.class) {
            isInputShown = mInner.isInputShownLocked();
        }
        resultReceiver.send(isInputShown
                        ? InputMethodManager.RESULT_UNCHANGED_SHOWN
                        : InputMethodManager.RESULT_UNCHANGED_HIDDEN,
                null);
    }

    @Override
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    public void hideSoftInputFromServerForTest() {
        mInner.hideSoftInputFromServerForTest();
    }

    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    @Override
    public void startInputOrWindowGainedFocusAsync(
            @StartInputReason int startInputReason,
            IInputMethodClient client, IBinder windowToken,
            @StartInputFlags int startInputFlags,
            @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode,
            int windowFlags, @Nullable EditorInfo editorInfo,
            IRemoteInputConnection inputConnection,
            IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher, int startInputSeq) {
        offload(() -> {
            InputBindResult result = mInner.startInputOrWindowGainedFocus(startInputReason, client,
                    windowToken, startInputFlags, softInputMode, windowFlags,
                    editorInfo,
                    inputConnection, remoteAccessibilityInputConnection,
                    unverifiedTargetSdkVersion,
                    userId, imeDispatcher);
            sendOnStartInputResult(client, result, startInputSeq);
            // For first-time client bind, MSG_BIND should arrive after MSG_START_INPUT_RESULT.
            if (result.result == InputBindResult.ResultCode.SUCCESS_WAITING_IME_SESSION) {
                InputMethodManagerService imms = ((InputMethodManagerService) mInner);
                synchronized (ImfLock.class) {
                    ClientState cs = imms.getClientStateLocked(client);
                    if (cs != null) {
                        imms.requestClientSessionLocked(cs);
                        imms.requestClientSessionForAccessibilityLocked(cs);
                    }
                }
            }
        });
    }

    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    @Override
    public InputBindResult startInputOrWindowGainedFocus(
            @StartInputReason int startInputReason,
            IInputMethodClient client, IBinder windowToken,
            @StartInputFlags int startInputFlags,
            @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode,
            int windowFlags, @Nullable EditorInfo editorInfo,
            IRemoteInputConnection inputConnection,
            IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        // Should never be called when flag is enabled i.e. when this proxy is used.
        return null;
    }

    @Override
    public void showInputMethodPickerFromClient(IInputMethodClient client,
            int auxiliarySubtypeMode) {
        offload(() -> mInner.showInputMethodPickerFromClient(client, auxiliarySubtypeMode));
    }

    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void showInputMethodPickerFromSystem(int auxiliarySubtypeMode, int displayId) {
        mInner.showInputMethodPickerFromSystem(auxiliarySubtypeMode, displayId);
    }

    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public boolean isInputMethodPickerShownForTest() {
        return mInner.isInputMethodPickerShownForTest();
    }

    @Override
    public InputMethodSubtype getCurrentInputMethodSubtype(int userId) {
        return mInner.getCurrentInputMethodSubtype(userId);
    }

    @Override
    public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes,
            @UserIdInt int userId) {
        mInner.setAdditionalInputMethodSubtypes(imiId, subtypes, userId);
    }

    @Override
    public void setExplicitlyEnabledInputMethodSubtypes(String imeId,
            @NonNull int[] subtypeHashCodes, @UserIdInt int userId) {
        mInner.setExplicitlyEnabledInputMethodSubtypes(imeId, subtypeHashCodes, userId);
    }

    @Override
    public int getInputMethodWindowVisibleHeight(IInputMethodClient client) {
        return mInner.getInputMethodWindowVisibleHeight(client);
    }

    @Override
    public void reportPerceptibleAsync(IBinder windowToken, boolean perceptible) {
        // Already async TODO(b/293640003): ordering issues?
        mInner.reportPerceptibleAsync(windowToken, perceptible);
    }

    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    @Override
    public void removeImeSurface() {
        mInner.removeImeSurface();
    }

    @Override
    public void removeImeSurfaceFromWindowAsync(IBinder windowToken) {
        mInner.removeImeSurfaceFromWindowAsync(windowToken);
    }

    @Override
    public void startProtoDump(byte[] bytes, int i, String s) {
        mInner.startProtoDump(bytes, i, s);
    }

    @Override
    public boolean isImeTraceEnabled() {
        return mInner.isImeTraceEnabled();
    }

    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void startImeTrace() {
        mInner.startImeTrace();
    }

    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void stopImeTrace() {
        mInner.stopImeTrace();
    }

    @Override
    public void startStylusHandwriting(IInputMethodClient client) {
        offload(() -> mInner.startStylusHandwriting(client));
    }

    @Override
    public void startConnectionlessStylusHandwriting(IInputMethodClient client, int userId,
            @Nullable CursorAnchorInfo cursorAnchorInfo, @Nullable String delegatePackageName,
            @Nullable String delegatorPackageName,
            @NonNull IConnectionlessHandwritingCallback callback) {
        offload(() -> mInner.startConnectionlessStylusHandwriting(
                client, userId, cursorAnchorInfo, delegatePackageName, delegatorPackageName,
                callback));
    }

    @Override
    public boolean acceptStylusHandwritingDelegation(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags) {
        try {
            return CompletableFuture.supplyAsync(() ->
                            mInner.acceptStylusHandwritingDelegation(
                                    client, userId, delegatePackageName, delegatorPackageName,
                                    flags),
                    this::offload).get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void acceptStylusHandwritingDelegationAsync(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags, IBooleanListener callback) {
        offload(() -> mInner.acceptStylusHandwritingDelegationAsync(
                client, userId, delegatePackageName, delegatorPackageName, flags, callback));
    }

    @Override
    public void prepareStylusHandwritingDelegation(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName) {
        offload(() -> mInner.prepareStylusHandwritingDelegation(
                client, userId, delegatePackageName, delegatorPackageName));
    }

    @Override
    public boolean isStylusHandwritingAvailableAsUser(int userId, boolean connectionless) {
        return mInner.isStylusHandwritingAvailableAsUser(userId, connectionless);
    }

    @IInputMethodManagerImpl.PermissionVerified("android.permission.TEST_INPUT_METHOD")
    @Override
    public void addVirtualStylusIdForTestSession(IInputMethodClient client) {
        mInner.addVirtualStylusIdForTestSession(client);
    }

    @IInputMethodManagerImpl.PermissionVerified("android.permission.TEST_INPUT_METHOD")
    @Override
    public void setStylusWindowIdleTimeoutForTest(IInputMethodClient client, long timeout) {
        mInner.setStylusWindowIdleTimeoutForTest(client, timeout);
    }

    @Override
    public IImeTracker getImeTrackerService() {
        return mInner.getImeTrackerService();
    }

    @BinderThread
    @Override
    public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err, @NonNull String[] args, @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver, @NonNull Binder self) {
        mInner.onShellCommand(in, out, err, args, callback, resultReceiver, self);
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
            @Nullable String[] args) {
        mInner.dump(fd, fout, args);
    }

    private void sendOnStartInputResult(
            IInputMethodClient client, InputBindResult res, int startInputSeq) {
        synchronized (ImfLock.class) {
            final ClientState cs = mInner.getClientStateLocked(client);
            if (cs != null && cs.mClient != null) {
                cs.mClient.onStartInputResult(res, startInputSeq);
            } else {
                // client is unbound.
                Slog.i(TAG, "Client that requested startInputOrWindowGainedFocus is no longer"
                        + " bound. InputBindResult: " + res + " for startInputSeq: "
                        + startInputSeq);
            }
        }
    }
}

