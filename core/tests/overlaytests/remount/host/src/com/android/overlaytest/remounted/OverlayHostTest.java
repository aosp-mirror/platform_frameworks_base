/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Rule;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OverlayHostTest extends BaseHostJUnit4Test {
    private static final long TIME_OUT_MS = 30000;
    private static final String RES_INSTRUMENTATION_ARG = "res";
    private static final String OVERLAY_INSTRUMENTATION_ARG = "overlays";
    private static final String RESOURCES_TYPE_SUFFIX = "_type";
    private static final String RESOURCES_DATA_SUFFIX = "_data";

    public final TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    public final SystemPreparer mPreparer = new SystemPreparer(mTemporaryFolder, this::getDevice);

    @Rule
    public final RuleChain ruleChain = RuleChain.outerRule(mTemporaryFolder).around(mPreparer);
    private Map<String, String> mLastResults;

    /**
     * Retrieves the values of the resources in the test package. The test package must use the
     * {@link com.android.overlaytest.remounted.target.ResourceRetrievalRunner} instrumentation.
     **/
    void retrieveResource(String testPackageName, List<String> requiredOverlayPaths,
            String... resourceNames) throws DeviceNotAvailableException {
        final HashMap<String, String> args = new HashMap<>();
        if (!requiredOverlayPaths.isEmpty()) {
            // Enclose the require overlay paths in quotes so the arguments will be string arguments
            // rather than file arguments.
            args.put(OVERLAY_INSTRUMENTATION_ARG,
                    String.format("\"%s\"", String.join(" ", requiredOverlayPaths)));
        }

        if (resourceNames.length == 0) {
            throw new IllegalArgumentException("Must specify at least one resource to retrieve.");
        }

        // Pass the names of the resources to retrieve into the test as one string.
        args.put(RES_INSTRUMENTATION_ARG,
                String.format("\"%s\"", String.join(" ", resourceNames)));

        runDeviceTests(getDevice(), null, testPackageName, null, null, null, TIME_OUT_MS,
                TIME_OUT_MS, TIME_OUT_MS, false, false, args);

        // Retrieve the results of the most recently run test.
        mLastResults = (getLastDeviceRunResults().getRunMetrics() == mLastResults) ? null :
                getLastDeviceRunResults().getRunMetrics();
    }

    /** Returns the base resource directories of the specified packages. */
    List<String> getPackagePaths(String... packageNames)
            throws DeviceNotAvailableException {
        final ArrayList<String> paths = new ArrayList<>();
        for (String packageName : packageNames) {
            // Use the package manager shell command to find the path of the package.
            final String result = getDevice().executeShellCommand(
                    String.format("pm dump %s | grep \"resourcePath=\"", packageName));
            assertNotNull("Failed to find path for package " + packageName, result);
            int splitIndex = result.indexOf('=');
            assertTrue(splitIndex >= 0);
            paths.add(result.substring(splitIndex + 1).trim());
        }
        return paths;
    }

    /** Builds the full name of a resource in the form package:type/entry. */
    String resourceName(String pkg, String type, String entry) {
        return String.format("%s:%s/%s", pkg, type, entry);
    }

    /**
     * Asserts that the type and data of a a previously retrieved is the same as expected.
     * @param resourceName the full name of the resource in the form package:type/entry
     * @param type the expected {@link android.util.TypedValue} type of the resource
     * @param data the expected value of the resource when coerced to a string using
     *             {@link android.util.TypedValue#coerceToString()}
     **/
    void assertResource(String resourceName, int type, String data) {
        assertNotNull("Failed to get test results", mLastResults);
        assertNotEquals("No resource values were retrieved", mLastResults.size(), 0);
        assertEquals("" + type, mLastResults.get(resourceName + RESOURCES_TYPE_SUFFIX));
        assertEquals("" + data, mLastResults.get(resourceName + RESOURCES_DATA_SUFFIX));
    }
}
