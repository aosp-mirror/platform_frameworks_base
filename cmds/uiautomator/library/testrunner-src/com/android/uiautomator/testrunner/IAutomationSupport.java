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

package com.android.uiautomator.testrunner;

import android.os.Bundle;

/**
 * Provides auxiliary support for running test cases
 *
 * @since API Level 16
 * @deprecated New tests should be written using UI Automator 2.0 which is available as part of the
 * Android Testing Support Library.
 */
@Deprecated
public interface IAutomationSupport {

    /**
     * Allows the running test cases to send out interim status
     *
     * @param resultCode
     * @param status status report, consisting of key value pairs
     * @since API Level 16
     */
    public void sendStatus(int resultCode, Bundle status);

}
