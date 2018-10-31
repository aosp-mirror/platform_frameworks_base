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
import android.text.style.SuggestionSpan;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.EditorInfo;

import com.android.internal.view.InputBindResult;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;

/**
 * Public interface to the global input method manager, used by all client
 * applications.
 */
interface IInputMethodManager {
    void addClient(in IInputMethodClient client, in IInputContext inputContext,
            int untrustedDisplayId);

    // TODO: Use ParceledListSlice instead
    List<InputMethodInfo> getInputMethodList();
    List<InputMethodInfo> getVrInputMethodList();
    // TODO: Use ParceledListSlice instead
    List<InputMethodInfo> getEnabledInputMethodList();
    List<InputMethodSubtype> getEnabledInputMethodSubtypeList(in String imiId,
            boolean allowsImplicitlySelectedSubtypes);
    InputMethodSubtype getLastInputMethodSubtype();
    // TODO: We should change the return type from List to List<Parcelable>
    // Currently there is a bug that aidl doesn't accept List<Parcelable>
    List getShortcutInputMethodsAndSubtypes();

    boolean showSoftInput(in IInputMethodClient client, int flags,
            in ResultReceiver resultReceiver);
    boolean hideSoftInput(in IInputMethodClient client, int flags,
            in ResultReceiver resultReceiver);
    // If windowToken is null, this just does startInput().  Otherwise this reports that a window
    // has gained focus, and if 'attribute' is non-null then also does startInput.
    // @NonNull
    InputBindResult startInputOrWindowGainedFocus(
            /* @StartInputReason */ int startInputReason,
            in IInputMethodClient client, in IBinder windowToken,
            /* @StartInputFlags */ int startInputFlags,
            /* @android.view.WindowManager.LayoutParams.SoftInputModeFlags */ int softInputMode,
            int windowFlags, in EditorInfo attribute, IInputContext inputContext,
            /* @InputConnectionInspector.MissingMethodFlags */ int missingMethodFlags,
            int unverifiedTargetSdkVersion);

    void showInputMethodPickerFromClient(in IInputMethodClient client,
            int auxiliarySubtypeMode);
    void showInputMethodAndSubtypeEnablerFromClient(in IInputMethodClient client, String topId);
    boolean isInputMethodPickerShownForTest();
    // TODO(Bug 114488811): this can be removed once we deprecate special null token rule.
    void setInputMethod(in IBinder token, String id);
    // TODO(Bug 114488811): this can be removed once we deprecate special null token rule.
    void setInputMethodAndSubtype(in IBinder token, String id, in InputMethodSubtype subtype);
    void registerSuggestionSpansForNotification(in SuggestionSpan[] spans);
    boolean notifySuggestionPicked(in SuggestionSpan span, String originalString, int index);
    InputMethodSubtype getCurrentInputMethodSubtype();
    boolean setCurrentInputMethodSubtype(in InputMethodSubtype subtype);
    // TODO(Bug 114488811): this can be removed once we deprecate special null token rule.
    boolean switchToPreviousInputMethod(in IBinder token);
    // TODO(Bug 114488811): this can be removed once we deprecate special null token rule.
    boolean switchToNextInputMethod(in IBinder token, boolean onlyCurrentIme);
    void setAdditionalInputMethodSubtypes(String id, in InputMethodSubtype[] subtypes);
    // This is kept due to @UnsupportedAppUsage.
    // TODO(Bug 113914148): Consider removing this.
    int getInputMethodWindowVisibleHeight();
}
