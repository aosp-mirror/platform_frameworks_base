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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.annotations.EnabledOnRavenwood;
import android.platform.test.annotations.IgnoreUnderRavenwood;

import org.junit.runner.Description;

/**
 * Calculates which tests need to be executed on Ravenwood.
 */
public class RavenwoodEnablementChecker {
    private static final String TAG = "RavenwoodDisablementChecker";

    private RavenwoodEnablementChecker() {
    }

    /**
     * Determine if the given {@link Description} should be enabled when running on the
     * Ravenwood test environment.
     *
     * A more specific method-level annotation always takes precedence over any class-level
     * annotation, and an {@link EnabledOnRavenwood} annotation always takes precedence over
     * an {@link DisabledOnRavenwood} annotation.
     */
    public static boolean shouldEnableOnRavenwood(Description description,
            boolean takeIntoAccountRunDisabledTestsFlag) {
        // First, consult any method-level annotations
        if (description.isTest()) {
            Boolean result = null;

            // Stopgap for http://g/ravenwood/EPAD-N5ntxM
            if (description.getMethodName().endsWith("$noRavenwood")) {
                result = false;
            } else if (description.getAnnotation(EnabledOnRavenwood.class) != null) {
                result = true;
            } else if (description.getAnnotation(DisabledOnRavenwood.class) != null) {
                result = false;
            } else if (description.getAnnotation(IgnoreUnderRavenwood.class) != null) {
                result = false;
            }
            if (result != null) {
                if (takeIntoAccountRunDisabledTestsFlag
                        && RavenwoodRule.private$ravenwood().isRunningDisabledTests()) {
                    result = !shouldStillIgnoreInProbeIgnoreMode(
                            description.getTestClass(), description.getMethodName());
                }
            }
            if (result != null) {
                return result;
            }
        }

        // Otherwise, consult any class-level annotations
        return shouldRunClassOnRavenwood(description.getTestClass(),
                takeIntoAccountRunDisabledTestsFlag);
    }

    public static boolean shouldRunClassOnRavenwood(@NonNull Class<?> testClass,
            boolean takeIntoAccountRunDisabledTestsFlag) {
        boolean result = true;
        if (testClass.getAnnotation(EnabledOnRavenwood.class) != null) {
            result = true;
        } else if (testClass.getAnnotation(DisabledOnRavenwood.class) != null) {
            result = false;
        } else if (testClass.getAnnotation(IgnoreUnderRavenwood.class) != null) {
            result = false;
        }
        if (!result) {
            if (takeIntoAccountRunDisabledTestsFlag
                    && RavenwoodRule.private$ravenwood().isRunningDisabledTests()) {
                result = !shouldStillIgnoreInProbeIgnoreMode(testClass, null);
            }
        }
        return result;
    }

    /**
     * Check if a test should _still_ disabled even if {@code RUN_DISABLED_TESTS}
     * is true, using {@code REALLY_DISABLED_PATTERN}.
     *
     * This only works on tests, not on classes.
     */
    static boolean shouldStillIgnoreInProbeIgnoreMode(
            @NonNull Class<?> testClass, @Nullable String methodName) {
        if (RavenwoodRule.private$ravenwood().getReallyDisabledPattern().pattern().isEmpty()) {
            return false;
        }

        final var fullname = testClass.getName() + (methodName != null ? "#" + methodName : "");

        System.out.println("XXX=" + fullname);

        if (RavenwoodRule.private$ravenwood().getReallyDisabledPattern().matcher(fullname).find()) {
            System.out.println("Still ignoring " + fullname);
            return true;
        }
        return false;
    }
}
