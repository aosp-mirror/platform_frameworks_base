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

import android.util.AndroidRuntimeException;

/**
 * Exception used by {@link ActivityThread} to crash an app process.
 *
 * @hide
 */
public class RemoteServiceException extends AndroidRuntimeException {
    /**
     * The type ID passed to {@link IApplicationThread#scheduleCrash}.
     *
     * Assign a unique ID to each subclass. See the above method for the numbers that are already
     * taken.
     */
    public static final int TYPE_ID = 0;

    public RemoteServiceException(String msg) {
        super(msg);
    }
}
