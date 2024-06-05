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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class TestLooperManagerTest {
    private static final String TAG = "TestLooperManagerTest";

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Test
    public void testMainThread() throws Exception {
        doTest(Looper.getMainLooper());
    }

    @Test
    public void testCustomThread() throws Exception {
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        doTest(thread.getLooper());
    }

    private void doTest(Looper looper) throws Exception {
        final TestLooperManager tlm =
                InstrumentationRegistry.getInstrumentation().acquireLooperManager(looper);

        final Handler handler = new Handler(looper);
        final CountDownLatch latch = new CountDownLatch(1);

        assertFalse(tlm.hasMessages(handler, null, 42));

        handler.sendEmptyMessage(42);
        handler.post(() -> {
            latch.countDown();
        });
        assertTrue(tlm.hasMessages(handler, null, 42));
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS));

        final Message first = tlm.next();
        assertEquals(42, first.what);
        assertNull(first.callback);
        tlm.execute(first);
        assertFalse(tlm.hasMessages(handler, null, 42));
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS));
        tlm.recycle(first);

        final Message second = tlm.next();
        assertNotNull(second.callback);
        tlm.execute(second);
        assertFalse(tlm.hasMessages(handler, null, 42));
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        tlm.recycle(second);

        tlm.release();
    }
}
