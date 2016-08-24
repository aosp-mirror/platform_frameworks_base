/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.uiautomator.core;

/**
 * See {@link UiDevice#registerWatcher(String, UiWatcher)} on how to register a
 * a condition watcher to be called by the automation library. The automation library will
 * invoke checkForCondition() only when a regular API call is in retry mode because it is unable
 * to locate its selector yet. Only during this time, the watchers are invoked to check if there is
 * something else unexpected on the screen.
 * @since API Level 16
 * @deprecated New tests should be written using UI Automator 2.0 which is available as part of the
 * Android Testing Support Library.
 */
@Deprecated
public interface UiWatcher {

    /**
     * Custom handler that is automatically called when the testing framework is unable to
     * find a match using the {@link UiSelector}
     *
     * When the framework is in the process of matching a {@link UiSelector} and it
     * is unable to match any widget based on the specified criteria in the selector,
     * the framework will perform retries for a predetermined time, waiting for the display
     * to update and show the desired widget. While the framework is in this state, it will call
     * registered watchers' checkForCondition(). This gives the registered watchers a chance
     * to take a look at the display and see if there is a recognized condition that can be
     * handled and in doing so allowing the current test to continue.
     *
     * An example usage would be to look for dialogs popped due to other background
     * processes requesting user attention and have nothing to do with the application
     * currently under test.
     *
     * @return true to indicate a matched condition or false for nothing was matched
     * @since API Level 16
     */
    public boolean checkForCondition();
}
