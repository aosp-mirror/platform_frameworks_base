/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class RemoteCallbackTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Test
    public void testSimple() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final RemoteCallback cb = new RemoteCallback((bundle) -> {
            latch.countDown();
        }, new Handler(Looper.getMainLooper()));

        assertEquals(1, latch.getCount());
        cb.sendResult(Bundle.EMPTY);
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
    }
}
