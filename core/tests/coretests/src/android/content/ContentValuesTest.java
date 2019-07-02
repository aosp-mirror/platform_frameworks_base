/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.content;

import android.test.AndroidTestCase;

import androidx.test.filters.SmallTest;

/*
  runtest -c android.content.ContentValuesTest frameworks-core

  or

  make -j256 FrameworksCoreTests && \
    adb shell pm uninstall -k com.android.frameworks.coretests && \
    adb install out/target/product/bullhead/testcases/FrameworksCoreTests/FrameworksCoreTests.apk && \
    adb shell am instrument -w -e package android.content \
      com.android.frameworks.coretests/androidx.test.runner.AndroidJUnitRunner
*/
public class ContentValuesTest extends AndroidTestCase {

    @SmallTest
    public void testIsEmpty() throws Exception {
        ContentValues cv = new ContentValues();
        assertTrue(cv.isEmpty());
        assertEquals(0, cv.size());

        cv.put("key", "value");
        assertFalse(cv.isEmpty());
        assertEquals(1, cv.size());
    }
}
