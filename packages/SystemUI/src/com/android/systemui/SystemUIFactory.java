/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.DaggerGlobalRootComponent;
import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.dagger.SysUIComponent;
import com.android.systemui.dagger.WMComponent;
import com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider;

import java.util.concurrent.Executor;

/**
 * Class factory to provide customizable SystemUI components.
 */
public class SystemUIFactory {
    private static final String TAG = "SystemUIFactory";

    static SystemUIFactory mFactory;
    private GlobalRootComponent mRootComponent;
    private WMComponent mWMComponent;
    private SysUIComponent mSysUIComponent;

    public static <T extends SystemUIFactory> T getInstance() {
        return (T) mFactory;
    }

    public static void createFromConfig(Context context) {
        if (mFactory != null) {
            return;
        }

        final String clsName = context.getString(R.string.config_systemUIFactoryComponent);
        if (clsName == null || clsName.length() == 0) {
            throw new RuntimeException("No SystemUIFactory component configured");
        }

        try {
            Class<?> cls = null;
            cls = context.getClassLoader().loadClass(clsName);
            mFactory = (SystemUIFactory) cls.newInstance();
            mFactory.init(context);
        } catch (Throwable t) {
            Log.w(TAG, "Error creating SystemUIFactory component: " + clsName, t);
            throw new RuntimeException(t);
        }
    }

    @VisibleForTesting
    static void cleanup() {
        mFactory = null;
    }

    public SystemUIFactory() {}

    private void init(Context context) {
        mRootComponent = buildGlobalRootComponent(context);
        mWMComponent = mRootComponent.getWMComponentBuilder().build();
        // TODO: use WMComponent to pass APIs into the SysUIComponent.
        mSysUIComponent = mRootComponent.getSysUIComponent().build();

        // Every other part of our codebase currently relies on Dependency, so we
        // really need to ensure the Dependency gets initialized early on.
        Dependency dependency = mSysUIComponent.createDependency();
        dependency.start();
    }

    protected GlobalRootComponent buildGlobalRootComponent(Context context) {
        return DaggerGlobalRootComponent.builder()
                .context(context)
                .build();
    }

    public GlobalRootComponent getRootComponent() {
        return mRootComponent;
    }

    public SysUIComponent getSysUIComponent() {
        return mSysUIComponent;
    }

    /** Returns the list of system UI components that should be started. */
    public String[] getSystemUIServiceComponents(Resources resources) {
        return resources.getStringArray(R.array.config_systemUIServiceComponents);
    }

    /** Returns the list of system UI components that should be started per user. */
    public String[] getSystemUIServiceComponentsPerUser(Resources resources) {
        return resources.getStringArray(R.array.config_systemUIServiceComponentsPerUser);
    }

    /**
     * Creates an instance of ScreenshotNotificationSmartActionsProvider.
     * This method is overridden in vendor specific implementation of Sys UI.
     */
    public ScreenshotNotificationSmartActionsProvider
            createScreenshotNotificationSmartActionsProvider(Context context,
            Executor executor,
            Handler uiHandler) {
        return new ScreenshotNotificationSmartActionsProvider();
    }
}
