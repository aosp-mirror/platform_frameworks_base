/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.inputmethod;

import android.os.ResultReceiver;
import android.view.inputmethod.EditorInfo;
import com.android.internal.view.IInputContext;

oneway interface IMultiClientInputMethodSession {
    void startInputOrWindowGainedFocus(
            in IInputContext inputContext, int missingMethods, in EditorInfo attribute,
            int controlFlags, int softInputMode, int targetWindowHandle);
    void showSoftInput(int flags, in ResultReceiver resultReceiver);
    void hideSoftInput(int flags, in ResultReceiver resultReceiver);
}
