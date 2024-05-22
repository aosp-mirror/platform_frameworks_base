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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.Manifest;
import android.annotation.BinderThread;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.Binder;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.view.MotionEvent;
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
import com.android.internal.inputmethod.InputMethodInfoSafeList;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.view.IInputMethodManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;

/**
 * An actual implementation class of {@link IInputMethodManager.Stub} to allow other classes to
 * focus on handling IPC callbacks.
 */
final class IInputMethodManagerImpl extends IInputMethodManager.Stub {

    /**
     * Tells that the given permission is already verified before the annotated method gets called.
     */
    @Retention(SOURCE)
    @Target({METHOD})
    @interface PermissionVerified {
        String value() default "";
    }

    @BinderThread
    interface Callback {
        void addClient(IInputMethodClient client, IRemoteInputConnection inputConnection,
                int selfReportedDisplayId);

        InputMethodInfo getCurrentInputMethodInfoAsUser(@UserIdInt int userId);

        @NonNull
        InputMethodInfoSafeList getInputMethodList(@UserIdInt int userId,
                @DirectBootAwareness int directBootAwareness);

        @NonNull
        InputMethodInfoSafeList getEnabledInputMethodList(@UserIdInt int userId);

        @NonNull
        List<InputMethodInfo> getInputMethodListLegacy(@UserIdInt int userId,
                @DirectBootAwareness int directBootAwareness);

        @NonNull
        List<InputMethodInfo> getEnabledInputMethodListLegacy(@UserIdInt int userId);

        List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String imiId,
                boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId);

        InputMethodSubtype getLastInputMethodSubtype(@UserIdInt int userId);

        boolean showSoftInput(IInputMethodClient client, IBinder windowToken,
                @Nullable ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
                @MotionEvent.ToolType int lastClickToolType, ResultReceiver resultReceiver,
                @SoftInputShowHideReason int reason);

        boolean hideSoftInput(IInputMethodClient client, IBinder windowToken,
                @Nullable ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
                ResultReceiver resultReceiver, @SoftInputShowHideReason int reason);

        @PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
        void hideSoftInputFromServerForTest();

        void startInputOrWindowGainedFocusAsync(
                @StartInputReason int startInputReason, IInputMethodClient client,
                IBinder windowToken, @StartInputFlags int startInputFlags,
                @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode, int windowFlags,
                @Nullable EditorInfo editorInfo, IRemoteInputConnection inputConnection,
                IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
                int unverifiedTargetSdkVersion, @UserIdInt int userId,
                @NonNull ImeOnBackInvokedDispatcher imeDispatcher, int startInputSeq);

        InputBindResult startInputOrWindowGainedFocus(
                @StartInputReason int startInputReason, IInputMethodClient client,
                IBinder windowToken, @StartInputFlags int startInputFlags,
                @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode, int windowFlags,
                @Nullable EditorInfo editorInfo, IRemoteInputConnection inputConnection,
                IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
                int unverifiedTargetSdkVersion, @UserIdInt int userId,
                @NonNull ImeOnBackInvokedDispatcher imeDispatcher);

        void showInputMethodPickerFromClient(IInputMethodClient client, int auxiliarySubtypeMode);

        @PermissionVerified(Manifest.permission.WRITE_SECURE_SETTINGS)
        void showInputMethodPickerFromSystem(int auxiliarySubtypeMode, int displayId);

        @PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
        boolean isInputMethodPickerShownForTest();

        InputMethodSubtype getCurrentInputMethodSubtype(@UserIdInt int userId);

