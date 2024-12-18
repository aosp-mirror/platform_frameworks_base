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

package android.app.admin;

import static android.app.admin.PackagePolicy.PACKAGE_POLICY_ALLOWLIST;
import static android.app.admin.PackagePolicy.PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM;
import static android.app.admin.PackagePolicy.PACKAGE_POLICY_BLOCKLIST;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class PackagePolicyTest {

    private static final String TEST_PACKAGE_NAME = "com.example";

    private static final String TEST_PACKAGE_NAME_2 = "com.example.2";

    private static final String TEST_SYSTEM_PACKAGE_NAME = "com.system";


    @Test
    public void testParceling() {
        final int policyType = PACKAGE_POLICY_BLOCKLIST;
        final Set<String> packageNames = new ArraySet<>();
        packageNames.add(TEST_PACKAGE_NAME);

        final Parcel parcel = Parcel.obtain();
        PackagePolicy packagePolicy = new PackagePolicy(policyType, packageNames);
        try {
            packagePolicy.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            packagePolicy = PackagePolicy.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }

        assertEquals(policyType, packagePolicy.getPolicyType());
        assertNotNull(packagePolicy.getPackageNames());
        assertEquals(1, packagePolicy.getPackageNames().size());
        assertTrue(packagePolicy.getPackageNames().contains(TEST_PACKAGE_NAME));
    }

    @Test
    public void testEmptyBlocklistCreation() {
        PackagePolicy policy = new PackagePolicy(PACKAGE_POLICY_BLOCKLIST);
        assertEquals(PACKAGE_POLICY_BLOCKLIST, policy.getPolicyType());
        assertNotNull(policy.getPackageNames());
        assertTrue(policy.getPackageNames().isEmpty());
    }

    @Test
    public void testEmptyAllowlistCreation() {
        PackagePolicy policy = new PackagePolicy(PACKAGE_POLICY_ALLOWLIST);
        assertEquals(PACKAGE_POLICY_ALLOWLIST, policy.getPolicyType());
        assertNotNull(policy.getPackageNames());
        assertTrue(policy.getPackageNames().isEmpty());
    }

    @Test
    public void testEmptyAllowlistAndSystemCreation() {
        PackagePolicy policy = new PackagePolicy(PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM);
        assertEquals(PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM, policy.getPolicyType());
        assertNotNull(policy.getPackageNames());
        assertTrue(policy.getPackageNames().isEmpty());
    }

    @Test
    public void testSuppliedBlocklistCreation() {
        final Set<String> packageNames = new ArraySet<>();
        packageNames.add(TEST_PACKAGE_NAME);
        PackagePolicy policy = new PackagePolicy(PACKAGE_POLICY_BLOCKLIST, packageNames);
        assertEquals(PACKAGE_POLICY_BLOCKLIST, policy.getPolicyType());
        assertNotNull(policy.getPackageNames());
        assertEquals(1, policy.getPackageNames().size());
        assertTrue(policy.getPackageNames().contains(TEST_PACKAGE_NAME));
    }

    @Test
    public void testSuppliedAllowlistCreation() {
        final Set<String> packageNames = new ArraySet<>();
        packageNames.add(TEST_PACKAGE_NAME);
        PackagePolicy policy = new PackagePolicy(PACKAGE_POLICY_ALLOWLIST, packageNames);
        assertEquals(PACKAGE_POLICY_ALLOWLIST, policy.getPolicyType());
        assertNotNull(policy.getPackageNames());
        assertEquals(1, policy.getPackageNames().size());
        assertTrue(policy.getPackageNames().contains(TEST_PACKAGE_NAME));
    }

    @Test
    public void testSuppliedAllowlistAndSystemCreation() {
        final Set<String> packageNames = new ArraySet<>();
        packageNames.add(TEST_PACKAGE_NAME);
        PackagePolicy policy = new PackagePolicy(PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM, packageNames);
        assertEquals(PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM, policy.getPolicyType());
        assertNotNull(policy.getPackageNames());
        assertEquals(1, policy.getPackageNames().size());
        assertTrue(policy.getPackageNames().contains(TEST_PACKAGE_NAME));
    }

    @Test
    public void testBlocklist_isPackageAllowed() {
        final Set<String> packageNames = new ArraySet<>();
        packageNames.add(TEST_PACKAGE_NAME);
        final Set<String> systemPackages = new ArraySet<>();
        systemPackages.add(TEST_SYSTEM_PACKAGE_NAME);

        PackagePolicy policy = new PackagePolicy(PACKAGE_POLICY_BLOCKLIST, packageNames);

        assertFalse(policy.isPackageAllowed(TEST_PACKAGE_NAME, Collections.emptySet()));
        assertFalse(policy.isPackageAllowed(TEST_PACKAGE_NAME, systemPackages));
        assertTrue(policy.isPackageAllowed(TEST_PACKAGE_NAME_2, Collections.emptySet()));
        assertTrue(policy.isPackageAllowed(TEST_PACKAGE_NAME_2, systemPackages));
        assertTrue(policy.isPackageAllowed(TEST_SYSTEM_PACKAGE_NAME, Collections.emptySet()));
        assertTrue(policy.isPackageAllowed(TEST_SYSTEM_PACKAGE_NAME, systemPackages));
    }

    @Test
    public void testAllowlist_isPackageAllowed() {
        final Set<String> packageNames = new ArraySet<>();
        packageNames.add(TEST_PACKAGE_NAME);
        final Set<String> systemPackages = new ArraySet<>();
        systemPackages.add(TEST_SYSTEM_PACKAGE_NAME);
        PackagePolicy policy = new PackagePolicy(PACKAGE_POLICY_ALLOWLIST, packageNames);

        assertTrue(policy.isPackageAllowed(TEST_PACKAGE_NAME, Collections.emptySet()));
        assertTrue(policy.isPackageAllowed(TEST_PACKAGE_NAME, systemPackages));
        assertFalse(policy.isPackageAllowed(TEST_PACKAGE_NAME_2, Collections.emptySet()));
        assertFalse(policy.isPackageAllowed(TEST_PACKAGE_NAME_2, systemPackages));
        assertFalse(policy.isPackageAllowed(TEST_SYSTEM_PACKAGE_NAME, Collections.emptySet()));
        assertFalse(policy.isPackageAllowed(TEST_SYSTEM_PACKAGE_NAME, systemPackages));
    }

    @Test
    public void testAllowlistAndSystem_isPackageAllowed() {
        final Set<String> packageNames = new ArraySet<>();
        packageNames.add(TEST_PACKAGE_NAME);
        final Set<String> systemPackages = new ArraySet<>();
        systemPackages.add(TEST_SYSTEM_PACKAGE_NAME);
        PackagePolicy policy = new PackagePolicy(PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM, packageNames);

        assertTrue(policy.isPackageAllowed(TEST_PACKAGE_NAME, Collections.emptySet()));
        assertTrue(policy.isPackageAllowed(TEST_PACKAGE_NAME, systemPackages));
        assertFalse(policy.isPackageAllowed(TEST_PACKAGE_NAME_2, Collections.emptySet()));
        assertFalse(policy.isPackageAllowed(TEST_PACKAGE_NAME_2, systemPackages));
        assertFalse(policy.isPackageAllowed(TEST_SYSTEM_PACKAGE_NAME, Collections.emptySet()));
        assertTrue(policy.isPackageAllowed(TEST_SYSTEM_PACKAGE_NAME, systemPackages));
    }

}
