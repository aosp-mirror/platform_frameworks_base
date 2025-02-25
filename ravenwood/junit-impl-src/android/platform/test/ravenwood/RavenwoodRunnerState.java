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

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_VERBOSE_LOGGING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;
import android.util.Pair;

import com.android.ravenwood.RavenwoodRuntimeNative;

import org.junit.runner.Description;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Used to store various states associated with the current test runner that's only needed
 * in junit-impl.
 *
 * We don't want to put it in junit-src to avoid having to recompile all the downstream
 * dependencies after changing this class.
 *
 * All members must be called from the runner's main thread.
 */
public final class RavenwoodRunnerState {
    private static final String TAG = com.android.ravenwood.common.RavenwoodCommonUtils.TAG;
    private static final String RAVENWOOD_RULE_ERROR =
            "RavenwoodRule(s) are not executed in the correct order";

    private static final List<Pair<RavenwoodRule, RavenwoodPropertyState>> sActiveProperties =
            new ArrayList<>();

    private final RavenwoodAwareTestRunner mRunner;

    /**
     * Ctor.
     */
    public RavenwoodRunnerState(RavenwoodAwareTestRunner runner) {
        mRunner = runner;
    }

    private Description mMethodDescription;

    public void enterTestRunner() {
        if (RAVENWOOD_VERBOSE_LOGGING) {
            Log.v(TAG, "enterTestRunner: " + mRunner);
        }
        RavenwoodRuntimeEnvironmentController.initForRunner();
    }

    public void enterTestClass() {
        if (RAVENWOOD_VERBOSE_LOGGING) {
            Log.v(TAG, "enterTestClass: " + mRunner.mTestJavaClass.getName());
        }
    }

    public void exitTestClass() {
        if (RAVENWOOD_VERBOSE_LOGGING) {
            Log.v(TAG, "exitTestClass: " + mRunner.mTestJavaClass.getName());
        }
        assertTrue(RAVENWOOD_RULE_ERROR, sActiveProperties.isEmpty());
        RavenwoodRuntimeEnvironmentController.exitTestClass();
    }

    /** Called when a test method is about to start */
    public void enterTestMethod(Description description) {
        mMethodDescription = description;
        RavenwoodRuntimeEnvironmentController.enterTestMethod(description);
    }

    /** Called when a test method finishes */
    public void exitTestMethod(Description description) {
        RavenwoodRuntimeEnvironmentController.exitTestMethod(description);
        mMethodDescription = null;
    }

    public void enterRavenwoodRule(RavenwoodRule rule) {
        pushTestProperties(rule);
    }

    public void exitRavenwoodRule(RavenwoodRule rule) {
        popTestProperties(rule);
    }

    static class RavenwoodPropertyState {

        final List<Pair<String, String>> mBackup;
        final Set<String> mKeyReadable;
        final Set<String> mKeyWritable;

        RavenwoodPropertyState(RavenwoodTestProperties props) {
            mBackup = props.mValues.keySet().stream()
                    .map(key -> Pair.create(key, RavenwoodRuntimeNative.getSystemProperty(key)))
                    .toList();
            mKeyReadable = Set.copyOf(props.mKeyReadable);
            mKeyWritable = Set.copyOf(props.mKeyWritable);
        }

        boolean isKeyAccessible(String key, boolean write) {
            return write ? mKeyWritable.contains(key) : mKeyReadable.contains(key);
        }

        void restore() {
            mBackup.forEach(pair -> {
                if (pair.second == null) {
                    RavenwoodRuntimeNative.removeSystemProperty(pair.first);
                } else {
                    RavenwoodRuntimeNative.setSystemProperty(pair.first, pair.second);
                }
            });
        }
    }

    private static void pushTestProperties(RavenwoodRule rule) {
        sActiveProperties.add(Pair.create(rule, new RavenwoodPropertyState(rule.mProperties)));
        rule.mProperties.mValues.forEach(RavenwoodRuntimeNative::setSystemProperty);
    }

    private static void popTestProperties(RavenwoodRule rule) {
        var pair = sActiveProperties.removeLast();
        assertNotNull(RAVENWOOD_RULE_ERROR, pair);
        assertEquals(RAVENWOOD_RULE_ERROR, rule, pair.first);
        pair.second.restore();
    }

    @SuppressWarnings("unused")  // Called from native code (ravenwood_sysprop.cpp)
    private static void checkSystemPropertyAccess(String key, boolean write) {
        if (write && RavenwoodSystemProperties.sDefaultValues.containsKey(key)) {
            // The default core values should never be modified
            throw new IllegalArgumentException(
                    "Setting core system property '" + key + "' is not allowed");
        }

        final boolean result = RavenwoodSystemProperties.isKeyAccessible(key, write)
                || sActiveProperties.stream().anyMatch(p -> p.second.isKeyAccessible(key, write));

        if (!result) {
            throw new IllegalArgumentException((write ? "Write" : "Read")
                    + " access to system property '" + key + "' denied via RavenwoodRule");
        }
    }
}
