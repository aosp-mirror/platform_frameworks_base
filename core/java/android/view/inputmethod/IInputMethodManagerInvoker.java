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

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.view.IInputMethodManager;

import java.util.List;

/**
 * A wrapper class to invoke IPCs defined in {@link IInputMethodManager}.
 */
final class IInputMethodManagerInvoker {
    @NonNull
    private final IInputMethodManager mTarget;

    private IInputMethodManagerInvoker(@NonNull IInputMethodManager target) {
        mTarget = target;
    }

    @AnyThread
    @NonNull
    static IInputMethodManagerInvoker create(@NonNull IInputMethodManager imm) {
        return new IInputMethodManagerInvoker(imm);
    }

    @AnyThread
    void addClient(@NonNull IInputMethodClient client,
            @NonNull IRemoteInputConnection fallbackInputConnection, int untrustedDisplayId) {
        try {
            mTarget.addClient(client, fallbackInputConnection, untrustedDisplayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    List<InputMethodInfo> getInputMethodList(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        try {
            return mTarget.getInputMethodList(userId, directBootAwareness);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    List<InputMethodInfo> getEnabledInputMethodList(@UserIdInt int userId) {
        try {
            return mTarget.getEnabledInputMethodList(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    List<InputMethodSubtype> getEnabledInputMethodSubtypeList(@Nullable String imiId,
            boolean allowsImplicitlySelectedSubtypes, @UserIdInt int userId) {
        try {
            return mTarget.getEnabledInputMethodSubtypeList(imiId,
                    allowsImplicitlySelectedSubtypes, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @Nullable
    InputMethodSubtype getLastInputMethodSubtype(@UserIdInt int userId) {
        try {
            return mTarget.getLastInputMethodSubtype(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    boolean showSoftInput(@NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            int flags, int lastClickToolType, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        try {
            return mTarget.showSoftInput(
                    client, windowToken, flags, lastClickToolType, resultReceiver, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    boolean hideSoftInput(@NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            int flags, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        try {
            return mTarget.hideSoftInput(client, windowToken, flags, resultReceiver, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @NonNull
    InputBindResult startInputOrWindowGainedFocus(@StartInputReason int startInputReason,
            @NonNull IInputMethodClient client, @Nullable IBinder windowToken,
            @StartInputFlags int startInputFlags, @SoftInputModeFlags int softInputMode,
            @WindowManager.LayoutParams.Flags int windowFlags, @Nullable EditorInfo editorInfo,
            @Nullable IRemoteInputConnection remoteInputConnection,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        try {
            return mTarget.startInputOrWindowGainedFocus(startInputReason, client, windowToken,
                    startInputFlags, softInputMode, windowFlags, editorInfo, remoteInputConnection,
                    remoteAccessibilityInputConnection, unverifiedTargetSdkVersion, userId,
                    imeDispatcher);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    void showInputMethodPickerFromClient(@NonNull IInputMethodClient client,
            int auxiliarySubtypeMode) {
        try {
            mTarget.showInputMethodPickerFromClient(client, auxiliarySubtypeMode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    void showInputMethodPickerFromSystem(@NonNull IInputMethodClient client,
            int auxiliarySubtypeMode, int displayId) {
        try {
            mTarget.showInputMethodPickerFromSystem(client, auxiliarySubtypeMode, displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    boolean isInputMethodPickerShownForTest() {
        try {
            return mTarget.isInputMethodPickerShownForTest();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    @Nullable
    InputMethodSubtype getCurrentInputMethodSubtype(@UserIdInt int userId) {
        try {
            return mTarget.getCurrentInputMethodSubtype(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    void setAdditionalInputMethodSubtypes(@NonNull String imeId,
            @NonNull InputMethodSubtype[] subtypes, @UserIdInt int userId) {
        try {
            mTarget.setAdditionalInputMethodSubtypes(imeId, subtypes, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    void setExplicitlyEnabledInputMethodSubtypes(@NonNull String imeId,
            @NonNull int[] subtypeHashCodes, @UserIdInt int userId) {
        try {
            mTarget.setExplicitlyEnabledInputMethodSubtypes(imeId, subtypeHashCodes, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    int getInputMethodWindowVisibleHeight(@NonNull IInputMethodClient client) {
        try {
            return mTarget.getInputMethodWindowVisibleHeight(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    void reportVirtualDisplayGeometryAsync(@NonNull IInputMethodClient client, int childDisplayId,
            @Nullable float[] matrixValues) {
        try {
            mTarget.reportVirtualDisplayGeometryAsync(client, childDisplayId, matrixValues);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    void reportPerceptibleAsync(@NonNull IBinder windowToken, boolean perceptible) {
        try {
            mTarget.reportPerceptibleAsync(windowToken, perceptible);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    void removeImeSurfaceFromWindowAsync(@NonNull IBinder windowToken) {
        try {
            mTarget.removeImeSurfaceFromWindowAsync(windowToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    void startStylusHandwriting(@NonNull IInputMethodClient client) {
        try {
            mTarget.startStylusHandwriting(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    boolean isStylusHandwritingAvailableAsUser(@UserIdInt int userId) {
        try {
            return mTarget.isStylusHandwritingAvailableAsUser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @AnyThread
    void addVirtualStylusIdForTestSession(IInputMethodClient client) {
        try {
            mTarget.addVirtualStylusIdForTestSession(client);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
