
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
 * limitations under the License.
 */
package com.android.server.pm;

import android.content.pm.ShortcutInfo;
import android.test.AndroidTestCase;

import com.android.server.testutis.TestUtils;

/**
 * Tests for {@link ShortcutInfo}.

 m FrameworksServicesTests &&
 adb install \
   -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutInfoTest \
   -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner

 */
public class ShortcutInfoTest extends AndroidTestCase {

    public void testNoId() {
        TestUtils.assertExpectException(
                IllegalArgumentException.class,
                "ID must be provided",
                () -> new ShortcutInfo.Builder(mContext).build());
    }

    // TODO Add more tests.
}
