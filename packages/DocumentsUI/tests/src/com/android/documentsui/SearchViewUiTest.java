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
public class SearchViewUiTest extends ActivityTest<FilesActivity> {

    private static final String TAG = "SearchViewUiTest";

    public SearchViewUiTest() {
        super(FilesActivity.class);
    }

    public void testSearchView_ExpandsOnClick() throws Exception {
        bot.openSearchView();
        bot.assertSearchTextFiledAndIcon(true, false);
    }

    public void testSearchView_CollapsesOnBack() throws Exception {
        bot.openSearchView();

        device.pressBack();

        bot.assertSearchTextFiledAndIcon(false, true);
    }

    public void testSearchView_ClearsTextOnBack() throws Exception {
        String query = "file2";
        bot.openSearchView();
        bot.setSearchQuery(query);

        device.pressBack();

        bot.assertSearchTextFiledAndIcon(false, true);
    }

    public void testSearch_ResultsFound() throws Exception {
        initTestFiles();
        bot.openRoot(ROOT_0_ID);
        assertDefaultContentOfTestDir0();

        String query = "file1";
        bot.openSearchView();
        bot.setSearchQuery(query);
        bot.assertSearchTextField(true, query);

        device.pressEnter();

        bot.assertDocumentsCountOnList(true, 2);
        bot.assertHasDocuments(fileName1, fileName2);

        bot.assertSearchTextField(false, query);
    }

    public void testSearchResultsFound_ClearsOnBack() throws Exception {
        initTestFiles();
        bot.openRoot(ROOT_0_ID);
        assertDefaultContentOfTestDir0();

        String query = fileName1;
        bot.openSearchView();
        bot.setSearchQuery(query);

        device.pressEnter();
        device.pressBack();

        assertDefaultContentOfTestDir0();
    }

    public void testSearch_NoResults() throws Exception {
        initTestFiles();
        bot.openRoot(ROOT_0_ID);
        assertDefaultContentOfTestDir0();

        String query = "chocolate";
        bot.openSearchView();
        bot.setSearchQuery(query);

        device.pressEnter();

        bot.assertDocumentsCountOnList(false, 0);

        device.waitForIdle();
        String msg = String.valueOf(context.getString(R.string.no_results));
        bot.assertMessageTextView(String.format(msg, "TEST_ROOT_0"));

        bot.assertSearchTextField(false, query);
    }

    public void testSearchNoResults_ClearsOnBack() throws Exception {
        initTestFiles();
        bot.openRoot(ROOT_0_ID);
        assertDefaultContentOfTestDir0();

        String query = "chocolate";
        bot.openSearchView();
        bot.setSearchQuery(query);

        device.pressEnter();
        device.pressBack();

        device.waitForIdle();
        assertDefaultContentOfTestDir0();
    }

    public void testSearchResultsFound_ClearsOnDirectoryChange() throws Exception {
        initTestFiles();
        bot.openRoot(ROOT_0_ID);
        assertDefaultContentOfTestDir0();

        String query = fileName1;
        bot.openSearchView();
        bot.setSearchQuery(query);

        device.pressEnter();

        bot.openRoot(ROOT_1_ID);
        assertDefaultContentOfTestDir1();

        bot.openRoot(ROOT_0_ID);
        assertDefaultContentOfTestDir0();
    }

    public void testSearchIconVisible_RootWithSearchSupport() throws Exception {
        bot.openRoot(ROOT_0_ID);
        bot.assertSearchTextFiledAndIcon(false, true);
    }

    public void testSearchIconHidden_RootNoSearchSupport() throws Exception {
        bot.openRoot(ROOT_1_ID);
        bot.assertSearchTextFiledAndIcon(false, false);
    }

}
