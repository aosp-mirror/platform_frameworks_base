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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import android.app.SearchableInfo;
import android.app.SearchableInfo.ActionKeyInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.test.MoreAsserts;
import android.view.KeyEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SearchablesTest {
    @Mock protected PackageManagerInternal mPackageManagerInternal;

    private Context mContext;

    @Before
    public final void setUp() {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternal);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After
    public final void tearDown() {
        Mockito.framework().clearInlineMocks();
    }

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
    @Test
    public void testNonSearchable() {
        // test basic array & hashmap
        Searchables searchables = new Searchables(mContext, 0);
        searchables.updateSearchableListIfNeeded();

        // confirm that we return null for non-searchy activities
        ComponentName nonActivity = new ComponentName("com.android.frameworks.servicestests",
                "com.android.frameworks.servicestests.activity.NO_SEARCH_ACTIVITY");
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
    @Test
    public void testSearchablesListReal() {
        doReturn(true).when(mPackageManagerInternal).canAccessComponent(anyInt(), any(), anyInt());

        Searchables searchables = new Searchables(mContext, 0);
        searchables.updateSearchableListIfNeeded();
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
    @Test
    public void testSearchablesListEmpty() {
        doReturn(false).when(mPackageManagerInternal).canAccessComponent(anyInt(), any(), anyInt());

        Searchables searchables = new Searchables(mContext, 0);
        searchables.updateSearchableListIfNeeded();
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
}

