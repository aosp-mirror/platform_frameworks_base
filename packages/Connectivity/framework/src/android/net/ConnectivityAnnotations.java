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

package android.net;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Type annotations for constants used in the connectivity API surface.
 *
 * The annotations are maintained in a separate class so that it can be built as
 * a separate library that other modules can build against, as Typedef should not
 * be exposed as SystemApi.
 *
 * @hide
 */
public final class ConnectivityAnnotations {
    private ConnectivityAnnotations() {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            ConnectivityManager.MULTIPATH_PREFERENCE_HANDOVER,
            ConnectivityManager.MULTIPATH_PREFERENCE_RELIABILITY,
            ConnectivityManager.MULTIPATH_PREFERENCE_PERFORMANCE,
    })
    public @interface MultipathPreference {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, value = {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED,
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
    })
    public @interface RestrictBackgroundStatus {}
}
