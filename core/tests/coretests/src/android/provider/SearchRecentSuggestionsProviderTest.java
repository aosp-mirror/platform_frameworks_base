/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.provider;

import android.app.SearchManager;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.test.ProviderTestCase2;

import androidx.test.filters.MediumTest;
import androidx.test.filters.Suppress;

/**
 * ProviderTestCase that performs unit tests of SearchRecentSuggestionsProvider.
 *
 * You can run this test in isolation via the commands:
 *
 * $ (cd tests/FrameworkTests/ && mm) && adb sync
 * $ adb shell am instrument -w \
 *     -e class android.provider.SearchRecentSuggestionsProviderTest
 *     com.android.frameworktest.tests/android.test.InstrumentationTestRunner
 */
@MediumTest
public class SearchRecentSuggestionsProviderTest extends ProviderTestCase2<TestProvider> {

    // Elements prepared by setUp()
    SearchRecentSuggestions mSearchHelper;

    public SearchRecentSuggestionsProviderTest() {
        super(TestProvider.class, TestProvider.AUTHORITY);
    }

    /**
     * During setup, grab a helper for DB access
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Use the recent suggestions helper.  As long as we pass in our isolated context,
        // it should correctly access the provider under test.
        mSearchHelper = new SearchRecentSuggestions(getMockContext(),
                TestProvider.AUTHORITY, TestProvider.MODE);

        // test for empty database at setup time
        checkOpenCursorCount(0);
    }

    /**
     * Simple test to see if we can instantiate the whole mess.
     */
    public void testSetup() {
        assertTrue(true);
    }

    /**
     * Simple test to see if we can write and read back a single query
     */
    @Suppress  // Failing.
    public void testOneQuery() {
        final String TEST_LINE1 = "test line 1";
        final String TEST_LINE2 = "test line 2";
        mSearchHelper.saveRecentQuery(TEST_LINE1, TEST_LINE2);
        mSearchHelper.waitForSave();

        // make sure that there are is exactly one entry returned by a non-filtering cursor
        checkOpenCursorCount(1);

        // test non-filtering cursor for correct entry
        checkResultCounts(null, 1, 1, TEST_LINE1, TEST_LINE2);

        // test filtering cursor for correct entry
        checkResultCounts(TEST_LINE1, 1, 1, TEST_LINE1, TEST_LINE2);
        checkResultCounts(TEST_LINE2, 1, 1, TEST_LINE1, TEST_LINE2);

        // test that a different filter returns zero results
        checkResultCounts("bad filter", 0, 0, null, null);
    }

    /**
     * Simple test to see if we can write and read back a diverse set of queries
     */
    @Suppress  // Failing.
    public void testMixedQueries() {
        // we'll make 10 queries named "query x" and 10 queries named "test x"
        final String TEST_GROUP_1 = "query ";
        final String TEST_GROUP_2 = "test ";
        final String TEST_LINE2 = "line2 ";
        final int GROUP_COUNT = 10;

        writeEntries(GROUP_COUNT, TEST_GROUP_1, TEST_LINE2);
        writeEntries(GROUP_COUNT, TEST_GROUP_2, TEST_LINE2);

        // check counts
        checkOpenCursorCount(2 * GROUP_COUNT);

        // check that each query returns the right result counts
        checkResultCounts(TEST_GROUP_1, GROUP_COUNT, GROUP_COUNT, null, null);
        checkResultCounts(TEST_GROUP_2, GROUP_COUNT, GROUP_COUNT, null, null);
        checkResultCounts(TEST_LINE2, 2 * GROUP_COUNT, 2 * GROUP_COUNT, null, null);
    }

