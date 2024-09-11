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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import com.android.systemui.plugins.ActivityStarter.OnDismissAction;

/** Executes actions that require the screen to be unlocked. */
public interface KeyguardDismissHandler {
    /**
     * Executes an action that requres the screen to be unlocked, showing the keyguard if
     * necessary. Does not close the notification shade (in case it was open).
     * @param requiresShadeOpen does the shade need to be forced open when hiding the keyguard?
     * @param afterKeyguardGone run the dismiss action after keyguard is gone?
     */
    void executeWhenUnlocked(OnDismissAction action, boolean requiresShadeOpen,
            boolean afterKeyguardGone);
}
