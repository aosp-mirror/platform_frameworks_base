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
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.ServiceManager;
import android.server.search.SearchableInfo;
import android.server.search.SearchableInfo.ActionKeyInfo;
import android.test.ActivityInstrumentationTestCase;
import android.test.MoreAsserts;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.AndroidRuntimeException;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * To launch this test from the command line:
 * 
 * adb shell am instrument -w \
 *   -e class com.android.unit_tests.SearchManagerTest \
 *   com.android.unit_tests/android.test.InstrumentationTestRunner
 */
public class SearchManagerTest extends ActivityInstrumentationTestCase<LocalActivity> {
    
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
     * SearchableInfo tests
     *  Mock the context so I can provide very specific input data
     *  Confirm OK with "zero" searchables
     *  Confirm "good" metadata read properly
     *  Confirm "bad" metadata skipped properly
     *  Confirm ordering of searchables
     *  Confirm "good" actionkeys
     *  confirm "bad" actionkeys are rejected
     *  confirm XML ordering enforced (will fail today - bug in SearchableInfo)
     *  findActionKey works
     *  getIcon works
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
    
    /**
     * The goal of this test is to confirm proper operation of the 
     * SearchableInfo helper class.
     * 
     * TODO:  The metadata source needs to be mocked out because adding
     * searchability metadata via this test is causing it to leak into the
     * real system.  So for now I'm just going to test for existence of the
     * GoogleSearch app (which is searchable).
     */
    @LargeTest
    public void testSearchableGoogleSearch() {
        // test basic array & hashmap
        SearchableInfo.buildSearchableList(mContext);

        // test linkage from another activity
        // TODO inject this via mocking into the package manager.
        // TODO for now, just check for searchable GoogleSearch app (this isn't really a unit test)
        ComponentName thisActivity = new ComponentName(
                "com.android.googlesearch", 
                "com.android.googlesearch.GoogleSearch");

        SearchableInfo si = SearchableInfo.getSearchableInfo(mContext, thisActivity);
        assertNotNull(si);
        assertTrue(si.mSearchable);
        assertEquals(thisActivity, si.mSearchActivity);
        
        Context appContext = si.getActivityContext(mContext);
        assertNotNull(appContext);
        MoreAsserts.assertNotEqual(appContext, mContext);
        assertEquals("Google Search", appContext.getString(si.getHintId()));
        assertEquals("Google", appContext.getString(si.getLabelId()));
    }
    
    /**
     * Test that non-searchable activities return no searchable info (this would typically
     * trigger the use of the default searchable e.g. contacts)
     */
    @LargeTest
    public void testNonSearchable() {
        // test basic array & hashmap
        SearchableInfo.buildSearchableList(mContext);

        // confirm that we return null for non-searchy activities
        ComponentName nonActivity = new ComponentName(
                            "com.android.unit_tests",
                            "com.android.unit_tests.NO_SEARCH_ACTIVITY");
        SearchableInfo si = SearchableInfo.getSearchableInfo(mContext, nonActivity);
        assertNull(si);
    }
    
    /**
     * This is an attempt to run the searchable info list with a mocked context.  Here are some
     * things I'd like to test.
     *
     *  Confirm OK with "zero" searchables
     *  Confirm "good" metadata read properly
     *  Confirm "bad" metadata skipped properly
     *  Confirm ordering of searchables
     *  Confirm "good" actionkeys
     *  confirm "bad" actionkeys are rejected
     *  confirm XML ordering enforced (will fail today - bug in SearchableInfo)
     *  findActionKey works
     *  getIcon works

     */
    @LargeTest
    public void testSearchableMocked() {
        MyMockPackageManager mockPM = new MyMockPackageManager(mContext.getPackageManager());
        MyMockContext mockContext = new MyMockContext(mContext, mockPM);
        ArrayList<SearchableInfo> searchables;
        int count;

        // build item list with real-world source data
        mockPM.setSearchablesMode(MyMockPackageManager.SEARCHABLES_PASSTHROUGH);
        SearchableInfo.buildSearchableList(mockContext);
        // tests with "real" searchables (deprecate, this should be a unit test)
        searchables = SearchableInfo.getSearchablesList();
        count = searchables.size();
        assertTrue(count >= 1);         // this isn't really a unit test
        checkSearchables(searchables);

        // build item list with mocked search data
        // this round of tests confirms good operations with "zero" searchables found
        // This should return either a null pointer or an empty list
        mockPM.setSearchablesMode(MyMockPackageManager.SEARCHABLES_MOCK_ZERO);
        SearchableInfo.buildSearchableList(mockContext);
        searchables = SearchableInfo.getSearchablesList();
        if (searchables != null) {
            count = searchables.size();
            assertTrue(count == 0);
        }
    }
    
