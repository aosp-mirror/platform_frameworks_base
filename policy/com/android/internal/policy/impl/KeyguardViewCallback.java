/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.policy.impl;

/**
 * The callback used by the keyguard view to tell the {@link KeyguardViewMediator} 
 * various things.
 */
public interface KeyguardViewCallback {

    /**
     * Request the wakelock to be poked for the default amount of time.
     */
    void pokeWakelock();

    /**
     * Request the wakelock to be poked for a specific amount of time.
     * @param millis The amount of time in millis.
     */
    void pokeWakelock(int millis);

    /**
     * Report that the keyguard is done.
     * @param authenticated Whether the user securely got past the keyguard.
     *   the only reason for this to be false is if the keyguard was instructed
     *   to appear temporarily to verify the user is supposed to get past the
     *   keyguard, and the user fails to do so.
     */
    void keyguardDone(boolean authenticated);

    /**
     * Report that the keyguard is done drawing.
     */
    void keyguardDoneDrawing();
}
