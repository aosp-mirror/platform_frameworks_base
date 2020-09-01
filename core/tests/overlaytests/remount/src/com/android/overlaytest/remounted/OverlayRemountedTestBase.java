/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.overlaytest.remounted;

import static org.junit.Assert.fail;

import com.android.internal.util.test.SystemPreparer;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

public class OverlayRemountedTestBase extends BaseHostJUnit4Test {
    private static final long ASSERT_RESOURCE_TIMEOUT_MS = 30000;
    static final String TARGET_APK = "OverlayRemountedTest_Target.apk";
    static final String TARGET_PACKAGE = "com.android.overlaytest.remounted.target";
    static final String OVERLAY_APK = "OverlayRemountedTest_Overlay.apk";
    static final String OVERLAY_PACKAGE = "com.android.overlaytest.remounted.target.overlay";

    private final TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    protected final SystemPreparer mPreparer = new SystemPreparer(mTemporaryFolder,
            this::getDevice);

    @Rule
    public final RuleChain ruleChain = RuleChain.outerRule(mTemporaryFolder).around(mPreparer);

    @Before
    public void startBefore() throws DeviceNotAvailableException {
        getDevice().waitForDeviceAvailable();
    }

    /** Builds the full name of a resource in the form package:type/entry. */
    String resourceName(String pkg, String type, String entry) {
        return String.format("%s:%s/%s", pkg, type, entry);
    }

    void assertResource(String resourceName, String expectedValue)
            throws DeviceNotAvailableException {
        String result = null;

        final long endMillis = System.currentTimeMillis() + ASSERT_RESOURCE_TIMEOUT_MS;
        while (System.currentTimeMillis() <= endMillis) {
            result = getDevice().executeShellCommand(
                    String.format("cmd overlay lookup %s %s", TARGET_PACKAGE, resourceName));
            if (result.equals(expectedValue + "\n") ||
                    result.endsWith("-> " + expectedValue + "\n")) {
                return;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {
            }
        }

        fail(String.format("expected: <[%s]> in: <[%s]>", expectedValue, result));
    }
}
