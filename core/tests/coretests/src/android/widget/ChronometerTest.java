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

package android.widget;

import android.app.Activity;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;

import androidx.test.filters.LargeTest;

import com.android.frameworks.coretests.R;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test {@link Chronometer} counting up and down.
 */
@SuppressWarnings("deprecation")
@LargeTest
public class ChronometerTest extends ActivityInstrumentationTestCase2<ChronometerActivity> {

    private Activity mActivity;
    private Chronometer mChronometer;

    public ChronometerTest() {
        super(ChronometerActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mChronometer = mActivity.findViewById(R.id.chronometer);
    }

    public void testChronometerTicksSequentially() throws Throwable {
        final CountDownLatch latch = new CountDownLatch(6);
        ArrayList<String> ticks = new ArrayList<>();
        runOnUiThread(() -> {
            mChronometer.setOnChronometerTickListener((chronometer) -> {
                ticks.add(chronometer.getText().toString());
                latch.countDown();
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                }
            });
            mChronometer.start();
        });
        assertTrue(latch.await(5500, TimeUnit.MILLISECONDS));
        assertEquals("00:00", ticks.get(0));
        assertEquals("00:01", ticks.get(1));
        assertEquals("00:02", ticks.get(2));
        assertEquals("00:03", ticks.get(3));
        assertEquals("00:04", ticks.get(4));
        assertEquals("00:05", ticks.get(5));
    }

    public void testChronometerCountDown() throws Throwable {
        final CountDownLatch latch = new CountDownLatch(5);
        ArrayList<String> ticks = new ArrayList<>();
        runOnUiThread(() -> {
            mChronometer.setBase(SystemClock.elapsedRealtime() + 3_000);
            mChronometer.setCountDown(true);
            mChronometer.post(() -> {
                mChronometer.setOnChronometerTickListener((chronometer) -> {
                    ticks.add(chronometer.getText().toString());
                    latch.countDown();
                });
                mChronometer.start();
            });
        });
        assertTrue(latch.await(4500, TimeUnit.MILLISECONDS));
        assertEquals("00:02", ticks.get(0));
        assertEquals("00:01", ticks.get(1));
        assertEquals("00:00", ticks.get(2));
        assertEquals("−00:01", ticks.get(3));
        assertEquals("−00:02", ticks.get(4));
    }

    private void runOnUiThread(Runnable runnable) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        mActivity.runOnUiThread(() -> {
            runnable.run();
            latch.countDown();
        });
        latch.await();
    }
}
