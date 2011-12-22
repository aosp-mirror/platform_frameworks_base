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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SearchRecentSuggestionsProvider;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.util.concurrent.Semaphore;

/**
 * This is a utility class providing access to
 * {@link android.content.SearchRecentSuggestionsProvider}.
 *
 * <p>Unlike some utility classes, this one must be instantiated and properly initialized, so that
 * it can be configured to operate with the search suggestions provider that you have created.
 *
 * <p>Typically, you will do this in your searchable activity, each time you receive an incoming
 * {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} Intent.  The code to record each
 * incoming query is as follows:
 * <pre class="prettyprint">
 *      SearchSuggestions suggestions = new SearchSuggestions(this,
 *              MySuggestionsProvider.AUTHORITY, MySuggestionsProvider.MODE);
 *      suggestions.saveRecentQuery(queryString, null);
 * </pre>
 *
 * <p>For a working example, see SearchSuggestionSampleProvider and SearchQueryResults in
 * samples/ApiDemos/app.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about using search suggestions in your application, read the
 * <a href="{@docRoot}guide/topics/search/adding-recent-query-suggestions.html">Adding Recent Query
 * Suggestions</a> developer guide.</p>
 * </div>
 */
public class SearchRecentSuggestions {
    // debugging support
    private static final String LOG_TAG = "SearchSuggestions";

    // This is a superset of all possible column names (need not all be in table)
    private static class SuggestionColumns implements BaseColumns {
        public static final String DISPLAY1 = "display1";
        public static final String DISPLAY2 = "display2";
        public static final String QUERY = "query";
        public static final String DATE = "date";
    }

    /* if you change column order you must also change indices below */
    /**
     * This is the database projection that can be used to view saved queries, when
     * configured for one-line operation.
     */
    public static final String[] QUERIES_PROJECTION_1LINE = new String[] {
        SuggestionColumns._ID,
        SuggestionColumns.DATE,
        SuggestionColumns.QUERY,
        SuggestionColumns.DISPLAY1,
    };

    /* if you change column order you must also change indices below */
    /**
     * This is the database projection that can be used to view saved queries, when
     * configured for two-line operation.
     */
    public static final String[] QUERIES_PROJECTION_2LINE = new String[] {
        SuggestionColumns._ID,
        SuggestionColumns.DATE,
        SuggestionColumns.QUERY,
        SuggestionColumns.DISPLAY1,
        SuggestionColumns.DISPLAY2,
    };

    /* these indices depend on QUERIES_PROJECTION_xxx */
    /** Index into the provided query projections.  For use with Cursor.update methods. */
    public static final int QUERIES_PROJECTION_DATE_INDEX = 1;
    /** Index into the provided query projections.  For use with Cursor.update methods. */
    public static final int QUERIES_PROJECTION_QUERY_INDEX = 2;
    /** Index into the provided query projections.  For use with Cursor.update methods. */
    public static final int QUERIES_PROJECTION_DISPLAY1_INDEX = 3;
    /** Index into the provided query projections.  For use with Cursor.update methods. */
    public static final int QUERIES_PROJECTION_DISPLAY2_INDEX = 4;  // only when 2line active

    /*
     * Set a cap on the count of items in the suggestions table, to
     * prevent db and layout operations from dragging to a crawl. Revisit this
     * cap when/if db/layout performance improvements are made.
     */
    private static final int MAX_HISTORY_COUNT = 250;

    // client-provided configuration values
    private final Context mContext;
    private final String mAuthority;
    private final boolean mTwoLineDisplay;
    private final Uri mSuggestionsUri;

    /** Released once per completion of async write.  Used for tests. */
    private static final Semaphore sWritesInProgress = new Semaphore(0);

