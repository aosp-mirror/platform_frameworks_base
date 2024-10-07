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
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.EditorInfo;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.inputmethod.IBooleanListener;
import com.android.internal.inputmethod.IConnectionlessHandwritingCallback;
import com.android.internal.inputmethod.IImeTracker;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.InputMethodInfoSafeList;

/**
 * Public interface to the global input method manager, used by all client applications.
 *
 * When adding new methods, make sure the associated user can be inferred from the arguments.
 * Consider passing the associated userId when not already passing a display id or a window token.
 */
interface IInputMethodManager {
    void addClient(in IInputMethodClient client, in IRemoteInputConnection inputmethod,
            int untrustedDisplayId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    InputMethodInfo getCurrentInputMethodInfoAsUser(int userId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    InputMethodInfoSafeList getInputMethodList(int userId, int directBootAwareness);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    InputMethodInfoSafeList getEnabledInputMethodList(int userId);

    // TODO(b/339761278): Remove after getInputMethodList() is fully deployed.
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    List<InputMethodInfo> getInputMethodListLegacy(int userId, int directBootAwareness);

    // TODO(b/339761278): Remove after getEnabledInputMethodList() is fully deployed.
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    List<InputMethodInfo> getEnabledInputMethodListLegacy(int userId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    List<InputMethodSubtype> getEnabledInputMethodSubtypeList(in @nullable String imiId,
            boolean allowsImplicitlyEnabledSubtypes, int userId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    InputMethodSubtype getLastInputMethodSubtype(int userId);

    boolean showSoftInput(in IInputMethodClient client, @nullable IBinder windowToken,
            in ImeTracker.Token statsToken, int flags, int lastClickToolType,
            in @nullable ResultReceiver resultReceiver, int reason, boolean async);
    boolean hideSoftInput(in IInputMethodClient client, @nullable IBinder windowToken,
            in ImeTracker.Token statsToken, int flags,
            in @nullable ResultReceiver resultReceiver, int reason, boolean async);

    /**
     * A test API for CTS to request hiding the current soft input window, with the request origin
     * on the server side.
     */
    @EnforcePermission("TEST_INPUT_METHOD")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.TEST_INPUT_METHOD)")
    void hideSoftInputFromServerForTest();

    // TODO(b/293640003): Remove method once Flags.useZeroJankProxy() is enabled.
    // If windowToken is null, this just does startInput().  Otherwise this reports that a window
    // has gained focus, and if 'editorInfo' is non-null then also does startInput.
    // @NonNull
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    InputBindResult startInputOrWindowGainedFocus(
            /* @StartInputReason */ int startInputReason,
            in IInputMethodClient client, in @nullable IBinder windowToken,
            /* @StartInputFlags */ int startInputFlags,
            /* @android.view.WindowManager.LayoutParams.SoftInputModeFlags */ int softInputMode,
            /* @android.view.WindowManager.LayoutParams.Flags */ int windowFlags,
            in @nullable EditorInfo editorInfo, in @nullable IRemoteInputConnection inputConnection,
            in @nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, int userId,
            in ImeOnBackInvokedDispatcher imeDispatcher);

    // If windowToken is null, this just does startInput().  Otherwise this reports that a window
    // has gained focus, and if 'editorInfo' is non-null then also does startInput.
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    void startInputOrWindowGainedFocusAsync(
            /* @StartInputReason */ int startInputReason,
            in IInputMethodClient client, in @nullable IBinder windowToken,
            /* @StartInputFlags */ int startInputFlags,
            /* @android.view.WindowManager.LayoutParams.SoftInputModeFlags */ int softInputMode,
            /* @android.view.WindowManager.LayoutParams.Flags */ int windowFlags,
            in @nullable EditorInfo editorInfo, in @nullable IRemoteInputConnection inputConnection,
            in @nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, int userId,
            in ImeOnBackInvokedDispatcher imeDispatcher, int startInputSeq,
            boolean useAsyncShowHideMethod);

    void showInputMethodPickerFromClient(in IInputMethodClient client,
            int auxiliarySubtypeMode);

    @EnforcePermission(allOf = {"WRITE_SECURE_SETTINGS", "INTERACT_ACROSS_USERS_FULL"})
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf = {android.Manifest."
    + "permission.WRITE_SECURE_SETTINGS, android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})")
    void showInputMethodPickerFromSystem(int auxiliarySubtypeMode, int displayId);

    @EnforcePermission("TEST_INPUT_METHOD")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.TEST_INPUT_METHOD)")
    boolean isInputMethodPickerShownForTest();

    /**
     * Called when the IME switch button was clicked from the system. Depending on the number of
     * enabled IME subtypes, this will either switch to the next IME/subtype, or show the input
     * method picker dialog.
     *
     * @param displayId The ID of the display where the input method picker dialog should be shown.
     * @param userId    The ID of the user that triggered the click.
     */
    @EnforcePermission(allOf = {"WRITE_SECURE_SETTINGS" ,"INTERACT_ACROSS_USERS_FULL"})
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf = {android.Manifest."
    + "permission.WRITE_SECURE_SETTINGS, android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})")
    oneway void onImeSwitchButtonClickFromSystem(int displayId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    @nullable InputMethodSubtype getCurrentInputMethodSubtype(int userId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    void setAdditionalInputMethodSubtypes(String id, in InputMethodSubtype[] subtypes,
            int userId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    void setExplicitlyEnabledInputMethodSubtypes(String imeId, in int[] subtypeHashCodes,
            int userId);

    // This is kept due to @UnsupportedAppUsage.
    // TODO(Bug 113914148): Consider removing this.
    int getInputMethodWindowVisibleHeight(in IInputMethodClient client);

    oneway void reportPerceptibleAsync(in IBinder windowToken, boolean perceptible);

    @EnforcePermission(allOf = {"INTERNAL_SYSTEM_WINDOW", "INTERACT_ACROSS_USERS_FULL"})
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf = {android.Manifest."
    + "permission.INTERNAL_SYSTEM_WINDOW, android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})")
    void removeImeSurface(int displayId);

    /** Remove the IME surface. Requires passing the currently focused window. */
    oneway void removeImeSurfaceFromWindowAsync(in IBinder windowToken);

    @JavaPassthrough(annotation="@android.annotation.RequiresNoPermission")
    void startProtoDump(in byte[] protoDump, int source, String where);

    @JavaPassthrough(annotation="@android.annotation.RequiresNoPermission")
    boolean isImeTraceEnabled();

    // Starts an ime trace.
    @EnforcePermission("CONTROL_UI_TRACING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.CONTROL_UI_TRACING)")
    void startImeTrace();

    // Stops an ime trace.
    @EnforcePermission("CONTROL_UI_TRACING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.CONTROL_UI_TRACING)")
    void stopImeTrace();

    /** Start Stylus handwriting session **/
    void startStylusHandwriting(in IInputMethodClient client);
    oneway void startConnectionlessStylusHandwriting(in IInputMethodClient client, int userId,
            in CursorAnchorInfo cursorAnchorInfo, in String delegatePackageName,
            in String delegatorPackageName, in IConnectionlessHandwritingCallback callback);

    /** Prepares delegation of starting stylus handwriting session to a different editor **/
    void prepareStylusHandwritingDelegation(in IInputMethodClient client,
                in int userId,
                in String delegatePackageName,
                in String delegatorPackageName);

    /** Accepts and starts a stylus handwriting session for the delegate view **/
    boolean acceptStylusHandwritingDelegation(in IInputMethodClient client, in int userId,
            in String delegatePackageName, in String delegatorPackageName, int flags);

    /** Accepts and starts a stylus handwriting session for the delegate view and provides result
     *  async **/
    oneway void acceptStylusHandwritingDelegationAsync(in IInputMethodClient client, in int userId,
            in String delegatePackageName, in String delegatorPackageName, int flags,
            in IBooleanListener callback);

    /** Returns {@code true} if currently selected IME supports Stylus handwriting. */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)")
    boolean isStylusHandwritingAvailableAsUser(int userId, boolean connectionless);

    /** add virtual stylus id for test Stylus handwriting session **/
    @EnforcePermission("TEST_INPUT_METHOD")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.TEST_INPUT_METHOD)")
    void addVirtualStylusIdForTestSession(in IInputMethodClient client);

    /** Set a stylus idle-timeout after which handwriting {@code InkWindow} will be removed. */
    @EnforcePermission("TEST_INPUT_METHOD")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.TEST_INPUT_METHOD)")
    void setStylusWindowIdleTimeoutForTest(in IInputMethodClient client, long timeout);

    /**
     * Returns the singleton instance for the Ime Tracker Service.
     * {@hide}
     */
    IImeTracker getImeTrackerService();
}
