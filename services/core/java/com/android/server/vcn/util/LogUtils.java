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

package com.android.server.vcn.util;

import android.annotation.Nullable;
import android.os.ParcelUuid;

import com.android.internal.util.HexDump;

/** @hide */
public class LogUtils {
    /**
     * Returns the hash of the subscription group in hexadecimal format.
     *
     * @return the hexadecimal encoded string if uuid was non-null, else {@code null}
     */
    @Nullable
    public static String getHashedSubscriptionGroup(@Nullable ParcelUuid uuid) {
        if (uuid == null) {
            return null;
        }

        return HexDump.toHexString(uuid.hashCode());
    }
}
