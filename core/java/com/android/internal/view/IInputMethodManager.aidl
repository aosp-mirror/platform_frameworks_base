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
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;

/**
 * Public interface to the global input method manager, used by all client
 * applications.
 */
interface IInputMethodManager {
    void addClient(in IInputMethodClient client, in IRemoteInputConnection inputmethod,
            int untrustedDisplayId);

    // TODO: Use ParceledListSlice instead
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    List<InputMethodInfo> getInputMethodList(int userId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    List<InputMethodInfo> getAwareLockedInputMethodList(int userId, int directBootAwareness);

    // TODO: Use ParceledListSlice instead
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    List<InputMethodInfo> getEnabledInputMethodList(int userId);

    List<InputMethodSubtype> getEnabledInputMethodSubtypeList(in String imiId,
            boolean allowsImplicitlySelectedSubtypes);
    InputMethodSubtype getLastInputMethodSubtype();

    boolean showSoftInput(in IInputMethodClient client, IBinder windowToken, int flags,
            in ResultReceiver resultReceiver, int reason);
    boolean hideSoftInput(in IInputMethodClient client, IBinder windowToken, int flags,
            in ResultReceiver resultReceiver, int reason);

    // If windowToken is null, this just does startInput().  Otherwise this reports that a window
    // has gained focus, and if 'editorInfo' is non-null then also does startInput.
    // @NonNull
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    InputBindResult startInputOrWindowGainedFocus(
            /* @StartInputReason */ int startInputReason,
            in IInputMethodClient client, in IBinder windowToken,
            /* @StartInputFlags */ int startInputFlags,
            /* @android.view.WindowManager.LayoutParams.SoftInputModeFlags */ int softInputMode,
            int windowFlags, in EditorInfo editorInfo, in IRemoteInputConnection inputConnection,
            in IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, in ImeOnBackInvokedDispatcher imeDispatcher);

    void showInputMethodPickerFromClient(in IInputMethodClient client,
            int auxiliarySubtypeMode);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.WRITE_SECURE_SETTINGS)")
    void showInputMethodPickerFromSystem(in IInputMethodClient client,
            int auxiliarySubtypeMode, int displayId);

    void showInputMethodAndSubtypeEnablerFromClient(in IInputMethodClient client, String topId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.TEST_INPUT_METHOD)")
    boolean isInputMethodPickerShownForTest();

    InputMethodSubtype getCurrentInputMethodSubtype();
    void setAdditionalInputMethodSubtypes(String id, in InputMethodSubtype[] subtypes);
    // This is kept due to @UnsupportedAppUsage.
    // TODO(Bug 113914148): Consider removing this.
    int getInputMethodWindowVisibleHeight(in IInputMethodClient client);

    oneway void reportVirtualDisplayGeometryAsync(in IInputMethodClient parentClient,
            int childDisplayId, in float[] matrixValues);

    oneway void reportPerceptibleAsync(in IBinder windowToken, boolean perceptible);

    @EnforcePermission("INTERNAL_SYSTEM_WINDOW")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERNAL_SYSTEM_WINDOW)")
    void removeImeSurface();

    /** Remove the IME surface. Requires passing the currently focused window. */
    oneway void removeImeSurfaceFromWindowAsync(in IBinder windowToken);
    void startProtoDump(in byte[] protoDump, int source, String where);
    boolean isImeTraceEnabled();

    // Starts an ime trace.
    void startImeTrace();
    // Stops an ime trace.
    void stopImeTrace();

    /** Start Stylus handwriting session **/
    void startStylusHandwriting(in IInputMethodClient client);

    /** Returns {@code true} if currently selected IME supports Stylus handwriting. */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    boolean isStylusHandwritingAvailableAsUser(int userId);

    /** add virtual stylus id for test Stylus handwriting session **/
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INJECT_EVENTS)")
    void addVirtualStylusIdForTestSession(in IInputMethodClient client);
}
