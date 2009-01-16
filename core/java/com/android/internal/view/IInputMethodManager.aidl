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

import android.view.inputmethod.InputMethodInfo;
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
    
    void addClient(in IInputMethodClient client,
            in IInputContext inputContext, int uid, int pid);
    void removeClient(in IInputMethodClient client);
            
    InputBindResult startInput(in IInputMethodClient client,
            in EditorInfo attribute, boolean initial, boolean needResult);
    void finishInput(in IInputMethodClient client);
    void showSoftInput(in IInputMethodClient client, int flags);
    void hideSoftInput(in IInputMethodClient client, int flags);
    void windowGainedFocus(in IInputMethodClient client,
            boolean viewHasFocus, boolean isTextEditor,
            int softInputMode, boolean first, int windowFlags);
            
    void showInputMethodPickerFromClient(in IInputMethodClient client);
    void setInputMethod(in IBinder token, String id);
    void hideMySoftInput(in IBinder token, int flags);
    void updateStatusIcon(int iconId, String iconPackage);
    
    boolean setInputMethodEnabled(String id, boolean enabled);
}

