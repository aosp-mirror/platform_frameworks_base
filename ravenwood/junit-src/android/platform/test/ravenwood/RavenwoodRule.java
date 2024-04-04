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

import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.USER_SYSTEM;

import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.platform.test.annotations.DisabledOnNonRavenwood;
import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.annotations.EnabledOnRavenwood;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.util.ArraySet;

import org.junit.Assume;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * {@code @Rule} that configures the Ravenwood test environment. This rule has no effect when
 * tests are run on non-Ravenwood test environments.
 *
 * This rule initializes and resets the Ravenwood environment between each test method to offer a
 * hermetic testing environment.
 *
 * By default, all tests are executed on Ravenwood, but annotations such as
 * {@link DisabledOnRavenwood} and {@link EnabledOnRavenwood} can be used at both the method
 * and class level to "ignore" tests that may not be ready. When needed, a
 * {@link RavenwoodClassRule} can be used in addition to a {@link RavenwoodRule} to ignore tests
 * before a test class is fully initialized.
 */
public class RavenwoodRule implements TestRule {
    static final boolean IS_ON_RAVENWOOD = RavenwoodRuleImpl.isOnRavenwood();

    /**
     * When probing is enabled, all tests will be unconditionally run on Ravenwood to detect
     * cases where a test is able to pass despite being marked as {@code IgnoreUnderRavenwood}.
     *
     * This is typically helpful for internal maintainers discovering tests that had previously
     * been ignored, but now have enough Ravenwood-supported functionality to be enabled.
     */
    static final boolean ENABLE_PROBE_IGNORED = "1".equals(
            System.getenv("RAVENWOOD_RUN_DISABLED_TESTS"));

    /**
     * When using ENABLE_PROBE_IGNORED, you may still want to skip certain tests,
     * for example because the test would crash the JVM.
     *
     * This regex defines the tests that should still be disabled even if ENABLE_PROBE_IGNORED
     * is set.
     *
     * Before running each test class and method, we check if this pattern can be found in
     * the full test name (either [class full name], or [class full name] + "#" + [method name]),
     * and if so, we skip it.
     *
     * For example, if you want to skip an entire test class, use:
     * RAVENWOOD_REALLY_DISABLE='\.CustomTileDefaultsRepositoryTest$'
     *
     * For example, if you want to skip an entire test class, use:
     * RAVENWOOD_REALLY_DISABLE='\.CustomTileDefaultsRepositoryTest#testSimple$'
     *
     * To ignore multiple classes, use (...|...), for example:
     * RAVENWOOD_REALLY_DISABLE='\.(ClassA|ClassB)$'
     *
     * Because we use a regex-find, setting "." would disable all tests.
     */
    private static final Pattern REALLY_DISABLE_PATTERN = Pattern.compile(
            Objects.requireNonNullElse(System.getenv("RAVENWOOD_REALLY_DISABLE"), ""));

    private static final boolean ENABLE_REALLY_DISABLE_PATTERN =
            !REALLY_DISABLE_PATTERN.pattern().isEmpty();

    /**
     * If true, enable optional validation on running tests.
     */
    private static final boolean ENABLE_OPTIONAL_VALIDATION = "1".equals(
            System.getenv("RAVENWOOD_OPTIONAL_VALIDATION"));

    static {
        if (ENABLE_PROBE_IGNORED) {
            System.out.println("$RAVENWOOD_RUN_DISABLED_TESTS enabled: force running all tests");
            if (ENABLE_REALLY_DISABLE_PATTERN) {
                System.out.println("$RAVENWOOD_REALLY_DISABLE=" + REALLY_DISABLE_PATTERN.pattern());
            }
        }
    }

    private static final int NOBODY_UID = 9999;

    private static final AtomicInteger sNextPid = new AtomicInteger(100);

    int mCurrentUser = USER_SYSTEM;

    /**
     * Unless the test author requests differently, run as "nobody", and give each collection of
     * tests its own unique PID.
     */
    int mUid = NOBODY_UID;
    int mPid = sNextPid.getAndIncrement();