        void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes,
                @UserIdInt int userId);

        void setExplicitlyEnabledInputMethodSubtypes(String imeId,
                @NonNull int[] subtypeHashCodes, @UserIdInt int userId);

        int getInputMethodWindowVisibleHeight(IInputMethodClient client);

        void reportPerceptibleAsync(IBinder windowToken, boolean perceptible);

        @PermissionVerified(Manifest.permission.INTERNAL_SYSTEM_WINDOW)
        void removeImeSurface();

        void removeImeSurfaceFromWindowAsync(IBinder windowToken);

        void startProtoDump(byte[] bytes, int i, String s);

        boolean isImeTraceEnabled();

        @PermissionVerified(Manifest.permission.CONTROL_UI_TRACING)
        void startImeTrace();

        @PermissionVerified(Manifest.permission.CONTROL_UI_TRACING)
        void stopImeTrace();

        void startStylusHandwriting(IInputMethodClient client);

        void startConnectionlessStylusHandwriting(IInputMethodClient client, @UserIdInt int userId,
                @Nullable CursorAnchorInfo cursorAnchorInfo, @Nullable String delegatePackageName,
                @Nullable String delegatorPackageName,
                @NonNull IConnectionlessHandwritingCallback callback);

        boolean acceptStylusHandwritingDelegation(@NonNull IInputMethodClient client,
                @UserIdInt int userId, @NonNull String delegatePackageName,
                @NonNull String delegatorPackageName,
                @InputMethodManager.HandwritingDelegateFlags int flags);

        void acceptStylusHandwritingDelegationAsync(@NonNull IInputMethodClient client,
                @UserIdInt int userId, @NonNull String delegatePackageName,
                @NonNull String delegatorPackageName,
                @InputMethodManager.HandwritingDelegateFlags int flags, IBooleanListener callback);

        void prepareStylusHandwritingDelegation(@NonNull IInputMethodClient client,
                @UserIdInt int userId, @NonNull String delegatePackageName,
                @NonNull String delegatorPackageName);

        boolean isStylusHandwritingAvailableAsUser(@UserIdInt int userId, boolean connectionless);

        @PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
        void addVirtualStylusIdForTestSession(IInputMethodClient client);

        @PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
        void setStylusWindowIdleTimeoutForTest(IInputMethodClient client, long timeout);

        IImeTracker getImeTrackerService();

        void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err, @NonNull String[] args,
                @Nullable ShellCallback callback, @NonNull ResultReceiver resultReceiver,
                @NonNull Binder self);

        void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout, @Nullable String[] args);
    }

    @NonNull
    private final Callback mCallback;

    private IInputMethodManagerImpl(@NonNull Callback callback) {
        mCallback = callback;
    }

    static IInputMethodManagerImpl create(@NonNull Callback callback) {
        return new IInputMethodManagerImpl(callback);
    }

    @Override
    public void addClient(IInputMethodClient client, IRemoteInputConnection inputmethod,
            int untrustedDisplayId) {
        mCallback.addClient(client, inputmethod, untrustedDisplayId);
    }

    @Override
    public InputMethodInfo getCurrentInputMethodInfoAsUser(@UserIdInt int userId) {
        return mCallback.getCurrentInputMethodInfoAsUser(userId);
    }

    @NonNull
    @Override
    public InputMethodInfoSafeList getInputMethodList(@UserIdInt int userId,
            int directBootAwareness) {
        return mCallback.getInputMethodList(userId, directBootAwareness);
    }

    @NonNull
    @Override
    public InputMethodInfoSafeList getEnabledInputMethodList(@UserIdInt int userId) {
        return mCallback.getEnabledInputMethodList(userId);
    }

    @NonNull
    @Override
    public List<InputMethodInfo> getInputMethodListLegacy(@UserIdInt int userId,
            int directBootAwareness) {
        return mCallback.getInputMethodListLegacy(userId, directBootAwareness);
    }

    @NonNull
    @Override
    public List<InputMethodInfo> getEnabledInputMethodListLegacy(@UserIdInt int userId) {
        return mCallback.getEnabledInputMethodListLegacy(userId);
    }

    @Override
    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String imiId,
            boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId) {
        return mCallback.getEnabledInputMethodSubtypeList(imiId, allowsImplicitlyEnabledSubtypes,
                userId);
    }

    @Override
    public InputMethodSubtype getLastInputMethodSubtype(@UserIdInt int userId) {
        return mCallback.getLastInputMethodSubtype(userId);
    }

    @Override
    public boolean showSoftInput(IInputMethodClient client, IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            @MotionEvent.ToolType int lastClickToolType, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        return mCallback.showSoftInput(client, windowToken, statsToken, flags, lastClickToolType,
                resultReceiver, reason);
    }

    @Override
    public boolean hideSoftInput(IInputMethodClient client, IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        return mCallback.hideSoftInput(client, windowToken, statsToken, flags, resultReceiver,
                reason);
    }

    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void hideSoftInputFromServerForTest() {
        super.hideSoftInputFromServerForTest_enforcePermission();

        mCallback.hideSoftInputFromServerForTest();
    }

    @Override
    public InputBindResult startInputOrWindowGainedFocus(
            @StartInputReason int startInputReason, IInputMethodClient client, IBinder windowToken,
            @StartInputFlags int startInputFlags,
            @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode,
            int windowFlags, @Nullable EditorInfo editorInfo,
            IRemoteInputConnection inputConnection,
            IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        return mCallback.startInputOrWindowGainedFocus(
                startInputReason, client, windowToken, startInputFlags, softInputMode,
                windowFlags, editorInfo, inputConnection, remoteAccessibilityInputConnection,
                unverifiedTargetSdkVersion, userId, imeDispatcher);
    }

    @Override
    public void startInputOrWindowGainedFocusAsync(@StartInputReason int startInputReason,
            IInputMethodClient client, IBinder windowToken,
            @StartInputFlags int startInputFlags,
            @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode,
            int windowFlags, @Nullable EditorInfo editorInfo,
            IRemoteInputConnection inputConnection,
            IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher, int startInputSeq) {
        mCallback.startInputOrWindowGainedFocusAsync(
                startInputReason, client, windowToken, startInputFlags, softInputMode,
                windowFlags, editorInfo, inputConnection, remoteAccessibilityInputConnection,
                unverifiedTargetSdkVersion, userId, imeDispatcher, startInputSeq);
    }

    @Override
    public void showInputMethodPickerFromClient(IInputMethodClient client,
            int auxiliarySubtypeMode) {
        mCallback.showInputMethodPickerFromClient(client, auxiliarySubtypeMode);
    }

    @EnforcePermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void showInputMethodPickerFromSystem(int auxiliarySubtypeMode, int displayId) {
        super.showInputMethodPickerFromSystem_enforcePermission();

        mCallback.showInputMethodPickerFromSystem(auxiliarySubtypeMode, displayId);

    }

    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public boolean isInputMethodPickerShownForTest() {
        super.isInputMethodPickerShownForTest_enforcePermission();

        return mCallback.isInputMethodPickerShownForTest();
    }

    @Override
    public InputMethodSubtype getCurrentInputMethodSubtype(@UserIdInt int userId) {
        return mCallback.getCurrentInputMethodSubtype(userId);
    }

    @Override
    public void setAdditionalInputMethodSubtypes(String id, InputMethodSubtype[] subtypes,
            @UserIdInt int userId) {
        mCallback.setAdditionalInputMethodSubtypes(id, subtypes, userId);
    }

    @Override
    public void setExplicitlyEnabledInputMethodSubtypes(String imeId, int[] subtypeHashCodes,
            @UserIdInt int userId) {
        mCallback.setExplicitlyEnabledInputMethodSubtypes(imeId, subtypeHashCodes, userId);
    }

    @Override
    public int getInputMethodWindowVisibleHeight(IInputMethodClient client) {
        return mCallback.getInputMethodWindowVisibleHeight(client);
    }

    @Override
    public void reportPerceptibleAsync(IBinder windowToken, boolean perceptible) {
        mCallback.reportPerceptibleAsync(windowToken, perceptible);
    }

    @EnforcePermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    @Override
    public void removeImeSurface() {
        super.removeImeSurface_enforcePermission();

        mCallback.removeImeSurface();
    }

    @Override
    public void removeImeSurfaceFromWindowAsync(IBinder windowToken) {
        mCallback.removeImeSurfaceFromWindowAsync(windowToken);
    }

    @Override
    public void startProtoDump(byte[] protoDump, int source, String where) {
        mCallback.startProtoDump(protoDump, source, where);
    }

    @Override
    public boolean isImeTraceEnabled() {
        return mCallback.isImeTraceEnabled();
    }

    @EnforcePermission(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void startImeTrace() {
        super.startImeTrace_enforcePermission();

        mCallback.startImeTrace();
    }

    @EnforcePermission(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void stopImeTrace() {
        super.stopImeTrace_enforcePermission();

        mCallback.stopImeTrace();
    }

    @Override
    public void startStylusHandwriting(IInputMethodClient client) {
        mCallback.startStylusHandwriting(client);
    }

    @Override
    public void startConnectionlessStylusHandwriting(IInputMethodClient client,
            @UserIdInt int userId, CursorAnchorInfo cursorAnchorInfo,
            String delegatePackageName, String delegatorPackageName,
            IConnectionlessHandwritingCallback callback) {
        mCallback.startConnectionlessStylusHandwriting(client, userId, cursorAnchorInfo,
                delegatePackageName, delegatorPackageName, callback);
    }

    @Override
    public void prepareStylusHandwritingDelegation(IInputMethodClient client, @UserIdInt int userId,
            String delegatePackageName, String delegatorPackageName) {
        mCallback.prepareStylusHandwritingDelegation(client, userId,
                delegatePackageName, delegatorPackageName);
    }

    @Override
    public boolean acceptStylusHandwritingDelegation(IInputMethodClient client,
            @UserIdInt int userId, String delegatePackageName, String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags) {
        return mCallback.acceptStylusHandwritingDelegation(client, userId,
                delegatePackageName, delegatorPackageName, flags);
    }

    @Override
    public void acceptStylusHandwritingDelegationAsync(IInputMethodClient client,
            @UserIdInt int userId, String delegatePackageName, String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags,
            IBooleanListener callback) {
        mCallback.acceptStylusHandwritingDelegationAsync(client, userId,
                delegatePackageName, delegatorPackageName, flags, callback);
    }

    @Override
    public boolean isStylusHandwritingAvailableAsUser(@UserIdInt int userId,
            boolean connectionless) {
        return mCallback.isStylusHandwritingAvailableAsUser(userId, connectionless);
    }

    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void addVirtualStylusIdForTestSession(IInputMethodClient client) {
        super.addVirtualStylusIdForTestSession_enforcePermission();

        mCallback.addVirtualStylusIdForTestSession(client);
    }

    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void setStylusWindowIdleTimeoutForTest(IInputMethodClient client, long timeout) {
        super.setStylusWindowIdleTimeoutForTest_enforcePermission();

        mCallback.setStylusWindowIdleTimeoutForTest(client, timeout);
    }

    @Override
    public IImeTracker getImeTrackerService() {
        return mCallback.getImeTrackerService();
    }

    @Override
    public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err, @NonNull String[] args, @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) {
        mCallback.onShellCommand(in, out, err, args, callback, resultReceiver, this);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mCallback.dump(fd, pw, args);
    }
}
