/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.window;

import android.content.Context;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/**
 * Registers {@link OverlayViewMediator}(s) and synchronizes their calls to hide/show {@link
 * OverlayViewController}(s) to allow for the correct visibility of system bars.
 */
@Singleton
public class SystemUIOverlayWindowManager extends SystemUI {
    private static final String TAG = "SystemUIOverlayWM";
    private final Map<Class<?>, Provider<OverlayViewMediator>>
            mContentMediatorCreators;
    private final OverlayViewGlobalStateController mOverlayViewGlobalStateController;

    @Inject
    public SystemUIOverlayWindowManager(
            Context context,
            Map<Class<?>, Provider<OverlayViewMediator>> contentMediatorCreators,
            OverlayViewGlobalStateController overlayViewGlobalStateController) {
        super(context);
        mContentMediatorCreators = contentMediatorCreators;
        mOverlayViewGlobalStateController = overlayViewGlobalStateController;
    }

    @Override
    public void start() {
        String[] names = mContext.getResources().getStringArray(
                R.array.config_carSystemUIOverlayViewsMediators);
        startServices(names);
    }

    private void startServices(String[] services) {
        for (String clsName : services) {
            long ti = System.currentTimeMillis();
            try {
                OverlayViewMediator obj = resolveContentMediator(clsName);
                if (obj == null) {
                    Constructor constructor = Class.forName(clsName).getConstructor(Context.class);
                    obj = (OverlayViewMediator) constructor.newInstance(this);
                }
                mOverlayViewGlobalStateController.registerMediator(obj);
            } catch (ClassNotFoundException
                    | NoSuchMethodException
                    | IllegalAccessException
                    | InstantiationException
                    | InvocationTargetException ex) {
                throw new RuntimeException(ex);
            }

            // Warn if initialization of component takes too long
            ti = System.currentTimeMillis() - ti;
            if (ti > 200) {
                Log.w(TAG, "Initialization of " + clsName + " took " + ti + " ms");
            }
        }
    }

    private OverlayViewMediator resolveContentMediator(String className) {
        return resolve(className, mContentMediatorCreators);
    }

    private <T> T resolve(String className, Map<Class<?>, Provider<T>> creators) {
        try {
            Class<?> clazz = Class.forName(className);
            Provider<T> provider = creators.get(clazz);
            return provider == null ? null : provider.get();
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
