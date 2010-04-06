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

package com.android.internal.policy.impl;

import android.content.Context;

/**
 * Defines operations necessary for showing a keyguard, including how to create
 * it, and various properties that are useful to be able to query independant
 * of whether the keyguard instance is around or not.
 */
public interface KeyguardViewProperties {
    
    /**
     * Create a keyguard view.
     * @param context the context to use when creating the view.
     * @param updateMonitor configuration may be based on this.
     * @param controller for talking back with the containing window.
     * @return the view.
     */
    KeyguardViewBase createKeyguardView(Context context,
            KeyguardUpdateMonitor updateMonitor,
            KeyguardWindowController controller);

    /**
     * Would the keyguard be secure right now?
     * @return Whether the keyguard is currently secure, meaning it will block
     *   the user from getting past it until the user enters some sort of PIN.
     */
    boolean isSecure();

}
