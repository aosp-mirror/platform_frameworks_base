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

import android.content.ComponentName;

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
     * Switches to VR InputMethod defined in the packageName of {@param componentName}.
     */
    public abstract void startVrInputMethodNoCheck(ComponentName componentName);

    /**
     * Fake implementation of {@link InputMethodManagerInternal}.  All the methods do nothing.
     */
    public static final InputMethodManagerInternal NOP =
            new InputMethodManagerInternal() {
                @Override
                public void setInteractive(boolean interactive) {
                }

                @Override
                public void hideCurrentInputMethod() {
                }

                @Override
                public void startVrInputMethodNoCheck(ComponentName componentName) {
                }
            };
}
