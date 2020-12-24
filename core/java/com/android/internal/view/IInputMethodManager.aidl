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

/**
 * Public interface to the global input method manager, used by all client
 * applications.
 */
interface IInputMethodManager {
    void addClient(in IInputMethodClient client, in IInputContext inputContext,
            int untrustedDisplayId);

    // TODO: Use ParceledListSlice instead
    void getInputMethodList(int userId, in IInputMethodInfoListResultCallback resultCallback);
    // TODO: Use ParceledListSlice instead
    void getEnabledInputMethodList(int userId,
            in IInputMethodInfoListResultCallback resultCallback);
    void getEnabledInputMethodSubtypeList(in String imiId, boolean allowsImplicitlySelectedSubtypes,
            in IInputMethodSubtypeListResultCallback resultCallback);
    void getLastInputMethodSubtype(in IInputMethodSubtypeResultCallback resultCallback);

    void showSoftInput(in IInputMethodClient client, IBinder windowToken, int flags,
            in ResultReceiver resultReceiver, in IBooleanResultCallback resultCallback);
    void hideSoftInput(in IInputMethodClient client, IBinder windowToken, int flags,
            in ResultReceiver resultReceiver, in IBooleanResultCallback resultCallback);
    // If windowToken is null, this just does startInput().  Otherwise this reports that a window
    // has gained focus, and if 'attribute' is non-null then also does startInput.
    // @NonNull
    void startInputOrWindowGainedFocus(
            /* @StartInputReason */ int startInputReason,
            in IInputMethodClient client, in IBinder windowToken,
            /* @StartInputFlags */ int startInputFlags,
            /* @android.view.WindowManager.LayoutParams.SoftInputModeFlags */ int softInputMode,
            int windowFlags, in EditorInfo attribute, IInputContext inputContext,
            /* @InputConnectionInspector.MissingMethodFlags */ int missingMethodFlags,
            int unverifiedTargetSdkVersion,
            in IInputBindResultResultCallback inputBindResult);

    void showInputMethodPickerFromClient(in IInputMethodClient client,
            int auxiliarySubtypeMode);
    void showInputMethodPickerFromSystem(in IInputMethodClient client, int auxiliarySubtypeMode,
            int displayId);
    void showInputMethodAndSubtypeEnablerFromClient(in IInputMethodClient client, String topId);
    void isInputMethodPickerShownForTest(in IBooleanResultCallback resultCallback);
    void getCurrentInputMethodSubtype(in IInputMethodSubtypeResultCallback resultCallback);
    void setAdditionalInputMethodSubtypes(String id, in InputMethodSubtype[] subtypes);
    // This is kept due to @UnsupportedAppUsage.
    // TODO(Bug 113914148): Consider removing this.
    void getInputMethodWindowVisibleHeight(IIntResultCallback resultCallback);

    void reportActivityView(in IInputMethodClient parentClient, int childDisplayId,
            in float[] matrixValues);

    oneway void reportPerceptible(in IBinder windowToken, boolean perceptible);
    /** Remove the IME surface. Requires INTERNAL_SYSTEM_WINDOW permission. */
    void removeImeSurface();
    /** Remove the IME surface. Requires passing the currently focused window. */
    void removeImeSurfaceFromWindow(in IBinder windowToken);
    void startProtoDump(in byte[] protoDump, int source, String where);
    void isImeTraceEnabled(in IBooleanResultCallback resultCallback);

    // Starts an ime trace.
    void startImeTrace();
    // Stops an ime trace.
    void stopImeTrace();
}
