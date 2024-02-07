/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.dagger.SysUIComponent;
import com.android.systemui.dagger.WMComponent;
import com.android.systemui.res.R;
import com.android.systemui.util.InitializationChecker;
import com.android.wm.shell.dagger.WMShellConcurrencyModule;
import com.android.wm.shell.keyguard.KeyguardTransitions;
import com.android.wm.shell.sysui.ShellInterface;
import com.android.wm.shell.transition.ShellTransitions;

import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Initializer that stands up SystemUI.
 *
 * Implementations should override {@link #getGlobalRootComponentBuilder()} to fill in their own
 * Dagger root component.
 */
public abstract class SystemUIInitializer {
    private static final String TAG = "SystemUIFactory";

    private final Context mContext;

    private GlobalRootComponent mRootComponent;
    private WMComponent mWMComponent;
    private SysUIComponent mSysUIComponent;
    private InitializationChecker mInitializationChecker;

    public SystemUIInitializer(Context context) {
        mContext = context;
    }

    protected abstract GlobalRootComponent.Builder getGlobalRootComponentBuilder();

    /**
     * Prepares the SysUIComponent builder before it is built.
     * @param sysUIBuilder the builder provided by the root component's getSysUIComponent() method
     * @param wm the built WMComponent from the root component's getWMComponent() method
     */
    protected SysUIComponent.Builder prepareSysUIComponentBuilder(
            SysUIComponent.Builder sysUIBuilder, WMComponent wm) {
        return sysUIBuilder;
    }

    /**
     * Starts the initialization process. This stands up the Dagger graph.
     */
    public void init(boolean fromTest) throws ExecutionException, InterruptedException {
        mRootComponent = getGlobalRootComponentBuilder()
                .context(mContext)
                .instrumentationTest(fromTest)
                .build();

        mInitializationChecker = mRootComponent.getInitializationChecker();
        boolean initializeComponents = mInitializationChecker.initializeComponents();

        // Stand up WMComponent
        setupWmComponent(mContext);

        // And finally, retrieve whatever SysUI needs from WMShell and build SysUI.
        SysUIComponent.Builder builder = mRootComponent.getSysUIComponent();
        if (initializeComponents) {
            // Only initialize when not starting from tests since this currently initializes some
            // components that shouldn't be run in the test environment
            builder = prepareSysUIComponentBuilder(builder, mWMComponent)
                    .setShell(mWMComponent.getShell())
                    .setPip(mWMComponent.getPip())
                    .setSplitScreen(mWMComponent.getSplitScreen())
                    .setOneHanded(mWMComponent.getOneHanded())
                    .setBubbles(mWMComponent.getBubbles())
                    .setTaskViewFactory(mWMComponent.getTaskViewFactory())
                    .setTransitions(mWMComponent.getTransitions())
                    .setKeyguardTransitions(mWMComponent.getKeyguardTransitions())
                    .setStartingSurface(mWMComponent.getStartingSurface())
                    .setDisplayAreaHelper(mWMComponent.getDisplayAreaHelper())
                    .setRecentTasks(mWMComponent.getRecentTasks())
                    .setBackAnimation(mWMComponent.getBackAnimation())
                    .setDesktopMode(mWMComponent.getDesktopMode());

            // Only initialize when not starting from tests since this currently initializes some
            // components that shouldn't be run in the test environment
            mWMComponent.init();
        } else {
            // TODO: Call on prepareSysUIComponentBuilder but not with real components. Other option
            // is separating this logic into newly creating SystemUITestsFactory.
            builder = prepareSysUIComponentBuilder(builder, mWMComponent)
                    .setShell(new ShellInterface() {})
                    .setPip(Optional.ofNullable(null))
                    .setSplitScreen(Optional.ofNullable(null))
                    .setOneHanded(Optional.ofNullable(null))
                    .setBubbles(Optional.ofNullable(null))
                    .setTaskViewFactory(Optional.ofNullable(null))
                    .setTransitions(new ShellTransitions() {})
                    .setKeyguardTransitions(new KeyguardTransitions() {})
                    .setDisplayAreaHelper(Optional.ofNullable(null))
                    .setStartingSurface(Optional.ofNullable(null))
                    .setRecentTasks(Optional.ofNullable(null))
                    .setBackAnimation(Optional.ofNullable(null))
                    .setDesktopMode(Optional.ofNullable(null));
        }
        mSysUIComponent = builder.build();

        // Every other part of our codebase currently relies on Dependency, so we
        // really need to ensure the Dependency gets initialized early on.
        Dependency dependency = mSysUIComponent.createDependency();
        dependency.start();
    }

    /**
     * Sets up {@link #mWMComponent}. On devices where the Shell runs on its own main thread,
     * this will pre-create the thread to ensure that the components are constructed on the
     * same thread, to reduce the likelihood of side effects from running the constructors on
     * a different thread than the rest of the class logic.
     */
    private void setupWmComponent(Context context) {
        WMComponent.Builder wmBuilder = mRootComponent.getWMComponentBuilder();
        if (!mInitializationChecker.initializeComponents()
                || !WMShellConcurrencyModule.enableShellMainThread(context)) {
            // If running under tests or shell thread is not enabled, we don't need anything special
            mWMComponent = wmBuilder.build();
            return;
        }

        // If the shell main thread is enabled, initialize the component on that thread
        HandlerThread shellThread = WMShellConcurrencyModule.createShellMainThread();
        shellThread.start();

        // Use an async handler since we don't care about synchronization
        Handler shellHandler = Handler.createAsync(shellThread.getLooper());
        boolean built = shellHandler.runWithScissors(() -> {
            wmBuilder.setShellMainThread(shellThread);
            mWMComponent = wmBuilder.build();
        }, 5000);
        if (!built) {
            Log.w(TAG, "Failed to initialize WMComponent");
            throw new RuntimeException();
        }
    }

    public GlobalRootComponent getRootComponent() {
        return mRootComponent;
    }

    public WMComponent getWMComponent() {
        return mWMComponent;
    }

    public SysUIComponent getSysUIComponent() {
        return mSysUIComponent;
    }

    /**
     * Returns the list of additional system UI components that should be started.
     */
    public String getVendorComponent(Resources resources) {
        return resources.getString(R.string.config_systemUIVendorServiceComponent);
    }
}
