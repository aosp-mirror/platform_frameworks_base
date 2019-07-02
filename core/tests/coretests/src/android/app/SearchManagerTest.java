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

package android.app;

import android.app.activity.LocalActivity;
import android.content.ComponentName;
import android.content.Context;
import android.os.ServiceManager;
import android.test.ActivityInstrumentationTestCase2;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

/**
 * To launch this test from the command line:
 * 
 * adb shell am instrument -w \
 *   -e class com.android.unit_tests.SearchManagerTest \
 *   com.android.unit_tests/android.test.InstrumentationTestRunner
 */
public class SearchManagerTest extends ActivityInstrumentationTestCase2<LocalActivity> {

    private ComponentName SEARCHABLE_ACTIVITY =
            new ComponentName("com.android.frameworks.coretests",
                    "android.app.activity.SearchableActivity");

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
        super("com.android.frameworks.coretests", LocalActivity.class);
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

    /**
     * Tests that startSearch() can be called multiple times without stopSearch()
     * in between.
     */
    public void testStartSearchIdempotent() throws Exception {
         SearchManager searchManager = (SearchManager)
                 mContext.getSystemService(Context.SEARCH_SERVICE);
         assertNotNull(searchManager);

         searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
         searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
         searchManager.stopSearch();
    }

    /**
     * Tests that stopSearch() can be called when the search UI is not visible and can be
     * called multiple times without startSearch() in between.
     */
    public void testStopSearchIdempotent() throws Exception {
         SearchManager searchManager = (SearchManager)
                 mContext.getSystemService(Context.SEARCH_SERVICE);
         assertNotNull(searchManager);
         searchManager.stopSearch();

         searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
         searchManager.stopSearch();
         searchManager.stopSearch();
    }

    /**
     * The goal of this test is to confirm that we can start and then
     * stop a simple search.
     */
    public void testSearchManagerInvocations() throws Exception {
        SearchManager searchManager = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        assertNotNull(searchManager);

        // These tests should simply run to completion w/o exceptions
        searchManager.startSearch(null, false, SEARCHABLE_ACTIVITY, null, false);
        searchManager.stopSearch();

        searchManager.startSearch("", false, SEARCHABLE_ACTIVITY, null, false);
        searchManager.stopSearch();

        searchManager.startSearch("test search string", false, SEARCHABLE_ACTIVITY, null, false);
        searchManager.stopSearch();

        searchManager.startSearch("test search string", true, SEARCHABLE_ACTIVITY, null, false);
        searchManager.stopSearch();
    }

}
