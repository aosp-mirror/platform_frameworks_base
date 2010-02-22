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

import android.content.Intent;
import junit.framework.TestSuite;

/**
 * @hide - This is part of a framework that is under development and should not be used for
 * active development.
 */
public interface TestBrowserController {
    String BUNDLE_EXTRA_TEST_METHOD_NAME = "testMethodName";

    Intent getIntentForTestAt(int position);

    void setTestSuite(TestSuite testSuite);

    void registerView(TestBrowserView testBrowserView);

    void setTargetBrowserActivityClassName(String targetBrowserActivityClassName);

    void setTargetPackageName(String targetPackageName);
}
