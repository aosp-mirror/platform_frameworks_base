/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.media;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaRoute2ProviderService;
import android.os.Looper;
import android.os.UserHandle;

/**
 * Extends {@link SystemMediaRoute2Provider} by adding system routes provided by {@link
 * MediaRoute2ProviderService provider services}.
 *
 * <p>System routes are those which can handle the system audio and/or video.
 */
/* package */ class SystemMediaRoute2Provider2 extends SystemMediaRoute2Provider {

    private static final ComponentName COMPONENT_NAME =
            new ComponentName(
                    SystemMediaRoute2Provider2.class.getPackage().getName(),
                    SystemMediaRoute2Provider2.class.getName());

    SystemMediaRoute2Provider2(Context context, UserHandle user, Looper looper) {
        super(context, COMPONENT_NAME, user, looper);
    }
}