    /**
     * Generic health checker for an array of searchables.
     * 
     * This is designed to pass for any semi-legal searchable, without knowing much about
     * the format of the underlying data.  It's fairly easy for a non-compliant application
     * to provide meta-data that will pass here (e.g. a non-existent suggestions authority).
     * 
     * @param searchables The list of searchables to examine.
     */
    private void checkSearchables(ArrayList<SearchableInfo> searchablesList) {
        assertNotNull(searchablesList);
        int count = searchablesList.size();
        for (int ii = 0; ii < count; ii++) {
            SearchableInfo si = searchablesList.get(ii);
            assertNotNull(si);
            assertTrue(si.mSearchable);
            assertTrue(si.getLabelId() != 0);        // This must be a useable string
            assertNotEmpty(si.mSearchActivity.getClassName());
            assertNotEmpty(si.mSearchActivity.getPackageName());
            if (si.getSuggestAuthority() != null) {
                // The suggestion fields are largely optional, so we'll just confirm basic health
                assertNotEmpty(si.getSuggestAuthority());
                assertNullOrNotEmpty(si.getSuggestPath());
                assertNullOrNotEmpty(si.getSuggestSelection());
                assertNullOrNotEmpty(si.getSuggestIntentAction());
                assertNullOrNotEmpty(si.getSuggestIntentData());
            }
            /* Add a way to get the entire action key list, then explicitly test its elements */
            /* For now, test the most common action key (CALL) */
            ActionKeyInfo ai = si.findActionKey(KeyEvent.KEYCODE_CALL);
            if (ai != null) {
                assertEquals(ai.mKeyCode, KeyEvent.KEYCODE_CALL);
                // one of these three fields must be non-null & non-empty
                boolean m1 = (ai.mQueryActionMsg != null) && (ai.mQueryActionMsg.length() > 0);
                boolean m2 = (ai.mSuggestActionMsg != null) && (ai.mSuggestActionMsg.length() > 0);
                boolean m3 = (ai.mSuggestActionMsgColumn != null) && 
                                (ai.mSuggestActionMsgColumn.length() > 0);
                assertTrue(m1 || m2 || m3);
            }
            
            /* 
             * Find ways to test these:
             * 
             * private int mSearchMode
             * private Drawable mIcon
             */
            
            /*
             * Explicitly not tested here:
             * 
             * Can be null, so not much to see:
             * public String mSearchHint
             * private String mZeroQueryBanner
             * 
             * To be deprecated/removed, so don't bother:
             * public boolean mFilterMode
             * public boolean mQuickStart
             * private boolean mIconResized
             * private int mIconResizeWidth
             * private int mIconResizeHeight
             * 
             * All of these are "internal" working variables, not part of any contract
             * private ActivityInfo mActivityInfo
             * private Rect mTempRect
             * private String mSuggestProviderPackage
             * private String mCacheActivityContext
             */
        }
    }
    
    /**
     * Combo assert for "string not null and not empty"
     */
    private void assertNotEmpty(final String s) {
        assertNotNull(s);
        MoreAsserts.assertNotEqual(s, "");
    }
    
    /**
     * Combo assert for "string null or (not null and not empty)"
     */
    private void assertNullOrNotEmpty(final String s) {
        if (s != null) {
            MoreAsserts.assertNotEqual(s, "");
        }
    }    
    
    /**
     * This is a mock for context.  Used to perform a true unit test on SearchableInfo.
     * 
     */
    private class MyMockContext extends MockContext {
        
        protected Context mRealContext;
        protected PackageManager mPackageManager;
        
        /**
         * Constructor.
         * 
         * @param realContext Please pass in a real context for some pass-throughs to function.
         */
        MyMockContext(Context realContext, PackageManager packageManager) {
            mRealContext = realContext;
            mPackageManager = packageManager;
        }
        
        /**
         * Resources.  Pass through for now.
         */
        @Override
        public Resources getResources() {
            return mRealContext.getResources();
        }

