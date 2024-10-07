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

package android.view.inputmethod;

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.util.ExceptionUtils;
import android.view.WindowManager;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.infra.AndroidFuture;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A global wrapper to directly invoke {@link IInputMethodManager} IPCs.
 *
 * <p>All public static methods are guaranteed to be thread-safe.</p>
 *
 * <p>All public methods are guaranteed to do nothing when {@link IInputMethodManager} is
 * unavailable.</p>
 *
 * <p>If you want to use any of this method outside of {@code android.view.inputmethod}, create
 * a wrapper method in {@link InputMethodManagerGlobal} instead of making this class public.</p>
 */
final class IInputMethodManagerGlobalInvoker {

    /** The threshold in milliseconds for an {@link AndroidFuture} completion signal. */
    private static final long TIMEOUT_MS = 10_000;

    @Nullable
    private static volatile IInputMethodManager sServiceCache = null;

    @Nullable
    private static volatile IImeTracker sTrackerServiceCache = null;
    private static int sCurStartInputSeq = 0;

    /**
     * @return {@code true} if {@link IInputMethodManager} is available.
     */
    @AnyThread
    static boolean isAvailable() {
        return getService() != null;
    }

    @AnyThread
    @Nullable
    static IInputMethodManager getService() {
        IInputMethodManager service = sServiceCache;
        if (service == null) {
            if (InputMethodManager.isInEditModeInternal()) {
                return null;
            }
            service = IInputMethodManager.Stub.asInterface(
                    ServiceManager.getService(Context.INPUT_METHOD_SERVICE));
            if (service == null) {
                return null;
            }
            sServiceCache = service;
        }
        return service;
    }

