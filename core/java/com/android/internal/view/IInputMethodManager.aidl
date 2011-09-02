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
    List<InputMethodInfo> getInputMethodList();
    List<InputMethodInfo> getEnabledInputMethodList();
    List<InputMethodSubtype> getEnabledInputMethodSubtypeList(in InputMethodInfo imi,
            boolean allowsImplicitlySelectedSubtypes);
    InputMethodSubtype getLastInputMethodSubtype();
    // TODO: We should change the return type from List to List<Parcelable>
    // Currently there is a bug that aidl doesn't accept List<Parcelable>
    List getShortcutInputMethodsAndSubtypes();
    void addClient(in IInputMethodClient client,
            in IInputContext inputContext, int uid, int pid);
    void removeClient(in IInputMethodClient client);
            
    InputBindResult startInput(in IInputMethodClient client,
            IInputContext inputContext, in EditorInfo attribute,
            boolean initial, boolean needResult);
    void finishInput(in IInputMethodClient client);
    boolean showSoftInput(in IInputMethodClient client, int flags,
            in ResultReceiver resultReceiver);
    boolean hideSoftInput(in IInputMethodClient client, int flags,
            in ResultReceiver resultReceiver);
    void windowGainedFocus(in IInputMethodClient client, in IBinder windowToken,
            boolean viewHasFocus, boolean isTextEditor,
            int softInputMode, boolean first, int windowFlags);
            
    void showInputMethodPickerFromClient(in IInputMethodClient client);
    void showInputMethodAndSubtypeEnablerFromClient(in IInputMethodClient client, String topId);
    void setInputMethod(in IBinder token, String id);
    void setInputMethodAndSubtype(in IBinder token, String id, in InputMethodSubtype subtype);
    void hideMySoftInput(in IBinder token, int flags);
    void showMySoftInput(in IBinder token, int flags);
    void updateStatusIcon(in IBinder token, String packageName, int iconId);
    void setImeWindowStatus(in IBinder token, int vis, int backDisposition);
    void registerSuggestionSpansForNotification(in SuggestionSpan[] spans);
    boolean notifySuggestionPicked(in SuggestionSpan span, String originalString, int index);
    InputMethodSubtype getCurrentInputMethodSubtype();
    boolean setCurrentInputMethodSubtype(in InputMethodSubtype subtype);
    boolean switchToLastInputMethod(in IBinder token);
    boolean setInputMethodEnabled(String id, boolean enabled);
    oneway void setAdditionalInputMethodSubtypes(String id, in InputMethodSubtype[] subtypes);
}
