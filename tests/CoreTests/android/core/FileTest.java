/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import junit.framework.TestCase;

import java.io.File;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Checks creation and deletion of a file.
 */
public class FileTest extends TestCase {

    @SmallTest
    public void testFile() throws Exception {

        File file = File.createTempFile(String.valueOf(System.currentTimeMillis()), null, null);

        assertTrue(file.exists());
        assertTrue(file.delete());
        assertFalse(file.exists());
    }
}
