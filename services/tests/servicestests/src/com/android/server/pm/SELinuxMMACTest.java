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

package com.android.server.pm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.os.Build;
import android.platform.test.annotations.Presubmit;

import com.android.server.compat.PlatformCompat;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;


/**
 * {@link SELinuxMMAC} tests.
 */
@RunWith(MockitoJUnitRunner.class)
@Presubmit
public class SELinuxMMACTest {

    private static final String PACKAGE_NAME = "my.package";
    private static final int LATEST_OPT_IN_VERSION = Build.VERSION_CODES.S;
    private static final int R_OPT_IN_VERSION = Build.VERSION_CODES.R;

    @Mock
    PlatformCompat mMockCompatibility;

    @Test
    public void getSeInfoOptInToLatest() {
        AndroidPackage pkg = makePackage(Build.VERSION_CODES.P);
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(pkg.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(pkg, null, mMockCompatibility),
                is("default:targetSdkVersion=" + LATEST_OPT_IN_VERSION));
    }

    @Test
    public void getSeInfoOptInToR() {
        AndroidPackage pkg = makePackage(Build.VERSION_CODES.P);
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_R_CHANGES),
                argThat(argument -> argument.packageName.equals(pkg.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(pkg, null, mMockCompatibility),
                is("default:targetSdkVersion=" + R_OPT_IN_VERSION));
    }

    @Test
    public void getSeInfoNoOptIn() {
        AndroidPackage pkg = makePackage(Build.VERSION_CODES.P);
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(pkg.getPackageName()))))
                .thenReturn(false);
        assertThat(SELinuxMMAC.getSeInfo(pkg, null, mMockCompatibility),
                is("default:targetSdkVersion=28"));
    }

    @Test
    public void getSeInfoNoOptInButAlreadyLatest() {
        AndroidPackage pkg = makePackage(LATEST_OPT_IN_VERSION);
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(pkg.getPackageName()))))
                .thenReturn(false);
        assertThat(SELinuxMMAC.getSeInfo(pkg, null, mMockCompatibility),
                is("default:targetSdkVersion=" + LATEST_OPT_IN_VERSION));
    }

    @Test
    public void getSeInfoNoOptInButAlreadyR() {
        AndroidPackage pkg = makePackage(R_OPT_IN_VERSION);
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_R_CHANGES),
                argThat(argument -> argument.packageName.equals(pkg.getPackageName()))))
                .thenReturn(false);
        assertThat(SELinuxMMAC.getSeInfo(pkg, null, mMockCompatibility),
                is("default:targetSdkVersion=" + R_OPT_IN_VERSION));
    }

    @Test
    public void getSeInfoOptInRButLater() {
        AndroidPackage pkg = makePackage(R_OPT_IN_VERSION + 1);
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_R_CHANGES),
                argThat(argument -> argument.packageName.equals(pkg.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(pkg, null, mMockCompatibility),
                is("default:targetSdkVersion=" + (R_OPT_IN_VERSION + 1)));
    }

    private AndroidPackage makePackage(int targetSdkVersion) {
        return ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(targetSdkVersion)
                .hideAsParsed())
                .hideAsFinal();
    }
}
