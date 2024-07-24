/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PersistableBundle;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.PowerStats;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PowerStatsCollectorTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private final MockClock mMockClock = new MockClock();
    private final HandlerThread mHandlerThread = new HandlerThread("test");
    private Handler mHandler;
    private PowerStatsCollector mCollector;
    private PowerStats mCollectedStats;

    @Before
    public void setup() {
        mHandlerThread.start();
        mHandler = mHandlerThread.getThreadHandler();
        mCollector = new PowerStatsCollector(mHandler,
                60000,
                mMockClock) {
            @Override
            protected PowerStats collectStats() {
                return new PowerStats(new PowerStats.Descriptor(0, 0, 0, new PersistableBundle()));
            }
        };
        mCollector.addConsumer(stats -> mCollectedStats = stats);
        mCollector.setEnabled(true);
    }

    @Test
    public void throttlePeriod() {
        mMockClock.uptime = 1000;
        mCollector.schedule();
        waitForIdle();

        assertThat(mCollectedStats).isNotNull();

        mMockClock.uptime += 1000;
        mCollectedStats = null;
        mCollector.schedule();      // Should be throttled
        waitForIdle();

        assertThat(mCollectedStats).isNull();

        // Should be allowed to run
        mMockClock.uptime += 100_000;
        mCollector.schedule();
        waitForIdle();

        assertThat(mCollectedStats).isNotNull();
    }

    private void waitForIdle() {
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();
    }
}
