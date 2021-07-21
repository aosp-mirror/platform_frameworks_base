/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.os;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ParcelObtainPerfTest {
    private static final int ITERATIONS = 1_000_000;

    @Test
    public void timeContention_01() throws Exception {
        timeContention(1);
    }

    @Test
    public void timeContention_04() throws Exception {
        timeContention(4);
    }

    @Test
    public void timeContention_16() throws Exception {
        timeContention(16);
    }

    private static void timeContention(int numThreads) throws Exception {
        final long start = SystemClock.elapsedRealtime();
        {
            final ObtainThread[] threads = new ObtainThread[numThreads];
            for (int i = 0; i < numThreads; i++) {
                final ObtainThread thread = new ObtainThread(ITERATIONS / numThreads);
                thread.start();
                threads[i] = thread;
            }
            for (int i = 0; i < numThreads; i++) {
                threads[i].join();
            }
        }
        final long duration = SystemClock.elapsedRealtime() - start;

        final Bundle results = new Bundle();
        results.putLong("duration", duration);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, results);
    }

    public static class ObtainThread extends Thread {
        public int iterations;

        public ObtainThread(int iterations) {
            this.iterations = iterations;
        }

        @Override
        public void run() {
            while (iterations-- > 0) {
                final Parcel data = Parcel.obtain();
                final Parcel reply = Parcel.obtain();
                try {
                    data.writeInt(32);
                    reply.writeInt(32);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
