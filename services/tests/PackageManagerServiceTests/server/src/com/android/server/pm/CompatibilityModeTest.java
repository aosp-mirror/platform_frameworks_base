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

package com.android.server.pm;

import static android.content.pm.ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS;
import static android.content.pm.ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS;
import static android.content.pm.ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS;
import static android.content.pm.ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;
import static android.content.pm.ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS;
import static android.content.pm.ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.platform.test.annotations.Presubmit;

import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.pkg.PackageStateUnserialized;
import com.android.server.pm.pkg.PackageUserStateImpl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@Presubmit
public class CompatibilityModeTest {

    private boolean mCompatibilityModeEnabled;;
    private PackageImpl mMockAndroidPackage;
    private PackageSetting mMockPackageState;
    private PackageUserStateImpl mMockUserState;

    @Before
    public void setUp() {
        mCompatibilityModeEnabled = ParsingPackageUtils.sCompatibilityModeEnabled;
        mMockAndroidPackage = mock(PackageImpl.class);
        mMockPackageState = mock(PackageSetting.class);
        when(mMockPackageState.getTransientState())
                .thenReturn(new PackageStateUnserialized(mMockPackageState));
        mMockUserState = new PackageUserStateImpl();
        mMockUserState.setInstalled(true);
    }

    @After
    public void tearDown() {
        setGlobalCompatibilityMode(mCompatibilityModeEnabled);
    }

    // The following tests ensure that apps with target SDK of Cupcake always use compat mode.

    @Test
    public void testGlobalCompatModeEnabled_oldApp_supportAllScreens_usesCompatMode() {
        setGlobalCompatibilityMode(true);
        final int flags = (FLAG_SUPPORTS_LARGE_SCREENS | FLAG_SUPPORTS_NORMAL_SCREENS
                | FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS
                | FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.CUPCAKE, flags);
        assertThat(info.usesCompatibilityMode(), is(true));
    }

    @Test
    public void testGlobalCompatModeEnabled_oldApp_supportSomeScreens_usesCompatMode() {
        setGlobalCompatibilityMode(true);
        final int flags = (FLAG_SUPPORTS_LARGE_SCREENS
                | FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS
                | FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.CUPCAKE, flags);
        assertThat(info.usesCompatibilityMode(), is(true));
    }

