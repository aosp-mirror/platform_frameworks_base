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

import android.support.test.uiautomator.UiObject;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;

@LargeTest
public class SearchViewUiTest extends InstrumentationTestCase {

    private static final String TAG = "SearchViewUiTest";

    private UiTestEnvironment mEnv;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mEnv = new UiTestEnvironment(getInstrumentation());
        mEnv.launch();

    }

    @Override
    protected void tearDown() throws Exception {
        mEnv.device().pressBack();
        super.tearDown();
    }

    public void testSearchView_ExpandsOnClick() throws Exception {
        mEnv.bot().openSearchView();
        mEnv.bot().assertSearchTextFiledAndIcon(true, false);
    }

    public void testSearchView_CollapsesOnBack() throws Exception {
        mEnv.bot().openSearchView();

        mEnv.device().pressBack();

        mEnv.bot().assertSearchTextFiledAndIcon(false, true);
    }

    public void testSearchView_ClearsTextOnBack() throws Exception {
        String query = "file2";
        mEnv.bot().openSearchView();
        mEnv.bot().setSearchQuery(query);

        mEnv.device().pressBack();

        mEnv.bot().assertSearchTextFiledAndIcon(false, true);
    }

    public void testSearch_ResultsFound() throws Exception {
        mEnv.initTestFiles();
        mEnv.bot().openRoot(ROOT_0_ID);
        mEnv.assertDefaultContentOfTestDir0();

        String query = "file1";
        mEnv.bot().openSearchView();
        mEnv.bot().setSearchQuery(query);
        mEnv.bot().assertSearchTextField(true, query);

        mEnv.device().pressEnter();

        mEnv.bot().assertDocumentsCountOnList(true, 2);
        mEnv.bot().assertHasDocuments(UiTestEnvironment.fileName1, UiTestEnvironment.fileName2);
        mEnv.bot().assertSearchTextField(false, query);
    }

    public void testSearchResultsFound_ClearsOnBack() throws Exception {
        mEnv.initTestFiles();
        mEnv.bot().openRoot(ROOT_0_ID);
        mEnv.assertDefaultContentOfTestDir0();

        String query = UiTestEnvironment.fileName1;
        mEnv.bot().openSearchView();
        mEnv.bot().setSearchQuery(query);

        mEnv.device().pressEnter();
        mEnv.device().pressBack();
        mEnv.assertDefaultContentOfTestDir0();
    }

    public void testSearch_NoResults() throws Exception {
        mEnv.initTestFiles();
        mEnv.bot().openRoot(ROOT_0_ID);
        mEnv.assertDefaultContentOfTestDir0();

        String query = "chocolate";
        mEnv.bot().openSearchView();
        mEnv.bot().setSearchQuery(query);

        mEnv.device().pressEnter();

        mEnv.bot().assertDocumentsCountOnList(false, 0);

        String msg = String.valueOf(mEnv.context().getString(R.string.no_results));
        mEnv.bot().assertMessageTextView(String.format(msg, "TEST_ROOT_0"));
        mEnv.bot().assertSearchTextField(false, query);
    }

    public void testSearchNoResults_ClearsOnBack() throws Exception {
        mEnv.initTestFiles();
        mEnv.bot().openRoot(ROOT_0_ID);
        mEnv.assertDefaultContentOfTestDir0();

        String query = "chocolate";
        mEnv.bot().openSearchView();
        mEnv.bot().setSearchQuery(query);

        mEnv.device().pressEnter();
        mEnv.device().pressBack();
        mEnv.assertDefaultContentOfTestDir0();
    }

    public void testSearchResultsFound_ClearsOnDirectoryChange() throws Exception {
        mEnv.initTestFiles();
        mEnv.bot().openRoot(ROOT_0_ID);
        mEnv.assertDefaultContentOfTestDir0();

        String query = UiTestEnvironment.fileName1;
        mEnv.bot().openSearchView();
        mEnv.bot().setSearchQuery(query);

        mEnv.device().pressEnter();

        mEnv.bot().openRoot(ROOT_1_ID);
        mEnv.assertDefaultContentOfTestDir1();

        mEnv.bot().openRoot(ROOT_0_ID);
        mEnv.assertDefaultContentOfTestDir0();
    }

    public void testSearchIconVisible_RootWithSearchSupport() throws Exception {
        mEnv.bot().openRoot(ROOT_0_ID);
        mEnv.bot().assertSearchTextFiledAndIcon(false, true);
    }

    public void testSearchIconHidden_RootNoSearchSupport() throws Exception {
        mEnv.bot().openRoot(ROOT_1_ID);
        mEnv.bot().assertSearchTextFiledAndIcon(false, false);
    }

}
