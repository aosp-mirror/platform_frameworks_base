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
package android.platform.test.ravenwood;

import static com.android.ravenwood.common.RavenwoodCommonUtils.ensureIsPublicMember;

import static org.junit.Assert.fail;

import android.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;
import com.android.ravenwood.common.RavenwoodRuntimeException;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.WeakHashMap;

/**
 * Used to store various states associated with the current test runner that's inly needed
 * in junit-impl.
 *
 * We don't want to put it in junit-src to avoid having to recompile all the downstream
 * dependencies after changing this class.
 *
 * All members must be called from the runner's main thread.
 */
public final class RavenwoodRunnerState {
    private static final String TAG = "RavenwoodRunnerState";

    @GuardedBy("sStates")
    private static final WeakHashMap<RavenwoodAwareTestRunner, RavenwoodRunnerState> sStates =
            new WeakHashMap<>();

    private final RavenwoodAwareTestRunner mRunner;

    /**
     * Ctor.
     */
    public RavenwoodRunnerState(RavenwoodAwareTestRunner runner) {
        mRunner = runner;
    }

    private Description mClassDescription;
    private Description mMethodDescription;

    private RavenwoodConfig mCurrentConfig;
    private RavenwoodRule mCurrentRule;
    private boolean mHasRavenwoodRule;

    public Description getClassDescription() {
        return mClassDescription;
    }

    public void enterTestClass(Description classDescription) throws IOException {
        mClassDescription = classDescription;

        mHasRavenwoodRule = hasRavenwoodRule(mRunner.getTestClass().getJavaClass());
        mCurrentConfig = extractConfiguration(mRunner.getTestClass().getJavaClass());

        if (mCurrentConfig != null) {
            RavenwoodRuntimeEnvironmentController.init(mCurrentConfig);
        }
    }

    public void exitTestClass() {
        if (mCurrentConfig != null) {
            try {
                RavenwoodRuntimeEnvironmentController.reset();
            } finally {
                mClassDescription = null;
            }
        }
    }

    public void enterTestMethod(Description description) {
        mMethodDescription = description;
    }

    public void exitTestMethod() {
        mMethodDescription = null;
    }

    public void enterRavenwoodRule(RavenwoodRule rule) throws IOException {
        if (!mHasRavenwoodRule) {
            fail("If you have a RavenwoodRule in your test, make sure the field type is"
                    + " RavenwoodRule so Ravenwood can detect it.");
        }
        if (mCurrentConfig != null) {
            fail("RavenwoodConfig and RavenwoodRule cannot be used in the same class."
                    + " Suggest migrating to RavenwoodConfig.");
        }
        if (mCurrentRule != null) {
            fail("Multiple nesting RavenwoodRule's are detected in the same class,"
                    + " which is not supported.");
        }
        mCurrentRule = rule;
        RavenwoodRuntimeEnvironmentController.init(rule.getConfiguration());
    }

    public void exitRavenwoodRule(RavenwoodRule rule) {
        if (mCurrentRule != rule) {
            return; // This happens if the rule did _not_ take effect somehow.
        }

        try {
            RavenwoodRuntimeEnvironmentController.reset();
        } finally {
            mCurrentRule = null;
        }
    }

    /**
     * @return a configuration from a test class, if any.
     */
    @Nullable
    private RavenwoodConfig extractConfiguration(Class<?> testClass) {
        var field = findConfigurationField(testClass);
        if (field == null) {
            if (mHasRavenwoodRule) {
                // Should be handled by RavenwoodRule
                return null;
            }

            // If no RavenwoodConfig and no RavenwoodRule, return a default config
            return new RavenwoodConfig.Builder().build();
        }
        if (mHasRavenwoodRule) {
            fail("RavenwoodConfig and RavenwoodRule cannot be used in the same class."
                    + " Suggest migrating to RavenwoodConfig.");
        }

        try {
            return (RavenwoodConfig) field.get(null);
        } catch (IllegalAccessException e) {
            throw new RavenwoodRuntimeException("Failed to fetch from the configuration field", e);
        }
    }

    /**
     * @return true if the current target class (or its super classes) has any @Rule / @ClassRule
     * fields of type RavenwoodRule.
     *
     * Note, this check won't detect cases where a Rule is of type
     * {@link TestRule} and still be a {@link RavenwoodRule}. But that'll be detected at runtime
     * as a failure, in {@link #enterRavenwoodRule}.
     */
    private static boolean hasRavenwoodRule(Class<?> testClass) {
        for (var field : testClass.getDeclaredFields()) {
            if (!field.isAnnotationPresent(Rule.class)
                    && !field.isAnnotationPresent(ClassRule.class)) {
                continue;
            }
            if (field.getType().equals(RavenwoodRule.class)) {
                return true;
            }
        }
        // JUnit supports rules as methods, so we need to check them too.
        for (var method : testClass.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Rule.class)
                    && !method.isAnnotationPresent(ClassRule.class)) {
                continue;
            }
            if (method.getReturnType().equals(RavenwoodRule.class)) {
                return true;
            }
        }
        // Look into the super class.
        if (!testClass.getSuperclass().equals(Object.class)) {
            return hasRavenwoodRule(testClass.getSuperclass());
        }
        return false;
    }

    /**
     * Find and return a field with @RavenwoodConfig.Config, which must be of type
     * RavenwoodConfig.
     */
    @Nullable
    private static Field findConfigurationField(Class<?> testClass) {
        Field foundField = null;

        for (var field : testClass.getDeclaredFields()) {
            final var hasAnot = field.isAnnotationPresent(RavenwoodConfig.Config.class);
            final var isType = field.getType().equals(RavenwoodConfig.class);

            if (hasAnot) {
                if (isType) {
                    // Good, use this field.
                    if (foundField != null) {
                        fail(String.format(
                                "Class %s has multiple fields with %s",
                                testClass.getCanonicalName(),
                                "@RavenwoodConfig.Config"));
                    }
                    // Make sure it's static public
                    ensureIsPublicMember(field, true);

                    foundField = field;
                } else {
                    fail(String.format(
                            "Field %s.%s has %s but type is not %s",
                            testClass.getCanonicalName(),
                            field.getName(),
                            "@RavenwoodConfig.Config",
                            "RavenwoodConfig"));
                    return null; // unreachable
                }
            } else {
                if (isType) {
                    fail(String.format(
                            "Field %s.%s does not have %s but type is %s",
                            testClass.getCanonicalName(),
                            field.getName(),
                            "@RavenwoodConfig.Config",
                            "RavenwoodConfig"));
                    return null; // unreachable
                } else {
                    // Unrelated field, ignore.
                    continue;
                }
            }
        }
        if (foundField != null) {
            return foundField;
        }
        if (!testClass.getSuperclass().equals(Object.class)) {
            return findConfigurationField(testClass.getSuperclass());
        }
        return null;
    }
}
