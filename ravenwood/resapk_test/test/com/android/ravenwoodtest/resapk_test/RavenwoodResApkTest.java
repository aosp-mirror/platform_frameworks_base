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
package com.android.ravenwoodtest.resapk_test;


import static junit.framework.TestCase.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.ravenwood.common.RavenwoodCommonUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class RavenwoodResApkTest {
    /**
     * Ensure the file "ravenwood-res.apk" exists.
     * TODO Check the content of it, once Ravenwood supports resources. The file should
     * be a copy of RavenwoodResApkTest-apk.apk
     */
    @Test
    public void testResApkExists() {
        var file = "ravenwood-res-apks/ravenwood-res.apk";

        assertTrue(new File(file).exists());
    }

    @Test
    public void testFrameworkResExists() {
        var file = "ravenwood-data/framework-res.apk";

        assertTrue(new File(
                RavenwoodCommonUtils.getRavenwoodRuntimePath() + "/" + file).exists());
    }
}
