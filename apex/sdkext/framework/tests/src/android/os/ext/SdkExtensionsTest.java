/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os.ext;

import android.os.Build;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

public class SdkExtensionsTest extends TestCase {

    @SmallTest
    public void testBadArgument() throws Exception {
        try {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.Q);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }

    @SmallTest
    public void testDefault() throws Exception {
        int r = SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R);
        assertTrue(r >= 0);
    }

}