    String mPackageName;

    boolean mProvideMainThread = false;

    final RavenwoodSystemProperties mSystemProperties = new RavenwoodSystemProperties();

    final List<Class<?>> mServicesRequired = new ArrayList<>();

    volatile Context mContext;
    volatile Instrumentation mInstrumentation;

    public RavenwoodRule() {
    }

    public static class Builder {
        private RavenwoodRule mRule = new RavenwoodRule();

        public Builder() {
        }

        /**
         * Configure the identity of this process to be the system UID for the duration of the
         * test. Has no effect on non-Ravenwood environments.
         */
        public Builder setProcessSystem() {
            mRule.mUid = SYSTEM_UID;
            return this;
        }

        /**
         * Configure the identity of this process to be an app UID for the duration of the
         * test. Has no effect on non-Ravenwood environments.
         */
        public Builder setProcessApp() {
            mRule.mUid = FIRST_APPLICATION_UID;
            return this;
        }

        /**
         * Configure the identity of this process to be the given package name for the duration
         * of the test. Has no effect on non-Ravenwood environments.
         */
        public Builder setPackageName(/* @NonNull */ String packageName) {
            mRule.mPackageName = Objects.requireNonNull(packageName);
            return this;
        }

        /**
         * Configure a "main" thread to be available for the duration of the test, as defined
         * by {@code Looper.getMainLooper()}. Has no effect on non-Ravenwood environments.
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
         * Has no effect on non-Ravenwood environments.
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
         * Has no effect on non-Ravenwood environments.
         */
        public Builder setSystemPropertyMutable(/* @NonNull */ String key,
                /* @Nullable */ Object value) {
            mRule.mSystemProperties.setValue(key, value);
            mRule.mSystemProperties.setAccessReadWrite(key);
            return this;
        }

        /**
         * Configure the set of system services that are required for this test to operate.
         *
         * For example, passing {@code android.hardware.SerialManager.class} as an argument will
         * ensure that the underlying service is created, initialized, and ready to use for the
         * duration of the test. The {@code SerialManager} instance can be obtained via
         * {@code RavenwoodRule.getContext()} and {@code Context.getSystemService()}, and
         * {@code SerialManagerInternal} can be obtained via {@code LocalServices.getService()}.
         */
        public Builder setServicesRequired(Class<?>... services) {
            mRule.mServicesRequired.clear();
            for (Class<?> service : services) {
                mRule.mServicesRequired.add(service);
            }
            return this;
        }

        public RavenwoodRule build() {
            return mRule;
        }
    }

    /**
     * @deprecated replaced by {@link #isOnRavenwood()}
     */
    @Deprecated
    public static boolean isUnderRavenwood() {
        return IS_ON_RAVENWOOD;
    }

    /**
     * Return if the current process is running on a Ravenwood test environment.
     */
    public static boolean isOnRavenwood() {
        return IS_ON_RAVENWOOD;
    }

    /**
     * Return a {@code Context} available for usage during the currently running test case.
     *
     * Each test should obtain needed information or references via this method;
     * references must not be stored beyond the scope of a test case.
     */
    public Context getContext() {
        return Objects.requireNonNull(mContext,
                "Context is only available during @Test execution");
    }

    /**
     * Return a {@code Instrumentation} available for usage during the currently running test case.
     *
     * Each test should obtain needed information or references via this method;
     * references must not be stored beyond the scope of a test case.
     */
    public Instrumentation getInstrumentation() {
        return Objects.requireNonNull(mInstrumentation,
                "Instrumentation is only available during @Test execution");
    }

