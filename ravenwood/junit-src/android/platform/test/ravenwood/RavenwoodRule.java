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

package android.platform.test.ravenwood;

import android.platform.test.annotations.IgnoreUnderRavenwood;

import org.junit.Assume;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * THIS RULE IS EXPERIMENTAL. REACH OUT TO g/ravenwood BEFORE USING IT, OR YOU HAVE ANY
 * QUESTIONS ABOUT IT.
 *
 * @hide
 */
public class RavenwoodRule implements TestRule {
    private static AtomicInteger sNextPid = new AtomicInteger(100);

    private static final int SYSTEM_UID = 1000;
    private static final int NOBODY_UID = 9999;
    private static final int FIRST_APPLICATION_UID = 10000;

    /**
     * Unless the test author requests differently, run as "nobody", and give each collection of
     * tests its own unique PID.
     */
    int mUid = NOBODY_UID;
    int mPid = sNextPid.getAndIncrement();

    public RavenwoodRule() {
    }

    public static class Builder {
        private RavenwoodRule mRule = new RavenwoodRule();

        public Builder() {
        }

        /**
         * Configure the identity of this process to be the system UID for the duration of the
         * test. Has no effect under non-Ravenwood environments.
         */
        public Builder setProcessSystem() {
            mRule.mUid = SYSTEM_UID;
            return this;
        }

        /**
         * Configure the identity of this process to be an app UID for the duration of the
         * test. Has no effect under non-Ravenwood environments.
         */
        public Builder setProcessApp() {
            mRule.mUid = FIRST_APPLICATION_UID;
            return this;
        }

        public RavenwoodRule build() {
            return mRule;
        }
    }

    /**
     * Return if the current process is running under a Ravenwood test environment.
     */
    public boolean isUnderRavenwood() {
        // TODO: give ourselves a better environment signal
        return System.getProperty("java.class.path").contains("ravenwood");
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                final boolean isUnderRavenwood = isUnderRavenwood();
                if (description.getAnnotation(IgnoreUnderRavenwood.class) != null) {
                    Assume.assumeFalse(isUnderRavenwood);
                }
                if (isUnderRavenwood) {
                    RavenwoodRuleImpl.init(RavenwoodRule.this);
                }
                try {
                    base.evaluate();
                } finally {
                    if (isUnderRavenwood) {
                        RavenwoodRuleImpl.reset(RavenwoodRule.this);
                    }
                }
            }
        };
    }
}
