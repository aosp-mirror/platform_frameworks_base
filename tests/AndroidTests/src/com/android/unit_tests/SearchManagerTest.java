/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.unit_tests;

import com.android.unit_tests.activity.LocalActivity;

import android.app.Activity;
import android.app.ISearchManager;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.server.search.SearchableInfo;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.AndroidRuntimeException;

/**
 * To launch this test from the command line:
 * 
 * adb shell am instrument -w \
 *   -e class com.android.unit_tests.SearchManagerTest \
 *   com.android.unit_tests/android.test.InstrumentationTestRunner
 */
public class SearchManagerTest extends ActivityInstrumentationTestCase2<LocalActivity> {

    private ComponentName SEARCHABLE_ACTIVITY =
            new ComponentName("com.android.unit_tests",
                    "com.android.unit_tests.SearchableActivity");

    /*
     * Bug list of test ideas.
     * 
     * testSearchManagerInterfaceAvailable()
     *  Exercise the interface obtained
     *  
     * testSearchManagerAvailable()
     *  Exercise the interface obtained
     *  
     * testSearchManagerInvocations()
     *  FIX - make it work again
     *  stress test with a very long string
     * 
     * SearchManager tests
     *  confirm proper identification of "default" activity based on policy, not hardcoded contacts
     *  
     * SearchBar tests
     *  Maybe have to do with framework / unittest runner - need instrumented activity?
     *  How can we unit test the suggestions content providers?
     *  Should I write unit tests for any of them?
     *  Test scenarios:
     *    type-BACK (cancel)
     *    type-GO (send)
     *    type-navigate-click (suggestion)
     *    type-action
     *    type-navigate-action (suggestion)
     */
    
    /**
     * Local copy of activity context
     */
    Context mContext;

    public SearchManagerTest() {
        super("com.android.unit_tests", LocalActivity.class);
    }

    /**
     * Setup any common data for the upcoming tests.
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        
        Activity testActivity = getActivity();
        mContext = testActivity;
    }

    private ISearchManager getSearchManagerService() {
        return ISearchManager.Stub.asInterface(
                ServiceManager.getService(Context.SEARCH_SERVICE));
    }

    // Checks that the search UI is visible.
    private void assertSearchVisible() {
        SearchManager searchManager = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        assertTrue("SearchManager thinks search UI isn't visible when it should be",
                searchManager.isVisible());
    }

    // Checks that the search UI is not visible.
    // This checks both the SearchManager and the SearchManagerService,
    // since SearchManager keeps a local variable for the visibility.
    private void assertSearchNotVisible() {
        SearchManager searchManager = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        assertFalse("SearchManager thinks search UI is visible when it shouldn't be",
                searchManager.isVisible());
    }

    /**
     * The goal of this test is to confirm that we can obtain
     * a search manager interface.
     */
    @MediumTest
    public void testSearchManagerInterfaceAvailable() {
        assertNotNull(getSearchManagerService());
    }
    
    /**
     * The goal of this test is to confirm that we can obtain
     * a search manager at any time, and that for any given context,
     * it is a singleton.
     */
    @LargeTest
    public void testSearchManagerAvailable() {
        SearchManager searchManager1 = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        assertNotNull(searchManager1);
        SearchManager searchManager2 = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        assertNotNull(searchManager2);
        assertSame(searchManager1, searchManager2 );
    }

    @MediumTest
    public void testSearchables() {
        SearchManager searchManager = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        SearchableInfo si;

        si = searchManager.getSearchableInfo(SEARCHABLE_ACTIVITY, false);
        assertNotNull(si);
        assertFalse(searchManager.isDefaultSearchable(si));
        si = searchManager.getSearchableInfo(SEARCHABLE_ACTIVITY, true);
        assertNotNull(si);
        assertTrue(searchManager.isDefaultSearchable(si));
        si = searchManager.getSearchableInfo(null, true);
        assertNotNull(si);
        assertTrue(searchManager.isDefaultSearchable(si));
    }

    /**
     * Tests that rapid calls to start-stop-start doesn't cause problems.
     */
    @MediumTest
    public void testSearchManagerFastInvocations() throws Exception {
         SearchManager searchManager = (SearchManager)
                 mContext.getSystemService(Context.SEARCH_SERVICE);
         assertNotNull(searchManager);
         assertSearchNotVisible();

         searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
         assertSearchVisible();
         searchManager.stopSearch();
         searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
         searchManager.stopSearch();
         assertSearchNotVisible();
    }

    /**
     * Tests that startSearch() is idempotent.
     */
    @MediumTest
    public void testStartSearchIdempotent() throws Exception {
         SearchManager searchManager = (SearchManager)
                 mContext.getSystemService(Context.SEARCH_SERVICE);
         assertNotNull(searchManager);
         assertSearchNotVisible();

         searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
         searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
         assertSearchVisible();
         searchManager.stopSearch();
         assertSearchNotVisible();
    }

    /**
     * Tests that stopSearch() is idempotent and can be called when the search UI is not visible.
     */
    @MediumTest
    public void testStopSearchIdempotent() throws Exception {
         SearchManager searchManager = (SearchManager)
                 mContext.getSystemService(Context.SEARCH_SERVICE);
         assertNotNull(searchManager);
         assertSearchNotVisible();
         searchManager.stopSearch();
         assertSearchNotVisible();

         searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
         assertSearchVisible();
         searchManager.stopSearch();
         searchManager.stopSearch();
         assertSearchNotVisible();
    }

    /**
     * The goal of this test is to confirm that we can start and then
     * stop a simple search.
     */
    @MediumTest
    public void testSearchManagerInvocations() throws Exception {
        SearchManager searchManager = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        assertNotNull(searchManager);
        assertSearchNotVisible();

        // These tests should simply run to completion w/o exceptions
        searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
        assertSearchVisible();
        searchManager.stopSearch();
        assertSearchNotVisible();

        searchManager.startSearch("", false, SEARCHABLE_ACTIVITY, null, false);
        assertSearchVisible();
        searchManager.stopSearch();
        assertSearchNotVisible();

        searchManager.startSearch("test search string", false, SEARCHABLE_ACTIVITY, null, false);
        assertSearchVisible();
        searchManager.stopSearch();
        assertSearchNotVisible();

        searchManager.startSearch("test search string", true, SEARCHABLE_ACTIVITY, null, false);
        assertSearchVisible();
        searchManager.stopSearch();
        assertSearchNotVisible();
    }

    @MediumTest
    public void testSearchDialogState() throws Exception {
        SearchManager searchManager = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        assertNotNull(searchManager);

        Bundle searchState;

        // search dialog not visible, so no state should be stored
        searchState = searchManager.saveSearchDialog();
        assertNull(searchState);

        searchManager.startSearch("test search string", true, SEARCHABLE_ACTIVITY, null, false);
        searchState = searchManager.saveSearchDialog();
        assertNotNull(searchState);
        searchManager.stopSearch();
    }

}
