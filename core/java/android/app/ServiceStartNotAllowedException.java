/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;

/**
 * Exception thrown when an app tries to start a {@link Service} when it's not allowed to do so.
 */
public abstract class ServiceStartNotAllowedException extends IllegalStateException {
    ServiceStartNotAllowedException(@NonNull String message) {
        super(message);
    }

    /**
     * Return either {@link ForegroundServiceStartNotAllowedException} or
     * {@link BackgroundServiceStartNotAllowedException}
     * @hide
     */
    @NonNull
    public static ServiceStartNotAllowedException newInstance(boolean foreground,
            @NonNull String message) {
        if (foreground) {
            return new ForegroundServiceStartNotAllowedException(message);
        } else {
            return new BackgroundServiceStartNotAllowedException(message);
        }
    }
}
