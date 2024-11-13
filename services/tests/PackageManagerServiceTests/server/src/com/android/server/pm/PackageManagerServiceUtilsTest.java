/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.testng.Assert.assertThrows;

import android.content.pm.PackageInfoLite;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.pkg.AndroidPackage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.UUID;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageManagerServiceUtilsTest {

    private static final String PACKAGE_NAME = "com.android.app";
    private static final File CODE_PATH =
            InstrumentationRegistry.getInstrumentation().getContext().getFilesDir();

    @Test
    public void testCheckDowngrade_packageSetting_versionCodeSmaller_throwException()
            throws Exception {
        final PackageSetting before = createPackageSetting();
        before.setLongVersionCode(2);
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;

        assertThrows(PackageManagerException.class,
                () -> PackageManagerServiceUtils.checkDowngrade(before, after));
    }

    @Test
    public void testCheckDowngrade_packageSetting_baseRevisionCodeSmaller_throwException()
            throws Exception {
        final PackageSetting before = createPackageSetting();
        before.setLongVersionCode(1);
        before.setBaseRevisionCode(2);
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.baseRevisionCode = 1;

        assertThrows(PackageManagerException.class,
                () -> PackageManagerServiceUtils.checkDowngrade(before, after));
    }

    @Test
    public void testCheckDowngrade_packageSetting_splitArraySizeIsDifferent_throwException()
            throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final String[] splitNames = new String[] { splitOne, splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionOne };
        final int[] afterSplitRevisionCodes = new int[] { revisionOne, revisionTwo };

        final PackageSetting before = createPackageSetting();
        before.setLongVersionCode(1);
        before.setSplitNames(splitNames);
        before.setSplitRevisionCodes(beforeSplitRevisionCodes);
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = splitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        assertThrows(PackageManagerException.class,
                () -> PackageManagerServiceUtils.checkDowngrade(before, after));
    }

    @Test
    public void testCheckDowngrade_packageSetting_splitRevisionCodeSmaller_throwException()
            throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final int revisionThree = 360;
        final String[] splitNames = new String[] { splitOne, splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionTwo, revisionThree};
        final int[] afterSplitRevisionCodes = new int[] { revisionOne, revisionTwo };

        final PackageSetting before = createPackageSetting();
        before.setLongVersionCode(1);
        before.setSplitNames(splitNames);
        before.setSplitRevisionCodes(beforeSplitRevisionCodes);
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = splitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        assertThrows(PackageManagerException.class,
                () -> PackageManagerServiceUtils.checkDowngrade(before, after));
    }

    @Test
    public void testCheckDowngrade_packageSetting_sameSplitNameRevisionsBigger()
            throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final int revisionThree = 360;
        final String[] splitNames = new String[] { splitOne, splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionOne, revisionTwo};
        final int[] afterSplitRevisionCodes = new int[] { revisionOne, revisionThree };

        final PackageSetting before = createPackageSetting();
        before.setLongVersionCode(1);
        before.setSplitNames(splitNames);
        before.setSplitRevisionCodes(beforeSplitRevisionCodes);
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = splitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        PackageManagerServiceUtils.checkDowngrade(before, after);
    }

    @Test
    public void testCheckDowngrade_packageSetting_hasDifferentSplitNames() throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final int revisionThree = 360;
        final String[] beforeSplitNames = new String[] { splitOne, splitTwo };
        final String[] afterSplitNames = new String[] { splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionOne, revisionTwo};
        final int[] afterSplitRevisionCodes = new int[] { revisionThree };

        final PackageSetting before = createPackageSetting();
        before.setLongVersionCode(1);
        before.setSplitNames(beforeSplitNames);
        before.setSplitRevisionCodes(beforeSplitRevisionCodes);
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = afterSplitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        PackageManagerServiceUtils.checkDowngrade(before, after);
    }

    @Test
    public void testCheckDowngrade_packageSetting_newSplitName() throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final String[] beforeSplitNames = new String[] { splitOne };
        final String[] afterSplitNames = new String[] { splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionTwo };
        final int[] afterSplitRevisionCodes = new int[] { revisionOne };

        final PackageSetting before = createPackageSetting();
        before.setLongVersionCode(1);
        before.setSplitNames(beforeSplitNames);
        before.setSplitRevisionCodes(beforeSplitRevisionCodes);
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = afterSplitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        PackageManagerServiceUtils.checkDowngrade(before, after);
    }

    @Test
    public void testCheckDowngrade_androidPackage_versionCodeSmaller_throwException()
            throws Exception {
        final AndroidPackage before = PackageImpl.forTesting(PACKAGE_NAME).hideAsParsed()
                .setVersionCode(2).hideAsFinal();
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;

        assertThrows(PackageManagerException.class,
                () -> PackageManagerServiceUtils.checkDowngrade(before, after));
    }

    @Test
    public void testCheckDowngrade_androidPackage_baseRevisionCodeSmaller_throwException()
            throws Exception {
        final AndroidPackage before = PackageImpl.forTesting(PACKAGE_NAME).setBaseRevisionCode(2)
                .hideAsParsed().setVersionCode(1).hideAsFinal();
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.baseRevisionCode = 1;

        assertThrows(PackageManagerException.class,
                () -> PackageManagerServiceUtils.checkDowngrade(before, after));
    }

    @Test
    public void testCheckDowngrade_androidPackage_splitArraySizeIsDifferent_throwException()
            throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final String[] splitNames = new String[] { splitOne, splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionOne };
        final int[] afterSplitRevisionCodes = new int[] { revisionOne, revisionTwo };

        final AndroidPackage before = PackageImpl.forTesting(PACKAGE_NAME)
                .asSplit(splitNames, /* splitCodePaths= */ null,
                        beforeSplitRevisionCodes, /* splitDependencies= */ null)
                .hideAsParsed().setVersionCode(1).hideAsFinal();
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = splitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        assertThrows(PackageManagerException.class,
                () -> PackageManagerServiceUtils.checkDowngrade(before, after));
    }

    @Test
    public void testCheckDowngrade_androidPackage_splitRevisionCodeSmaller_throwException()
            throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final int revisionThree = 360;
        final String[] splitNames = new String[] { splitOne, splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionTwo, revisionThree};
        final int[] afterSplitRevisionCodes = new int[] { revisionOne, revisionTwo };

        final AndroidPackage before = PackageImpl.forTesting(PACKAGE_NAME)
                .asSplit(splitNames, /* splitCodePaths= */ null,
                        beforeSplitRevisionCodes, /* splitDependencies= */ null)
                .hideAsParsed().setVersionCode(1).hideAsFinal();
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = splitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        assertThrows(PackageManagerException.class,
                () -> PackageManagerServiceUtils.checkDowngrade(before, after));
    }

    @Test
    public void testCheckDowngrade_androidPackage_sameSplitNameRevisionsBigger()
            throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final int revisionThree = 360;
        final String[] splitNames = new String[] { splitOne, splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionOne, revisionTwo};
        final int[] afterSplitRevisionCodes = new int[] { revisionOne, revisionThree };

        final AndroidPackage before = PackageImpl.forTesting(PACKAGE_NAME)
                .asSplit(splitNames, /* splitCodePaths= */ null,
                        beforeSplitRevisionCodes, /* splitDependencies= */ null)
                .hideAsParsed().setVersionCode(1).hideAsFinal();
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = splitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        PackageManagerServiceUtils.checkDowngrade(before, after);
    }

    @Test
    public void testCheckDowngrade_androidPackage_hasDifferentSplitNames() throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final int revisionThree = 360;
        final String[] beforeSplitNames = new String[] { splitOne, splitTwo };
        final String[] afterSplitNames = new String[] { splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionOne, revisionTwo};
        final int[] afterSplitRevisionCodes = new int[] { revisionThree };

        final AndroidPackage before = PackageImpl.forTesting(PACKAGE_NAME)
                .asSplit(beforeSplitNames, /* splitCodePaths= */ null,
                        beforeSplitRevisionCodes, /* splitDependencies= */ null)
                .hideAsParsed().setVersionCode(1).hideAsFinal();
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = afterSplitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        PackageManagerServiceUtils.checkDowngrade(before, after);
    }

    @Test
    public void testCheckDowngrade_androidPackage_newSplitName() throws Exception {
        final String splitOne = "one";
        final String splitTwo = "two";
        final int revisionOne = 311;
        final int revisionTwo = 330;
        final String[] beforeSplitNames = new String[] { splitOne };
        final String[] afterSplitNames = new String[] { splitTwo };
        final int[] beforeSplitRevisionCodes = new int[] { revisionTwo };
        final int[] afterSplitRevisionCodes = new int[] { revisionOne };

        final AndroidPackage before = PackageImpl.forTesting(PACKAGE_NAME)
                .asSplit(beforeSplitNames, /* splitCodePaths= */ null,
                        beforeSplitRevisionCodes, /* splitDependencies= */ null)
                .hideAsParsed().setVersionCode(1).hideAsFinal();
        final PackageInfoLite after = new PackageInfoLite();
        after.versionCode = 1;
        after.splitNames = afterSplitNames;
        after.splitRevisionCodes = afterSplitRevisionCodes;

        PackageManagerServiceUtils.checkDowngrade(before, after);
    }

    private PackageSetting createPackageSetting() {
        return new PackageSetting(PACKAGE_NAME, PACKAGE_NAME, CODE_PATH, /* pkgFlags= */ 0,
                /* privateFlags= */ 0 , UUID.randomUUID());
    }
}
