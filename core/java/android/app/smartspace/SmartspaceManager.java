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

package android.app.smartspace;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;

import java.util.Objects;

/**
 * Smartspace is a container in Android which is used to show contextual content powered by the
 * intelligence service running on the device. A smartspace container can be on AoD, lockscreen or
 * on the homescreen and can show personalized cards which are either derived from on device or
 * online signals.
 *
 * {@link SmartspaceManager} is a system service that provides methods to create Smartspace session
 * clients. An instance of this class is returned when a client calls
 * <code> context.getSystemService("smartspace"); </code>.
 *
 * After receiving the service, a client must call
 * {@link SmartspaceManager#createSmartspaceSession(SmartspaceConfig)} with a corresponding
 * {@link SmartspaceConfig} to get an instance of {@link SmartspaceSession}.
 * This session is then a client's point of contact with the api. They can send events, request for
 * updates using the session. It is client's duty to call {@link SmartspaceSession#destroy()} to
 * destroy the session once they no longer need it.
 *
 * @hide
 */
@SystemApi
public final class SmartspaceManager {

    private final Context mContext;

    /**
     * @hide
     */
    public SmartspaceManager(Context context) {
        mContext = Objects.requireNonNull(context);
    }

    /**
     * Creates a new Smartspace session.
     */
    @NonNull
    public SmartspaceSession createSmartspaceSession(
            @NonNull SmartspaceConfig smartspaceConfig) {
        return new SmartspaceSession(mContext, smartspaceConfig);
    }
}
