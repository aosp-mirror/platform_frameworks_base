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

package com.android.server.pm.parsing.library;

import android.os.Build;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;

import com.android.server.SystemConfig;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/**
 * Test for {@link ApexSharedLibraryUpdater}
 */
@Presubmit
@SmallTest
@RunWith(JUnit4.class)
public class ApexSharedLibraryUpdaterTest extends PackageSharedLibraryUpdaterTest {

    private final ArrayMap<String, SystemConfig.SharedLibraryEntry> mSharedLibraries =
            new ArrayMap<>(8);

    @Before
    public void setUp() throws Exception {
        installSharedLibraries();
    }

    private void installSharedLibraries() throws Exception {
        mSharedLibraries.clear();
        insertLibrary("foo", 0, 0);
        insertLibrary("fooBcpSince30", 30, 0);
        insertLibrary("fooBcpBefore30", 0, 30);
        insertLibrary("fooFromFuture", Build.VERSION.SDK_INT + 2, 0);
    }

    private void insertLibrary(String libraryName, int onBootclasspathSince,
            int onBootclasspathBefore) {
        mSharedLibraries.put(libraryName, new SystemConfig.SharedLibraryEntry(
                libraryName,
                "foo.jar",
                new String[0] /* dependencies */,
                onBootclasspathSince,
                onBootclasspathBefore
                )
        );
    }

    @Test
    public void testRegularAppOnRPlus() {
        // platform Q should have changes (tested below)

        // these should have no changes
        checkNoChanges(Build.VERSION_CODES.R);
        checkNoChanges(Build.VERSION_CODES.S);
        checkNoChanges(Build.VERSION_CODES.TIRAMISU);
        checkNoChanges(Build.VERSION_CODES.CUR_DEVELOPMENT);
    }

    private void checkNoChanges(int targetSdkVersion) {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(targetSdkVersion)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(targetSdkVersion)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void testBcpSince30Applied() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .addUsesLibrary("fooBcpSince30")
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .hideAsParsed())
                .hideAsFinal();

        // note: target sdk is not what matters in this logic. It's the system SDK
        // should be removed because on 30+ (R+) it is implicit

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void testBcpSince11kNotAppliedWithoutLibrary() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .hideAsParsed())
                .hideAsFinal();

        // note: target sdk is not what matters in this logic. It's the system SDK
        // nothing should change because the implicit from is only from a future platform release
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void testBcpSince11kNotAppliedWithLibrary() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .addUsesLibrary("fooFromFuture")
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .addUsesLibrary("fooFromFuture")
                .hideAsParsed())
                .hideAsFinal();

        // note: target sdk is not what matters in this logic. It's the system SDK
        // nothing should change because the implicit from is only from a future platform release
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void testBcpBefore30NotApplied() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .hideAsParsed())
                .hideAsFinal();

        // should not be affected because it is still in the BCP in 30 / R
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void testBcpBefore30Applied() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .addUsesLibrary("fooBcpBefore30")
                .hideAsParsed())
                .hideAsFinal();

        // should be present because this was in BCP in 29 / Q
        checkBackwardsCompatibility(before, after);
    }

    /**
     * Test a library that was first removed from the BCP [to a mainline module] and later was
     * moved back to the BCP via a mainline module update. All of this happening before the current
     * SDK.
     */
    @Test
    public void testBcpRemovedThenAddedPast() {
        insertLibrary("fooBcpRemovedThenAdded", 30, 28);

        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.N)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.N)
                .addUsesLibrary("fooBcpBefore30")
                .hideAsParsed())
                .hideAsFinal();

        // the library is now in the BOOTCLASSPATH (for the second time) so it doesn't need to be
        // listed
        checkBackwardsCompatibility(before, after);
    }

    /**
     * Test a library that was first removed from the BCP [to a mainline module] and later was
     * moved back to the BCP via a mainline module update. The first part happening before the
     * current SDK and the second part after.
     */
    @Test
    public void testBcpRemovedThenAddedMiddle_targetQ() {
        insertLibrary("fooBcpRemovedThenAdded", Build.VERSION.SDK_INT + 1, 30);

        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .addUsesLibrary("fooBcpRemovedThenAdded")
                .addUsesLibrary("fooBcpBefore30")
                .hideAsParsed())
                .hideAsFinal();

        // in this example, we are at the point where the library is not in the BOOTCLASSPATH.
        // Because the app targets Q / 29 (when this library was in the BCP) then we need to add it
        checkBackwardsCompatibility(before, after);
    }

    /**
     * Test a library that was first removed from the BCP [to a mainline module] and later was
     * moved back to the BCP via a mainline module update. The first part happening before the
     * current SDK and the second part after.
     */
    @Test
    public void testBcpRemovedThenAddedMiddle_targetR() {
        insertLibrary("fooBcpRemovedThenAdded", Build.VERSION.SDK_INT + 1, 30);

        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .hideAsParsed())
                .hideAsFinal();

        // in this example, we are at the point where the library is not in the BOOTCLASSPATH.
        // Because the app targets R/30 (when this library was removed from the BCP) then we don't
        //need to add it
        checkBackwardsCompatibility(before, after);
    }

    /**
     * Test a library that was first removed from the BCP [to a mainline module] and later was
     * moved back to the BCP via a mainline module update. The first part happening before the
     * current SDK and the second part after.
     */
    @Test
    public void testBcpRemovedThenAddedMiddle_targetR_usingLib() {
        insertLibrary("fooBcpRemovedThenAdded", Build.VERSION.SDK_INT + 1, 30);

        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .addUsesLibrary("fooBcpRemovedThenAdded")
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.R)
                .addUsesLibrary("fooBcpRemovedThenAdded")
                .hideAsParsed())
                .hideAsFinal();

        // in this example, we are at the point where the library is not in the BOOTCLASSPATH.
        // Because the app wants to use the library, it needs to be present
        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after) {
        checkBackwardsCompatibility(before, after,
                () -> new ApexSharedLibraryUpdater(mSharedLibraries));
    }
}
