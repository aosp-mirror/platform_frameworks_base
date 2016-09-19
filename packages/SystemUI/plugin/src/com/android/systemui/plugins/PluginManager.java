/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins;

import android.content.Context;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.Thread.UncaughtExceptionHandler;

/**
 * @see Plugin
 */
public class PluginManager {

    private static PluginManager sInstance;

    private final HandlerThread mBackgroundThread;
    private final ArrayMap<PluginListener<?>, PluginInstanceManager> mPluginMap
            = new ArrayMap<>();
    private final Context mContext;
    private final PluginInstanceManagerFactory mFactory;
    private final boolean isDebuggable;

    private PluginManager(Context context) {
        this(context, new PluginInstanceManagerFactory(), Build.IS_DEBUGGABLE,
                Thread.getDefaultUncaughtExceptionHandler());
    }

    @VisibleForTesting
    PluginManager(Context context, PluginInstanceManagerFactory factory, boolean debuggable,
            UncaughtExceptionHandler defaultHandler) {
        mContext = context;
        mFactory = factory;
        mBackgroundThread = new HandlerThread("Plugins");
        mBackgroundThread.start();
        isDebuggable = debuggable;

        PluginExceptionHandler uncaughtExceptionHandler = new PluginExceptionHandler(
                defaultHandler);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
    }

    public <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener,
            int version) {
        addPluginListener(action, listener, version, false);
    }

    public <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener,
            int version, boolean allowMultiple) {
        if (!isDebuggable) {
            // Never ever ever allow these on production builds, they are only for prototyping.
            return;
        }
        PluginInstanceManager p = mFactory.createPluginInstanceManager(mContext, action, listener,
                allowMultiple, mBackgroundThread.getLooper(), version);
        p.startListening();
        mPluginMap.put(listener, p);
    }

    public void removePluginListener(PluginListener<?> listener) {
        if (!isDebuggable) {
            // Never ever ever allow these on production builds, they are only for prototyping.
            return;
        }
        if (!mPluginMap.containsKey(listener)) return;
        mPluginMap.remove(listener).stopListening();
    }

    public static PluginManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PluginManager(context.getApplicationContext());
        }
        return sInstance;
    }

    @VisibleForTesting
    public static class PluginInstanceManagerFactory {
        public <T extends Plugin> PluginInstanceManager createPluginInstanceManager(Context context,
                String action, PluginListener<T> listener, boolean allowMultiple, Looper looper,
                int version) {
            return new PluginInstanceManager(context, action, listener, allowMultiple, looper,
                    version);
        }
    }

    private class PluginExceptionHandler implements UncaughtExceptionHandler {
        private final UncaughtExceptionHandler mHandler;

        private PluginExceptionHandler(UncaughtExceptionHandler handler) {
            mHandler = handler;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            // Search for and disable plugins that may have been involved in this crash.
            boolean disabledAny = checkStack(throwable);
            if (!disabledAny) {
                // We couldn't find any plugins involved in this crash, just to be safe
                // disable all the plugins, so we can be sure that SysUI is running as
                // best as possible.
                for (PluginInstanceManager manager : mPluginMap.values()) {
                    manager.disableAll();
                }
            }

            // Run the normal exception handler so we can crash and cleanup our state.
            mHandler.uncaughtException(thread, throwable);
        }

        private boolean checkStack(Throwable throwable) {
            if (throwable == null) return false;
            boolean disabledAny = false;
            for (StackTraceElement element : throwable.getStackTrace()) {
                for (PluginInstanceManager manager : mPluginMap.values()) {
                    disabledAny |= manager.checkAndDisable(element.getClassName());
                }
            }
            return disabledAny | checkStack(throwable.getCause());
        }
    }
}
