/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.content.pm.PackageManager;

import com.android.internal.util.Preconditions;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TODO(b/225012970): add javadoc from {@code com.android.server.devicepolicy.FactoryResetter}
 */
public final class FactoryResetter {

    private static final AtomicBoolean sFactoryResetting = new AtomicBoolean(false);

    /**
     * Checks whether a factory reset is in progress.
     */
    public static boolean isFactoryResetting() {
        return sFactoryResetting.get();
    }

    /**
     * @deprecated called by {@code com.android.server.devicepolicy.FactoryResetter}, won't be
     * needed once that class logic is moved into this.
     */
    @Deprecated
    public static void setFactoryResetting(Context context) {
        Preconditions.checkCallAuthorization(context.checkCallingOrSelfPermission(
                android.Manifest.permission.MASTER_CLEAR) == PackageManager.PERMISSION_GRANTED);
        sFactoryResetting.set(true);
    }

    private FactoryResetter() {
        throw new UnsupportedOperationException("Provides only static methods");
    }
}
