/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.am;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class ActivityManagerUtilsTest {
    @Test
    public void getAndroidIdHash() {
        // getAndroidIdHash() essentially returns a random a value. Just make sure it's
        // non-negative.
        assertThat(ActivityManagerUtils.getAndroidIdHash()).isAtLeast(0);
    }

    @Test
    public void getUnsignedHashCached() {
        assertThat(ActivityManagerUtils.getUnsignedHashCached("x")).isEqualTo(
                ActivityManagerUtils.getUnsignedHashCached("x"));

        assertThat(ActivityManagerUtils.getUnsignedHashCached("x")).isNotEqualTo(
                ActivityManagerUtils.getUnsignedHashCached("y"));
    }

    @Test
    public void shouldSamplePackage_sampleNone() {
        final int numTests = 100000;
        for (int i = 0; i < numTests; i++) {
            assertThat(ActivityManagerUtils.shouldSamplePackageForAtom("" + i, 0))
                    .isFalse();
        }
    }

    @Test
    public void shouldSamplePackage_sampleAll() {
        final int numTests = 100000;

        for (int i = 0; i < numTests; i++) {
            assertThat(ActivityManagerUtils.shouldSamplePackageForAtom("" + i, 1))
                    .isTrue();
        }
    }

    /**
     * Make sure, with the same android ID, an expected rate of the packages are selected.
     */
    @Test
    public void shouldSamplePackage_sampleSome_fixedAndroidId() {
        checkShouldSamplePackage_fixedAndroidId(0.1f);
        checkShouldSamplePackage_fixedAndroidId(0.5f);
        checkShouldSamplePackage_fixedAndroidId(0.9f);
    }

    /**
     * Make sure, the same package is selected on an expected rate of the devices.
     */
    @Test
    public void shouldSamplePackage_sampleSome_fixedPackage() {
        checkShouldSamplePackage_fixedPackage(0.1f);
        checkShouldSamplePackage_fixedPackage(0.5f);
        checkShouldSamplePackage_fixedPackage(0.9f);
    }

    private void checkShouldSamplePackage_fixedPackage(float sampleRate) {
        checkShouldSamplePackage(sampleRate, sampleRate, true, false);
    }

    private void checkShouldSamplePackage_fixedAndroidId(float sampleRate) {
        checkShouldSamplePackage(sampleRate, sampleRate, false, true);
    }

    @Test
    public void testCheckShouldSamplePackage() {
        // Just make sure checkShouldSamplePackage is actually working...
        assertFailure(() -> checkShouldSamplePackage(0.3f, 0.6f, false, true));
        assertFailure(() -> checkShouldSamplePackage(0.6f, 0.3f, true, false));
    }

    private static void assertFailure(Runnable r) {
        boolean failed = false;
        try {
            r.run();
        } catch (AssertionError e) {
            failed = true;
        }
        assertTrue(failed);
    }

    private void checkShouldSamplePackage(float inputSampleRate, float expectedRate,
            boolean fixedPackage, boolean fixedAndroidId) {
        final int numTests = 100000;

        try {
            int numSampled = 0;
            for (int i = 0; i < numTests; i++) {
                final String pkg = fixedPackage ? "fixed-package" : "" + i;
                ActivityManagerUtils.injectAndroidIdForTest(
                        fixedAndroidId ? "fixed-android-id" : "" + i);

                if (ActivityManagerUtils.shouldSamplePackageForAtom(pkg, inputSampleRate)) {
                    numSampled++;
                }
                assertThat(ActivityManagerUtils.getUnsignedHashCached(pkg)).isEqualTo(
                        ActivityManagerUtils.getUnsignedHashCached(pkg));
            }
            final double actualSampleRate = ((double) numSampled) / numTests;

            assertThat(actualSampleRate).isWithin(0.05).of(expectedRate);
        } finally {
            ActivityManagerUtils.injectAndroidIdForTest(null);
        }
    }
}
