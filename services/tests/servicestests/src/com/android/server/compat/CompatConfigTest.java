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

package com.android.server.compat;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.ApplicationInfo;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CompatConfigTest {

    private ApplicationInfo makeAppInfo(String pName, int targetSdkVersion) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pName;
        ai.targetSdkVersion = targetSdkVersion;
        return ai;
    }

    @Test
    public void testUnknownChangeEnabled() {
        CompatConfig pc = new CompatConfig();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 1))).isTrue();
    }

    @Test
    public void testDisabledChangeDisabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, true));
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 1))).isFalse();
    }

    @Test
    public void testTargetSdkChangeDisabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", 2, false));
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isFalse();
    }

    @Test
    public void testTargetSdkChangeEnabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", 2, false));
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 3))).isTrue();
    }

    @Test
    public void testDisabledOverrideTargetSdkChange() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", 2, true));
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 3))).isFalse();
    }

    @Test
    public void testGetDisabledChanges() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, true));
        pc.addChange(new CompatChange(2345L, "OTHER_CHANGE", -1, false));
        assertThat(pc.getDisabledChanges(
                makeAppInfo("com.some.package", 2))).asList().containsExactly(1234L);
    }

    @Test
    public void testGetDisabledChangesSorted() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", 2, true));
        pc.addChange(new CompatChange(123L, "OTHER_CHANGE", 2, true));
        pc.addChange(new CompatChange(12L, "THIRD_CHANGE", 2, true));
        assertThat(pc.getDisabledChanges(
                makeAppInfo("com.some.package", 2))).asList().containsExactly(12L, 123L, 1234L);
    }

    @Test
    public void testPackageOverrideEnabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, true)); // disabled
        pc.addOverride(1234L, "com.some.package", true);
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isTrue();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.other.package", 2))).isFalse();
    }

    @Test
    public void testPackageOverrideDisabled() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, false));
        pc.addOverride(1234L, "com.some.package", false);
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isFalse();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.other.package", 2))).isTrue();
    }

    @Test
    public void testPackageOverrideUnknownPackage() {
        CompatConfig pc = new CompatConfig();
        pc.addOverride(1234L, "com.some.package", false);
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isFalse();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.other.package", 2))).isTrue();
    }

    @Test
    public void testPackageOverrideUnknownChange() {
        CompatConfig pc = new CompatConfig();
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 1))).isTrue();
    }

    @Test
    public void testRemovePackageOverride() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, false));
        pc.addOverride(1234L, "com.some.package", false);
        pc.removeOverride(1234L, "com.some.package");
        assertThat(pc.isChangeEnabled(1234L, makeAppInfo("com.some.package", 2))).isTrue();
    }

    @Test
    public void testLookupChangeId() {
        CompatConfig pc = new CompatConfig();
        pc.addChange(new CompatChange(1234L, "MY_CHANGE", -1, false));
        pc.addChange(new CompatChange(2345L, "ANOTHER_CHANGE", -1, false));
        assertThat(pc.lookupChangeId("MY_CHANGE")).isEqualTo(1234L);
    }

    @Test
    public void testLookupChangeIdNotPresent() {
        CompatConfig pc = new CompatConfig();
        assertThat(pc.lookupChangeId("MY_CHANGE")).isEqualTo(-1L);
    }
}
