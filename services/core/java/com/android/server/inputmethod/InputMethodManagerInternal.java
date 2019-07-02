/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.annotation.UserIdInt;
import android.view.inputmethod.InputMethodInfo;

import com.android.server.LocalServices;

import java.util.Collections;
import java.util.List;

/**
 * Input method manager local system service interface.
 */
public abstract class InputMethodManagerInternal {
    /**
     * Called by the power manager to tell the input method manager whether it
     * should start watching for wake events.
     */
    public abstract void setInteractive(boolean interactive);

    /**
     * Hides the current input method, if visible.
     */
    public abstract void hideCurrentInputMethod();

    /**
     * Returns the list of installed input methods for the specified user.
     *
     * @param userId The user ID to be queried.
     * @return A list of {@link InputMethodInfo}.  VR-only IMEs are already excluded.
     */
    public abstract List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId);

    /**
     * Returns the list of installed input methods that are enabled for the specified user.
     *
     * @param userId The user ID to be queried.
     * @return A list of {@link InputMethodInfo} that are enabled for {@code userId}.
     */
    public abstract List<InputMethodInfo> getEnabledInputMethodListAsUser(@UserIdInt int userId);

    /**
     * Fake implementation of {@link InputMethodManagerInternal}.  All the methods do nothing.
     */
    private static final InputMethodManagerInternal NOP =
            new InputMethodManagerInternal() {
                @Override
                public void setInteractive(boolean interactive) {
                }

                @Override
                public void hideCurrentInputMethod() {
                }

                @Override
                public List<InputMethodInfo> getInputMethodListAsUser(int userId) {
                    return Collections.emptyList();
                }

                @Override
                public List<InputMethodInfo> getEnabledInputMethodListAsUser(int userId) {
                    return Collections.emptyList();
                }
            };

    /**
     * @return Global instance if exists.  Otherwise, a dummy no-op instance.
     */
    @NonNull
    public static InputMethodManagerInternal get() {
        final InputMethodManagerInternal instance =
                LocalServices.getService(InputMethodManagerInternal.class);
        return instance != null ? instance : NOP;
    }
}
