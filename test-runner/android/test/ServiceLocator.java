/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.test;

/**
 * @hide - This is part of a framework that is under development and should not be used for
 * active development.
 */
public class ServiceLocator {

    private static TestBrowserController mTestBrowserController =
            new TestBrowserControllerImpl();

    public static TestBrowserController getTestBrowserController() {
        return mTestBrowserController;
    }

    static void setTestBrowserController(TestBrowserController testBrowserController) {
        mTestBrowserController = testBrowserController;
    }
}
