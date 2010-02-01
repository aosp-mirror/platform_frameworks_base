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

package android.app;

import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Simple test provider that runs in the local process.
 *
 * Used by {@link SearchManagerTest}.
 */
public class SuggestionProvider extends ContentProvider {
    private static final String TAG = "SuggestionProvider";

    private static final int SEARCH_SUGGESTIONS = 1;

    private static final UriMatcher sURLMatcher = new UriMatcher(
            UriMatcher.NO_MATCH);

    static {
        sURLMatcher.addURI("*", SearchManager.SUGGEST_URI_PATH_QUERY,
                SEARCH_SUGGESTIONS);
        sURLMatcher.addURI("*", SearchManager.SUGGEST_URI_PATH_QUERY + "/*",
                SEARCH_SUGGESTIONS);
    }

    private static final String[] COLUMNS = new String[] {
            "_id",
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_QUERY
    };

    public SuggestionProvider() {
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        int match = sURLMatcher.match(url);
        switch (match) {
            case SEARCH_SUGGESTIONS:
                String query = url.getLastPathSegment();
                MatrixCursor cursor = new MatrixCursor(COLUMNS);
                String[] suffixes = { "", "a", " foo", "XXXXXXXXXXXXXXXXX" };
                for (String suffix : suffixes) {
                    addRow(cursor, query + suffix);
                }
                return cursor;
            default:
                throw new IllegalArgumentException("Unknown URL: " + url);
        }
    }

    private void addRow(MatrixCursor cursor, String string) {
        long id = cursor.getCount();
        cursor.newRow().add(id).add(string).add(Intent.ACTION_SEARCH).add(string);
    }

    @Override
    public String getType(Uri url) {
        int match = sURLMatcher.match(url);
        switch (match) {
            case SEARCH_SUGGESTIONS:
                return SearchManager.SUGGEST_MIME_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URL: " + url);
        }
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        throw new UnsupportedOperationException("update not supported");
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        throw new UnsupportedOperationException("insert not supported");
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        throw new UnsupportedOperationException("delete not supported");
    }
}