    /**
     * Test that the reordering code works properly.  The most recently injected queries
     * should replace existing queries and be sorted to the top of the list.
     */
    @Suppress  // Failing.
    public void testReordering() {
        // first we'll make 10 queries named "group1 x"
        final int GROUP_1_COUNT = 10;
        final String GROUP_1_QUERY = "group1 ";
        final String GROUP_1_LINE2 = "line2 ";
        writeEntries(GROUP_1_COUNT, GROUP_1_QUERY, GROUP_1_LINE2);

        // check totals
        checkOpenCursorCount(GROUP_1_COUNT);

        // guarantee that group 1 has older timestamps
        writeDelay();

        // next we'll add 10 entries named "group2 x"
        final int GROUP_2_COUNT = 10;
        final String GROUP_2_QUERY = "group2 ";
        final String GROUP_2_LINE2 = "line2 ";
        writeEntries(GROUP_2_COUNT, GROUP_2_QUERY, GROUP_2_LINE2);

        // check totals
        checkOpenCursorCount(GROUP_1_COUNT + GROUP_2_COUNT);

        // guarantee that group 2 has older timestamps
        writeDelay();

        // now refresh 5 of the 10 from group 1
        // change line2 so they can be more easily tracked
        final int GROUP_3_COUNT = 5;
        final String GROUP_3_QUERY = GROUP_1_QUERY;
        final String GROUP_3_LINE2 = "refreshed ";
        writeEntries(GROUP_3_COUNT, GROUP_3_QUERY, GROUP_3_LINE2);

        // confirm that the total didn't change (those were replacements, not adds)
        checkOpenCursorCount(GROUP_1_COUNT + GROUP_2_COUNT);

        // confirm that the are now 5 in group 1, 10 in group 2, and 5 in group 3
        int newGroup1Count = GROUP_1_COUNT - GROUP_3_COUNT;
        checkResultCounts(GROUP_1_QUERY, newGroup1Count, newGroup1Count, null, GROUP_1_LINE2);
        checkResultCounts(GROUP_2_QUERY, GROUP_2_COUNT, GROUP_2_COUNT, null, null);
        checkResultCounts(GROUP_3_QUERY, GROUP_3_COUNT, GROUP_3_COUNT, null, GROUP_3_LINE2);

        // finally, spot check that the right groups are in the right places
        // the ordering should be group 3 (newest), group 2, group 1 (oldest)
        Cursor c = getQueryCursor(null);
        int colQuery = c.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_QUERY);
        int colDisplay1 = c.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1);
        int colDisplay2 = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_2);

        // Spot check the first and last expected entries of group 3
        c.moveToPosition(0);
        assertTrue("group 3 did not properly reorder to head of list",
                checkRow(c, colQuery, colDisplay1, colDisplay2, GROUP_3_QUERY, GROUP_3_LINE2));
        c.move(GROUP_3_COUNT - 1);
        assertTrue("group 3 did not properly reorder to head of list",
                checkRow(c, colQuery, colDisplay1, colDisplay2, GROUP_3_QUERY, GROUP_3_LINE2));

        // Spot check the first and last expected entries of group 2
        c.move(1);
        assertTrue("group 2 not in expected position after reordering",
                checkRow(c, colQuery, colDisplay1, colDisplay2, GROUP_2_QUERY, GROUP_2_LINE2));
        c.move(GROUP_2_COUNT - 1);
        assertTrue("group 2 not in expected position after reordering",
                checkRow(c, colQuery, colDisplay1, colDisplay2, GROUP_2_QUERY, GROUP_2_LINE2));

        // Spot check the first and last expected entries of group 1
        c.move(1);
        assertTrue("group 1 not in expected position after reordering",
                checkRow(c, colQuery, colDisplay1, colDisplay2, GROUP_1_QUERY, GROUP_1_LINE2));
        c.move(newGroup1Count - 1);
        assertTrue("group 1 not in expected position after reordering",
                checkRow(c, colQuery, colDisplay1, colDisplay2, GROUP_1_QUERY, GROUP_1_LINE2));

        c.close();
    }

    /**
     * Test that the pruning code works properly,  The database should not go beyond 250 entries,
     * and the oldest entries should always be discarded first.
     *
     * TODO:  This is a slow test, do we have annotation for that?
     */
    @Suppress  // Failing.
    public void testPruning() {
        // first we'll make 50 queries named "group1 x"
        final int GROUP_1_COUNT = 50;
        final String GROUP_1_QUERY = "group1 ";
        final String GROUP_1_LINE2 = "line2 ";
        writeEntries(GROUP_1_COUNT, GROUP_1_QUERY, GROUP_1_LINE2);

        // check totals
        checkOpenCursorCount(GROUP_1_COUNT);

        // guarantee that group 1 has older timestamps (and will be pruned first)
        writeDelay();

        // next we'll add 200 entries named "group2 x"
        final int GROUP_2_COUNT = 200;
        final String GROUP_2_QUERY = "group2 ";
        final String GROUP_2_LINE2 = "line2 ";
        writeEntries(GROUP_2_COUNT, GROUP_2_QUERY, GROUP_2_LINE2);

        // check totals
        checkOpenCursorCount(GROUP_1_COUNT + GROUP_2_COUNT);

        // Finally we'll add 10 more entries named "group3 x"
        // These should push out 10 entries from group 1
        final int GROUP_3_COUNT = 10;
        final String GROUP_3_QUERY = "group3 ";
        final String GROUP_3_LINE2 = "line2 ";
        writeEntries(GROUP_3_COUNT, GROUP_3_QUERY, GROUP_3_LINE2);

        // total should still be 250
        checkOpenCursorCount(GROUP_1_COUNT + GROUP_2_COUNT);

        // there should be 40 group 1, 200 group 2, and 10 group 3
        int group1NewCount = GROUP_1_COUNT-GROUP_3_COUNT;
        checkResultCounts(GROUP_1_QUERY, group1NewCount, group1NewCount, null, null);
        checkResultCounts(GROUP_2_QUERY, GROUP_2_COUNT, GROUP_2_COUNT, null, null);
        checkResultCounts(GROUP_3_QUERY, GROUP_3_COUNT, GROUP_3_COUNT, null, null);
    }

    /**
     * Test that the clear history code works properly.
     */
    @Suppress  // Failing.
    public void testClear() {
        // first we'll make 10 queries named "group1 x"
        final int GROUP_1_COUNT = 10;
        final String GROUP_1_QUERY = "group1 ";
        final String GROUP_1_LINE2 = "line2 ";
        writeEntries(GROUP_1_COUNT, GROUP_1_QUERY, GROUP_1_LINE2);

        // next we'll add 10 entries named "group2 x"
        final int GROUP_2_COUNT = 10;
        final String GROUP_2_QUERY = "group2 ";
        final String GROUP_2_LINE2 = "line2 ";
        writeEntries(GROUP_2_COUNT, GROUP_2_QUERY, GROUP_2_LINE2);

        // check totals
        checkOpenCursorCount(GROUP_1_COUNT + GROUP_2_COUNT);

        // delete all
        mSearchHelper.clearHistory();

        // check totals
        checkOpenCursorCount(0);
    }

    /**
     * Write a sequence of queries into the database, with incrementing counters in the strings.
     */
    private void writeEntries(int groupCount, String line1Base, String line2Base) {
        for (int i = 0; i < groupCount; i++) {
            final String line1 = line1Base + i;
            final String line2 = line2Base + i;
            mSearchHelper.saveRecentQuery(line1, line2);
            mSearchHelper.waitForSave();
        }
    }

    /**
     * A very slight delay to ensure that successive groups of queries in the DB cannot
     * have the same timestamp.
     */
    private void writeDelay() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            fail("Interrupted sleep.");
        }
    }

    /**
     * Access an "open" (no selection) suggestions cursor and confirm that it has the specified
     * number of entries.
     *
     * @param expectCount The expected number of entries returned by the cursor.
     */
    private void checkOpenCursorCount(int expectCount) {
        Cursor c = getQueryCursor(null);
        assertEquals(expectCount, c.getCount());
        c.close();
    }

    /**
     * Set up a filter cursor and then scan it for specific results.
     *
     * @param queryString The query string to apply.
     * @param minRows The minimum number of matching rows that must be found.
     * @param maxRows The maximum number of matching rows that must be found.
     * @param matchDisplay1 If non-null, must match DISPLAY1 column if row counts as match
     * @param matchDisplay2 If non-null, must match DISPLAY2 column if row counts as match
     */
    private void checkResultCounts(String queryString, int minRows, int maxRows,
            String matchDisplay1, String matchDisplay2) {

        // get the cursor and apply sanity checks to result
        Cursor c = getQueryCursor(queryString);
        assertNotNull(c);
        assertTrue("Insufficient rows in filtered cursor", c.getCount() >= minRows);

        // look for minimum set of columns (note, display2 is optional)
        int colQuery = c.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_QUERY);
        int colDisplay1 = c.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1);
        int colDisplay2 = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_2);

        // now loop through rows and look for desired rows
        int foundRows = 0;
        c.moveToFirst();
        while (!c.isAfterLast()) {
            if (checkRow(c, colQuery, colDisplay1, colDisplay2, matchDisplay1, matchDisplay2)) {
                foundRows++;
            }
            c.moveToNext();
        }

        // now check the results
        assertTrue(minRows <= foundRows);
        assertTrue(foundRows <= maxRows);

        c.close();
    }

    /**
     * Check a single row for equality with target strings.
     *
     * @param c The cursor, already moved to the row
     * @param colQuery The column # containing the query.  The query must match display1.
     * @param colDisp1 The column # containing display line 1.
     * @param colDisp2 The column # containing display line 2, or -1 if no column
     * @param matchDisplay1 If non-null, this must be the prefix of display1
     * @param matchDisplay2 If non-null, this must be the prefix of display2
     * @return Returns true if the row is a "match"
     */
    private boolean checkRow(Cursor c, int colQuery, int colDisp1, int colDisp2,
            String matchDisplay1, String matchDisplay2) {
        // Get the data from the row
        String query = c.getString(colQuery);
        String display1 = c.getString(colDisp1);
        String display2 = (colDisp2 >= 0) ? c.getString(colDisp2) : null;

        assertEquals(query, display1);
        boolean result = true;
        if (matchDisplay1 != null) {
            result = result && (display1 != null) && display1.startsWith(matchDisplay1);
        }
        if (matchDisplay2 != null) {
            result = result && (display2 != null) && display2.startsWith(matchDisplay2);
        }

        return result;
    }

    /**
     * Generate a query cursor in a manner like the search dialog would.
     *
     * @param queryString The search string, or, null for "all"
     * @return Returns a cursor, or null if there was some problem.  Be sure to close the cursor
     * when done with it.
     */
    private Cursor getQueryCursor(String queryString) {
        ContentResolver cr = getMockContext().getContentResolver();

        String uriStr = "content://" + TestProvider.AUTHORITY +
        '/' + SearchManager.SUGGEST_URI_PATH_QUERY;
        Uri contentUri = Uri.parse(uriStr);

        String[] selArgs = new String[] {queryString};

        Cursor c = cr.query(contentUri, null, null, selArgs, null);

        assertNotNull(c);
        return c;
    }
}
