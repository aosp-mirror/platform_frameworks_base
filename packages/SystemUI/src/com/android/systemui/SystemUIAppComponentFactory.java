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

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

    private static final String TAG = "AppComponentFactory";
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
        if (app instanceof ContextInitializer) {
            ((ContextInitializer) app).setContextAvailableCallback(
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
        if (contentProvider instanceof ContextInitializer) {
            ((ContextInitializer) contentProvider).setContextAvailableCallback(
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
    public Activity instantiateActivityCompat(@NonNull ClassLoader cl, @NonNull String className,
            @Nullable Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Activity activity = mComponentHelper.resolveActivity(className);
        if (activity != null) {
            return activity;
        }
        return super.instantiateActivityCompat(cl, className, intent);
    }

    @NonNull
    @Override
    public Service instantiateServiceCompat(
            @NonNull ClassLoader cl, @NonNull String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (mComponentHelper == null) {
            // This shouldn't happen, but does when a device is freshly formatted.
            // Bug filed against framework to take a look: http://b/141008541
            SystemUIFactory.getInstance().getRootComponent().inject(
                    SystemUIAppComponentFactory.this);
        }
        Service service = mComponentHelper.resolveService(className);
        if (service != null) {
            return service;
        }
        return super.instantiateServiceCompat(cl, className, intent);
    }

    /**
     * A callback that receives a Context when one is ready.
     */
    public interface ContextAvailableCallback {
        void onContextAvailable(Context context);
    }

    /**
     * Implemented in classes that get started by the system before a context is available.
     */
    public interface ContextInitializer {
        void setContextAvailableCallback(ContextAvailableCallback callback);
    }
}
