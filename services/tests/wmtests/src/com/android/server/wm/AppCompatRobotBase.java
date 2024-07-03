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

import androidx.annotation.NonNull;

import java.util.function.Consumer;

/**
 * Base class for the Robots related to App Compat tests.
 */
abstract class AppCompatRobotBase {

    private static final int DEFAULT_DISPLAY_WIDTH = 1000;
    private static final int DEFAULT_DISPLAY_HEIGHT = 2000;

    @NonNull
    private final AppCompatActivityRobot mActivityRobot;
    @NonNull
    private final AppCompatLetterboxConfigurationRobot mConfigurationRobot;
    @NonNull
    private final AppCompatComponentPropRobot mOptPropRobot;

    AppCompatRobotBase(@NonNull WindowManagerService wm,
            @NonNull ActivityTaskManagerService atm,
            @NonNull ActivityTaskSupervisor supervisor,
            int displayWidth, int displayHeight) {
        mActivityRobot = new AppCompatActivityRobot(wm, atm, supervisor,
                displayWidth, displayHeight);
        mConfigurationRobot =
                new AppCompatLetterboxConfigurationRobot(wm.mLetterboxConfiguration);
        mOptPropRobot = new AppCompatComponentPropRobot(wm);
    }

    AppCompatRobotBase(@NonNull WindowManagerService wm,
            @NonNull ActivityTaskManagerService atm,
            @NonNull ActivityTaskSupervisor supervisor) {
        this(wm, atm, supervisor, DEFAULT_DISPLAY_WIDTH, DEFAULT_DISPLAY_HEIGHT);
    }

    @NonNull
    AppCompatLetterboxConfigurationRobot conf() {
        return mConfigurationRobot;
    }

    @NonNull
    void applyOnConf(@NonNull Consumer<AppCompatLetterboxConfigurationRobot> consumer) {
        consumer.accept(mConfigurationRobot);
    }

    @NonNull
    AppCompatActivityRobot activity() {
        return mActivityRobot;
    }

    @NonNull
    void applyOnActivity(@NonNull Consumer<AppCompatActivityRobot> consumer) {
        consumer.accept(mActivityRobot);
    }

    @NonNull
    AppCompatComponentPropRobot prop() {
        return mOptPropRobot;
    }

    @NonNull
    void applyOnProp(@NonNull Consumer<AppCompatComponentPropRobot> consumer) {
        consumer.accept(mOptPropRobot);
    }
}
