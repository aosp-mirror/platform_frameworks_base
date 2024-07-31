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

package android.content.pm;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.platform.test.annotations.AppModeFull;
import android.text.TextUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@AppModeFull
public class ModuleInfoTest {

    private static final String APEX_MODULE_NAME = "apexModuleName";
    private static final String APK_IN_APEX_PACKAGE_NAME = "apkInApexPackageName";
    private static final String MODULE_PACKAGE_NAME = "modulePackageName";
    private static final String MODULE_NAME = "moduleName";

    @Test
    public void testSimple() {
        ModuleInfo info = new ModuleInfo();
        assertThat(info.toString()).isNotNull();
    }

    @Test
    public void testDefaultCopy() {
        ModuleInfo oldInfo = new ModuleInfo();
        ModuleInfo newInfo = new ModuleInfo(oldInfo);
        assertThat(newInfo).isEqualTo(oldInfo);
    }

    @Test
    public void testCopy() {
        boolean isHidden = false;
        ModuleInfo info = new ModuleInfo();
        info.setHidden(isHidden);
        info.setApexModuleName(APEX_MODULE_NAME);
        info.setPackageName(MODULE_PACKAGE_NAME);
        info.setName(MODULE_NAME);
        info.setApkInApexPackageNames(List.of(APK_IN_APEX_PACKAGE_NAME));

        ModuleInfo newInfo = new ModuleInfo(info);
        assertThat(newInfo).isEqualTo(info);
    }

    @Test
    public void testGetApkInApexPackageNamesReturnEmptyListInDefault() {
        ModuleInfo info = new ModuleInfo();
        assertThat(info.getApkInApexPackageNames()).isNotNull();
        assertThat(info.getApkInApexPackageNames()).isEmpty();
    }

    @Test
    public void testModuleInfoParcelizeDeparcelize() {
        boolean isHidden = false;
        ModuleInfo info = new ModuleInfo();
        info.setHidden(isHidden);
        info.setApexModuleName(APEX_MODULE_NAME);
        info.setPackageName(MODULE_PACKAGE_NAME);
        info.setName(MODULE_NAME);
        info.setApkInApexPackageNames(List.of(APK_IN_APEX_PACKAGE_NAME));

        final Parcel p = Parcel.obtain();
        info.writeToParcel(p, 0);
        p.setDataPosition(0);

        final ModuleInfo targetInfo = ModuleInfo.CREATOR.createFromParcel(p);
        p.recycle();

        assertThat(info.isHidden()).isEqualTo(targetInfo.isHidden());
        assertThat(info.getApexModuleName()).isEqualTo(targetInfo.getApexModuleName());
        assertThat(info.getPackageName()).isEqualTo(targetInfo.getPackageName());
        assertThat(TextUtils.equals(info.getName(), targetInfo.getName())).isTrue();
        assertThat(info.getApkInApexPackageNames().toArray()).isEqualTo(
                targetInfo.getApkInApexPackageNames().toArray());
    }
}
