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

import android.platform.test.annotations.ExcludeUnderRavenwood;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.annotations.IncludeUnderRavenwood;

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

    final RavenwoodSystemProperties mSystemProperties = new RavenwoodSystemProperties();

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

        /**
         * Configure the given system property as immutable for the duration of the test.
         * Read access to the key is allowed, and write access will fail. When {@code value} is
         * {@code null}, the value is left as undefined.
         *
         * All properties in the {@code debug.*} namespace are automatically mutable, with no
         * developer action required.
         *
         * Has no effect under non-Ravenwood environments.
         */
        public Builder setSystemPropertyImmutable(/* @NonNull */ String key,
                /* @Nullable */ Object value) {
            mRule.mSystemProperties.setValue(key, value);
            mRule.mSystemProperties.setAccessReadOnly(key);
            return this;
        }

        /**
         * Configure the given system property as mutable for the duration of the test.
         * Both read and write access to the key is allowed, and its value will be reset between
         * each test. When {@code value} is {@code null}, the value is left as undefined.
         *
         * All properties in the {@code debug.*} namespace are automatically mutable, with no
         * developer action required.
         *
         * Has no effect under non-Ravenwood environments.
         */
        public Builder setSystemPropertyMutable(/* @NonNull */ String key,
                /* @Nullable */ Object value) {
            mRule.mSystemProperties.setValue(key, value);
            mRule.mSystemProperties.setAccessReadWrite(key);
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
     * Determine if the given {@link Description} should be included when running under the
     * Ravenwood test environment.
     *
     * A more specific method-level annotation always takes precedence over any class-level
     * annotation, and an {@link IncludeUnderRavenwood} annotation always takes precedence over
     * an {@link ExcludeUnderRavenwood} annotation.
     */
    private boolean shouldIncludeUnderRavenwood(Description description) {
        // Stopgap for http://g/ravenwood/EPAD-N5ntxM
        if (description.getMethodName().endsWith("$noRavenwood")) {
            return false;
        }

        // First, consult any method-level annotations
        if (description.getAnnotation(IncludeUnderRavenwood.class) != null) {
            return true;
        }
        if (description.getAnnotation(ExcludeUnderRavenwood.class) != null) {
            return false;
        }
        if (description.getAnnotation(IgnoreUnderRavenwood.class) != null) {
            return false;
        }

        // Otherwise, consult any class-level annotations
        if (description.getTestClass().getAnnotation(IncludeUnderRavenwood.class) != null) {
            return true;
        }
        if (description.getTestClass().getAnnotation(ExcludeUnderRavenwood.class) != null) {
            return false;
        }
        if (description.getTestClass().getAnnotation(IgnoreUnderRavenwood.class) != null) {
            return false;
        }

        // When no annotations have been requested, assume test should be included
        return true;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        // No special treatment when running outside Ravenwood; run tests as-is
        if (!IS_UNDER_RAVENWOOD) {
            return base;
        }

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
                Assume.assumeTrue(shouldIncludeUnderRavenwood(description));

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
                    // If the test isn't included, eat the exception and report the
                    // assumption failure that test authors expect; otherwise throw
                    Assume.assumeTrue(shouldIncludeUnderRavenwood(description));
                    throw t;
                } finally {
                    RavenwoodRuleImpl.reset(RavenwoodRule.this);
                }

                if (!shouldIncludeUnderRavenwood(description)) {
                    fail("Test wasn't included under Ravenwood, but it actually "
                            + "passed under Ravenwood; consider updating annotations");
                }
            }
        };
    }
}
