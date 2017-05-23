/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android;

import static org.junit.Assert.assertFalse;

import android.support.test.filters.LargeTest;
import android.support.test.filters.MediumTest;
import android.support.test.filters.SmallTest;
import android.support.test.internal.runner.ClassPathScanner;
import android.support.test.internal.runner.ClassPathScanner.ChainedClassNameFilter;
import android.support.test.internal.runner.ClassPathScanner.ExternalClassNameFilter;
import android.support.test.internal.runner.TestLoader;
import android.testing.AndroidTestingRunner;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * This is named AAAPlusPlusVerifySysuiRequiredTestPropertiesTest for two reasons.
 * a) Its so awesome it deserves an AAA++
 * b) It should run first to draw attention to itself.
 *
 * For trues though: this test verifies that all the sysui tests extend the right classes.
 * This matters because including tests with different context implementations in the same
 * test suite causes errors, such as the incorrect settings provider being cached.
 * For an example, see {@link com.android.systemui.DependencyTest}.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class AAAPlusPlusVerifySysuiRequiredTestPropertiesTest extends SysuiTestCase {

    private static final String TAG = "AAA++VerifyTest";

    private static final Class[] BASE_CLS_WHITELIST = {
            SysuiTestCase.class,
            SysuiBaseFragmentTest.class,
    };

    private static final Class[] SUPPORTED_SIZES = {
            SmallTest.class,
            MediumTest.class,
            LargeTest.class,
            android.test.suitebuilder.annotation.SmallTest.class,
            android.test.suitebuilder.annotation.MediumTest.class,
            android.test.suitebuilder.annotation.LargeTest.class,
    };

    @Test
    public void testAllClassInheritance() {
        boolean anyClassWrong = false;
        TestLoader loader = new TestLoader();
        for (String className : getClassNamesFromClassPath()) {
            Class<?> cls = loader.loadIfTest(className);
            if (cls == null) continue;

            boolean hasParent = false;
            for (Class<?> parent : BASE_CLS_WHITELIST) {
                if (parent.isAssignableFrom(cls)) {
                    hasParent = true;
                    break;
                }
            }
            boolean hasSize = hasSize(cls);
            if (!hasSize) {
                anyClassWrong = true;
                Log.e(TAG, cls.getName() + " does not have size annotation, such as @SmallTest");
            }
            if (!hasParent) {
                anyClassWrong = true;
                Log.e(TAG, cls.getName() + " does not extend any of " + getClsStr());
            }
        }

        assertFalse("All sysui test classes must have size and extend one of " + getClsStr(),
                anyClassWrong);
    }

    private boolean hasSize(Class<?> cls) {
        for (int i = 0; i < SUPPORTED_SIZES.length; i++) {
            if (cls.getDeclaredAnnotation(SUPPORTED_SIZES[i]) != null) return true;
        }
        return false;
    }

    private Collection<String> getClassNamesFromClassPath() {
        ClassPathScanner scanner = new ClassPathScanner(mContext.getPackageCodePath());

        ChainedClassNameFilter filter = new ChainedClassNameFilter();

        filter.add(new ExternalClassNameFilter());
        filter.add(s -> s.startsWith("com.android.systemui")
                || s.startsWith("com.android.keyguard"));
        try {
            return scanner.getClassPathEntries(filter);
        } catch (IOException e) {
            Log.e(TAG, "Failed to scan classes", e);
        }
        return Collections.emptyList();
    }

    private String getClsStr() {
        return TextUtils.join(",", Arrays.asList(BASE_CLS_WHITELIST)
                .stream().map(cls -> cls.getSimpleName()).toArray());
    }
}
