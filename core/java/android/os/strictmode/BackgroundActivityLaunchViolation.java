/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os.strictmode;

import android.annotation.NonNull;
import android.app.Activity;

/**
 * Violation raised when your app is blocked from launching an {@link Activity}
 * (from the background).
 * <p>
 * This occurs when the app:
 * <ul>
 *     <li>Does not have sufficient privileges to launch the Activity.</li>
 *     <li>Has not explicitly opted-in to launch the Activity.</li>
 * </ul>
 * Violations may affect the functionality of your app and should be addressed to ensure
 * proper behavior.
 * @hide
 */
public class BackgroundActivityLaunchViolation extends Violation {

    /** @hide */
    public BackgroundActivityLaunchViolation(@NonNull String message) {
        super(message);
    }
}
