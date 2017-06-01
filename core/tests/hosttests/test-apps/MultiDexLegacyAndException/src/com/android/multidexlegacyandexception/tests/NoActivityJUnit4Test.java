/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.multidexlegacyandexception.tests;

import android.support.test.runner.AndroidJUnit4;
import org.junit.runner.RunWith;

/**
 * Run the tests with: <code>
adb shell am instrument -w com.android.multidexlegacyandexception/com.android.test.runner.MultiDexTestRunner
</code>
 * or <code>
adb shell am instrument -w com.android.multidexlegacyandexception/com.android.multidexlegacyandexception.tests.MultiDexAndroidJUnitRunner
</code>
 */
@RunWith(AndroidJUnit4.class)
public class NoActivityJUnit4Test extends NoActivityJUnit3Test {

  @org.junit.Test
  public void multidexedTestJUnit4() {
    super.testMultidexedTest();
  }

}
