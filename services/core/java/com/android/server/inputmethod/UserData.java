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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.util.SparseArray;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ImeTracker;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;

import java.util.concurrent.atomic.AtomicBoolean;

/** Placeholder for all IMMS user specific fields */
final class UserData {
    @UserIdInt
    final int mUserId;

    @NonNull
    final InputMethodBindingController mBindingController;

    @NonNull
    final InputMethodSubtypeSwitchingController mSwitchingController =
            new InputMethodSubtypeSwitchingController();

    @NonNull
    final HardwareKeyboardShortcutController mHardwareKeyboardShortcutController =
            new HardwareKeyboardShortcutController();

    /**
     * Have we called mCurMethod.bindInput()?
     */
    @GuardedBy("ImfLock.class")
    boolean mBoundToMethod = false;

    /**
     * Have we called bindInput() for accessibility services?
     */
    @GuardedBy("ImfLock.class")
    boolean mBoundToAccessibility;

    @GuardedBy("ImfLock.class")
    @NonNull
    ImeBindingState mImeBindingState = ImeBindingState.newEmptyState();

    @GuardedBy("ImfLock.class")
    @Nullable
    ClientState mCurClient = null;

    @GuardedBy("ImfLock.class")
    boolean mInFullscreenMode;

    /**
     * The {@link IRemoteInputConnection} last provided by the current client.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    IRemoteInputConnection mCurInputConnection;

    /**
     * The {@link ImeOnBackInvokedDispatcher} last provided by the current client to
     * receive {@link android.window.OnBackInvokedCallback}s forwarded from IME.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    ImeOnBackInvokedDispatcher mCurImeDispatcher;

    /**
     * The {@link IRemoteAccessibilityInputConnection} last provided by the current client.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    IRemoteAccessibilityInputConnection mCurRemoteAccessibilityInputConnection;

    /**
     * The {@link EditorInfo} last provided by the current client.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    EditorInfo mCurEditorInfo;

    /**
     * The token tracking the current IME show request that is waiting for a connection to an
     * IME, otherwise {@code null}.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    ImeTracker.Token mCurStatsToken;

    /**
     * Currently enabled session.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    InputMethodManagerService.SessionState mEnabledSession;

    @GuardedBy("ImfLock.class")
    @Nullable
    SparseArray<InputMethodManagerService.AccessibilitySessionState>
            mEnabledAccessibilitySessions = new SparseArray<>();

    /**
     * A per-user cache of {@link InputMethodSettings#getEnabledInputMethodsStr()}.
     */
    @GuardedBy("ImfLock.class")
    @NonNull
    String mLastEnabledInputMethodsStr = "";

    /**
     * {@code true} when the IME is responsible for drawing the navigation bar and its buttons.
     */
    @NonNull
    final AtomicBoolean mImeDrawsNavBar = new AtomicBoolean();

    /**
     * Intended to be instantiated only from this file.
     */
    UserData(@UserIdInt int userId,
            @NonNull InputMethodBindingController bindingController) {
        mUserId = userId;
        mBindingController = bindingController;
    }

    @Override
    public String toString() {
        return "UserData{" + "mUserId=" + mUserId + '}';
    }
}
