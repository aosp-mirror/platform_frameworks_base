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
public class SearchViewUiTest extends ActivityTest<FilesActivity> {

    public SearchViewUiTest() {
        super(FilesActivity.class);
    }

    @Suppress
    public void testSearchView_ExpandsOnClick() throws Exception {
        bots.main.openSearchView();
        bots.main.assertSearchTextFiledAndIcon(true, false);
    }

    public void testSearchView_CollapsesOnBack() throws Exception {
        bots.main.openSearchView();

        device.pressBack();

        bots.main.assertSearchTextFiledAndIcon(false, true);
    }

    @Suppress
    public void testSearchView_ClearsTextOnBack() throws Exception {
        String query = "file2";
        bots.main.openSearchView();
        bots.main.setSearchQuery(query);

        device.pressBack();

        bots.main.assertSearchTextFiledAndIcon(false, true);
    }

    @Suppress
    public void testSearch_ResultsFound() throws Exception {
        initTestFiles();
        assertDefaultContentOfTestDir0();

        String query = "file1";
        bots.main.openSearchView();
        bots.main.setSearchQuery(query);
        bots.main.assertSearchTextField(true, query);

        device.pressEnter();

        bots.directory.assertDocumentsCountOnList(true, 2);
        bots.directory.assertDocumentsPresent(fileName1, fileName2);

        bots.main.assertSearchTextField(false, query);
    }

    @Suppress
    public void testSearchResultsFound_ClearsOnBack() throws Exception {
        initTestFiles();
        assertDefaultContentOfTestDir0();

        String query = fileName1;
        bots.main.openSearchView();
        bots.main.setSearchQuery(query);

        device.pressEnter();
        device.pressBack();

        assertDefaultContentOfTestDir0();
    }

    @Suppress
    public void testSearch_NoResults() throws Exception {
        initTestFiles();
        assertDefaultContentOfTestDir0();

        String query = "chocolate";
        bots.main.openSearchView();
        bots.main.setSearchQuery(query);

        device.pressEnter();

        bots.directory.assertDocumentsCountOnList(false, 0);

        device.waitForIdle();
        String msg = String.valueOf(context.getString(R.string.no_results));
        bots.directory.assertMessageTextView(String.format(msg, "TEST_ROOT_0"));

        bots.main.assertSearchTextField(false, query);
    }

    @Suppress
    public void testSearchNoResults_ClearsOnBack() throws Exception {
        initTestFiles();
        assertDefaultContentOfTestDir0();

        String query = "chocolate";
        bots.main.openSearchView();
        bots.main.setSearchQuery(query);

        device.pressEnter();
        device.pressBack();

        device.waitForIdle();
        assertDefaultContentOfTestDir0();
    }

    @Suppress
    public void testSearchResultsFound_ClearsOnDirectoryChange() throws Exception {
        initTestFiles();
        assertDefaultContentOfTestDir0();

        String query = fileName1;
        bots.main.openSearchView();
        bots.main.setSearchQuery(query);

        device.pressEnter();

        bots.roots.openRoot(ROOT_1_ID);
        assertDefaultContentOfTestDir1();

        bots.roots.openRoot(ROOT_0_ID);
        assertDefaultContentOfTestDir0();
    }

    public void testSearchIconVisible_RootWithSearchSupport() throws Exception {
        bots.roots.openRoot(ROOT_0_ID);
        bots.main.assertSearchTextFiledAndIcon(false, true);
    }

    public void testSearchIconHidden_RootNoSearchSupport() throws Exception {
        bots.roots.openRoot(ROOT_1_ID);
        bots.main.assertSearchTextFiledAndIcon(false, false);
    }

}