    /**
     * Although provider utility classes are typically static, this one must be constructed
     * because it needs to be initialized using the same values that you provided in your
     * {@link android.content.SearchRecentSuggestionsProvider}.
     *
     * @param authority This must match the authority that you've declared in your manifest.
     * @param mode You can use mode flags here to determine certain functional aspects of your
     * database.  Note, this value should not change from run to run, because when it does change,
     * your suggestions database may be wiped.
     *
     * @see android.content.SearchRecentSuggestionsProvider
     * @see android.content.SearchRecentSuggestionsProvider#setupSuggestions
     */
    public SearchRecentSuggestions(Context context, String authority, int mode) {
        if (TextUtils.isEmpty(authority) ||
                ((mode & SearchRecentSuggestionsProvider.DATABASE_MODE_QUERIES) == 0)) {
            throw new IllegalArgumentException();
        }
        // unpack mode flags
        mTwoLineDisplay = (0 != (mode & SearchRecentSuggestionsProvider.DATABASE_MODE_2LINES));

        // saved values
        mContext = context;
        mAuthority = new String(authority);

        // derived values
        mSuggestionsUri = Uri.parse("content://" + mAuthority + "/suggestions");
    }

    /**
     * Add a query to the recent queries list.  Returns immediately, performing the save
     * in the background.
     *
     * @param queryString The string as typed by the user.  This string will be displayed as
     * the suggestion, and if the user clicks on the suggestion, this string will be sent to your
     * searchable activity (as a new search query).
     * @param line2 If you have configured your recent suggestions provider with
     * {@link android.content.SearchRecentSuggestionsProvider#DATABASE_MODE_2LINES}, you can
     * pass a second line of text here.  It will be shown in a smaller font, below the primary
     * suggestion.  When typing, matches in either line of text will be displayed in the list.
     * If you did not configure two-line mode, or if a given suggestion does not have any
     * additional text to display, you can pass null here.
     */
    public void saveRecentQuery(final String queryString, final String line2) {
        if (TextUtils.isEmpty(queryString)) {
            return;
        }
        if (!mTwoLineDisplay && !TextUtils.isEmpty(line2)) {
            throw new IllegalArgumentException();
        }

        new Thread("saveRecentQuery") {
            @Override
            public void run() {
                saveRecentQueryBlocking(queryString, line2);
                sWritesInProgress.release();
            }
        }.start();
    }

    // Visible for testing.
    void waitForSave() {
        // Acquire writes semaphore until there is nothing available.
        // This is to clean up after any previous callers to saveRecentQuery
        // who did not also call waitForSave().
        do {
            sWritesInProgress.acquireUninterruptibly();
        } while (sWritesInProgress.availablePermits() > 0);
    }

    private void saveRecentQueryBlocking(String queryString, String line2) {
        ContentResolver cr = mContext.getContentResolver();
        long now = System.currentTimeMillis();

        // Use content resolver (not cursor) to insert/update this query
        try {
            ContentValues values = new ContentValues();
            values.put(SuggestionColumns.DISPLAY1, queryString);
            if (mTwoLineDisplay) {
                values.put(SuggestionColumns.DISPLAY2, line2);
            }
            values.put(SuggestionColumns.QUERY, queryString);
            values.put(SuggestionColumns.DATE, now);
            cr.insert(mSuggestionsUri, values);
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "saveRecentQuery", e);
        }

        // Shorten the list (if it has become too long)
        truncateHistory(cr, MAX_HISTORY_COUNT);
    }

    /**
     * Completely delete the history.  Use this call to implement a "clear history" UI.
     *
     * Any application that implements search suggestions based on previous actions (such as
     * recent queries, page/items viewed, etc.) should provide a way for the user to clear the
     * history.  This gives the user a measure of privacy, if they do not wish for their recent
     * searches to be replayed by other users of the device (via suggestions).
     */
    public void clearHistory() {
        ContentResolver cr = mContext.getContentResolver();
        truncateHistory(cr, 0);
    }

    /**
     * Reduces the length of the history table, to prevent it from growing too large.
     *
     * @param cr Convenience copy of the content resolver.
     * @param maxEntries Max entries to leave in the table. 0 means remove all entries.
     */
    protected void truncateHistory(ContentResolver cr, int maxEntries) {
        if (maxEntries < 0) {
            throw new IllegalArgumentException();
        }

        try {
            // null means "delete all".  otherwise "delete but leave n newest"
            String selection = null;
            if (maxEntries > 0) {
                selection = "_id IN " +
                        "(SELECT _id FROM suggestions" +
                        " ORDER BY " + SuggestionColumns.DATE + " DESC" +
                        " LIMIT -1 OFFSET " + String.valueOf(maxEntries) + ")";
            }
            cr.delete(mSuggestionsUri, selection, null);
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "truncateHistory", e);
        }
    }
}
