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

package android.testing;

import junit.framework.Assert;
import java.util.concurrent.Callable;

public abstract class PollingCheck {
    private static final long TIME_SLICE = 50;
    private long mTimeout = 3000;

    public static interface PollingCheckCondition {
        boolean canProceed();
    }

    public PollingCheck() {
    }

    public PollingCheck(long timeout) {
        mTimeout = timeout;
    }

    protected abstract boolean check();

    public void run() {
        if (check()) {
            return;
        }

        long timeout = mTimeout;
        while (timeout > 0) {
            try {
                Thread.sleep(TIME_SLICE);
            } catch (InterruptedException e) {
                Assert.fail("unexpected InterruptedException");
            }

            if (check()) {
                return;
            }

            timeout -= TIME_SLICE;
        }

        Assert.fail("unexpected timeout");
    }

    public static void check(CharSequence message, long timeout, Callable<Boolean> condition)
            throws Exception {
        while (timeout > 0) {
            if (condition.call()) {
                return;
            }

            Thread.sleep(TIME_SLICE);
            timeout -= TIME_SLICE;
        }

        Assert.fail(message.toString());
    }

    public static void waitFor(final PollingCheckCondition condition) {
        new PollingCheck() {
            @Override
            protected boolean check() {
                return condition.canProceed();
            }
        }.run();
    }

    public static void waitFor(long timeout, final PollingCheckCondition condition) {
        new PollingCheck(timeout) {
            @Override
            protected boolean check() {
                return condition.canProceed();
            }
        }.run();
    }
}

