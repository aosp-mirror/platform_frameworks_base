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
 * limitations under the License
 */

package com.android.server.pm.dex;


import static com.android.server.pm.PackageManagerServiceCompilerMapping.getCompilerFilterForReason;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageManagerServiceCompilerMapping;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class DexoptOptionsTests {
    private final static String mPackageName = "test.android.com";
    private final static String mCompilerFilter =
            PackageManagerServiceCompilerMapping.getDefaultCompilerFilter();
    private final static String mSplitName = "split-A.apk";

    @Test
    public void testCreateDexoptOptionsEmpty() {
        DexoptOptions opt = new DexoptOptions(mPackageName, mCompilerFilter, /*flags*/ 0);
        assertEquals(mPackageName, opt.getPackageName());
        assertEquals(mCompilerFilter, opt.getCompilerFilter());
        assertEquals(null, opt.getSplitName());
        assertFalse(opt.isBootComplete());
        assertFalse(opt.isCheckForProfileUpdates());
        assertFalse(opt.isDexoptOnlySecondaryDex());
        assertFalse(opt.isDexoptOnlySharedDex());
        assertFalse(opt.isDowngrade());
        assertFalse(opt.isForce());
        assertFalse(opt.isDexoptIdleBackgroundJob());
        assertFalse(opt.isDexoptInstallWithDexMetadata());
        assertFalse(opt.isDexoptInstallForRestore());
    }

    @Test
    public void testCreateDexoptOptionsFull() {
        int flags =
                DexoptOptions.DEXOPT_FORCE |
                DexoptOptions.DEXOPT_BOOT_COMPLETE |
                DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES |
                DexoptOptions.DEXOPT_ONLY_SECONDARY_DEX |
                DexoptOptions.DEXOPT_ONLY_SHARED_DEX |
                DexoptOptions.DEXOPT_DOWNGRADE  |
                DexoptOptions.DEXOPT_AS_SHARED_LIBRARY |
                DexoptOptions.DEXOPT_IDLE_BACKGROUND_JOB |
                DexoptOptions.DEXOPT_INSTALL_WITH_DEX_METADATA_FILE |
                DexoptOptions.DEXOPT_FOR_RESTORE;

        DexoptOptions opt = new DexoptOptions(mPackageName, mCompilerFilter, flags);
        assertEquals(mPackageName, opt.getPackageName());
        assertEquals(mCompilerFilter, opt.getCompilerFilter());
        assertEquals(null, opt.getSplitName());
        assertTrue(opt.isBootComplete());
        assertTrue(opt.isCheckForProfileUpdates());
        assertTrue(opt.isDexoptOnlySecondaryDex());
        assertTrue(opt.isDexoptOnlySharedDex());
        assertTrue(opt.isDowngrade());
        assertTrue(opt.isForce());
        assertTrue(opt.isDexoptAsSharedLibrary());
        assertTrue(opt.isDexoptIdleBackgroundJob());
        assertTrue(opt.isDexoptInstallWithDexMetadata());
        assertTrue(opt.isDexoptInstallForRestore());
    }

    @Test
    public void testCreateDexoptOptionsReason() {
        int flags =
                DexoptOptions.DEXOPT_FORCE |
                DexoptOptions.DEXOPT_BOOT_COMPLETE |
                DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES;

        int[] reasons = new int[] {
                PackageManagerService.REASON_FIRST_BOOT,
                PackageManagerService.REASON_POST_BOOT,
                PackageManagerService.REASON_INSTALL,
                PackageManagerService.REASON_BACKGROUND_DEXOPT,
                PackageManagerService.REASON_AB_OTA,
                PackageManagerService.REASON_INACTIVE_PACKAGE_DOWNGRADE,};

        for (int reason : reasons) {
            DexoptOptions opt = new DexoptOptions(mPackageName, reason, flags);
            assertEquals(mPackageName, opt.getPackageName());
            assertEquals(getCompilerFilterForReason(reason), opt.getCompilerFilter());
            assertEquals(null, opt.getSplitName());
            assertTrue(opt.isBootComplete());
            assertTrue(opt.isCheckForProfileUpdates());
            assertFalse(opt.isDexoptOnlySecondaryDex());
            assertFalse(opt.isDexoptOnlySharedDex());
            assertFalse(opt.isDowngrade());
            assertTrue(opt.isForce());
            assertFalse(opt.isDexoptAsSharedLibrary());
        }
    }

    @Test
    public void testCreateDexoptOptionsSplit() {
        int flags = DexoptOptions.DEXOPT_FORCE | DexoptOptions.DEXOPT_BOOT_COMPLETE;

        DexoptOptions opt = new DexoptOptions(mPackageName, -1, mCompilerFilter, mSplitName, flags);
        assertEquals(mPackageName, opt.getPackageName());
        assertEquals(mCompilerFilter, opt.getCompilerFilter());
        assertEquals(mSplitName, opt.getSplitName());
        assertTrue(opt.isBootComplete());
        assertFalse(opt.isCheckForProfileUpdates());
        assertFalse(opt.isDexoptOnlySecondaryDex());
        assertFalse(opt.isDexoptOnlySharedDex());
        assertFalse(opt.isDowngrade());
        assertTrue(opt.isForce());
        assertFalse(opt.isDexoptAsSharedLibrary());
    }

    @Test
    public void testCreateDexoptInvalid() {
        boolean gotException = false;
        try {
            int invalidFlags = 999;
            new DexoptOptions(mPackageName, mCompilerFilter, invalidFlags);
        } catch (IllegalArgumentException ignore) {
            gotException = true;
        }

        assertTrue(gotException);
    }
}
