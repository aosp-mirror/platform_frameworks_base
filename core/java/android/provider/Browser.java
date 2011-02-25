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

package android.provider;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.BrowserContract.Bookmarks;
import android.provider.BrowserContract.Combined;
import android.provider.BrowserContract.History;
import android.provider.BrowserContract.Searches;
import android.util.Log;
import android.webkit.WebIconDatabase;

public class Browser {
    private static final String LOGTAG = "browser";

    /**
     * A table containing both bookmarks and history items. The columns of the table are defined in
     * {@link BookmarkColumns}. Reading this table requires the
     * {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS} permission and writing to it
     * requires the {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS} permission.
     */
    public static final Uri BOOKMARKS_URI = Uri.parse("content://browser/bookmarks");

    /**
     * The name of extra data when starting Browser with ACTION_VIEW or
     * ACTION_SEARCH intent.
     * <p>
     * The value should be an integer between 0 and 1000. If not set or set to
     * 0, the Browser will use default. If set to 100, the Browser will start
     * with 100%.
     */
    public static final String INITIAL_ZOOM_LEVEL = "browser.initialZoomLevel";

    /**
     * The name of the extra data when starting the Browser from another
     * application.
     * <p>
     * The value is a unique identification string that will be used to
     * identify the calling application. The Browser will attempt to reuse the
     * same window each time the application launches the Browser with the same
     * identifier.
     */
    public static final String EXTRA_APPLICATION_ID = "com.android.browser.application_id";

    /**
     * The name of the extra data in the VIEW intent. The data are key/value
     * pairs in the format of Bundle. They will be sent in the HTTP request
     * headers for the provided url. The keys can't be the standard HTTP headers
     * as they are set by the WebView. The url's schema must be http(s).
     * <p>
     */
    public static final String EXTRA_HEADERS = "com.android.browser.headers";

    /* if you change column order you must also change indices
       below */
    public static final String[] HISTORY_PROJECTION = new String[] {
            BookmarkColumns._ID, // 0
            BookmarkColumns.URL, // 1
            BookmarkColumns.VISITS, // 2
            BookmarkColumns.DATE, // 3
            BookmarkColumns.BOOKMARK, // 4
            BookmarkColumns.TITLE, // 5
            BookmarkColumns.FAVICON, // 6
            BookmarkColumns.THUMBNAIL, // 7
            BookmarkColumns.TOUCH_ICON, // 8
            BookmarkColumns.USER_ENTERED, // 9
    };

    /* these indices dependent on HISTORY_PROJECTION */
    public static final int HISTORY_PROJECTION_ID_INDEX = 0;
    public static final int HISTORY_PROJECTION_URL_INDEX = 1;
    public static final int HISTORY_PROJECTION_VISITS_INDEX = 2;
    public static final int HISTORY_PROJECTION_DATE_INDEX = 3;
    public static final int HISTORY_PROJECTION_BOOKMARK_INDEX = 4;
    public static final int HISTORY_PROJECTION_TITLE_INDEX = 5;
    public static final int HISTORY_PROJECTION_FAVICON_INDEX = 6;
    /**
     * @hide
     */
    public static final int HISTORY_PROJECTION_THUMBNAIL_INDEX = 7;
    /**
     * @hide
     */
    public static final int HISTORY_PROJECTION_TOUCH_ICON_INDEX = 8;

    /* columns needed to determine whether to truncate history */
    public static final String[] TRUNCATE_HISTORY_PROJECTION = new String[] {
            BookmarkColumns._ID,
            BookmarkColumns.DATE,
    };

    public static final int TRUNCATE_HISTORY_PROJECTION_ID_INDEX = 0;

    /* truncate this many history items at a time */
    public static final int TRUNCATE_N_OLDEST = 5;

    /**
     * A table containing a log of browser searches. The columns of the table are defined in
     * {@link SearchColumns}. Reading this table requires the
     * {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS} permission and writing to it
     * requires the {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS} permission.
     */
    public static final Uri SEARCHES_URI = Uri.parse("content://browser/searches");

