/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.content.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.pm.PackageParser.Package;
import android.os.Build;
import android.support.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collections;

@SmallTest
@RunWith(JUnit4.class)
public class PackageBackwardCompatibilityTest {

    private static final String ORG_APACHE_HTTP_LEGACY = "org.apache.http.legacy";

    private static final String ANDROID_TEST_RUNNER = "android.test.runner";

    private static final String ANDROID_TEST_MOCK = "android.test.mock";

    private Package mPackage;

    private static ArrayList<String> arrayList(String... strings) {
        ArrayList<String> list = new ArrayList<>();
        Collections.addAll(list, strings);
        return list;
    }

    @Before
    public void setUp() {
        mPackage = new Package("org.package.name");
        mPackage.applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    @Test
    public void null_usesLibraries() {
        PackageBackwardCompatibility.modifySharedLibraries(mPackage);
        assertNull("usesLibraries not updated correctly", mPackage.usesLibraries);
    }

    @Test
    public void null_usesOptionalLibraries() {
        PackageBackwardCompatibility.modifySharedLibraries(mPackage);
        assertNull("usesOptionalLibraries not updated correctly", mPackage.usesOptionalLibraries);
    }

    @Test
    public void remove_org_apache_http_legacy_from_usesLibraries() {
        mPackage.usesLibraries = arrayList(ORG_APACHE_HTTP_LEGACY);
        PackageBackwardCompatibility.modifySharedLibraries(mPackage);
        assertNull("usesLibraries not updated correctly", mPackage.usesLibraries);
    }

    @Test
    public void remove_org_apache_http_legacy_from_usesOptionalLibraries() {
        mPackage.usesOptionalLibraries = arrayList(ORG_APACHE_HTTP_LEGACY);
        PackageBackwardCompatibility.modifySharedLibraries(mPackage);
        assertNull("usesOptionalLibraries not updated correctly", mPackage.usesOptionalLibraries);
    }

    @Test
    public void android_test_runner_in_usesLibraries() {
        mPackage.usesLibraries = arrayList(ANDROID_TEST_RUNNER);
        PackageBackwardCompatibility.modifySharedLibraries(mPackage);
        assertEquals("usesLibraries not updated correctly",
                arrayList(ANDROID_TEST_RUNNER, ANDROID_TEST_MOCK),
                mPackage.usesLibraries);
    }

    @Test
    public void android_test_runner_in_usesOptionalLibraries() {
        mPackage.usesOptionalLibraries = arrayList(ANDROID_TEST_RUNNER);
        PackageBackwardCompatibility.modifySharedLibraries(mPackage);
        assertEquals("usesOptionalLibraries not updated correctly",
                arrayList(ANDROID_TEST_RUNNER, ANDROID_TEST_MOCK),
                mPackage.usesOptionalLibraries);
    }

    @Test
    public void android_test_runner_in_usesLibraries_android_test_mock_in_usesOptionalLibraries() {
        mPackage.usesLibraries = arrayList(ANDROID_TEST_RUNNER);
        mPackage.usesOptionalLibraries = arrayList(ANDROID_TEST_MOCK);
        PackageBackwardCompatibility.modifySharedLibraries(mPackage);
        assertEquals("usesLibraries not updated correctly",
                arrayList(ANDROID_TEST_RUNNER),
                mPackage.usesLibraries);
        assertEquals("usesOptionalLibraries not updated correctly",
                arrayList(ANDROID_TEST_MOCK),
                mPackage.usesOptionalLibraries);
    }
}