    @AnyThread
    private static void handleRemoteExceptionOrRethrow(@NonNull RemoteException e,
            @Nullable Consumer<RemoteException> exceptionHandler) {
        if (exceptionHandler != null) {
            exceptionHandler.accept(e);
        } else {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Invokes {@link IInputMethodManager#startProtoDump(byte[], int, String)}.
     *
     * @param protoDump client or service side information to be stored by the server
     * @param source where the information is coming from, refer to
     *               {@link com.android.internal.inputmethod.ImeTracing#IME_TRACING_FROM_CLIENT} and
     *               {@link com.android.internal.inputmethod.ImeTracing#IME_TRACING_FROM_IMS}
     * @param where where the information is coming from.
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @AnyThread
    @RequiresNoPermission
    static void startProtoDump(byte[] protoDump, int source, String where,
            @Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.startProtoDump(protoDump, source, where);
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    /**
     * Invokes {@link IInputMethodManager#startImeTrace()}.
     *
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @AnyThread
    @RequiresPermission(Manifest.permission.CONTROL_UI_TRACING)
    static void startImeTrace(@Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.startImeTrace();
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    /**
     * Invokes {@link IInputMethodManager#stopImeTrace()}.
     *
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @AnyThread
    @RequiresPermission(Manifest.permission.CONTROL_UI_TRACING)
    static void stopImeTrace(@Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.stopImeTrace();
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    /**
     * Invokes {@link IInputMethodManager#isImeTraceEnabled()}.
     *
     * @return The return value of {@link IInputMethodManager#isImeTraceEnabled()}.
     */
    @AnyThread
    @RequiresNoPermission
    static boolean isImeTraceEnabled() {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isImeTraceEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Invokes {@link IInputMethodManager#removeImeSurface()}
     *
     * @param displayId display ID from which this request originates
     * @param exceptionHandler an optional {@link RemoteException} handler
     */
    @AnyThread
    @RequiresPermission(allOf = {
            Manifest.permission.INTERNAL_SYSTEM_WINDOW,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    static void removeImeSurface(int displayId,
            @Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.removeImeSurface(displayId);
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    @AnyThread
    static void addClient(@NonNull IInputMethodClient client,
            @NonNull IRemoteInputConnection fallbackInputConnection, int untrustedDisplayId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.addClient(client, fallbackInputConnection, untrustedDisplayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @Nullable
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static InputMethodInfo getCurrentInputMethodInfoAsUser(@UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return null;
        }
        try {
            return service.getCurrentInputMethodInfoAsUser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static List<InputMethodInfo> getInputMethodList(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return new ArrayList<>();
        }
        try {
            if (Flags.useInputMethodInfoSafeList()) {
                return InputMethodInfoSafeList.extractFrom(
                        service.getInputMethodList(userId, directBootAwareness));
            } else {
                return service.getInputMethodListLegacy(userId, directBootAwareness);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static List<InputMethodInfo> getEnabledInputMethodList(@UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return new ArrayList<>();
        }
        try {
            if (Flags.useInputMethodInfoSafeList()) {
                return InputMethodInfoSafeList.extractFrom(
                        service.getEnabledInputMethodList(userId));
            } else {
                return service.getEnabledInputMethodListLegacy(userId);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static List<InputMethodSubtype> getEnabledInputMethodSubtypeList(@Nullable String imiId,
            boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return new ArrayList<>();
        }
        try {
            return service.getEnabledInputMethodSubtypeList(imiId,
                    allowsImplicitlyEnabledSubtypes, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @Nullable
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static InputMethodSubtype getLastInputMethodSubtype(@UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return null;
        }
        try {
            return service.getLastInputMethodSubtype(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static boolean showSoftInput(@NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            int lastClickToolType, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason, boolean async) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.showSoftInput(client, windowToken, statsToken, flags, lastClickToolType,
                    resultReceiver, reason, async);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static boolean hideSoftInput(@NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
            @Nullable ResultReceiver resultReceiver, @SoftInputShowHideReason int reason,
            boolean async) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.hideSoftInput(client, windowToken, statsToken, flags, resultReceiver,
                    reason, async);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO(b/293640003): Remove method once Flags.useZeroJankProxy() is enabled.
    @AnyThread
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    static void hideSoftInputFromServerForTest() {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.hideSoftInputFromServerForTest();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static InputBindResult startInputOrWindowGainedFocus(@StartInputReason int startInputReason,
            @NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            @StartInputFlags int startInputFlags,
            @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode,
            @WindowManager.LayoutParams.Flags int windowFlags, @Nullable EditorInfo editorInfo,
            @Nullable IRemoteInputConnection remoteInputConnection,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return InputBindResult.NULL;
        }
        try {
            return service.startInputOrWindowGainedFocus(startInputReason, client, windowToken,
                    startInputFlags, softInputMode, windowFlags, editorInfo, remoteInputConnection,
                    remoteAccessibilityInputConnection, unverifiedTargetSdkVersion, userId,
                    imeDispatcher);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a sequence number for startInput.
     */
    @AnyThread
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static int startInputOrWindowGainedFocusAsync(@StartInputReason int startInputReason,
            @NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            @StartInputFlags int startInputFlags,
            @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode,
            @WindowManager.LayoutParams.Flags int windowFlags, @Nullable EditorInfo editorInfo,
            @Nullable IRemoteInputConnection remoteInputConnection,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher, boolean useAsyncShowHideMethod) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return -1;
        }
        try {
            service.startInputOrWindowGainedFocusAsync(startInputReason, client, windowToken,
                    startInputFlags, softInputMode, windowFlags, editorInfo, remoteInputConnection,
                    remoteAccessibilityInputConnection, unverifiedTargetSdkVersion, userId,
                    imeDispatcher, advanceAngGetStartInputSequenceNumber(), useAsyncShowHideMethod);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return sCurStartInputSeq;
    }

    private static int advanceAngGetStartInputSequenceNumber() {
        return ++sCurStartInputSeq;
    }


    @AnyThread
    static void showInputMethodPickerFromClient(@NonNull IInputMethodClient client,
            int auxiliarySubtypeMode) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.showInputMethodPickerFromClient(client, auxiliarySubtypeMode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @RequiresPermission(allOf = {
            Manifest.permission.WRITE_SECURE_SETTINGS,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    static void showInputMethodPickerFromSystem(int auxiliarySubtypeMode, int displayId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.showInputMethodPickerFromSystem(auxiliarySubtypeMode, displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    static boolean isInputMethodPickerShownForTest() {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isInputMethodPickerShownForTest();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @RequiresPermission(allOf = {
            Manifest.permission.WRITE_SECURE_SETTINGS,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    static void onImeSwitchButtonClickFromSystem(int displayId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.onImeSwitchButtonClickFromSystem(displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @Nullable
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static InputMethodSubtype getCurrentInputMethodSubtype(@UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return null;
        }
        try {
            return service.getCurrentInputMethodSubtype(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static void setAdditionalInputMethodSubtypes(@NonNull String imeId,
            @NonNull InputMethodSubtype[] subtypes, @UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.setAdditionalInputMethodSubtypes(imeId, subtypes, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static void setExplicitlyEnabledInputMethodSubtypes(@NonNull String imeId,
            @NonNull int[] subtypeHashCodes, @UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.setExplicitlyEnabledInputMethodSubtypes(imeId, subtypeHashCodes, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static int getInputMethodWindowVisibleHeight(@NonNull IInputMethodClient client) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return 0;
        }
        try {
            return service.getInputMethodWindowVisibleHeight(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void reportPerceptibleAsync(@NonNull IBinder windowToken, boolean perceptible) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.reportPerceptibleAsync(windowToken, perceptible);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void removeImeSurfaceFromWindowAsync(@NonNull IBinder windowToken) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.removeImeSurfaceFromWindowAsync(windowToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static void startStylusHandwriting(@NonNull IInputMethodClient client) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.startStylusHandwriting(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static boolean startConnectionlessStylusHandwriting(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @Nullable CursorAnchorInfo cursorAnchorInfo,
            @Nullable String delegatePackageName,
            @Nullable String delegatorPackageName,
            @NonNull IConnectionlessHandwritingCallback callback) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            service.startConnectionlessStylusHandwriting(client, userId, cursorAnchorInfo,
                    delegatePackageName, delegatorPackageName, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return true;
    }

    @AnyThread
    static void prepareStylusHandwritingDelegation(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.prepareStylusHandwritingDelegation(
                    client, userId, delegatePackageName, delegatorPackageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static boolean acceptStylusHandwritingDelegation(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.acceptStylusHandwritingDelegation(
                    client, userId, delegatePackageName, delegatorPackageName, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Returns {@code true} if method is invoked */
    @AnyThread
    static boolean acceptStylusHandwritingDelegationAsync(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags,
            @NonNull IBooleanListener callback) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            service.acceptStylusHandwritingDelegationAsync(
                    client, userId, delegatePackageName, delegatorPackageName, flags, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return true;
    }

    @AnyThread
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static boolean isStylusHandwritingAvailableAsUser(
            @UserIdInt int userId, boolean connectionless) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isStylusHandwritingAvailableAsUser(userId, connectionless);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    static void addVirtualStylusIdForTestSession(IInputMethodClient client) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.addVirtualStylusIdForTestSession(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    static void setStylusWindowIdleTimeoutForTest(
            IInputMethodClient client, @DurationMillisLong long timeout) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.setStylusWindowIdleTimeoutForTest(client, timeout);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see com.android.server.inputmethod.ImeTrackerService#onStart */
    @AnyThread
    @NonNull
    static ImeTracker.Token onStart(@NonNull String tag, int uid, @ImeTracker.Type int type,
            @ImeTracker.Origin int origin, @SoftInputShowHideReason int reason, boolean fromUser) {
        final var service = getImeTrackerService();
        if (service == null) {
            // Create token with "empty" binder if the service was not found.
            return ImeTracker.Token.empty(tag);
        }
        try {
            return service.onStart(tag, uid, type, origin, reason, fromUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see com.android.server.inputmethod.ImeTrackerService#onProgress */
    @AnyThread
    static void onProgress(@NonNull IBinder binder, @ImeTracker.Phase int phase) {
        final IImeTracker service = getImeTrackerService();
        if (service == null) {
            return;
        }
        try {
            service.onProgress(binder, phase);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see com.android.server.inputmethod.ImeTrackerService#onFailed */
    @AnyThread
    static void onFailed(@NonNull ImeTracker.Token statsToken, @ImeTracker.Phase int phase) {
        final IImeTracker service = getImeTrackerService();
        if (service == null) {
            return;
        }
        try {
            service.onFailed(statsToken, phase);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see com.android.server.inputmethod.ImeTrackerService#onCancelled */
    @AnyThread
    static void onCancelled(@NonNull ImeTracker.Token statsToken, @ImeTracker.Phase int phase) {
        final IImeTracker service = getImeTrackerService();
        if (service == null) {
            return;
        }
        try {
            service.onCancelled(statsToken, phase);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see com.android.server.inputmethod.ImeTrackerService#onShown */
    @AnyThread
    static void onShown(@NonNull ImeTracker.Token statsToken) {
        final IImeTracker service = getImeTrackerService();
        if (service == null) {
            return;
        }
        try {
            service.onShown(statsToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see com.android.server.inputmethod.ImeTrackerService#onHidden */
    @AnyThread
    static void onHidden(@NonNull ImeTracker.Token statsToken) {
        final IImeTracker service = getImeTrackerService();
        if (service == null) {
            return;
        }
        try {
            service.onHidden(statsToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see com.android.server.inputmethod.ImeTrackerService#onDispatched */
    static void onDispatched(@NonNull ImeTracker.Token statsToken) {
        final IImeTracker service = getImeTrackerService();
        if (service == null) {
            return;
        }
        try {
            service.onDispatched(statsToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see com.android.server.inputmethod.ImeTrackerService#hasPendingImeVisibilityRequests */
    @AnyThread
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    static boolean hasPendingImeVisibilityRequests() {
        final var service = getImeTrackerService();
        if (service == null) {
            return true;
        }
        try {
            return service.hasPendingImeVisibilityRequests();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    static void finishTrackingPendingImeVisibilityRequests() {
        final var service = getImeTrackerService();
        if (service == null) {
            return;
        }
        try {
            final var completionSignal = new AndroidFuture<Void>();
            service.finishTrackingPendingImeVisibilityRequests(completionSignal);
            completionSignal.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (Exception e) {
            throw ExceptionUtils.propagate(e);
        }
    }

    @AnyThread
    @Nullable
    private static IImeTracker getImeTrackerService() {
        var trackerService = sTrackerServiceCache;
        if (trackerService == null) {
            final var service = getService();
            if (service == null) {
                return null;
            }

            try {
                trackerService = service.getImeTrackerService();
                if (trackerService == null) {
                    return null;
                }

                sTrackerServiceCache = trackerService;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return trackerService;
    }
}
