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

package android.app;

import android.os.Bundle;
import android.support.test.filters.LargeTest;
import android.test.InstrumentationTestCase;

@LargeTest
public class InstrumentationTest extends InstrumentationTestCase {

    /**
     * Simple stress test for {@link Instrumentation#sendStatus(int, android.os.Bundle)}, to
     * ensure it can handle many rapid calls without failing.
     */
    public void testSendStatus() {
        for (int i = 0; i < 10000; i++) {
            Bundle bundle = new Bundle();
            bundle.putInt("iterations", i);
            getInstrumentation().sendStatus(-1, bundle);
        }
    }
}
