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

package com.android.systemui;

import android.app.Application;
import android.app.Service;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.AppComponentFactory;

import javax.inject.Inject;

/**
 * Implementation of AppComponentFactory that injects into constructors.
 *
 * This class sets up dependency injection when creating our application.
 *
 * Services support dependency injection into their constructors.
 *
 * ContentProviders support injection into member variables - _not_ constructors.
 */
public class SystemUIAppComponentFactory extends AppComponentFactory {

    @Inject
    public ContextComponentHelper mComponentHelper;

    public SystemUIAppComponentFactory() {
        super();
    }

    @NonNull
    @Override
    public Application instantiateApplicationCompat(
            @NonNull ClassLoader cl, @NonNull String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Application app = super.instantiateApplicationCompat(cl, className);
        if (app instanceof ContextProvider) {
            ((ContextProvider) app).setContextAvailableCallback(
                    context -> {
                        SystemUIFactory.createFromConfig(context);
                        SystemUIFactory.getInstance().getRootComponent().inject(
                                SystemUIAppComponentFactory.this);
                    }
            );
        }

        return app;
    }

    @NonNull
    @Override
    public ContentProvider instantiateProviderCompat(
            @NonNull ClassLoader cl, @NonNull String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {

        ContentProvider contentProvider = super.instantiateProviderCompat(cl, className);
        if (contentProvider instanceof ContextProvider) {
            ((ContextProvider) contentProvider).setContextAvailableCallback(
                    context -> {
                        SystemUIFactory.createFromConfig(context);
                        SystemUIFactory.getInstance().getRootComponent().inject(
                                contentProvider);
                    }
            );
        }

        return contentProvider;
    }

    @NonNull
    @Override
    public Service instantiateServiceCompat(
            @NonNull ClassLoader cl, @NonNull String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Service service = mComponentHelper.resolveService(className);
        if (service != null) {
            return service;
        }
        return super.instantiateServiceCompat(cl, className, intent);
    }

    interface ContextAvailableCallback {
        void onContextAvailable(Context context);
    }

    interface ContextProvider {
        void setContextAvailableCallback(ContextAvailableCallback callback);
    }
}
