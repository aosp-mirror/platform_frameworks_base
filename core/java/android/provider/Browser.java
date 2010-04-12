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
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import android.webkit.WebIconDatabase;

import java.util.Date;

public class Browser {
    private static final String LOGTAG = "browser";
    public static final Uri BOOKMARKS_URI =
        Uri.parse("content://browser/bookmarks");

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
     * indentify the calling application. The Browser will attempt to reuse the
     * same window each time the application launches the Browser with the same
     * identifier.
     */
    public static final String EXTRA_APPLICATION_ID =
        "com.android.browser.application_id";

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
        BookmarkColumns._ID, BookmarkColumns.URL, BookmarkColumns.VISITS,
        BookmarkColumns.DATE, BookmarkColumns.BOOKMARK, BookmarkColumns.TITLE,
        BookmarkColumns.FAVICON, BookmarkColumns.THUMBNAIL,
        BookmarkColumns.TOUCH_ICON, BookmarkColumns.USER_ENTERED };

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
        BookmarkColumns._ID, BookmarkColumns.DATE, };
    public static final int TRUNCATE_HISTORY_PROJECTION_ID_INDEX = 0;

    /* truncate this many history items at a time */
    public static final int TRUNCATE_N_OLDEST = 5;

    public static final Uri SEARCHES_URI =
        Uri.parse("content://browser/searches");

    /* if you change column order you must also change indices
       below */
    public static final String[] SEARCHES_PROJECTION = new String[] {
        SearchColumns._ID, SearchColumns.SEARCH, SearchColumns.DATE };

    /* these indices dependent on SEARCHES_PROJECTION */
    public static final int SEARCHES_PROJECTION_SEARCH_INDEX = 1;
    public static final int SEARCHES_PROJECTION_DATE_INDEX = 2;

    private static final String SEARCHES_WHERE_CLAUSE = "search = ?";

    /* Set a cap on the count of history items in the history/bookmark
       table, to prevent db and layout operations from dragging to a
       crawl.  Revisit this cap when/if db/layout performance
       improvements are made.  Note: this does not affect bookmark
       entries -- if the user wants more bookmarks than the cap, they
       get them. */
    private static final int MAX_HISTORY_COUNT = 250;

    /**
     *  Open the AddBookmark activity to save a bookmark.  Launch with
     *  and/or url, which can be edited by the user before saving.
     *  @param c        Context used to launch the AddBookmark activity.
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

    public static final void sendString(Context c, String s) {
        sendString(c, s, c.getString(com.android.internal.R.string.sendText));
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
            c.startActivity(Intent.createChooser(send, chooserDialogTitle));
        } catch(android.content.ActivityNotFoundException ex) {
            // if no app handles it, do nothing
        }
    }

    /**
     *  Return a cursor pointing to a list of all the bookmarks.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *  @param cr   The ContentResolver used to access the database.
     */
    public static final Cursor getAllBookmarks(ContentResolver cr) throws 
            IllegalStateException {
        return cr.query(BOOKMARKS_URI,
                new String[] { BookmarkColumns.URL }, 
                "bookmark = 1", null, null);
    }

    /**
     *  Return a cursor pointing to a list of all visited site urls.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *  @param cr   The ContentResolver used to access the database.
     */
    public static final Cursor getAllVisitedUrls(ContentResolver cr) throws
            IllegalStateException {
        return cr.query(BOOKMARKS_URI,
                new String[] { BookmarkColumns.URL }, null, null, null);
    }

    private static final void addOrUrlEquals(StringBuilder sb) {
        sb.append(" OR " + BookmarkColumns.URL + " = ");
    }

    /**
     *  Return a Cursor with all history/bookmarks that are similar to url,
     *  where similar means 'http(s)://' and 'www.' are optional, but the rest
     *  of the url is the same.
     *  @param cr   The ContentResolver used to access the database.
     *  @param url  The url to compare to.
     *  @hide
     */
    public static final Cursor getVisitedLike(ContentResolver cr, String url) {
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
            whereClause = new StringBuilder(BookmarkColumns.URL + " = ");
            DatabaseUtils.appendEscapedSQLString(whereClause,
                    "https://" + compareString);
            addOrUrlEquals(whereClause);
            DatabaseUtils.appendEscapedSQLString(whereClause,
                    "https://www." + compareString);
        } else {
            whereClause = new StringBuilder(BookmarkColumns.URL + " = ");
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
        return cr.query(BOOKMARKS_URI, HISTORY_PROJECTION,
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
        long now = new Date().getTime();
        Cursor c = null;
        try {
            c = getVisitedLike(cr, url);
            /* We should only get one answer that is exactly the same. */
            if (c.moveToFirst()) {
                ContentValues map = new ContentValues();
                if (real) {
                    map.put(BookmarkColumns.VISITS, c
                            .getInt(HISTORY_PROJECTION_VISITS_INDEX) + 1);
                } else {
                    map.put(BookmarkColumns.USER_ENTERED, 1);
                }
                map.put(BookmarkColumns.DATE, now);
                String[] projection = new String[]
                        { Integer.valueOf(c.getInt(0)).toString() };
                cr.update(BOOKMARKS_URI, map, "_id = ?", projection);
            } else {
                truncateHistory(cr);
                ContentValues map = new ContentValues();
                int visits;
                int user_entered;
                if (real) {
                    visits = 1;
                    user_entered = 0;
                } else {
                    visits = 0;
                    user_entered = 1;
                }
                map.put(BookmarkColumns.URL, url);
                map.put(BookmarkColumns.VISITS, visits);
                map.put(BookmarkColumns.DATE, now);
                map.put(BookmarkColumns.BOOKMARK, 0);
                map.put(BookmarkColumns.TITLE, url);
                map.put(BookmarkColumns.CREATED, 0);
                map.put(BookmarkColumns.USER_ENTERED, user_entered);
                cr.insert(BOOKMARKS_URI, map);
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
                "url"
            };
            c = cr.query(BOOKMARKS_URI, projection, "visits > 0", null,
                    null);
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
        Cursor c = null;
        try {
            // Select non-bookmark history, ordered by date
            c = cr.query(
                    BOOKMARKS_URI,
                    TRUNCATE_HISTORY_PROJECTION,
                    "bookmark = 0",
                    null,
                    BookmarkColumns.DATE);
            // Log.v(LOGTAG, "history count " + c.count());
            if (c.moveToFirst() && c.getCount() >= MAX_HISTORY_COUNT) {
                /* eliminate oldest history items */
                for (int i = 0; i < TRUNCATE_N_OLDEST; i++) {
                    // Log.v(LOGTAG, "truncate history " +
                    // c.getInt(TRUNCATE_HISTORY_PROJECTION_ID_INDEX));
                    cr.delete(BOOKMARKS_URI, "_id = " +
                            c.getInt(TRUNCATE_HISTORY_PROJECTION_ID_INDEX),
                            null);
                    if (!c.moveToNext()) break;
                }
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "truncateHistory", e);
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Returns whether there is any history to clear.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     * @param cr   The ContentResolver used to access the database.
     * @return boolean  True if the history can be cleared.
     */
    public static final boolean canClearHistory(ContentResolver cr) {
        Cursor c = null;
        boolean ret = false;
        try {
            c = cr.query(
                BOOKMARKS_URI,
                new String [] { BookmarkColumns._ID, 
                                BookmarkColumns.BOOKMARK,
                                BookmarkColumns.VISITS },
                "bookmark = 0 OR visits > 0", 
                null,
                null
                );
            ret = c.moveToFirst();
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "canClearHistory", e);
        } finally {
            if (c != null) c.close();
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
     * Helper function to delete all history items and revert all
     * bookmarks to zero visits which meet the criteria provided.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *  Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     * @param cr   The ContentResolver used to access the database.
     * @param whereClause   String to limit the items affected.
     *                      null means all items.
     */
    private static final void deleteHistoryWhere(ContentResolver cr,
            String whereClause) {
        Cursor c = null;
        try {
            c = cr.query(BOOKMARKS_URI,
                HISTORY_PROJECTION,
                whereClause,
                null,
                null);
            if (c.moveToFirst()) {
                final WebIconDatabase iconDb = WebIconDatabase.getInstance();
                /* Delete favicons, and revert bookmarks which have been visited
                 * to simply bookmarks.
                 */
                StringBuffer sb = new StringBuffer();
                boolean firstTime = true;
                do {
                    String url = c.getString(HISTORY_PROJECTION_URL_INDEX);
                    boolean isBookmark =
                        c.getInt(HISTORY_PROJECTION_BOOKMARK_INDEX) == 1;
                    if (isBookmark) {
                        if (firstTime) {
                            firstTime = false;
                        } else {
                            sb.append(" OR ");
                        }
                        sb.append("( _id = ");
                        sb.append(c.getInt(0));
                        sb.append(" )");
                    } else {
                        iconDb.releaseIconForPageUrl(url);
                    }
                } while (c.moveToNext());

                if (!firstTime) {
                    ContentValues map = new ContentValues();
                    map.put(BookmarkColumns.VISITS, 0);
                    map.put(BookmarkColumns.DATE, 0);
                    /* FIXME: Should I also remove the title? */
                    cr.update(BOOKMARKS_URI, map, sb.toString(), null);
                }

                String deleteWhereClause = BookmarkColumns.BOOKMARK + " = 0";
                if (whereClause != null) {
                    deleteWhereClause += " AND " + whereClause;
                }
                cr.delete(BOOKMARKS_URI, deleteWhereClause, null);
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "deleteHistoryWhere", e);
            return;
        } finally {
            if (c != null) c.close();
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
        StringBuilder sb = new StringBuilder(BookmarkColumns.URL + " = ");
        DatabaseUtils.appendEscapedSQLString(sb, url);
        String matchesUrl = sb.toString();
        deleteHistoryWhere(cr, matchesUrl);
    }

    /**
     * Add a search string to the searches database.
     *  Requires {@link android.Manifest.permission#READ_HISTORY_BOOKMARKS}
     *  Requires {@link android.Manifest.permission#WRITE_HISTORY_BOOKMARKS}
     * @param cr   The ContentResolver used to access the database.
     * @param search    The string to add to the searches database.
     */
    public static final void addSearchUrl(ContentResolver cr, String search) {
        long now = new Date().getTime();
        Cursor c = null;
        try {
            c = cr.query(
                SEARCHES_URI,
                SEARCHES_PROJECTION,
                SEARCHES_WHERE_CLAUSE,
                new String [] { search },
                null);
            ContentValues map = new ContentValues();
            map.put(SearchColumns.SEARCH, search);
            map.put(SearchColumns.DATE, now);
            /* We should only get one answer that is exactly the same. */
            if (c.moveToFirst()) {
                cr.update(SEARCHES_URI, map, "_id = " + c.getInt(0), null);
            } else {
                cr.insert(SEARCHES_URI, map);
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "addSearchUrl", e);
        } finally {
            if (c != null) c.close();
        }
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
            cr.delete(SEARCHES_URI, null, null);
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
        WebIconDatabase.getInstance()
                .bulkRequestIconForPageUrl(cr, where, listener);
    }

    public static class BookmarkColumns implements BaseColumns {
        public static final String URL = "url";
        public static final String VISITS = "visits";
        public static final String DATE = "date";
        public static final String BOOKMARK = "bookmark";
        public static final String TITLE = "title";
        public static final String CREATED = "created";
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

    public static class SearchColumns implements BaseColumns {
        public static final String URL = "url";
        public static final String SEARCH = "search";
        public static final String DATE = "date";
    }
}
