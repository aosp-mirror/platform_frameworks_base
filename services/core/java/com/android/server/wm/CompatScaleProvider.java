/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.wm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.CompatibilityInfo.CompatScale;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An interface for services that need to provide compatibility scale different than
 * the default android compatibility.
 */
public interface CompatScaleProvider {

    /**
     * The unique id of each provider registered by a system service which determines the order
     * it will execute in.
     */
    @IntDef(prefix = { "COMPAT_SCALE_MODE_" }, value = {
        // Order Ids for system services
        COMPAT_SCALE_MODE_SYSTEM_FIRST,
        COMPAT_SCALE_MODE_GAME,
        COMPAT_SCALE_MODE_PRODUCT,
        COMPAT_SCALE_MODE_SYSTEM_LAST, // Update this when adding new ids
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface CompatScaleModeOrderId {}

    /**
     * The first id, used by the framework to determine the valid range of ids.
     * @hide
     */
    int COMPAT_SCALE_MODE_SYSTEM_FIRST = 0;

    /**
     * TODO(b/295207384)
     * The identifier for {@link android.app.GameManagerInternal} provider
     * @hide
     */
    int COMPAT_SCALE_MODE_GAME = 1;

    /**
     * The identifier for a provider which is specific to the type of android product like
     * Automotive, Wear, TV etc.
     * @hide
     */
    int COMPAT_SCALE_MODE_PRODUCT = 2;

    /**
     * The final id, used by the framework to determine the valid range of ids. Update this when
     * adding new ids.
     * @hide
     */
    int COMPAT_SCALE_MODE_SYSTEM_LAST = COMPAT_SCALE_MODE_PRODUCT;

    /**
     * Returns {@code true} if the id is in the range of valid system services
     * @hide
     */
    static boolean isValidOrderId(int id) {
        return (id >= COMPAT_SCALE_MODE_SYSTEM_FIRST && id <= COMPAT_SCALE_MODE_SYSTEM_LAST);
    }

    /**
     * @return an instance of {@link CompatScale} to apply for the given package
     */
    @Nullable
    CompatScale getCompatScale(@NonNull String packageName, int uid);
}
