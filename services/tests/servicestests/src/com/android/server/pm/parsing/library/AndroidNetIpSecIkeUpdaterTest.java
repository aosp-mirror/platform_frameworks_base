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

import androidx.test.filters.SmallTest;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link AndroidNetIpSecIkeUpdater}
 */
@Presubmit
@SmallTest
@RunWith(JUnit4.class)
public class AndroidNetIpSecIkeUpdaterTest extends PackageSharedLibraryUpdaterTest {

    @Test
    public void otherUsesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary("other")
                .addUsesOptionalLibrary("optional")
                .addUsesLibrary("android.net.ipsec.ike")
                .hideAsParsed());
        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary("other")
                .addUsesOptionalLibrary("optional")
                .hideAsParsed())
                .hideAsFinal();
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary("android.net.ipsec.ike")
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesOptionalLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary("android.net.ipsec.ike")
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after) {
        checkBackwardsCompatibility(before, after, AndroidNetIpSecIkeUpdater::new);
    }
}
