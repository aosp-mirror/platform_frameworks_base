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
import android.content.Intent;

import androidx.core.app.CoreComponentFactory;

import javax.inject.Inject;

/**
 * Implementation of AppComponentFactory that injects into constructors.
 */
public class SystemUIAppComponentFactory extends CoreComponentFactory {

    @Inject
    public ContextComponentHelper mComponentHelper;

    public SystemUIAppComponentFactory() {
        super();
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Application app = super.instantiateApplication(cl, className);
        if (app instanceof SystemUIApplication) {
            ((SystemUIApplication) app).setContextAvailableCallback(
                    context -> {
                        SystemUIFactory.createFromConfig(context);
                        SystemUIFactory.getInstance().getRootComponent().inject(
                                SystemUIAppComponentFactory.this);
                    }
            );
        }

        return app;
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Service service = mComponentHelper.resolve(className);
        if (service != null) {
            return checkCompatWrapper(service);
        }
        return super.instantiateService(cl, className, intent);
    }

    static <T> T checkCompatWrapper(T obj) {
        if (obj instanceof CompatWrapped) {
            T wrapper = (T) ((CompatWrapped) obj).getWrapper();
            if (wrapper != null) {
                return wrapper;
            }
        }

        return obj;
    }
}
