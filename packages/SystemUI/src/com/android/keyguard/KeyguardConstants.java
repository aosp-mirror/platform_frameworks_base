/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.keyguard;

import android.util.Log;

/**
 * Defines constants for the Keyguard.
 */
public class KeyguardConstants {

    /**
     * Turns on debugging information for the whole Keyguard. This is very verbose and should only
     * be used temporarily for debugging.
     */
    public static final boolean DEBUG = Log.isLoggable("Keyguard", Log.DEBUG);
}
