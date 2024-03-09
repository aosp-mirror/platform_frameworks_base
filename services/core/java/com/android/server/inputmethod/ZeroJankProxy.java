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
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.ExceptionUtils;
import android.util.Slog;
import android.view.WindowManager;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IBooleanListener;
import com.android.internal.inputmethod.IConnectionlessHandwritingCallback;
import com.android.internal.inputmethod.IImeTracker;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;
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
 * @hide
 */
public class ZeroJankProxy extends IInputMethodManager.Stub {

    private final IInputMethodManager mInner;
    private final Executor mExecutor;

    ZeroJankProxy(Executor executor, IInputMethodManager inner) {
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
            int selfReportedDisplayId) throws RemoteException {
        offload(() -> mInner.addClient(client, inputConnection, selfReportedDisplayId));
    }

    @Override
    public InputMethodInfo getCurrentInputMethodInfoAsUser(int userId) throws RemoteException {
        return mInner.getCurrentInputMethodInfoAsUser(userId);
    }

    @Override
    public List<InputMethodInfo> getInputMethodList(
            int userId, @DirectBootAwareness int directBootAwareness) throws RemoteException {
        return mInner.getInputMethodList(userId, directBootAwareness);
    }

    @Override
    public List<InputMethodInfo> getEnabledInputMethodList(int userId) throws RemoteException {
        return mInner.getEnabledInputMethodList(userId);
    }

