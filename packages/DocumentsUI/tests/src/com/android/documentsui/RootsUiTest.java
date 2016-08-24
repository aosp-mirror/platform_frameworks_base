/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.documentsui.StubProvider.ROOT_0_ID;
import static com.android.documentsui.StubProvider.ROOT_1_ID;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;

@LargeTest
public class RootsUiTest extends ActivityTest<FilesActivity> {

    private static final String TAG = "RootUiTest";

    public RootsUiTest() {
        super(FilesActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        initTestFiles();
    }

    public void testRootTapped_GoToRootFromChildDir() throws Exception {
        bots.directory.openDocument(dirName1);
        bots.main.assertWindowTitle(dirName1);
        bots.roots.openRoot(ROOT_0_ID);
        bots.main.assertWindowTitle(ROOT_0_ID);
        assertDefaultContentOfTestDir0();
    }

    @Suppress
    public void testRootChanged_ClearSelection() throws Exception {
        bots.directory.selectDocument(fileName1);
        bots.main.assertInActionMode(true);

        bots.roots.openRoot(ROOT_1_ID);
        bots.main.assertInActionMode(false);
    }

}