    @Test
    public void testGlobalCompatModeEnabled_oldApp_supportOnlyOneScreen_usesCompatMode() {
        setGlobalCompatibilityMode(true);
        final int flags = FLAG_SUPPORTS_NORMAL_SCREENS;
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.CUPCAKE, flags);
        assertThat(info.usesCompatibilityMode(), is(true));
    }

    @Test
    public void testGlobalCompatModeEnabled_oldApp_DoesntSupportAllScreens_usesCompatMode() {
        setGlobalCompatibilityMode(true);
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.CUPCAKE, 0 /*flags*/);
        assertThat(info.usesCompatibilityMode(), is(true));
    }

    @Test
    public void testGlobalCompatModeDisabled_oldApp_supportAllScreens_usesCompatMode() {
        setGlobalCompatibilityMode(false);
        final int flags = (FLAG_SUPPORTS_LARGE_SCREENS | FLAG_SUPPORTS_NORMAL_SCREENS
                | FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS
                | FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.CUPCAKE, flags);
        assertThat(info.usesCompatibilityMode(), is(true));
    }

    @Test
    public void testGlobalCompatModeDisabled_oldApp_supportSomeScreens_usesCompatMode() {
        setGlobalCompatibilityMode(false);
        final int flags = (FLAG_SUPPORTS_LARGE_SCREENS
                | FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS
                | FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.CUPCAKE, flags);
        assertThat(info.usesCompatibilityMode(), is(true));
    }

    @Test
    public void testGlobalCompatModeDisabled_oldApp_supportOnlyOneScreen_usesCompatMode() {
        setGlobalCompatibilityMode(false);
        final int flags = FLAG_SUPPORTS_NORMAL_SCREENS;
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.CUPCAKE, flags);
        assertThat(info.usesCompatibilityMode(), is(true));
    }

    @Test
    public void testGlobalCompatModeDisabled_oldApp_doesntSupportAllScreens_usesCompatMode() {
        setGlobalCompatibilityMode(false);
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.CUPCAKE, 0 /*flags*/);
        assertThat(info.usesCompatibilityMode(), is(true));
    }

    // The following tests ensure that apps with newer target SDK use compat mode as expected.

    @Test
    public void testGlobalCompatModeEnabled_newApp_supportAllScreens_doesntUseCompatMode() {
        setGlobalCompatibilityMode(true);
        final int flags = (FLAG_SUPPORTS_LARGE_SCREENS | FLAG_SUPPORTS_NORMAL_SCREENS
                | FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS
                | FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
        final ApplicationInfo info = generateMockApplicationInfo(Build.VERSION_CODES.DONUT, flags);
        assertThat(info.usesCompatibilityMode(), is(false));
    }

    @Test
    public void testGlobalCompatModeEnabled_newApp_supportSomeScreens_doesntUseCompatMode() {
        setGlobalCompatibilityMode(true);
        final int flags = (FLAG_SUPPORTS_LARGE_SCREENS
                | FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS
                | FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
        final ApplicationInfo info = generateMockApplicationInfo(Build.VERSION_CODES.DONUT, flags);
        assertThat(info.usesCompatibilityMode(), is(false));
    }

    @Test
    public void testGlobalCompatModeEnabled_newApp_supportOnlyOneScreen_doesntUseCompatMode() {
        setGlobalCompatibilityMode(true);
        final int flags = FLAG_SUPPORTS_NORMAL_SCREENS;
        final ApplicationInfo info = generateMockApplicationInfo(Build.VERSION_CODES.DONUT, flags);
        assertThat(info.usesCompatibilityMode(), is(false));
    }

    @Test
    public void testGlobalCompatModeEnabled_newApp_doesntSupportAllScreens_usesCompatMode() {
        setGlobalCompatibilityMode(true);
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.DONUT, 0 /*flags*/);
        assertThat(info.usesCompatibilityMode(), is(true));
    }

    @Test
    public void testGlobalCompatModeDisabled_newApp_supportAllScreens_doesntUseCompatMode() {
        setGlobalCompatibilityMode(false);
        final int flags = (FLAG_SUPPORTS_LARGE_SCREENS | FLAG_SUPPORTS_NORMAL_SCREENS
                | FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS
                | FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
        final ApplicationInfo info = generateMockApplicationInfo(Build.VERSION_CODES.DONUT, flags);
        assertThat(info.usesCompatibilityMode(), is(false));
    }

    @Test
    public void testGlobalCompatModeDisabled_newApp_supportSomeScreens_doesntUseCompatMode() {
        setGlobalCompatibilityMode(false);
        final int flags = (FLAG_SUPPORTS_LARGE_SCREENS
                | FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS
                | FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
        final ApplicationInfo info = generateMockApplicationInfo(Build.VERSION_CODES.DONUT, flags);
        assertThat(info.usesCompatibilityMode(), is(false));
    }

    @Test
    public void testGlobalCompatModeDisabled_newApp_supportOnlyOneScreen_doesntUseCompatMode() {
        setGlobalCompatibilityMode(false);
        final int flags = FLAG_SUPPORTS_NORMAL_SCREENS;
        final ApplicationInfo info = generateMockApplicationInfo(Build.VERSION_CODES.DONUT, flags);
        assertThat(info.usesCompatibilityMode(), is(false));
    }

    @Test
    public void testGlobalCompatModeDisabled_newApp_doesntSupportAllScreens_doesntUseCompatMode() {
        setGlobalCompatibilityMode(false);
        final ApplicationInfo info =
                generateMockApplicationInfo(Build.VERSION_CODES.DONUT, 0 /*flags*/);
        assertThat(info.usesCompatibilityMode(), is(false));
    }

    private ApplicationInfo generateMockApplicationInfo(int targetSdkVersion, int flags) {
        final ApplicationInfo info = new ApplicationInfo();
        info.targetSdkVersion = targetSdkVersion;
        info.flags |= flags;
        when(mMockAndroidPackage.toAppInfoWithoutState()).thenReturn(info);
        return PackageInfoUtils.generateApplicationInfo(mMockAndroidPackage,
                0 /*flags*/, mMockUserState, 0 /*userId*/, mMockPackageState);
    }

    private void setGlobalCompatibilityMode(boolean enabled) {
        if (ParsingPackageUtils.sCompatibilityModeEnabled == enabled) {
            return;
        }
        ParsingPackageUtils.setCompatibilityModeEnabled(enabled);
    }
}
