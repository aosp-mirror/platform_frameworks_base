/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.back;

import android.window.IOnBackInvokedCallback;

/**
 * Interface for Launcher process to register back invocation callbacks.
 */
interface IBackAnimation {

    /**
     * Sets a {@link IOnBackInvokedCallback} to be invoked when
     * back navigation has type {@link BackNavigationInfo#TYPE_RETURN_TO_HOME}.
     */
    void setBackToLauncherCallback(in IOnBackInvokedCallback callback);

    /**
     * Clears the previously registered {@link IOnBackInvokedCallback}.
     */
    void clearBackToLauncherCallback();

    /**
     * Notifies Shell that the back to launcher animation has fully finished
     * (including the transition animation that runs after the finger is lifted).
     *
     * At this point the top window leash (if one was created) should be ready to be released.
     * //TODO: Remove once we play the transition animation through shell transitions.
     */
    void onBackToLauncherAnimationFinished();
}