        /**
         * Package manager.  Pass through for now.
         */
        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        /**
         * Package manager.  Pass through for now.
         */
        @Override
        public Context createPackageContext(String packageName, int flags)
                throws PackageManager.NameNotFoundException {
            return mRealContext.createPackageContext(packageName, flags);
        }
    }

/**
 * This is a mock for package manager.  Used to perform a true unit test on SearchableInfo.
 * 
 */
    private class MyMockPackageManager extends MockPackageManager {
        
        public final static int SEARCHABLES_PASSTHROUGH = 0;
        public final static int SEARCHABLES_MOCK_ZERO = 1;
        public final static int SEARCHABLES_MOCK_ONEGOOD = 2;
        public final static int SEARCHABLES_MOCK_ONEGOOD_ONEBAD = 3;
        
        protected PackageManager mRealPackageManager;
        protected int mSearchablesMode;

        public MyMockPackageManager(PackageManager realPM) {
            mRealPackageManager = realPM;
            mSearchablesMode = SEARCHABLES_PASSTHROUGH;
        }

        /**
         * Set the mode for various tests.
         */
        public void setSearchablesMode(int newMode) {
            switch (newMode) {
            case SEARCHABLES_PASSTHROUGH:
            case SEARCHABLES_MOCK_ZERO:
                mSearchablesMode = newMode;
                break;
                
            default:
                throw new UnsupportedOperationException();       
            }
        }
        
        /**
         * Find activities that support a given intent.
         * 
         * Retrieve all activities that can be performed for the given intent.
         * 
         * @param intent The desired intent as per resolveActivity().
         * @param flags Additional option flags.  The most important is
         *                    MATCH_DEFAULT_ONLY, to limit the resolution to only
         *                    those activities that support the CATEGORY_DEFAULT.
         * 
         * @return A List<ResolveInfo> containing one entry for each matching
         *         Activity. These are ordered from best to worst match -- that
         *         is, the first item in the list is what is returned by
         *         resolveActivity().  If there are no matching activities, an empty
         *         list is returned.
         */
        @Override 
        public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
            assertNotNull(intent);
            assertEquals(intent.getAction(), Intent.ACTION_SEARCH);
            switch (mSearchablesMode) {
            case SEARCHABLES_PASSTHROUGH:
                return mRealPackageManager.queryIntentActivities(intent, flags);
            case SEARCHABLES_MOCK_ZERO:
                return null;
            default:
                throw new UnsupportedOperationException();
            }
        }
        
        /**
         * Retrieve an XML file from a package.  This is a low-level API used to
         * retrieve XML meta data.
         * 
         * @param packageName The name of the package that this xml is coming from.
         * Can not be null.
         * @param resid The resource identifier of the desired xml.  Can not be 0.
         * @param appInfo Overall information about <var>packageName</var>.  This
         * may be null, in which case the application information will be retrieved
         * for you if needed; if you already have this information around, it can
         * be much more efficient to supply it here.
         * 
         * @return Returns an XmlPullParser allowing you to parse out the XML
         * data.  Returns null if the xml resource could not be found for any
         * reason.
         */
        @Override 
        public XmlResourceParser getXml(String packageName, int resid, ApplicationInfo appInfo) {
            assertNotNull(packageName);
            MoreAsserts.assertNotEqual(packageName, "");
            MoreAsserts.assertNotEqual(resid, 0);
            switch (mSearchablesMode) {
            case SEARCHABLES_PASSTHROUGH:
                return mRealPackageManager.getXml(packageName, resid, appInfo);
            case SEARCHABLES_MOCK_ZERO:
            default:
                throw new UnsupportedOperationException();
            }
        }
        
        /**
         * Find a single content provider by its base path name.
         * 
         * @param name The name of the provider to find.
         * @param flags Additional option flags.  Currently should always be 0.
         * 
         * @return ContentProviderInfo Information about the provider, if found,
         *         else null.
         */
        @Override 
        public ProviderInfo resolveContentProvider(String name, int flags) {
            assertNotNull(name);
            MoreAsserts.assertNotEqual(name, "");
            assertEquals(flags, 0);
            switch (mSearchablesMode) {
            case SEARCHABLES_PASSTHROUGH:
                return mRealPackageManager.resolveContentProvider(name, flags);
            case SEARCHABLES_MOCK_ZERO:
            default:
                throw new UnsupportedOperationException();
            }
        }
    }
}

