/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.cat;


/**
 * Browser launch mode for LAUNCH BROWSER proactive command.
 *
 * {@hide}
 */
public enum LaunchBrowserMode {
    /** Launch browser if not already launched. */
    LAUNCH_IF_NOT_ALREADY_LAUNCHED,
    /**
     * Use the existing browser (the browser shall not use the active existing
     * secured session).
     */
    USE_EXISTING_BROWSER,
    /** Close the existing browser session and launch new browser session. */
    LAUNCH_NEW_BROWSER;
}
