/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.shared;

import android.annotation.Nullable;
import android.net.LinkProperties;
import android.net.ProxyInfo;

/**
 * Collection of utility methods to convert to and from stable AIDL parcelables for LinkProperties
 * and its attributes.
 * @hide
 */
public final class LinkPropertiesParcelableUtil {
    // Temporary methods to facilitate migrating clients away from LinkPropertiesParcelable
    // TODO: remove the following methods after migrating clients.

    /**
     * @deprecated conversion to stable parcelable is no longer necessary.
     */
    @Deprecated
    public static LinkProperties toStableParcelable(@Nullable LinkProperties lp) {
        return lp;
    }

    /**
     * @deprecated conversion to stable parcelable is no longer necessary.
     */
    @Deprecated
    public static ProxyInfo toStableParcelable(@Nullable ProxyInfo info) {
        return info;
    }
}
