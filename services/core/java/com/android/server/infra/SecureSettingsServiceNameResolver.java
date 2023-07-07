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
import android.text.TextUtils;
import android.util.ArraySet;

import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

/**
 * Gets the service name using a property from the {@link android.provider.Settings.Secure}
 * provider.
 *
 * @hide
 */
public final class SecureSettingsServiceNameResolver extends ServiceNameBaseResolver {
    /**
     * The delimiter to be used to parse the secure settings string. Services must make sure
     * that this delimiter is used while adding component names to their secure setting property.
     */
    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private final TextUtils.SimpleStringSplitter mStringColonSplitter =
            new TextUtils.SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    @NonNull
    private final String mProperty;

    public SecureSettingsServiceNameResolver(@NonNull Context context, @NonNull String property) {
        this(context, property, /*isMultiple*/false);
    }

    /**
     *
     * @param context the context required to retrieve the secure setting value
     * @param property name of the secure setting key
     * @param isMultiple true if the system service using this resolver needs to connect to
     *                   multiple remote services, false otherwise
     */
    public SecureSettingsServiceNameResolver(@NonNull Context context, @NonNull String property,
            boolean isMultiple) {
        super(context, isMultiple);
        mProperty = property;
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

    @Override
    public String[] readServiceNameList(int userId) {
        return parseColonDelimitedServiceNames(
                Settings.Secure.getStringForUser(
                        mContext.getContentResolver(), mProperty, userId));
    }

    @Override
    public String readServiceName(int userId) {
        return Settings.Secure.getStringForUser(
                mContext.getContentResolver(), mProperty, userId);
    }

    @Override
    public void setServiceNameList(List<String> componentNames, int userId) {
        if (componentNames == null || componentNames.isEmpty()) {
            Settings.Secure.putStringForUser(
                    mContext.getContentResolver(), mProperty, null, userId);
            return;
        }
        StringBuilder builder = new StringBuilder(componentNames.get(0));
        for (int i = 1; i < componentNames.size(); i++) {
            builder.append(COMPONENT_NAME_SEPARATOR);
            builder.append(componentNames.get(i));
        }
        Settings.Secure.putStringForUser(
                mContext.getContentResolver(), mProperty, builder.toString(), userId);
    }

    private String[] parseColonDelimitedServiceNames(String serviceNames) {
        final Set<String> delimitedServices = new ArraySet<>();
        if (!TextUtils.isEmpty(serviceNames)) {
            final TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(serviceNames);
            while (splitter.hasNext()) {
                final String str = splitter.next();
                if (TextUtils.isEmpty(str)) {
                    continue;
                }
                delimitedServices.add(str);
            }
        }
        String[] delimitedServicesArray = new String[delimitedServices.size()];
        return delimitedServices.toArray(delimitedServicesArray);
    }
}
