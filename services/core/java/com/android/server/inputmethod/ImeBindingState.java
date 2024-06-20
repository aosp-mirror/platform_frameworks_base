/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_FOCUSED_WINDOW_NAME;
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_FOCUSED_WINDOW_SOFT_INPUT_MODE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Printer;
import android.util.proto.ProtoOutputStream;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.inputmethod.EditorInfo;

import com.android.internal.inputmethod.InputMethodDebug;
import com.android.server.wm.WindowManagerInternal;

/**
 * Stores information related to one active IME client on one display.
 */
final class ImeBindingState {

    @UserIdInt
    final int mUserId;

    /**
     * The last window token that we confirmed to be focused.  This is always updated upon
     * reports from the input method client. If the window state is already changed before the
     * report is handled, this field just keeps the last value.
     */
    @Nullable
    final IBinder mFocusedWindow;

    /**
     * {@link WindowManager.LayoutParams#softInputMode} of {@link #mFocusedWindow}.
     *
     * @see #mFocusedWindow
     */
    @SoftInputModeFlags
    final int mFocusedWindowSoftInputMode;

    /**
     * The client by which {@link #mFocusedWindow} was reported. This gets updated whenever
     * an
     * IME-focusable window gained focus (without necessarily starting an input connection),
     * while {@link InputMethodManagerService#mClient} only gets updated when we actually start an
     * input connection.
     *
     * @see #mFocusedWindow
     */
    @Nullable
    final ClientState mFocusedWindowClient;

    /**
     * The editor info by which {@link #mFocusedWindow} was reported. This differs from
     * {@link InputMethodManagerService#mCurEditorInfo} the same way {@link #mFocusedWindowClient}
     * differs from {@link InputMethodManagerService#mCurClient}.
     *
     * @see #mFocusedWindow
     */
    @Nullable
    final EditorInfo mFocusedWindowEditorInfo;

    void dumpDebug(ProtoOutputStream proto, WindowManagerInternal windowManagerInternal) {
        proto.write(CUR_FOCUSED_WINDOW_NAME,
                windowManagerInternal.getWindowName(mFocusedWindow));
        proto.write(CUR_FOCUSED_WINDOW_SOFT_INPUT_MODE,
                InputMethodDebug.softInputModeToString(mFocusedWindowSoftInputMode));
    }

    void dump(String prefix, Printer p) {
        p.println(prefix + "mFocusedWindow()=" + mFocusedWindow);
        p.println(prefix + "softInputMode=" + InputMethodDebug.softInputModeToString(
                mFocusedWindowSoftInputMode));
        p.println(prefix + "mFocusedWindowClient=" + mFocusedWindowClient);
    }

    static ImeBindingState newEmptyState() {
        return new ImeBindingState(
                /*userId=*/ UserHandle.USER_NULL,
                /*focusedWindow=*/ null,
                /*focusedWindowSoftInputMode=*/ SOFT_INPUT_STATE_UNSPECIFIED,
                /*focusedWindowClient=*/ null,
                /*focusedWindowEditorInfo=*/ null
        );
    }

    ImeBindingState(@UserIdInt int userId,
            @Nullable IBinder focusedWindow,
            @SoftInputModeFlags int focusedWindowSoftInputMode,
            @Nullable ClientState focusedWindowClient,
            @Nullable EditorInfo focusedWindowEditorInfo) {
        mUserId = userId;
        mFocusedWindow = focusedWindow;
        mFocusedWindowSoftInputMode = focusedWindowSoftInputMode;
        mFocusedWindowClient = focusedWindowClient;
        mFocusedWindowEditorInfo = focusedWindowEditorInfo;
    }
}
