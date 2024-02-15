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
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.StringContains.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.os.Build;
import android.platform.test.annotations.Presubmit;

import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.pkg.PackageState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;


/**
 * {@link SELinuxMMAC} tests.
 */
@RunWith(MockitoJUnitRunner.class)
@Presubmit
public class SELinuxMMACTest {

    private static final String PACKAGE_NAME = "my.package";
    private static final int LATEST_OPT_IN_VERSION = Build.VERSION_CODES.CUR_DEVELOPMENT;
    private static final int R_OPT_IN_VERSION = Build.VERSION_CODES.R;

    @Mock
    PlatformCompat mMockCompatibility;

    @Test
    public void getSeInfoOptInToLatest() {
        var packageState = new PackageStateBuilder(Build.VERSION_CODES.P).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                is("default:targetSdkVersion=" + LATEST_OPT_IN_VERSION));
    }

    @Test
    public void getSeInfoOptInToR() {
        var packageState = new PackageStateBuilder(Build.VERSION_CODES.P).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_R_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                is("default:targetSdkVersion=" + R_OPT_IN_VERSION));
    }

    @Test
    public void getSeInfoNoOptIn() {
        var packageState = new PackageStateBuilder(Build.VERSION_CODES.P).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(false);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                is("default:targetSdkVersion=28"));
    }

    @Test
    public void getSeInfoNoOptInButAlreadyLatest() {
        var packageState = new PackageStateBuilder(LATEST_OPT_IN_VERSION).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(false);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                is("default:targetSdkVersion=" + LATEST_OPT_IN_VERSION));
    }

    @Test
    public void getSeInfoTargetingCurDevelopment() {
        var packageState = new PackageStateBuilder(Build.VERSION_CODES.CUR_DEVELOPMENT).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                is("default:targetSdkVersion=" + Build.VERSION_CODES.CUR_DEVELOPMENT));
    }

    @Test
    public void getSeInfoNoOptInButAlreadyR() {
        var packageState = new PackageStateBuilder(R_OPT_IN_VERSION).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_R_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(false);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                is("default:targetSdkVersion=" + R_OPT_IN_VERSION));
    }

    @Test
    public void getSeInfoOptInRButLater() {
        var packageState = new PackageStateBuilder(R_OPT_IN_VERSION + 1).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_R_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                is("default:targetSdkVersion=" + (R_OPT_IN_VERSION + 1)));
    }

    @Test
    public void getSeInfoPreinstalledToSystem() {
        var packageState = new PackageStateBuilder(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .setSystem(true).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                containsString(":partition=system"));
    }


    @Test
    public void getSeInfoPreinstalledToSystemExt() {
        var packageState = new PackageStateBuilder(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .setSystem(true).setSystemExt(true).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                containsString(":partition=system_ext"));
    }


    @Test
    public void getSeInfoPreinstalledToProduct() {
        var packageState = new PackageStateBuilder(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .setSystem(true).setProduct(true).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                containsString(":partition=product"));
    }


    @Test
    public void getSeInfoPreinstalledToVendor() {
        var packageState = new PackageStateBuilder(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .setSystem(true).setVendor(true).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                containsString(":partition=vendor"));
    }


    @Test
    public void getSeInfoNotPreinstalled() {
        var packageState = new PackageStateBuilder(Build.VERSION_CODES.CUR_DEVELOPMENT).build();
        when(mMockCompatibility.isChangeEnabledInternal(eq(SELinuxMMAC.SELINUX_LATEST_CHANGES),
                argThat(argument -> argument.packageName.equals(packageState.getPackageName()))))
                .thenReturn(true);
        assertThat(SELinuxMMAC.getSeInfo(packageState, packageState.getAndroidPackage(), null,
                        mMockCompatibility),
                not(containsString(":partition=")));
    }

    private static class PackageStateBuilder {
        private final int mTargetSdkVersion;
        private boolean mIsSystem = false;
        private boolean mIsSystemExt = false;
        private boolean mIsProduct = false;
        private boolean mIsVendor = false;

        PackageStateBuilder(int targetSdkVersion) {
            mTargetSdkVersion = targetSdkVersion;
        }

        PackageStateBuilder setSystem(boolean isSystem) {
            mIsSystem = isSystem;
            return this;
        }

        PackageStateBuilder setSystemExt(boolean isSystemExt) {
            mIsSystemExt = isSystemExt;
            return this;
        }

        PackageStateBuilder setProduct(boolean isProduct) {
            mIsProduct = isProduct;
            return this;
        }

        PackageStateBuilder setVendor(boolean isVendor) {
            mIsVendor = isVendor;
            return this;
        }

        PackageState build() {
            var packageState = Mockito.mock(PackageState.class);
            when(packageState.getPackageName()).thenReturn(PACKAGE_NAME);
            when(packageState.getAndroidPackage()).thenReturn(
                    ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                            .setTargetSdkVersion(mTargetSdkVersion)
                            .hideAsParsed())
                            .hideAsFinal()
            );
            when(packageState.isSystem()).thenReturn(mIsSystem);
            when(packageState.isSystemExt()).thenReturn(mIsSystemExt);
            when(packageState.isProduct()).thenReturn(mIsProduct);
            when(packageState.isVendor()).thenReturn(mIsVendor);
            return packageState;
        }
    }
}
