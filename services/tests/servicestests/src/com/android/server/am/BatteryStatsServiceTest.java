/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BatteryStatsImpl;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public final class BatteryStatsServiceTest {

    private BatteryStatsService mBatteryStatsService;
    private HandlerThread mBgThread;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getContext();
        mBgThread = new HandlerThread("bg thread");
        mBgThread.start();
        mBatteryStatsService = new BatteryStatsService(context,
                context.getCacheDir(), new Handler(mBgThread.getLooper()));
    }

    @After
    public void tearDown() {
        mBatteryStatsService.shutdown();
        mBgThread.quitSafely();
    }

    @Test
    @Ignore("b/180015146")
    public void testAwaitCompletion() throws Exception {
        final CountDownLatch readyLatch = new CountDownLatch(2);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(1);
        final AtomicBoolean quiting = new AtomicBoolean(false);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final int uid = Process.myUid();
        final Thread noteThread = new Thread(() -> {
            final int maxIterations = 1000;
            final int eventCode = 12345;
            final String eventName = "placeholder";
            final BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();

            readyLatch.countDown();
            try {
                startLatch.await();
            } catch (InterruptedException e) {
            }

            for (int i = 0; i < maxIterations && !quiting.get(); i++) {
                synchronized (stats) {
                    mBatteryStatsService.noteEvent(eventCode, eventName, uid);
                }
            }
            finished.set(true);
        });
        final Thread waitThread = new Thread(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
            } catch (InterruptedException e) {
            }

            do {
                mBatteryStatsService.takeUidSnapshot(uid);
            } while (!finished.get() && !quiting.get());

            if (!quiting.get()) {
                // do one more to ensure we've cleared the queue
                mBatteryStatsService.takeUidSnapshot(uid);
            }

            testLatch.countDown();
        });
        noteThread.start();
        waitThread.start();
        readyLatch.await();
        startLatch.countDown();

        try {
            assertTrue("Timed out in waiting for the completion of battery event handling",
                    testLatch.await(10 * 1000, TimeUnit.MILLISECONDS));
        } finally {
            quiting.set(true);
            noteThread.interrupt();
            noteThread.join(1000);
            waitThread.interrupt();
            waitThread.join(1000);
        }
    }
}
