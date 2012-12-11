/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server.search;

import android.app.SearchManager;
import android.app.SearchableInfo;
import android.app.SearchableInfo.ActionKeyInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.RemoteException;
import com.android.server.search.Searchables;
import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * To launch this test from the command line:
 * 
 * adb shell am instrument -w \
 *   -e class com.android.unit_tests.SearchablesTest \
 *   com.android.unit_tests/android.test.InstrumentationTestRunner
 */
@SmallTest
public class SearchablesTest extends AndroidTestCase {
    
    /*
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
     */

    /**
     * Test that non-searchable activities return no searchable info (this would typically
     * trigger the use of the default searchable e.g. contacts)
     */
    public void testNonSearchable() {
        // test basic array & hashmap
        Searchables searchables = new Searchables(mContext, 0);
        searchables.buildSearchableList();

        // confirm that we return null for non-searchy activities
        ComponentName nonActivity = new ComponentName(
                            "com.android.frameworks.coretests",
                            "com.android.frameworks.coretests.activity.NO_SEARCH_ACTIVITY");
        SearchableInfo si = searchables.getSearchableInfo(nonActivity);
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
    public void testSearchablesListReal() {
        MyMockPackageManager mockPM = new MyMockPackageManager(mContext.getPackageManager());
        MyMockContext mockContext = new MyMockContext(mContext, mockPM);

        // build item list with real-world source data
        mockPM.setSearchablesMode(MyMockPackageManager.SEARCHABLES_PASSTHROUGH);
        Searchables searchables = new Searchables(mockContext, 0);
        searchables.buildSearchableList();
        // tests with "real" searchables (deprecate, this should be a unit test)
        ArrayList<SearchableInfo> searchablesList = searchables.getSearchablesList();
        int count = searchablesList.size();
        assertTrue(count >= 1);         // this isn't really a unit test
        checkSearchables(searchablesList);
        ArrayList<SearchableInfo> global = searchables.getSearchablesInGlobalSearchList();
        checkSearchables(global);
    }

    /**
     * This round of tests confirms good operations with "zero" searchables found
     */
    public void testSearchablesListEmpty() {
        MyMockPackageManager mockPM = new MyMockPackageManager(mContext.getPackageManager());
        MyMockContext mockContext = new MyMockContext(mContext, mockPM);

        mockPM.setSearchablesMode(MyMockPackageManager.SEARCHABLES_MOCK_ZERO);
        Searchables searchables = new Searchables(mockContext, 0);
        searchables.buildSearchableList();
        ArrayList<SearchableInfo> searchablesList = searchables.getSearchablesList();
        assertNotNull(searchablesList);
        MoreAsserts.assertEmpty(searchablesList);
        ArrayList<SearchableInfo> global = searchables.getSearchablesInGlobalSearchList();
        MoreAsserts.assertEmpty(global);
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
            checkSearchable(si);
        }
    }
    
    private void checkSearchable(SearchableInfo si) {
        assertNotNull(si);
        assertTrue(si.getLabelId() != 0);        // This must be a useable string
        assertNotEmpty(si.getSearchActivity().getClassName());
        assertNotEmpty(si.getSearchActivity().getPackageName());
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
            assertEquals(ai.getKeyCode(), KeyEvent.KEYCODE_CALL);
            // one of these three fields must be non-null & non-empty
            boolean m1 = (ai.getQueryActionMsg() != null) && (ai.getQueryActionMsg().length() > 0);
            boolean m2 = (ai.getSuggestActionMsg() != null) && (ai.getSuggestActionMsg().length() > 0);
            boolean m3 = (ai.getSuggestActionMsgColumn() != null) && 
                            (ai.getSuggestActionMsgColumn().length() > 0);
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

        /**
         * Message broadcast.  Pass through for now.
         */
        @Override
        public void sendBroadcast(Intent intent) {
            mRealContext.sendBroadcast(intent);
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
            assertTrue(intent.getAction().equals(Intent.ACTION_SEARCH)
                    || intent.getAction().equals(Intent.ACTION_WEB_SEARCH)
                    || intent.getAction().equals(SearchManager.INTENT_ACTION_GLOBAL_SEARCH));
            switch (mSearchablesMode) {
            case SEARCHABLES_PASSTHROUGH:
                return mRealPackageManager.queryIntentActivities(intent, flags);
            case SEARCHABLES_MOCK_ZERO:
                return null;
            default:
                throw new UnsupportedOperationException();
            }
        }
        
        @Override
        public ResolveInfo resolveActivity(Intent intent, int flags) {
            assertNotNull(intent);
            assertTrue(intent.getAction().equals(Intent.ACTION_WEB_SEARCH)
                    || intent.getAction().equals(SearchManager.INTENT_ACTION_GLOBAL_SEARCH));
            switch (mSearchablesMode) {
            case SEARCHABLES_PASSTHROUGH:
                return mRealPackageManager.resolveActivity(intent, flags);
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

        /**
         * Get the activity information for a particular activity.
         *
         * @param name The name of the activity to find.
         * @param flags Additional option flags.
         *
         * @return ActivityInfo Information about the activity, if found, else null.
         */
        @Override
        public ActivityInfo getActivityInfo(ComponentName name, int flags)
                throws NameNotFoundException {
            assertNotNull(name);
            MoreAsserts.assertNotEqual(name, "");
            switch (mSearchablesMode) {
            case SEARCHABLES_PASSTHROUGH:
                return mRealPackageManager.getActivityInfo(name, flags);
            case SEARCHABLES_MOCK_ZERO:
                throw new NameNotFoundException();
            default:
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public int checkPermission(String permName, String pkgName) {
            assertNotNull(permName);
            assertNotNull(pkgName);
            switch (mSearchablesMode) {
                case SEARCHABLES_PASSTHROUGH:
                    return mRealPackageManager.checkPermission(permName, pkgName);
                case SEARCHABLES_MOCK_ZERO:
                    return PackageManager.PERMISSION_DENIED;
                default:
                    throw new UnsupportedOperationException();
                }
        }
    }
}

