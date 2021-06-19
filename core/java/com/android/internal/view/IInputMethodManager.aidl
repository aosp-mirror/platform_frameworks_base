/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.view;

import android.os.ResultReceiver;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.EditorInfo;

import com.android.internal.view.InputBindResult;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.inputmethod.IBooleanResultCallback;
import com.android.internal.inputmethod.IInputBindResultResultCallback;
import com.android.internal.inputmethod.IInputMethodInfoListResultCallback;
import com.android.internal.inputmethod.IInputMethodSubtypeResultCallback;
import com.android.internal.inputmethod.IInputMethodSubtypeListResultCallback;
import com.android.internal.inputmethod.IIntResultCallback;
import com.android.internal.inputmethod.IVoidResultCallback;

/**
 * Public interface to the global input method manager, used by all client
 * applications.
 */
interface IInputMethodManager {
    void addClient(in IInputMethodClient client, in IInputContext inputContext,
            int untrustedDisplayId);

    // TODO: Use ParceledListSlice instead
    oneway void getInputMethodList(int userId,
            in IInputMethodInfoListResultCallback resultCallback);
    // TODO: Use ParceledListSlice instead
    oneway void getEnabledInputMethodList(int userId,
            in IInputMethodInfoListResultCallback resultCallback);
    oneway void getEnabledInputMethodSubtypeList(in String imiId,
            boolean allowsImplicitlySelectedSubtypes,
            in IInputMethodSubtypeListResultCallback resultCallback);
    oneway void getLastInputMethodSubtype(in IInputMethodSubtypeResultCallback resultCallback);

    oneway void showSoftInput(in IInputMethodClient client, IBinder windowToken, int flags,
            in ResultReceiver resultReceiver, int reason, in IBooleanResultCallback resultCallback);
    oneway void hideSoftInput(in IInputMethodClient client, IBinder windowToken, int flags,
            in ResultReceiver resultReceiver, int reason, in IBooleanResultCallback resultCallback);
    // If windowToken is null, this just does startInput().  Otherwise this reports that a window
    // has gained focus, and if 'attribute' is non-null then also does startInput.
    // @NonNull
    oneway void startInputOrWindowGainedFocus(
            /* @StartInputReason */ int startInputReason,
            in IInputMethodClient client, in IBinder windowToken,
            /* @StartInputFlags */ int startInputFlags,
            /* @android.view.WindowManager.LayoutParams.SoftInputModeFlags */ int softInputMode,
            int windowFlags, in EditorInfo attribute, IInputContext inputContext,
            /* @InputConnectionInspector.MissingMethodFlags */ int missingMethodFlags,
            int unverifiedTargetSdkVersion,
            in IInputBindResultResultCallback inputBindResult);

    oneway void reportWindowGainedFocusAsync(
            boolean nextFocusHasConnection, in IInputMethodClient client, in IBinder windowToken,
            /* @StartInputFlags */ int startInputFlags,
            /* @android.view.WindowManager.LayoutParams.SoftInputModeFlags */ int softInputMode,
            int windowFlags, int unverifiedTargetSdkVersion);

    oneway void showInputMethodPickerFromClient(in IInputMethodClient client,
            int auxiliarySubtypeMode, in IVoidResultCallback resultCallback);
    oneway void showInputMethodPickerFromSystem(in IInputMethodClient client,
            int auxiliarySubtypeMode, int displayId, in IVoidResultCallback resultCallback);
    oneway void showInputMethodAndSubtypeEnablerFromClient(in IInputMethodClient client,
            String topId, in IVoidResultCallback resultCallback);
    oneway void isInputMethodPickerShownForTest(in IBooleanResultCallback resultCallback);
    oneway void getCurrentInputMethodSubtype(in IInputMethodSubtypeResultCallback resultCallback);
    oneway void setAdditionalInputMethodSubtypes(String id, in InputMethodSubtype[] subtypes,
            in IVoidResultCallback resultCallback);
    // This is kept due to @UnsupportedAppUsage.
    // TODO(Bug 113914148): Consider removing this.
    oneway void getInputMethodWindowVisibleHeight(IIntResultCallback resultCallback);

    oneway void reportPerceptibleAsync(in IBinder windowToken, boolean perceptible);
    /** Remove the IME surface. Requires INTERNAL_SYSTEM_WINDOW permission. */
    oneway void removeImeSurface(in IVoidResultCallback resultCallback);
    /** Remove the IME surface. Requires passing the currently focused window. */
    oneway void removeImeSurfaceFromWindowAsync(in IBinder windowToken);
    oneway void startProtoDump(in byte[] protoDump, int source, String where,
            in IVoidResultCallback resultCallback);
    oneway void isImeTraceEnabled(in IBooleanResultCallback resultCallback);

    // Starts an ime trace.
    oneway void startImeTrace(in IVoidResultCallback resultCallback);
    // Stops an ime trace.
    oneway void stopImeTrace(in IVoidResultCallback resultCallback);
}