    @Override
    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String imiId,
            boolean allowsImplicitlyEnabledSubtypes, int userId)
            throws RemoteException {
        return mInner.getEnabledInputMethodSubtypeList(imiId, allowsImplicitlyEnabledSubtypes,
                userId);
    }

    @Override
    public InputMethodSubtype getLastInputMethodSubtype(int userId) throws RemoteException {
        return mInner.getLastInputMethodSubtype(userId);
    }

    @Override
    public boolean showSoftInput(IInputMethodClient client, IBinder windowToken,
            @Nullable ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            int lastClickTooType, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason)
            throws RemoteException {
        offload(() -> mInner.showSoftInput(client, windowToken, statsToken, flags, lastClickTooType,
                resultReceiver, reason));
        return true;
    }

    @Override
    public boolean hideSoftInput(IInputMethodClient client, IBinder windowToken,
            @Nullable ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason)
            throws RemoteException {
        offload(() -> mInner.hideSoftInput(client, windowToken, statsToken, flags, resultReceiver,
                reason));
        return true;
    }

    @Override
    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    public void hideSoftInputFromServerForTest() throws RemoteException {
        super.hideSoftInputFromServerForTest_enforcePermission();
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
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher, int startInputSeq)
            throws RemoteException {
        offload(() -> {
            InputBindResult result = mInner.startInputOrWindowGainedFocus(startInputReason, client,
                    windowToken, startInputFlags, softInputMode, windowFlags,
                    editorInfo,
                    inputConnection, remoteAccessibilityInputConnection,
                    unverifiedTargetSdkVersion,
                    userId, imeDispatcher);
            sendOnStartInputResult(client, result, startInputSeq);
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
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher)
            throws RemoteException {
        // Should never be called when flag is enabled i.e. when this proxy is used.
        return null;
    }

    @Override
    public void showInputMethodPickerFromClient(IInputMethodClient client,
            int auxiliarySubtypeMode)
            throws RemoteException {
        offload(() -> mInner.showInputMethodPickerFromClient(client, auxiliarySubtypeMode));
    }

    @EnforcePermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void showInputMethodPickerFromSystem(int auxiliarySubtypeMode, int displayId)
            throws RemoteException {
        mInner.showInputMethodPickerFromSystem(auxiliarySubtypeMode, displayId);
    }

    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public boolean isInputMethodPickerShownForTest() throws RemoteException {
        super.isInputMethodPickerShownForTest_enforcePermission();
        return mInner.isInputMethodPickerShownForTest();
    }

    @Override
    public InputMethodSubtype getCurrentInputMethodSubtype(int userId) throws RemoteException {
        return mInner.getCurrentInputMethodSubtype(userId);
    }

    @Override
    public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes,
            @UserIdInt int userId) throws RemoteException {
        mInner.setAdditionalInputMethodSubtypes(imiId, subtypes, userId);
    }

    @Override
    public void setExplicitlyEnabledInputMethodSubtypes(String imeId,
            @NonNull int[] subtypeHashCodes, @UserIdInt int userId) throws RemoteException {
        mInner.setExplicitlyEnabledInputMethodSubtypes(imeId, subtypeHashCodes, userId);
    }

    @Override
    public int getInputMethodWindowVisibleHeight(IInputMethodClient client)
            throws RemoteException {
        return mInner.getInputMethodWindowVisibleHeight(client);
    }

    @Override
    public void reportPerceptibleAsync(IBinder windowToken, boolean perceptible)
            throws RemoteException {
        // Already async TODO(b/293640003): ordering issues?
        mInner.reportPerceptibleAsync(windowToken, perceptible);
    }

    @EnforcePermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    @Override
    public void removeImeSurface() throws RemoteException {
        mInner.removeImeSurface();
    }

    @Override
    public void removeImeSurfaceFromWindowAsync(IBinder windowToken) throws RemoteException {
        mInner.removeImeSurfaceFromWindowAsync(windowToken);
    }

    @Override
    public void startProtoDump(byte[] bytes, int i, String s) throws RemoteException {
        mInner.startProtoDump(bytes, i, s);
    }

    @Override
    public boolean isImeTraceEnabled() throws RemoteException {
        return mInner.isImeTraceEnabled();
    }

    @EnforcePermission(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void startImeTrace() throws RemoteException {
        mInner.startImeTrace();
    }

    @EnforcePermission(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void stopImeTrace() throws RemoteException {
        mInner.stopImeTrace();
    }

    @Override
    public void startStylusHandwriting(IInputMethodClient client)
            throws RemoteException {
        offload(() -> mInner.startStylusHandwriting(client));
    }

    @Override
    public void startConnectionlessStylusHandwriting(IInputMethodClient client, int userId,
            @Nullable CursorAnchorInfo cursorAnchorInfo, @Nullable String delegatePackageName,
            @Nullable String delegatorPackageName,
            @NonNull IConnectionlessHandwritingCallback callback) throws RemoteException {
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
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return mInner.acceptStylusHandwritingDelegation(
                            client, userId, delegatePackageName, delegatorPackageName, flags);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }, this::offload).get();
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
            @InputMethodManager.HandwritingDelegateFlags int flags, IBooleanListener callback)
            throws RemoteException {
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
    public boolean isStylusHandwritingAvailableAsUser(int userId, boolean connectionless)
            throws RemoteException {
        return mInner.isStylusHandwritingAvailableAsUser(userId, connectionless);
    }

    @EnforcePermission("android.permission.TEST_INPUT_METHOD")
    @Override
    public void addVirtualStylusIdForTestSession(IInputMethodClient client)
            throws RemoteException {
        mInner.addVirtualStylusIdForTestSession(client);
    }

    @EnforcePermission("android.permission.TEST_INPUT_METHOD")
    @Override
    public void setStylusWindowIdleTimeoutForTest(IInputMethodClient client, long timeout)
            throws RemoteException {
        mInner.setStylusWindowIdleTimeoutForTest(client, timeout);
    }

    @Override
    public IImeTracker getImeTrackerService() throws RemoteException {
        return mInner.getImeTrackerService();
    }

    @BinderThread
    @Override
    public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args, @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) throws RemoteException {
        ((InputMethodManagerService) mInner).onShellCommand(
                in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd,
            @NonNull PrintWriter fout,
            @Nullable String[] args) {
        ((InputMethodManagerService) mInner).dump(fd, fout, args);
    }

    private void sendOnStartInputResult(
            IInputMethodClient client, InputBindResult res, int startInputSeq) {
        InputMethodManagerService service = (InputMethodManagerService) mInner;
        final ClientState cs = service.getClientState(client);
        if (cs != null && cs.mClient != null) {
            cs.mClient.onStartInputResult(res, startInputSeq);
        } else {
            // client is unbound.
            Slog.i(TAG, "Client that requested startInputOrWindowGainedFocus is no longer"
                    + " bound. InputBindResult: " + res + " for startInputSeq: " + startInputSeq);
        }
    }
}

