/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.infra;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.provider.Settings;

import java.io.PrintWriter;

/**
 * Gets the service name using a property from the {@link android.provider.Settings.Secure}
 * provider.
 *
 * @hide
 */
public final class SecureSettingsServiceNameResolver implements ServiceNameResolver {

    private final @NonNull Context mContext;

    @NonNull
    private final String mProperty;

    public SecureSettingsServiceNameResolver(@NonNull Context context, @NonNull String property) {
        mContext = context;
        mProperty = property;
    }

    @Override
    public String getDefaultServiceName(@UserIdInt int userId) {
        return Settings.Secure.getStringForUser(mContext.getContentResolver(), mProperty, userId);
    }

    // TODO(b/117779333): support proto
    @Override
    public void dumpShort(@NonNull PrintWriter pw) {
        pw.print("SecureSettingsServiceNamer: prop="); pw.print(mProperty);
    }

    // TODO(b/117779333): support proto
    @Override
    public void dumpShort(@NonNull PrintWriter pw, @UserIdInt int userId) {
        pw.print("defaultService="); pw.print(getDefaultServiceName(userId));
    }

    @Override
    public String toString() {
        return "SecureSettingsServiceNameResolver[" + mProperty + "]";
    }
}
