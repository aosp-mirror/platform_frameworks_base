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

package com.android.systemui.dagger;

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;

import com.android.systemui.SystemUI;
import com.android.systemui.recents.RecentsImplementation;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Used during Service and Activity instantiation to make them injectable.
 */
@SysUISingleton
public class ContextComponentResolver implements ContextComponentHelper {
    private final Map<Class<?>, Provider<Activity>> mActivityCreators;
    private final Map<Class<?>, Provider<Service>> mServiceCreators;
    private final Map<Class<?>, Provider<SystemUI>> mSystemUICreators;
    private final Map<Class<?>, Provider<RecentsImplementation>> mRecentsCreators;
    private final Map<Class<?>, Provider<BroadcastReceiver>> mBroadcastReceiverCreators;

    @Inject
    ContextComponentResolver(Map<Class<?>, Provider<Activity>> activityCreators,
            Map<Class<?>, Provider<Service>> serviceCreators,
            Map<Class<?>, Provider<SystemUI>> systemUICreators,
            Map<Class<?>, Provider<RecentsImplementation>> recentsCreators,
            Map<Class<?>, Provider<BroadcastReceiver>> broadcastReceiverCreators) {
        mActivityCreators = activityCreators;
        mServiceCreators = serviceCreators;
        mSystemUICreators = systemUICreators;
        mRecentsCreators = recentsCreators;
        mBroadcastReceiverCreators = broadcastReceiverCreators;
    }

    /**
     * Looks up the Activity class name to see if Dagger has an instance of it.
     */
    @Override
    public Activity resolveActivity(String className) {
        return resolve(className, mActivityCreators);
    }

    /**
     * Looks up the BroadcastReceiver class name to see if Dagger has an instance of it.
     */
    @Override
    public BroadcastReceiver resolveBroadcastReceiver(String className) {
        return resolve(className, mBroadcastReceiverCreators);
    }

    /**
     * Looks up the RecentsImplementation class name to see if Dagger has an instance of it.
     */
    @Override
    public RecentsImplementation resolveRecents(String className) {
        return resolve(className, mRecentsCreators);
    }

    /**
     * Looks up the Service class name to see if Dagger has an instance of it.
     */
    @Override
    public Service resolveService(String className) {
        return resolve(className, mServiceCreators);
    }

    /**
     * Looks up the SystemUI class name to see if Dagger has an instance of it.
     */
    @Override
    public SystemUI resolveSystemUI(String className) {
        return resolve(className, mSystemUICreators);
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