    /**
     * A projection of {@link #SEARCHES_URI} that contains {@link SearchColumns#_ID},
     * {@link SearchColumns#SEARCH}, and {@link SearchColumns#DATE}.
     */
    public static final String[] SEARCHES_PROJECTION = new String[] {
            // if you change column order you must also change indices below
            SearchColumns._ID, // 0
            SearchColumns.SEARCH, // 1
            SearchColumns.DATE, // 2
    };

    /* these indices dependent on SEARCHES_PROJECTION */
    public static final int SEARCHES_PROJECTION_SEARCH_INDEX = 1;
    public static final int SEARCHES_PROJECTION_DATE_INDEX = 2;

    /* Set a cap on the count of history items in the history/bookmark
       table, to prevent db and layout operations from dragging to a
       crawl.  Revisit this cap when/if db/layout performance
       improvements are made.  Note: this does not affect bookmark
       entries -- if the user wants more bookmarks than the cap, they
       get them. */
    private static final int MAX_HISTORY_COUNT = 250;

    /**
     *  Open an activity to save a bookmark. Launch with a title
     *  and/or a url, both of which can be edited by the user before saving.
     *
     *  @param c        Context used to launch the activity to add a bookmark.
     *  @param title    Title for the bookmark. Can be null or empty string.
     *  @param url      Url for the bookmark. Can be null or empty string.
     */
    public static final void saveBookmark(Context c, 
                                          String title, 
                                          String url) {
        Intent i = new Intent(Intent.ACTION_INSERT, Browser.BOOKMARKS_URI);
        i.putExtra("title", title);
        i.putExtra("url", url);
        c.startActivity(i);
    }

    /**
     * Boolean extra passed along with an Intent to a browser, specifying that
     * a new tab be created.  Overrides EXTRA_APPLICATION_ID; if both are set,
     * a new tab will be used, rather than using the same one.
     */
    public static final String EXTRA_CREATE_NEW_TAB = "create_new_tab";

    /**
     * Stores a Bitmap extra in an {@link Intent} representing the screenshot of
     * a page to share.  When receiving an {@link Intent#ACTION_SEND} from the
     * Browser, use this to access the screenshot.
     * @hide
     */
    public final static String EXTRA_SHARE_SCREENSHOT = "share_screenshot";

    /**
     * Stores a Bitmap extra in an {@link Intent} representing the favicon of a
     * page to share.  When receiving an {@link Intent#ACTION_SEND} from the
     * Browser, use this to access the favicon.
     * @hide
     */
    public final static String EXTRA_SHARE_FAVICON = "share_favicon";

    /**
     * Sends the given string using an Intent with {@link Intent#ACTION_SEND} and a mime type
     * of text/plain. The string is put into {@link Intent#EXTRA_TEXT}.
     *
     * @param context the context used to start the activity
     * @param string the string to send
     */
    public static final void sendString(Context context, String string) {
        sendString(context, string, context.getString(com.android.internal.R.string.sendText));
    }

