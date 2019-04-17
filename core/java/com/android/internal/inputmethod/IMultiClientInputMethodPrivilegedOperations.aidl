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

import android.view.InputChannel;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.inputmethod.IMultiClientInputMethodSession;

/**
 * Defines priviledged operations that only the current MSIMS is allowed to call.
 * Actual operations are implemented and handled by MultiClientInputMethodManagerService.
 */
interface IMultiClientInputMethodPrivilegedOperations {
    IBinder createInputMethodWindowToken(int displayId);
    void deleteInputMethodWindowToken(IBinder token);
    void acceptClient(int clientId, in IInputMethodSession session,
            in IMultiClientInputMethodSession multiClientSession, in InputChannel writeChannel);
    void reportImeWindowTarget(int clientId, int targetWindowHandle, in IBinder imeWindowToken);
    boolean isUidAllowedOnDisplay(int displayId, int uid);
    void setActive(int clientId, boolean active);
}
