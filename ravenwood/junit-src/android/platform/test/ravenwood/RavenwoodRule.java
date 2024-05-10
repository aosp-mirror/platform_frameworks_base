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

import static org.junit.Assert.fail;

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

    private static final boolean IS_UNDER_RAVENWOOD = RavenwoodRuleImpl.isUnderRavenwood();

    /**
     * When probing is enabled, all tests will be unconditionally run under Ravenwood to detect
     * cases where a test is able to pass despite being marked as {@code IgnoreUnderRavenwood}.
     *
     * This is typically helpful for internal maintainers discovering tests that had previously
     * been ignored, but now have enough Ravenwood-supported functionality to be enabled.
     */
    private static final boolean ENABLE_PROBE_IGNORED = false; // DO NOT SUBMIT WITH TRUE

    private static final int SYSTEM_UID = 1000;
    private static final int NOBODY_UID = 9999;
    private static final int FIRST_APPLICATION_UID = 10000;

    /**
     * Unless the test author requests differently, run as "nobody", and give each collection of
     * tests its own unique PID.
     */
    int mUid = NOBODY_UID;
    int mPid = sNextPid.getAndIncrement();

    boolean mProvideMainThread = false;

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

        /**
         * Configure a "main" thread to be available for the duration of the test, as defined
         * by {@code Looper.getMainLooper()}. Has no effect under non-Ravenwood environments.
         */
        public Builder setProvideMainThread(boolean provideMainThread) {
            mRule.mProvideMainThread = provideMainThread;
            return this;
        }

        public RavenwoodRule build() {
            return mRule;
        }
    }

    /**
     * Return if the current process is running under a Ravenwood test environment.
     */
    public static boolean isUnderRavenwood() {
        return IS_UNDER_RAVENWOOD;
    }

    /**
     * Test if the given {@link Description} has been marked with an {@link IgnoreUnderRavenwood}
     * annotation, either at the method or class level.
     */
    private static boolean hasIgnoreUnderRavenwoodAnnotation(Description description) {
        if (description.getTestClass().getAnnotation(IgnoreUnderRavenwood.class) != null) {
            return true;
        } else if (description.getAnnotation(IgnoreUnderRavenwood.class) != null) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public Statement apply(Statement base, Description description) {
        if (ENABLE_PROBE_IGNORED) {
            return applyProbeIgnored(base, description);
        } else {
            return applyDefault(base, description);
        }
    }

    /**
     * Run the given {@link Statement} with no special treatment.
     */
    private Statement applyDefault(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                if (hasIgnoreUnderRavenwoodAnnotation(description)) {
                    Assume.assumeFalse(IS_UNDER_RAVENWOOD);
                }

                RavenwoodRuleImpl.init(RavenwoodRule.this);
                try {
                    base.evaluate();
                } finally {
                    RavenwoodRuleImpl.reset(RavenwoodRule.this);
                }
            }
        };
    }

    /**
     * Run the given {@link Statement} with probing enabled. All tests will be unconditionally
     * run under Ravenwood to detect cases where a test is able to pass despite being marked as
     * {@code IgnoreUnderRavenwood}.
     */
    private Statement applyProbeIgnored(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                RavenwoodRuleImpl.init(RavenwoodRule.this);
                try {
                    base.evaluate();
                } catch (Throwable t) {
                    if (hasIgnoreUnderRavenwoodAnnotation(description)) {
                        // This failure is expected, so eat the exception and report the
                        // assumption failure that test authors expect
                        Assume.assumeFalse(IS_UNDER_RAVENWOOD);
                    }
                    throw t;
                } finally {
                    RavenwoodRuleImpl.reset(RavenwoodRule.this);
                }

                if (hasIgnoreUnderRavenwoodAnnotation(description) && IS_UNDER_RAVENWOOD) {
                    fail("Test was annotated with IgnoreUnderRavenwood, but it actually "
                            + "passed under Ravenwood; consider removing the annotation");
                }
            }
        };
    }
}
