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

package com.android.internal.infra;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.ComponentName;
import android.util.ArraySet;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit test for {@link WhitelistHelper}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:com.android.internal.infra.WhitelistHelperTest}
 */

@RunWith(MockitoJUnitRunner.class)
public class WhitelistHelperTest {
    private WhitelistHelper mWhitelistHelper = new WhitelistHelper();

    private String mPackage1 = "com.example";
    private String mPackage2 = "com.example2";

    private ComponentName mComponent1 = new ComponentName(mPackage1, "class1");
    private ComponentName mComponent2 = new ComponentName(mPackage1, "class2");
    private ComponentName mComponentDifferentPkg = new ComponentName(mPackage2, "class3");

    @Test
    public void testSetWhitelist_emptyArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> mWhitelistHelper.setWhitelist(new ArraySet<>(), null));
        assertThrows(IllegalArgumentException.class,
                () -> mWhitelistHelper.setWhitelist(null, new ArraySet<>()));
        assertThrows(IllegalArgumentException.class,
                () -> mWhitelistHelper.setWhitelist(new ArraySet<>(), new ArraySet<>()));
    }

    @Test
    public void testWhitelistHelper_nullArguments() {
        assertThrows(NullPointerException.class,
                () -> mWhitelistHelper.isWhitelisted((String) null));
        assertThrows(NullPointerException.class,
                () -> mWhitelistHelper.isWhitelisted((ComponentName) null));
        assertThrows(NullPointerException.class,
                () -> mWhitelistHelper.getWhitelistedComponents(null));
    }

    @Test
    public void testSetWhitelist_nullPackage() {
        final ArraySet<String> packages = new ArraySet<>();
        packages.add(null);
        mWhitelistHelper.setWhitelist(packages, null);

        assertThat(mWhitelistHelper.isWhitelisted(mPackage1)).isFalse();
        assertThat(mWhitelistHelper.isWhitelisted(mPackage2)).isFalse();

        assertThat(mWhitelistHelper.isWhitelisted(mComponent1)).isFalse();
        assertThat(mWhitelistHelper.isWhitelisted(mComponent2)).isFalse();
        assertThat(mWhitelistHelper.isWhitelisted(mComponentDifferentPkg)).isFalse();
    }

    @Test
    public void testSetWhitelist_nullActivity() {
        final ArraySet<ComponentName> components = new ArraySet<>();
        components.add(null);
        mWhitelistHelper.setWhitelist(null, components);

        assertThat(mWhitelistHelper.isWhitelisted(mPackage1)).isFalse();
        assertThat(mWhitelistHelper.isWhitelisted(mPackage2)).isFalse();

        assertThat(mWhitelistHelper.isWhitelisted(mComponent1)).isFalse();
        assertThat(mWhitelistHelper.isWhitelisted(mComponent2)).isFalse();
        assertThat(mWhitelistHelper.isWhitelisted(mComponentDifferentPkg)).isFalse();
    }

    @Test
    public void testSetWhitelist_replaceWhitelist() {
        final ArraySet<ComponentName> components = new ArraySet<>();
        components.add(mComponent1);
        mWhitelistHelper.setWhitelist(null, components);
        assertThat(mWhitelistHelper.isWhitelisted(mComponent1)).isTrue();
        assertThat(mWhitelistHelper.isWhitelisted(mComponent2)).isFalse();

        final ArraySet<ComponentName> components2 = new ArraySet<>();
        components2.add(mComponent2);
        mWhitelistHelper.setWhitelist(null, components2);
        assertThat(mWhitelistHelper.isWhitelisted(mComponent1)).isFalse();
        assertThat(mWhitelistHelper.isWhitelisted(mComponent2)).isTrue();
    }

    @Test
    public void testIsWhitelisted_packageWhitelisted() {
        final ArraySet<String> packages = new ArraySet<>();
        packages.add(mPackage1);
        mWhitelistHelper.setWhitelist(packages, null);

        assertThat(mWhitelistHelper.isWhitelisted(mPackage1)).isTrue();
        assertThat(mWhitelistHelper.isWhitelisted(mPackage2)).isFalse();

        assertThat(mWhitelistHelper.isWhitelisted(mComponent1)).isTrue();
        assertThat(mWhitelistHelper.isWhitelisted(mComponent2)).isTrue();
        assertThat(mWhitelistHelper.isWhitelisted(mComponentDifferentPkg)).isFalse();
    }

    @Test
    public void testIsWhitelisted_activityWhitelisted() {
        final ArraySet<ComponentName> components = new ArraySet<>();
        components.add(mComponent1);
        mWhitelistHelper.setWhitelist(null, components);

        assertThat(mWhitelistHelper.isWhitelisted(mPackage1)).isFalse();
        assertThat(mWhitelistHelper.isWhitelisted(mPackage2)).isFalse();

        assertThat(mWhitelistHelper.isWhitelisted(mComponent1)).isTrue();
        assertThat(mWhitelistHelper.isWhitelisted(mComponent2)).isFalse();
        assertThat(mWhitelistHelper.isWhitelisted(mComponentDifferentPkg)).isFalse();
    }
}
