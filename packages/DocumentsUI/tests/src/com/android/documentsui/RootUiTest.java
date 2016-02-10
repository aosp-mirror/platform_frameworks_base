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

import android.support.test.uiautomator.Configurator;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.MotionEvent;

@LargeTest
public class RootUiTest extends ActivityTest<FilesActivity> {

    private static final String TAG = "RootUiTest";

    public RootUiTest() {
        super(FilesActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        initTestFiles();
        bot.openRoot(ROOT_0_ID);
    }

    public void testRootTapped_GoToRootFromChildDir() throws Exception {
        bot.openDocument(dirName1);
        bot.assertWindowTitle(dirName1);
        bot.openRoot(ROOT_0_ID);
        bot.assertWindowTitle(ROOT_0_ID);
        assertDefaultContentOfTestDir0();
    }
}
