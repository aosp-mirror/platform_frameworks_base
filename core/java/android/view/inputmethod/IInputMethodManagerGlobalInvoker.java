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
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.view.WindowManager;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IImeTracker;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.view.IInputMethodManager;

import java.util.ArrayList;
import java.util.List;
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
    @Nullable
    private static volatile IInputMethodManager sServiceCache = null;

    @Nullable
    private static volatile IImeTracker sTrackerServiceCache = null;

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
     */
    @AnyThread
    @RequiresPermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    static void removeImeSurface(@Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.removeImeSurface();
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
            return service.getInputMethodList(userId, directBootAwareness);
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
            return service.getEnabledInputMethodList(userId);
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
            @Nullable ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            int lastClickToolType, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.showSoftInput(client, windowToken, statsToken, flags, lastClickToolType,
                    resultReceiver, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    static boolean hideSoftInput(@NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            @Nullable ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
            @Nullable ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.hideSoftInput(client, windowToken, statsToken, flags, resultReceiver,
                    reason);
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
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
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

    @AnyThread
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    static boolean isStylusHandwritingAvailableAsUser(@UserIdInt int userId) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isStylusHandwritingAvailableAsUser(userId);
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

    /** @see com.android.server.inputmethod.ImeTrackerService#onRequestShow */
    @AnyThread
    @NonNull
    static ImeTracker.Token onRequestShow(@NonNull String tag, int uid,
            @ImeTracker.Origin int origin, @SoftInputShowHideReason int reason, boolean fromUser) {
        final IImeTracker service = getImeTrackerService();
        if (service == null) {
            // Create token with "fake" binder if the service was not found.
            return new ImeTracker.Token(new Binder(), tag);
        }
        try {
            return service.onRequestShow(tag, uid, origin, reason, fromUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @see com.android.server.inputmethod.ImeTrackerService#onRequestHide */
    @AnyThread
    @NonNull
    static ImeTracker.Token onRequestHide(@NonNull String tag, int uid,
            @ImeTracker.Origin int origin, @SoftInputShowHideReason int reason, boolean fromUser) {
        final IImeTracker service = getImeTrackerService();
        if (service == null) {
            // Create token with "fake" binder if the service was not found.
            return new ImeTracker.Token(new Binder(), tag);
        }
        try {
            return service.onRequestHide(tag, uid, origin, reason, fromUser);
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
