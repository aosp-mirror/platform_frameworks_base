/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;

import java.util.function.Consumer;

/**
 * Base class for the Robots related to App Compat tests.
 */
abstract class AppCompatRobotBase {

    private static final int DEFAULT_DISPLAY_WIDTH = 1000;
    private static final int DEFAULT_DISPLAY_HEIGHT = 2000;

    static final float FLOAT_TOLLERANCE = 0.01f;

    @NonNull
    private final AppCompatActivityRobot mActivityRobot;
    @NonNull
    private final AppCompatConfigurationRobot mConfigurationRobot;
    @NonNull
    private final AppCompatComponentPropRobot mOptPropRobot;
    @NonNull
    private final AppCompatResourcesRobot mResourcesRobot;

    AppCompatRobotBase(@NonNull WindowManagerService wm,
            @NonNull ActivityTaskManagerService atm,
            @NonNull ActivityTaskSupervisor supervisor,
            int displayWidth, int displayHeight) {
        mActivityRobot = new AppCompatActivityRobot(wm, atm, supervisor,
                displayWidth, displayHeight, this::onPostActivityCreation,
                this::onPostDisplayContentCreation);
        mConfigurationRobot =
                new AppCompatConfigurationRobot(wm.mAppCompatConfiguration);
        mOptPropRobot = new AppCompatComponentPropRobot(wm);
        mResourcesRobot = new AppCompatResourcesRobot(wm.mContext.getResources());
    }

    AppCompatRobotBase(@NonNull WindowManagerService wm,
            @NonNull ActivityTaskManagerService atm,
            @NonNull ActivityTaskSupervisor supervisor) {
        this(wm, atm, supervisor, DEFAULT_DISPLAY_WIDTH, DEFAULT_DISPLAY_HEIGHT);
    }

    /**
     * Specific Robots can override this method to add operation to run on a newly created
     * {@link ActivityRecord}. Common case is to invoke spyOn().
     *
     * @param activity The newly created {@link ActivityRecord}.
     */
    @CallSuper
    void onPostActivityCreation(@NonNull ActivityRecord activity) {
    }

    /**
     * Specific Robots can override this method to add operation to run on a newly created
     * {@link DisplayContent}. Common case is to invoke spyOn().
     *
     * @param displayContent THe newly created {@link DisplayContent}.
     */
    @CallSuper
    void onPostDisplayContentCreation(@NonNull DisplayContent displayContent) {
    }

    @NonNull
    AppCompatConfigurationRobot conf() {
        return mConfigurationRobot;
    }

    void applyOnConf(@NonNull Consumer<AppCompatConfigurationRobot> consumer) {
        consumer.accept(mConfigurationRobot);
    }

    @NonNull
    AppCompatActivityRobot activity() {
        return mActivityRobot;
    }

    void applyOnActivity(@NonNull Consumer<AppCompatActivityRobot> consumer) {
        consumer.accept(mActivityRobot);
    }

    @NonNull
    AppCompatComponentPropRobot prop() {
        return mOptPropRobot;
    }

    void applyOnProp(@NonNull Consumer<AppCompatComponentPropRobot> consumer) {
        consumer.accept(mOptPropRobot);
    }

    @NonNull
    AppCompatResourcesRobot resources() {
        return mResourcesRobot;
    }

    void applyOnResources(@NonNull Consumer<AppCompatResourcesRobot> consumer) {
        consumer.accept(mResourcesRobot);
    }
}
