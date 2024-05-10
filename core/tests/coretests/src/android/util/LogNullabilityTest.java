/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class LogNullabilityTest {
    @Test
    public void nullTag() {
        Log.v(null, "");
        Log.d(null, "");
        Log.i(null, "");
        Log.w(null, "");
        Log.e(null, "");
        if (!RavenwoodRule.isUnderRavenwood()) {
            Log.wtf(null, "");
            Log.wtfStack(null, "");
        }
        Log.println(Log.INFO, null, "");

        // Implicit assertions of not crashing.
    }

    @Test
    public void nullTagWithThrowable() {
        Log.v(null, "", new Throwable());
        Log.d(null, "", new Throwable());
        Log.i(null, "", new Throwable());
        Log.w(null, "", new Throwable());
        Log.e(null, "", new Throwable());
        if (!RavenwoodRule.isUnderRavenwood()) {
            Log.wtf(null, "", new Throwable());
        }
        Log.printlns(Log.LOG_ID_MAIN, Log.INFO, null, "", new Throwable());

        // Implicit assertions of not crashing.
    }

    @Test
    public void nullMessage() {
        try {
            Log.v("", null);
            fail();
        } catch (NullPointerException expected) {
        }
        try {
            Log.d("", null);
            fail();
        } catch (NullPointerException expected) {
        }
        try {
            Log.i("", null);
            fail();
        } catch (NullPointerException expected) {
        }
        try {
            // Explicit cast needed because there's a weird (String, Throwable) overload.
            Log.w("", (String) null);
            fail();
        } catch (NullPointerException expected) {
        }
        try {
            Log.e("", null);
            fail();
        } catch (NullPointerException expected) {
        }

        if (!RavenwoodRule.isUnderRavenwood()) {
            Log.wtf("", (String) null);
            Log.wtfStack("", (String) null);
        }

        // Implicit assertion of not crashing.

        try {
            Log.println(Log.INFO, "", null);
            fail();
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void nullMessageWithThrowable() {
        Log.v("", null, new Throwable());
        Log.d("", null, new Throwable());
        Log.i("", null, new Throwable());
        Log.w("", null, new Throwable());
        Log.e("", null, new Throwable());
        if (!RavenwoodRule.isUnderRavenwood()) {
            Log.wtf("", null, new Throwable());
        }
        Log.printlns(Log.LOG_ID_MAIN, Log.INFO, "", null, new Throwable());
    }

    @Test
    public void nullThrowable() {
        Log.v("", "", null);
        Log.d("", "", null);
        Log.i("", "", null);
        Log.w("", "", null);
        Log.e("", "", null);
        if (!RavenwoodRule.isUnderRavenwood()) {
            Log.wtf("", "", null);
        }

        // Warning has its own (String, Throwable) overload.
        Log.w("", (Throwable) null);

        Log.printlns(Log.LOG_ID_MAIN, Log.INFO, "", "", null);

        // Implicit assertions of not crashing.

        // WTF has its own (String, Throwable) overload with different behavior.
        if (!RavenwoodRule.isUnderRavenwood()) {
            try {
                Log.wtf("", (Throwable) null);
                fail();
            } catch (NullPointerException expected) {
            }
        }
    }

    @Test
    public void nullMessageWithNullThrowable() {
        Log.v("", null, null);
        Log.d("", null, null);
        Log.i("", null, null);
        Log.w("", null, null);
        Log.e("", null, null);
        if (!RavenwoodRule.isUnderRavenwood()) {
            Log.wtf("", null, null);
        }
        Log.printlns(Log.LOG_ID_MAIN, Log.INFO, "", null, null);
    }

    @Test
    public void nullTagIsLoggable() {
        Log.isLoggable(null, Log.INFO);

        // Implicit assertion of not crashing.
    }

    @Test
    public void nullGetStackTraceString() {
        assertNotNull(Log.getStackTraceString(null));
    }
}
