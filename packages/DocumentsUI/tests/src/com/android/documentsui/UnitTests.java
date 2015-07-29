/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
        MultiSelectManager_SelectionTest.class,
        MultiSelectManagerTest.class
})

/**
 * This test suite can be run using the "art" runtime (which can be built
 * via the `build-art-host` target.) You'll also need to "mma -j32" the
 * DocumentsUI package to ensure all deps are built.
 *
 * <p>Once the dependencies have been built, the tests can be executed as follows:
 *
 * <pre>
 *  CP=$OUT/system/framework/framework.jar:\
 *      $OUT/system/framework/core-junit.jar:\
 *      $OUT/system/app/DocumentsUI/DocumentsUI.apk:\
 *      $OUT/data/app/DocumentsUITests/DocumentsUITests.apk
 *
 *  art -cp $CP org.junit.runner.JUnitCore com.android.documentsui.UnitTests
 * </pre>
 */
public class UnitTests {}
