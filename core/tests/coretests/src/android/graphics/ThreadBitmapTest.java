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

package android.graphics;

import androidx.test.filters.LargeTest;

import junit.framework.TestCase;

public class ThreadBitmapTest extends TestCase {

    @Override
    protected void setUp() throws Exception {
    }

    @LargeTest
    public void testCreation() {
        for (int i = 0; i < 200; i++) {

            new MThread().start();
        }
    }

    class MThread extends Thread {
        public Bitmap b;

        public MThread() {
            b = Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565);
        }

        public void run() {}
    }
}