    static boolean shouldEnableOnDevice(Description description) {
        if (description.isTest()) {
            if (description.getAnnotation(DisabledOnNonRavenwood.class) != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determine if the given {@link Description} should be enabled when running on the
     * Ravenwood test environment.
     *
     * A more specific method-level annotation always takes precedence over any class-level
     * annotation, and an {@link EnabledOnRavenwood} annotation always takes precedence over
     * an {@link DisabledOnRavenwood} annotation.
     */
    static boolean shouldEnableOnRavenwood(Description description) {
        // First, consult any method-level annotations
        if (description.isTest()) {
            // Stopgap for http://g/ravenwood/EPAD-N5ntxM
            if (description.getMethodName().endsWith("$noRavenwood")) {
                return false;
            }
            if (description.getAnnotation(EnabledOnRavenwood.class) != null) {
                return true;
            }
            if (description.getAnnotation(DisabledOnRavenwood.class) != null) {
                return false;
            }
            if (description.getAnnotation(IgnoreUnderRavenwood.class) != null) {
                return false;
            }
        }

        // Otherwise, consult any class-level annotations
        if (description.getTestClass().getAnnotation(EnabledOnRavenwood.class) != null) {
            return true;
        }
        if (description.getTestClass().getAnnotation(DisabledOnRavenwood.class) != null) {
            return false;
        }
        if (description.getTestClass().getAnnotation(IgnoreUnderRavenwood.class) != null) {
            return false;
        }

        // When no annotations have been requested, assume test should be included
        return true;
    }

    static boolean shouldStillIgnoreInProbeIgnoreMode(Description description) {
        if (!ENABLE_REALLY_DISABLE_PATTERN) {
            return false;
        }

        final var fullname = description.getTestClass().getName()
                + (description.isTest() ? "#" + description.getMethodName() : "");

        if (REALLY_DISABLE_PATTERN.matcher(fullname).find()) {
            System.out.println("Still ignoring " + fullname);
            return true;
        }
        return false;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        // No special treatment when running outside Ravenwood; run tests as-is
        if (!IS_ON_RAVENWOOD) {
            Assume.assumeTrue(shouldEnableOnDevice(description));
            return base;
        }

        if (ENABLE_PROBE_IGNORED) {
            return applyProbeIgnored(base, description);
        } else {
            return applyDefault(base, description);
        }
    }

    private void commonPrologue(Statement base, Description description) {
        RavenwoodRuleImpl.logTestRunner("started", description);
        RavenwoodRuleImpl.validate(base, description, ENABLE_OPTIONAL_VALIDATION);
        RavenwoodRuleImpl.init(RavenwoodRule.this);
    }

    /**
     * Run the given {@link Statement} with no special treatment.
     */
    private Statement applyDefault(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Assume.assumeTrue(shouldEnableOnRavenwood(description));

                commonPrologue(base, description);
                try {
                    base.evaluate();
                    RavenwoodRuleImpl.logTestRunner("finished", description);
                } catch (Throwable t) {
                    RavenwoodRuleImpl.logTestRunner("failed", description);
                    throw t;
                } finally {
                    RavenwoodRuleImpl.reset(RavenwoodRule.this);
                }
            }
        };
    }

    /**
     * Run the given {@link Statement} with probing enabled. All tests will be unconditionally
     * run on Ravenwood to detect cases where a test is able to pass despite being marked as
     * {@code IgnoreUnderRavenwood}.
     */
    private Statement applyProbeIgnored(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Assume.assumeFalse(shouldStillIgnoreInProbeIgnoreMode(description));

                commonPrologue(base, description);
                try {
                    base.evaluate();
                } catch (Throwable t) {
                    // If the test isn't included, eat the exception and report the
                    // assumption failure that test authors expect; otherwise throw
                    Assume.assumeTrue(shouldEnableOnRavenwood(description));
                    throw t;
                } finally {
                    RavenwoodRuleImpl.logTestRunner("finished", description);
                    RavenwoodRuleImpl.reset(RavenwoodRule.this);
                }

                if (!shouldEnableOnRavenwood(description)) {
                    fail("Test wasn't included under Ravenwood, but it actually "
                            + "passed under Ravenwood; consider updating annotations");
                }
            }
        };
    }

    /**
     * Do not use it outside ravenwood core classes.
     */
    public boolean _ravenwood_private$isOptionalValidationEnabled() {
        return ENABLE_OPTIONAL_VALIDATION;
    }
}