    /**
     *  Find an application to handle the given string and, if found, invoke
     *  it with the given string as a parameter.
     *  @param c Context used to launch the new activity.
     *  @param stringToSend The string to be handled.
     *  @param chooserDialogTitle The title of the dialog that allows the user
     *  to select between multiple applications that are all capable of handling
     *  the string.
     *  @hide pending API council approval
     */
    public static final void sendString(Context c,
                                        String stringToSend,
                                        String chooserDialogTitle) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, stringToSend);

        try {
            Intent i = Intent.createChooser(send, chooserDialogTitle);
            // In case this is called from outside an Activity
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch(android.content.ActivityNotFoundException ex) {
            // if no app handles it, do nothing
        }
    }

    /**
     *  Return a cursor pointing to a list of all the bookmarks. The cursor will have a single
     *  column, {@link BookmarkColumns#URL}.
     *  <p>
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *
     *  @param cr   The ContentResolver used to access the database.
     */
    public static final Cursor getAllBookmarks(ContentResolver cr) throws 
            IllegalStateException {
        return cr.query(Bookmarks.CONTENT_URI,
                new String[] { Bookmarks.URL }, 
                Bookmarks.IS_FOLDER + " = 0", null, null);
    }

    /**
     *  Return a cursor pointing to a list of all visited site urls. The cursor will
     *  have a single column, {@link BookmarkColumns#URL}.
     *  <p>
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *
     *  @param cr   The ContentResolver used to access the database.
     */
    public static final Cursor getAllVisitedUrls(ContentResolver cr) throws
            IllegalStateException {
        return cr.query(Combined.CONTENT_URI,
                new String[] { Combined.URL }, null, null,
                Combined.DATE_CREATED + " ASC");
    }

    private static final void addOrUrlEquals(StringBuilder sb) {
        sb.append(" OR " + BookmarkColumns.URL + " = ");
    }

    private static final Cursor getVisitedLike(ContentResolver cr, String url) {
        boolean secure = false;
        String compareString = url;
        if (compareString.startsWith("http://")) {
            compareString = compareString.substring(7);
        } else if (compareString.startsWith("https://")) {
            compareString = compareString.substring(8);
            secure = true;
        }
        if (compareString.startsWith("www.")) {
            compareString = compareString.substring(4);
        }
        StringBuilder whereClause = null;
        if (secure) {
            whereClause = new StringBuilder(Bookmarks.URL + " = ");
            DatabaseUtils.appendEscapedSQLString(whereClause,
                    "https://" + compareString);
            addOrUrlEquals(whereClause);
            DatabaseUtils.appendEscapedSQLString(whereClause,
                    "https://www." + compareString);
        } else {
            whereClause = new StringBuilder(Bookmarks.URL + " = ");
            DatabaseUtils.appendEscapedSQLString(whereClause,
                    compareString);
            addOrUrlEquals(whereClause);
            String wwwString = "www." + compareString;
            DatabaseUtils.appendEscapedSQLString(whereClause,
                    wwwString);
            addOrUrlEquals(whereClause);
            DatabaseUtils.appendEscapedSQLString(whereClause,
                    "http://" + compareString);
            addOrUrlEquals(whereClause);
            DatabaseUtils.appendEscapedSQLString(whereClause,
                    "http://" + wwwString);
        }
        return cr.query(History.CONTENT_URI, new String[] { History._ID, History.VISITS },
                whereClause.toString(), null, null);
    }

    /**
     *  Update the visited history to acknowledge that a site has been
     *  visited.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *  Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     *  @param cr   The ContentResolver used to access the database.
     *  @param url  The site being visited.
     *  @param real If true, this is an actual visit, and should add to the
     *              number of visits.  If false, the user entered it manually.
     */
    public static final void updateVisitedHistory(ContentResolver cr,
                                                  String url, boolean real) {
        long now = System.currentTimeMillis();
        Cursor c = null;
        try {
            c = getVisitedLike(cr, url);
            /* We should only get one answer that is exactly the same. */
            if (c.moveToFirst()) {
                ContentValues values = new ContentValues();
                if (real) {
                    values.put(History.VISITS, c.getInt(1) + 1);
                } else {
                    values.put(History.USER_ENTERED, 1);
                }
                values.put(History.DATE_LAST_VISITED, now);
                cr.update(ContentUris.withAppendedId(History.CONTENT_URI, c.getLong(0)),
                        values, null, null);
            } else {
                truncateHistory(cr);
                ContentValues values = new ContentValues();
                int visits;
                int user_entered;
                if (real) {
                    visits = 1;
                    user_entered = 0;
                } else {
                    visits = 0;
                    user_entered = 1;
                }
                values.put(History.URL, url);
                values.put(History.VISITS, visits);
                values.put(History.DATE_LAST_VISITED, now);
                values.put(History.TITLE, url);
                values.put(History.DATE_CREATED, 0);
                values.put(History.USER_ENTERED, user_entered);
                cr.insert(History.CONTENT_URI, values);
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "updateVisitedHistory", e);
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     *  Returns all the URLs in the history.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *  @param cr   The ContentResolver used to access the database.
     *  @hide pending API council approval
     */
    public static final String[] getVisitedHistory(ContentResolver cr) {
        Cursor c = null;
        String[] str = null;
        try {
            String[] projection = new String[] {
                    History.URL,
            };
            c = cr.query(History.CONTENT_URI, projection, History.VISITS + " > 0", null, null);
            if (c == null) return new String[0];
            str = new String[c.getCount()];
            int i = 0;
            while (c.moveToNext()) {
                str[i] = c.getString(0);
                i++;
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "getVisitedHistory", e);
            str = new String[0];
        } finally {
            if (c != null) c.close();
        }
        return str;
    }

    /**
     * If there are more than MAX_HISTORY_COUNT non-bookmark history
     * items in the bookmark/history table, delete TRUNCATE_N_OLDEST
     * of them.  This is used to keep our history table to a
     * reasonable size.  Note: it does not prune bookmarks.  If the
     * user wants 1000 bookmarks, the user gets 1000 bookmarks.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *  Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     *
     * @param cr The ContentResolver used to access the database.
     */
    public static final void truncateHistory(ContentResolver cr) {
        // TODO make a single request to the provider to do this in a single transaction
        Cursor cursor = null;
        try {
            
            // Select non-bookmark history, ordered by date
            cursor = cr.query(History.CONTENT_URI,
                    new String[] { History._ID, History.URL, History.DATE_LAST_VISITED },
                    null, null, History.DATE_LAST_VISITED + " ASC");

            if (cursor.moveToFirst() && cursor.getCount() >= MAX_HISTORY_COUNT) {
                final WebIconDatabase iconDb = WebIconDatabase.getInstance();
                /* eliminate oldest history items */
                for (int i = 0; i < TRUNCATE_N_OLDEST; i++) {
                    cr.delete(ContentUris.withAppendedId(History.CONTENT_URI, cursor.getLong(0)),
                            null, null);
                    iconDb.releaseIconForPageUrl(cursor.getString(1));
                    if (!cursor.moveToNext()) break;
                }
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "truncateHistory", e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * Returns whether there is any history to clear.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     * @param cr   The ContentResolver used to access the database.
     * @return boolean  True if the history can be cleared.
     */
    public static final boolean canClearHistory(ContentResolver cr) {
        Cursor cursor = null;
        boolean ret = false;
        try {
            cursor = cr.query(History.CONTENT_URI,
                new String [] { History._ID, History.VISITS },
                null, null, null);
            ret = cursor.getCount() > 0;
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "canClearHistory", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return ret;
    }

    /**
     *  Delete all entries from the bookmarks/history table which are
     *  not bookmarks.  Also set all visited bookmarks to unvisited.
     *  Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     *  @param cr   The ContentResolver used to access the database.
     */
    public static final void clearHistory(ContentResolver cr) {
        deleteHistoryWhere(cr, null);
    }

    /**
     * Helper function to delete all history items and release the icons for them in the
     * {@link WebIconDatabase}.
     *
     * Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     * Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     *
     * @param cr   The ContentResolver used to access the database.
     * @param whereClause   String to limit the items affected.
     *                      null means all items.
     */
    private static final void deleteHistoryWhere(ContentResolver cr, String whereClause) {
        Cursor cursor = null;
        try {
            cursor = cr.query(History.CONTENT_URI, new String[] { History.URL }, whereClause,
                    null, null);
            if (cursor.moveToFirst()) {
                final WebIconDatabase iconDb = WebIconDatabase.getInstance();
                do {
                    // Delete favicons
                    // TODO don't release if the URL is bookmarked
                    iconDb.releaseIconForPageUrl(cursor.getString(0));
                } while (cursor.moveToNext());

                cr.delete(History.CONTENT_URI, whereClause, null);
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "deleteHistoryWhere", e);
            return;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * Delete all history items from begin to end.
     *  Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     * @param cr    The ContentResolver used to access the database.
     * @param begin First date to remove.  If -1, all dates before end.
     *              Inclusive.
     * @param end   Last date to remove. If -1, all dates after begin.
     *              Non-inclusive.
     */
    public static final void deleteHistoryTimeFrame(ContentResolver cr,
            long begin, long end) {
        String whereClause;
        String date = BookmarkColumns.DATE;
        if (-1 == begin) {
            if (-1 == end) {
                clearHistory(cr);
                return;
            }
            whereClause = date + " < " + Long.toString(end);
        } else if (-1 == end) {
            whereClause = date + " >= " + Long.toString(begin);
        } else {
            whereClause = date + " >= " + Long.toString(begin) + " AND " + date
                    + " < " + Long.toString(end);
        }
        deleteHistoryWhere(cr, whereClause);
    }

    /**
     * Remove a specific url from the history database.
     *  Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     * @param cr    The ContentResolver used to access the database.
     * @param url   url to remove.
     */
    public static final void deleteFromHistory(ContentResolver cr, 
                                               String url) {
        cr.delete(History.CONTENT_URI, History.URL + "=?", new String[] { url });
    }

    /**
     * Add a search string to the searches database.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *  Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     * @param cr   The ContentResolver used to access the database.
     * @param search    The string to add to the searches database.
     */
    public static final void addSearchUrl(ContentResolver cr, String search) {
        // The content provider will take care of updating existing searches instead of duplicating
        ContentValues values = new ContentValues();
        values.put(Searches.SEARCH, search);
        values.put(Searches.DATE, System.currentTimeMillis());
        cr.insert(Searches.CONTENT_URI, values);
    }

    /**
     * Remove all searches from the search database.
     *  Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     * @param cr   The ContentResolver used to access the database.
     */
    public static final void clearSearches(ContentResolver cr) {
        // FIXME: Should this clear the urls to which these searches lead?
        // (i.e. remove google.com/query= blah blah blah)
        try {
            cr.delete(Searches.CONTENT_URI, null, null);
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "clearSearches", e);
        }
    }
    
    /**
     *  Request all icons from the database.  This call must either be called
     *  in the main thread or have had Looper.prepare() invoked in the calling
     *  thread.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *  @param  cr The ContentResolver used to access the database.
     *  @param  where Clause to be used to limit the query from the database.
     *          Must be an allowable string to be passed into a database query.
     *  @param  listener IconListener that gets the icons once they are 
     *          retrieved.
     */
    public static final void requestAllIcons(ContentResolver cr, String where,
            WebIconDatabase.IconListener listener) {
        WebIconDatabase.getInstance().bulkRequestIconForPageUrl(cr, where, listener);
    }

    /**
     * Column definitions for the mixed bookmark and history items available
     * at {@link #BOOKMARKS_URI}.
     */
    public static class BookmarkColumns implements BaseColumns {
        /**
         * The URL of the bookmark or history item.
         * <p>Type: TEXT (URL)</p>
         */
        public static final String URL = "url";

        /**
         * The number of time the item has been visited.
         * <p>Type: NUMBER</p>
         */
        public static final String VISITS = "visits";

        /**
         * The date the item was last visited, in milliseconds since the epoch.
         * <p>Type: NUMBER (date in milliseconds since January 1, 1970)</p>
         */
        public static final String DATE = "date";

        /**
         * Flag indicating that an item is a bookmark. A value of 1 indicates a bookmark, a value
         * of 0 indicates a history item.
         * <p>Type: INTEGER (boolean)</p>
         */
        public static final String BOOKMARK = "bookmark";

        /**
         * The user visible title of the bookmark or history item.
         * <p>Type: TEXT</p>
         */
        public static final String TITLE = "title";

        /**
         * The date the item created, in milliseconds since the epoch.
         * <p>Type: NUMBER (date in milliseconds since January 1, 1970)</p>
         */
        public static final String CREATED = "created";

        /**
         * The favicon of the bookmark. Must decode via {@link BitmapFactory#decodeByteArray}.
         * <p>Type: BLOB (image)</p>
         */
        public static final String FAVICON = "favicon";

        /**
         * @hide
         */
        public static final String THUMBNAIL = "thumbnail";

        /**
         * @hide
         */
        public static final String TOUCH_ICON = "touch_icon";

        /**
         * @hide
         */
        public static final String USER_ENTERED = "user_entered";
    }

    /**
     * Column definitions for the search history table, available at {@link #SEARCHES_URI}.
     */
    public static class SearchColumns implements BaseColumns {
        /**
         * @deprecated Not used.
         */
        @Deprecated
        public static final String URL = "url";

        /**
         * The user entered search term.
         */
        public static final String SEARCH = "search";

        /**
         * The date the search was performed, in milliseconds since the epoch.
         * <p>Type: NUMBER (date in milliseconds since January 1, 1970)</p>
         */
        public static final String DATE = "date";
    }
}
