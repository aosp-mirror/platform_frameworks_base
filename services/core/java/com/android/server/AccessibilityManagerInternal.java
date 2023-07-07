/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.util.ArraySet;
import android.util.SparseArray;
import android.view.inputmethod.EditorInfo;

import com.android.internal.inputmethod.IAccessibilityInputMethodSession;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;

/**
 * Accessibility manager local system service interface.
 */
public abstract class AccessibilityManagerInternal {
    /** Enable or disable the sessions. */
    public abstract void setImeSessionEnabled(
            SparseArray<IAccessibilityInputMethodSession> sessions, boolean enabled);

    /** Unbind input for all accessibility services which require ime capabilities. */
    public abstract void unbindInput();

    /** Bind input for all accessibility services which require ime capabilities. */
    public abstract void bindInput();

    /**
     * Request input session from all accessibility services which require ime capabilities and
     * whose id is not in the ignoreSet.
     */
    public abstract void createImeSession(ArraySet<Integer> ignoreSet);

    /** Start input for all accessibility services which require ime capabilities. */
    public abstract void startInput(
            IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            EditorInfo editorInfo, boolean restarting);

    /** Trigger a system action with the provided {@code actionId}. */
    public abstract void performSystemAction(int actionId);

    /**
     * Queries whether touch-exploration mode is enabled or not for the specified user.
     *
     * @param userId User ID to be queried about.
     * @return {@code true} if touch-exploration mode is enabled.
     * @see android.view.accessibility.AccessibilityManager#isTouchExplorationEnabled()
     */
    public abstract boolean isTouchExplorationEnabled(@UserIdInt int userId);

    private static final AccessibilityManagerInternal NOP = new AccessibilityManagerInternal() {
        @Override
        public void setImeSessionEnabled(SparseArray<IAccessibilityInputMethodSession> sessions,
                boolean enabled) {
        }

        @Override
        public void unbindInput() {
        }

        @Override
        public void bindInput() {
        }

        @Override
        public void createImeSession(ArraySet<Integer> ignoreSet) {
        }

        @Override
        public void startInput(IRemoteAccessibilityInputConnection remoteAccessibility,
                EditorInfo editorInfo, boolean restarting) {
        }

        @Override
        public boolean isTouchExplorationEnabled(int userId) {
            return false;
        }

        @Override
        public void performSystemAction(int actionId) {
        }
    };

    /**
     * @return Global instance if exists. Otherwise, a fallback no-op instance.
     */
    @NonNull
    public static AccessibilityManagerInternal get() {
        final AccessibilityManagerInternal instance =
                LocalServices.getService(AccessibilityManagerInternal.class);
        return instance != null ? instance : NOP;
    }
}
