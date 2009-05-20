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
import android.os.ServiceManager;
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
    
    // If non-zero, enable a set of tests that start and stop the search manager.
    // This is currently disabled because it's causing an unwanted jump from the unit test
    // activity into the contacts activity.  We'll put this back after we disable that jump.
    private static final int TEST_SEARCH_START = 0;
    
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
        mContext = (Context)testActivity;
    }

    /**
     * The goal of this test is to confirm that we can obtain
     * a search manager interface.
     */
    @MediumTest
    public void testSearchManagerInterfaceAvailable() {
        ISearchManager searchManager1 = ISearchManager.Stub.asInterface(
                ServiceManager.getService(Context.SEARCH_SERVICE));
        assertNotNull(searchManager1);
    }
    
    /**
     * The goal of this test is to confirm that we can *only* obtain a search manager
     * interface from an Activity context.
     */
    @MediumTest
    public void testSearchManagerContextRestrictions() {
        SearchManager searchManager1 = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        assertNotNull(searchManager1);
        
        Context applicationContext = mContext.getApplicationContext();
        // this should fail, because you can't get a SearchManager from a non-Activity context
        try {
            applicationContext.getSystemService(Context.SEARCH_SERVICE);
            assertFalse("Shouldn't retrieve SearchManager from a non-Activity context", true);
        } catch (AndroidRuntimeException e) {
            // happy here - we should catch this.
        }
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
        assertSame( searchManager1, searchManager2 );
    }
    
    /**
     * The goal of this test is to confirm that we can start and then
     * stop a simple search.
     */
    
   @MediumTest
   public void testSearchManagerInvocations() {
        SearchManager searchManager = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        assertNotNull(searchManager);
        
            // TODO: make a real component name, or remove this need
        final ComponentName cn = new ComponentName("", "");

        if (TEST_SEARCH_START != 0) {
            // These tests should simply run to completion w/o exceptions
            searchManager.startSearch(null, false, cn, null, false);
            searchManager.stopSearch();
            
            searchManager.startSearch("", false, cn, null, false);
            searchManager.stopSearch();
            
            searchManager.startSearch("test search string", false, cn, null, false);
            searchManager.stopSearch();
            
            searchManager.startSearch("test search string", true, cn, null, false);
            searchManager.stopSearch();
        }
     }

}

