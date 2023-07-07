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

import android.annotation.ArrayRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;

import java.io.PrintWriter;

/**
 * Gets the service name using a framework resources, temporarily changing the service if necessary
 * (typically during CTS tests or service development).
 *
 * @hide
 */
public final class FrameworkResourcesServiceNameResolver extends ServiceNameBaseResolver {

    private final int mStringResourceId;
    @ArrayRes
    private final int mArrayResourceId;

    public FrameworkResourcesServiceNameResolver(@NonNull Context context,
            @StringRes int resourceId) {
        super(context, false);
        mStringResourceId = resourceId;
        mArrayResourceId = -1;
    }

    public FrameworkResourcesServiceNameResolver(@NonNull Context context,
            @ArrayRes int resourceId, boolean isMultiple) {
        super(context, isMultiple);
        if (!isMultiple) {
            throw new UnsupportedOperationException("Please use "
                    + "FrameworkResourcesServiceNameResolver(context, @StringRes int) constructor "
                    + "if single service mode is requested.");
        }
        mStringResourceId = -1;
        mArrayResourceId = resourceId;
    }

    @Override
    public String[] readServiceNameList(int userId) {
        return mContext.getResources().getStringArray(mArrayResourceId);
    }

    @Nullable
    @Override
    public String readServiceName(int userId) {
        return mContext.getResources().getString(mStringResourceId);
    }


    // TODO(b/117779333): support proto
    @Override
    public void dumpShort(@NonNull PrintWriter pw) {
        synchronized (mLock) {
            pw.print("FrameworkResourcesServiceNamer: resId=");
            pw.print(mStringResourceId);
            pw.print(", numberTemps=");
            pw.print(mTemporaryServiceNamesList.size());
            pw.print(", enabledDefaults=");
            pw.print(mDefaultServicesDisabled.size());
        }
    }
}
