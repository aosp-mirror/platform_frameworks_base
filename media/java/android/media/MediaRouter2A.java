/*
 * Copyright 2019 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.content.Context;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * A new media router
 *
 * TODO: Replace MediaRouter2 with this file once the implementation is finished.
 * @hide
 */
public class MediaRouter2A {

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static MediaRouter2A sInstance;

    /**
     * Gets an instance of a MediaRouter.
     */
    public static MediaRouter2A getInstance(@NonNull Context context) {
        Objects.requireNonNull(context, "context must not be null");
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new MediaRouter2A(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    private MediaRouter2A(Context appContext) {
        // TODO: Implement this
    }
}
