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

package com.android.internal.policy.impl.keyguard_obsolete;

/**
 * Common interface of each {@link android.view.View} that is a screen of
 * {@link LockPatternKeyguardView}.
 */
public interface KeyguardScreen {

    /**
     * Return true if your view needs input, so should allow the soft
     * keyboard to be displayed.
     */
    boolean needsInput();

    /**
     * This screen is no longer in front of the user.
     */
    void onPause();

    /**
     * This screen is going to be in front of the user.
     */
    void onResume();

    /**
     * This view is going away; a hook to do cleanup.
     */
    void cleanUp();
}
