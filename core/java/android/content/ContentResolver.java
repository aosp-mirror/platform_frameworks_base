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

package android.content;

import android.accounts.Account;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.CrossProcessCursorWrapper;
import android.database.Cursor;
import android.database.IContentObserver;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.util.MimeIconUtils;
import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class provides applications access to the content model.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using a ContentResolver with content providers, read the
 * <a href="{@docRoot}guide/topics/providers/content-providers.html">Content Providers</a>
 * developer guide.</p>
 */
public abstract class ContentResolver {
    /**
     * @deprecated instead use
     * {@link #requestSync(android.accounts.Account, String, android.os.Bundle)}
     */
    @Deprecated
    public static final String SYNC_EXTRAS_ACCOUNT = "account";

    /**
     * If this extra is set to true, the sync request will be scheduled
     * at the front of the sync request queue and without any delay
     */
    public static final String SYNC_EXTRAS_EXPEDITED = "expedited";

    /**
     * If this extra is set to true, the sync request will be scheduled
     * only when the device is plugged in. This is equivalent to calling
     * setRequiresCharging(true) on {@link SyncRequest}.
     */
    public static final String SYNC_EXTRAS_REQUIRE_CHARGING = "require_charging";

    /**
     * @deprecated instead use
     * {@link #SYNC_EXTRAS_MANUAL}
     */
    @Deprecated
    public static final String SYNC_EXTRAS_FORCE = "force";

    /**
     * If this extra is set to true then the sync settings (like getSyncAutomatically())
     * are ignored by the sync scheduler.
     */
    public static final String SYNC_EXTRAS_IGNORE_SETTINGS = "ignore_settings";

    /**
     * If this extra is set to true then any backoffs for the initial attempt (e.g. due to retries)
     * are ignored by the sync scheduler. If this request fails and gets rescheduled then the
     * retries will still honor the backoff.
     */
    public static final String SYNC_EXTRAS_IGNORE_BACKOFF = "ignore_backoff";

    /**
     * If this extra is set to true then the request will not be retried if it fails.
     */
    public static final String SYNC_EXTRAS_DO_NOT_RETRY = "do_not_retry";

    /**
     * Setting this extra is the equivalent of setting both {@link #SYNC_EXTRAS_IGNORE_SETTINGS}
     * and {@link #SYNC_EXTRAS_IGNORE_BACKOFF}
     */
    public static final String SYNC_EXTRAS_MANUAL = "force";

    /**
     * Indicates that this sync is intended to only upload local changes to the server.
     * For example, this will be set to true if the sync is initiated by a call to
     * {@link ContentResolver#notifyChange(android.net.Uri, android.database.ContentObserver, boolean)}
     */
    public static final String SYNC_EXTRAS_UPLOAD = "upload";

    /**
     * Indicates that the sync adapter should proceed with the delete operations,
     * even if it determines that there are too many.
     * See {@link SyncResult#tooManyDeletions}
     */
    public static final String SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS = "deletions_override";

    /**
     * Indicates that the sync adapter should not proceed with the delete operations,
     * if it determines that there are too many.
     * See {@link SyncResult#tooManyDeletions}
     */
    public static final String SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS = "discard_deletions";

    /* Extensions to API. TODO: Not clear if we will keep these as public flags. */
    /** {@hide} User-specified flag for expected upload size. */
    public static final String SYNC_EXTRAS_EXPECTED_UPLOAD = "expected_upload";

    /** {@hide} User-specified flag for expected download size. */
    public static final String SYNC_EXTRAS_EXPECTED_DOWNLOAD = "expected_download";

    /** {@hide} Priority of this sync with respect to other syncs scheduled for this application. */
    public static final String SYNC_EXTRAS_PRIORITY = "sync_priority";

    /** {@hide} Flag to allow sync to occur on metered network. */
    public static final String SYNC_EXTRAS_DISALLOW_METERED = "allow_metered";

    /**
     * {@hide} Integer extra containing a SyncExemption flag.
     *
     * Only the system and the shell user can set it.
     *
     * This extra is "virtual". Once passed to the system server, it'll be removed from the bundle.
     */
    public static final String SYNC_VIRTUAL_EXTRAS_EXEMPTION_FLAG = "v_exemption";

    /**
     * Set by the SyncManager to request that the SyncAdapter initialize itself for
     * the given account/authority pair. One required initialization step is to
     * ensure that {@link #setIsSyncable(android.accounts.Account, String, int)} has been
     * called with a >= 0 value. When this flag is set the SyncAdapter does not need to
     * do a full sync, though it is allowed to do so.
     */
    public static final String SYNC_EXTRAS_INITIALIZE = "initialize";

    /** @hide */
    public static final Intent ACTION_SYNC_CONN_STATUS_CHANGED =
            new Intent("com.android.sync.SYNC_CONN_STATUS_CHANGED");

    public static final String SCHEME_CONTENT = "content";
    public static final String SCHEME_ANDROID_RESOURCE = "android.resource";
    public static final String SCHEME_FILE = "file";

    /**
     * An extra {@link Point} describing the optimal size for a requested image
     * resource, in pixels. If a provider has multiple sizes of the image, it
     * should return the image closest to this size.
     *
     * @see #openTypedAssetFileDescriptor(Uri, String, Bundle)
     * @see #openTypedAssetFileDescriptor(Uri, String, Bundle,
     *      CancellationSignal)
     */
    public static final String EXTRA_SIZE = "android.content.extra.SIZE";

    /**
     * An extra boolean describing whether a particular provider supports refresh
     * or not. If a provider supports refresh, it should include this key in its
     * returned Cursor as part of its query call.
     *
     */
    public static final String EXTRA_REFRESH_SUPPORTED = "android.content.extra.REFRESH_SUPPORTED";

    /**
     * Key for an SQL style selection string that may be present in the query Bundle argument
     * passed to {@link ContentProvider#query(Uri, String[], Bundle, CancellationSignal)}
     * when called by a legacy client.
     *
     * <p>Clients should never include user supplied values directly in the selection string,
     * as this presents an avenue for SQL injection attacks. In lieu of this, a client
     * should use standard placeholder notation to represent values in a selection string,
     * then supply a corresponding value in {@value #QUERY_ARG_SQL_SELECTION_ARGS}.
     *
     * <p><b>Apps targeting {@link android.os.Build.VERSION_CODES#O} or higher are strongly
     * encourage to use structured query arguments in lieu of opaque SQL query clauses.</b>
     *
     * @see #QUERY_ARG_SORT_COLUMNS
     * @see #QUERY_ARG_SORT_DIRECTION
     * @see #QUERY_ARG_SORT_COLLATION
     */
    public static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection";

    /**
     * Key for SQL selection string arguments list.
     *
     * <p>Clients should never include user supplied values directly in the selection string,
     * as this presents an avenue for SQL injection attacks. In lieu of this, a client
     * should use standard placeholder notation to represent values in a selection string,
     * then supply a corresponding value in {@value #QUERY_ARG_SQL_SELECTION_ARGS}.
     *
     * <p><b>Apps targeting {@link android.os.Build.VERSION_CODES#O} or higher are strongly
     * encourage to use structured query arguments in lieu of opaque SQL query clauses.</b>
     *
     * @see #QUERY_ARG_SORT_COLUMNS
     * @see #QUERY_ARG_SORT_DIRECTION
     * @see #QUERY_ARG_SORT_COLLATION
     */
    public static final String QUERY_ARG_SQL_SELECTION_ARGS =
            "android:query-arg-sql-selection-args";

    /**
     * Key for an SQL style sort string that may be present in the query Bundle argument
     * passed to {@link ContentProvider#query(Uri, String[], Bundle, CancellationSignal)}
     * when called by a legacy client.
     *
     * <p><b>Apps targeting {@link android.os.Build.VERSION_CODES#O} or higher are strongly
     * encourage to use structured query arguments in lieu of opaque SQL query clauses.</b>
     *
     * @see #QUERY_ARG_SORT_COLUMNS
     * @see #QUERY_ARG_SORT_DIRECTION
     * @see #QUERY_ARG_SORT_COLLATION
     */
    public static final String QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order";

    /**
     * Specifies the list of columns against which to sort results. When first column values
     * are identical, records are then sorted based on second column values, and so on.
     *
     * <p>Columns present in this list must also be included in the projection
     * supplied to {@link ContentResolver#query(Uri, String[], Bundle, CancellationSignal)}.
     *
     * <p>Apps targeting {@link android.os.Build.VERSION_CODES#O} or higher:
     *
     * <li>{@link ContentProvider} implementations: When preparing data in
     * {@link ContentProvider#query(Uri, String[], Bundle, CancellationSignal)}, if sort columns
     * is reflected in the returned Cursor, it is  strongly recommended that
     * {@link #QUERY_ARG_SORT_COLUMNS} then be included in the array of honored arguments
     * reflected in {@link Cursor} extras {@link Bundle} under {@link #EXTRA_HONORED_ARGS}.
     *
     * <li>When querying a provider, where no QUERY_ARG_SQL* otherwise exists in the
     * arguments {@link Bundle}, the Content framework will attempt to synthesize
     * an QUERY_ARG_SQL* argument using the corresponding QUERY_ARG_SORT* values.
     */
    public static final String QUERY_ARG_SORT_COLUMNS = "android:query-arg-sort-columns";

    /**
     * Specifies desired sort order. When unspecified a provider may provide a default
     * sort direction, or choose to return unsorted results.
     *
     * <p>Apps targeting {@link android.os.Build.VERSION_CODES#O} or higher:
     *
     * <li>{@link ContentProvider} implementations: When preparing data in
     * {@link ContentProvider#query(Uri, String[], Bundle, CancellationSignal)}, if sort direction
     * is reflected in the returned Cursor, it is  strongly recommended that
     * {@link #QUERY_ARG_SORT_DIRECTION} then be included in the array of honored arguments
     * reflected in {@link Cursor} extras {@link Bundle} under {@link #EXTRA_HONORED_ARGS}.
     *
     * <li>When querying a provider, where no QUERY_ARG_SQL* otherwise exists in the
     * arguments {@link Bundle}, the Content framework will attempt to synthesize
     * a QUERY_ARG_SQL* argument using the corresponding QUERY_ARG_SORT* values.
     *
     * @see #QUERY_SORT_DIRECTION_ASCENDING
     * @see #QUERY_SORT_DIRECTION_DESCENDING
     */
    public static final String QUERY_ARG_SORT_DIRECTION = "android:query-arg-sort-direction";

    /**
     * Allows client to specify a hint to the provider declaring which collation
     * to use when sorting text values.
     *
     * <p>Providers may support custom collators. When specifying a custom collator
     * the value is determined by the Provider.
     *
     * <li>{@link ContentProvider} implementations: When preparing data in
     * {@link ContentProvider#query(Uri, String[], Bundle, CancellationSignal)}, if sort collation
     * is reflected in the returned Cursor, it is  strongly recommended that
     * {@link #QUERY_ARG_SORT_COLLATION} then be included in the array of honored arguments
     * reflected in {@link Cursor} extras {@link Bundle} under {@link #EXTRA_HONORED_ARGS}.
     *
     * <li>When querying a provider, where no QUERY_ARG_SQL* otherwise exists in the
     * arguments {@link Bundle}, the Content framework will attempt to synthesize
     * a QUERY_ARG_SQL* argument using the corresponding QUERY_ARG_SORT* values.
     *
     * @see java.text.Collator#PRIMARY
     * @see java.text.Collator#SECONDARY
     * @see java.text.Collator#TERTIARY
     * @see java.text.Collator#IDENTICAL
     */
    public static final String QUERY_ARG_SORT_COLLATION = "android:query-arg-sort-collation";

    /**
     * Allows provider to report back to client which query keys are honored in a Cursor.
     *
     * <p>Key identifying a {@code String[]} containing all QUERY_ARG_SORT* arguments
     * honored by the provider. Include this in {@link Cursor} extras {@link Bundle}
     * when any QUERY_ARG_SORT* value was honored during the preparation of the
     * results {@link Cursor}.
     *
     * <p>If present, ALL honored arguments are enumerated in this extra’s payload.
     *
     * @see #QUERY_ARG_SORT_COLUMNS
     * @see #QUERY_ARG_SORT_DIRECTION
     * @see #QUERY_ARG_SORT_COLLATION
     */
    public static final String EXTRA_HONORED_ARGS = "android.content.extra.HONORED_ARGS";

    /** @hide */
    @IntDef(flag = false, prefix = { "QUERY_SORT_DIRECTION_" }, value = {
            QUERY_SORT_DIRECTION_ASCENDING,
            QUERY_SORT_DIRECTION_DESCENDING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SortDirection {}
    public static final int QUERY_SORT_DIRECTION_ASCENDING = 0;
    public static final int QUERY_SORT_DIRECTION_DESCENDING = 1;

    /**
     * @see {@link java.text.Collector} for details on respective collation strength.
     * @hide
     */
    @IntDef(flag = false, value = {
            java.text.Collator.PRIMARY,
            java.text.Collator.SECONDARY,
            java.text.Collator.TERTIARY,
            java.text.Collator.IDENTICAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface QueryCollator {}

    /**
     * Specifies the offset row index within a Cursor.
     */
    public static final String QUERY_ARG_OFFSET = "android:query-arg-offset";

    /**
     * Specifies the max number of rows to include in a Cursor.
     */
    public static final String QUERY_ARG_LIMIT = "android:query-arg-limit";

    /**
     * Added to {@link Cursor} extras {@link Bundle} to indicate total row count of
     * recordset when paging is supported. Providers must include this when
     * implementing paging support.
     *
     * <p>A provider may return -1 that row count of the recordset is unknown.
     *
     * <p>Providers having returned -1 in a previous query are recommended to
     * send content change notification once (if) full recordset size becomes
     * known.
     */
    public static final String EXTRA_TOTAL_COUNT = "android.content.extra.TOTAL_COUNT";

    /**
     * This is the Android platform's base MIME type for a content: URI
     * containing a Cursor of a single item.  Applications should use this
     * as the base type along with their own sub-type of their content: URIs
     * that represent a particular item.  For example, hypothetical IMAP email
     * client may have a URI
     * <code>content://com.company.provider.imap/inbox/1</code> for a particular
     * message in the inbox, whose MIME type would be reported as
     * <code>CURSOR_ITEM_BASE_TYPE + "/vnd.company.imap-msg"</code>
     *
     * <p>Compare with {@link #CURSOR_DIR_BASE_TYPE}.
     */
    public static final String CURSOR_ITEM_BASE_TYPE = "vnd.android.cursor.item";

    /**
     * This is the Android platform's base MIME type for a content: URI
     * containing a Cursor of zero or more items.  Applications should use this
     * as the base type along with their own sub-type of their content: URIs
     * that represent a directory of items.  For example, hypothetical IMAP email
     * client may have a URI
     * <code>content://com.company.provider.imap/inbox</code> for all of the
     * messages in its inbox, whose MIME type would be reported as
     * <code>CURSOR_DIR_BASE_TYPE + "/vnd.company.imap-msg"</code>
     *
     * <p>Note how the base MIME type varies between this and
     * {@link #CURSOR_ITEM_BASE_TYPE} depending on whether there is
     * one single item or multiple items in the data set, while the sub-type
     * remains the same because in either case the data structure contained
     * in the cursor is the same.
     */
    public static final String CURSOR_DIR_BASE_TYPE = "vnd.android.cursor.dir";

    /**
     * This is the Android platform's generic MIME type to match any MIME
     * type of the form "{@link #CURSOR_ITEM_BASE_TYPE}/{@code SUB_TYPE}".
     * {@code SUB_TYPE} is the sub-type of the application-dependent
     * content, e.g., "audio", "video", "playlist".
     */
    public static final String ANY_CURSOR_ITEM_TYPE = "vnd.android.cursor.item/*";

    /** @hide */
    public static final int SYNC_ERROR_SYNC_ALREADY_IN_PROGRESS = 1;
    /** @hide */
    public static final int SYNC_ERROR_AUTHENTICATION = 2;
    /** @hide */
    public static final int SYNC_ERROR_IO = 3;
    /** @hide */
    public static final int SYNC_ERROR_PARSE = 4;
    /** @hide */
    public static final int SYNC_ERROR_CONFLICT = 5;
    /** @hide */
    public static final int SYNC_ERROR_TOO_MANY_DELETIONS = 6;
    /** @hide */
    public static final int SYNC_ERROR_TOO_MANY_RETRIES = 7;
    /** @hide */
    public static final int SYNC_ERROR_INTERNAL = 8;

    private static final String[] SYNC_ERROR_NAMES = new String[] {
          "already-in-progress",
          "authentication-error",
          "io-error",
          "parse-error",
          "conflict",
          "too-many-deletions",
          "too-many-retries",
          "internal-error",
    };

    /** @hide */
    public static String syncErrorToString(int error) {
        if (error < 1 || error > SYNC_ERROR_NAMES.length) {
            return String.valueOf(error);
        }
        return SYNC_ERROR_NAMES[error - 1];
    }

    /** @hide */
    public static int syncErrorStringToInt(String error) {
        for (int i = 0, n = SYNC_ERROR_NAMES.length; i < n; i++) {
            if (SYNC_ERROR_NAMES[i].equals(error)) {
                return i + 1;
            }
        }
        if (error != null) {
            try {
                return Integer.parseInt(error);
            } catch (NumberFormatException e) {
                Log.d(TAG, "error parsing sync error: " + error);
            }
        }
        return 0;
    }

    public static final int SYNC_OBSERVER_TYPE_SETTINGS = 1<<0;
    public static final int SYNC_OBSERVER_TYPE_PENDING = 1<<1;
    public static final int SYNC_OBSERVER_TYPE_ACTIVE = 1<<2;
    /** @hide */
    public static final int SYNC_OBSERVER_TYPE_STATUS = 1<<3;
    /** @hide */
    public static final int SYNC_OBSERVER_TYPE_ALL = 0x7fffffff;

    /** @hide */
    @IntDef(flag = true, prefix = { "NOTIFY_" }, value = {
            NOTIFY_SYNC_TO_NETWORK,
            NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NotifyFlags {}

    /**
     * Flag for {@link #notifyChange(Uri, ContentObserver, int)}: attempt to sync the change
     * to the network.
     */
    public static final int NOTIFY_SYNC_TO_NETWORK = 1<<0;

    /**
     * Flag for {@link #notifyChange(Uri, ContentObserver, int)}: if set, this notification
     * will be skipped if it is being delivered to the root URI of a ContentObserver that is
     * using "notify for descendants."  The purpose of this is to allow the provide to send
     * a general notification of "something under X" changed that observers of that specific
     * URI can receive, while also sending a specific URI under X.  It would use this flag
     * when sending the former, so that observers of "X and descendants" only see the latter.
     */
    public static final int NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS = 1<<1;

    /**
     * No exception, throttled by app standby normally.
     * @hide
     */
    public static final int SYNC_EXEMPTION_NONE = 0;

    /**
     * Exemption given to a sync request made by a foreground app (including
     * PROCESS_STATE_IMPORTANT_FOREGROUND).
     *
     * At the schedule time, we promote the sync adapter app for a higher bucket:
     * - If the device is not dozing (so the sync will start right away)
     *   promote to ACTIVE for 1 hour.
     * - If the device is dozing (so the sync *won't* start right away),
     * promote to WORKING_SET for 4 hours, so it'll get a higher chance to be started once the
     * device comes out of doze.
     * - When the sync actually starts, we promote the sync adapter app to ACTIVE for 10 minutes,
     * so it can schedule and start more syncs without getting throttled, even when the first
     * operation was canceled and now we're retrying.
     *
     *
     * @hide
     */
    public static final int SYNC_EXEMPTION_PROMOTE_BUCKET = 1;

    /**
     * In addition to {@link #SYNC_EXEMPTION_PROMOTE_BUCKET}, we put the sync adapter app in the
     * temp whitelist for 10 minutes, so that even RARE apps can run syncs right away.
     * @hide
     */
    public static final int SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP = 2;

    /** @hide */
    @IntDef(flag = false, prefix = { "SYNC_EXEMPTION_" }, value = {
            SYNC_EXEMPTION_NONE,
            SYNC_EXEMPTION_PROMOTE_BUCKET,
            SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SyncExemption {}

    // Always log queries which take 500ms+; shorter queries are
    // sampled accordingly.
    private static final boolean ENABLE_CONTENT_SAMPLE = false;
    private static final int SLOW_THRESHOLD_MILLIS = 500;
    private final Random mRandom = new Random();  // guarded by itself

    public ContentResolver(Context context) {
        mContext = context != null ? context : ActivityThread.currentApplication();
        mPackageName = mContext.getOpPackageName();
        mTargetSdkVersion = mContext.getApplicationInfo().targetSdkVersion;
    }

    /** @hide */
    protected abstract IContentProvider acquireProvider(Context c, String name);

    /**
     * Providing a default implementation of this, to avoid having to change a
     * lot of other things, but implementations of ContentResolver should
     * implement it.
     *
     * @hide
     */
    protected IContentProvider acquireExistingProvider(Context c, String name) {
        return acquireProvider(c, name);
    }

    /** @hide */
    public abstract boolean releaseProvider(IContentProvider icp);
    /** @hide */
    protected abstract IContentProvider acquireUnstableProvider(Context c, String name);
    /** @hide */
    public abstract boolean releaseUnstableProvider(IContentProvider icp);
    /** @hide */
    public abstract void unstableProviderDied(IContentProvider icp);

    /** @hide */
    public void appNotRespondingViaProvider(IContentProvider icp) {
        throw new UnsupportedOperationException("appNotRespondingViaProvider");
    }

    /**
     * Return the MIME type of the given content URL.
     *
     * @param url A Uri identifying content (either a list or specific type),
     * using the content:// scheme.
     * @return A MIME type for the content, or null if the URL is invalid or the type is unknown
     */
    public final @Nullable String getType(@NonNull Uri url) {
        Preconditions.checkNotNull(url, "url");

        // XXX would like to have an acquireExistingUnstableProvider for this.
        IContentProvider provider = acquireExistingProvider(url);
        if (provider != null) {
            try {
                return provider.getType(url);
            } catch (RemoteException e) {
                // Arbitrary and not worth documenting, as Activity
                // Manager will kill this process shortly anyway.
                return null;
            } catch (java.lang.Exception e) {
                Log.w(TAG, "Failed to get type for: " + url + " (" + e.getMessage() + ")");
                return null;
            } finally {
                releaseProvider(provider);
            }
        }

        if (!SCHEME_CONTENT.equals(url.getScheme())) {
            return null;
        }

        try {
            String type = ActivityManager.getService().getProviderMimeType(
                    ContentProvider.getUriWithoutUserId(url), resolveUserId(url));
            return type;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (java.lang.Exception e) {
            Log.w(TAG, "Failed to get type for: " + url + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * Query for the possible MIME types for the representations the given
     * content URL can be returned when opened as as stream with
     * {@link #openTypedAssetFileDescriptor}.  Note that the types here are
     * not necessarily a superset of the type returned by {@link #getType} --
     * many content providers cannot return a raw stream for the structured
     * data that they contain.
     *
     * @param url A Uri identifying content (either a list or specific type),
     * using the content:// scheme.
     * @param mimeTypeFilter The desired MIME type.  This may be a pattern,
     * such as *&#47;*, to query for all available MIME types that match the
     * pattern.
     * @return Returns an array of MIME type strings for all available
     * data streams that match the given mimeTypeFilter.  If there are none,
     * null is returned.
     */
    public @Nullable String[] getStreamTypes(@NonNull Uri url, @NonNull String mimeTypeFilter) {
        Preconditions.checkNotNull(url, "url");
        Preconditions.checkNotNull(mimeTypeFilter, "mimeTypeFilter");

        IContentProvider provider = acquireProvider(url);
        if (provider == null) {
            return null;
        }

        try {
            return provider.getStreamTypes(url, mimeTypeFilter);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        } finally {
            releaseProvider(provider);
        }
    }

    /**
     * Query the given URI, returning a {@link Cursor} over the result set.
     * <p>
     * For best performance, the caller should follow these guidelines:
     * <ul>
     * <li>Provide an explicit projection, to prevent
     * reading data from storage that aren't going to be used.</li>
     * <li>Use question mark parameter markers such as 'phone=?' instead of
     * explicit values in the {@code selection} parameter, so that queries
     * that differ only by those values will be recognized as the same
     * for caching purposes.</li>
     * </ul>
     * </p>
     *
     * @param uri The URI, using the content:// scheme, for the content to
     *         retrieve.
     * @param projection A list of which columns to return. Passing null will
     *         return all columns, which is inefficient.
     * @param selection A filter declaring which rows to return, formatted as an
     *         SQL WHERE clause (excluding the WHERE itself). Passing null will
     *         return all rows for the given URI.
     * @param selectionArgs You may include ?s in selection, which will be
     *         replaced by the values from selectionArgs, in the order that they
     *         appear in the selection. The values will be bound as Strings.
     * @param sortOrder How to order the rows, formatted as an SQL ORDER BY
     *         clause (excluding the ORDER BY itself). Passing null will use the
     *         default sort order, which may be unordered.
     * @return A Cursor object, which is positioned before the first entry, or null
     * @see Cursor
     */
    public final @Nullable Cursor query(@RequiresPermission.Read @NonNull Uri uri,
            @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return query(uri, projection, selection, selectionArgs, sortOrder, null);
    }

    /**
     * Query the given URI, returning a {@link Cursor} over the result set
     * with optional support for cancellation.
     * <p>
     * For best performance, the caller should follow these guidelines:
     * <ul>
     * <li>Provide an explicit projection, to prevent
     * reading data from storage that aren't going to be used.</li>
     * <li>Use question mark parameter markers such as 'phone=?' instead of
     * explicit values in the {@code selection} parameter, so that queries
     * that differ only by those values will be recognized as the same
     * for caching purposes.</li>
     * </ul>
     * </p>
     *
     * @param uri The URI, using the content:// scheme, for the content to
     *         retrieve.
     * @param projection A list of which columns to return. Passing null will
     *         return all columns, which is inefficient.
     * @param selection A filter declaring which rows to return, formatted as an
     *         SQL WHERE clause (excluding the WHERE itself). Passing null will
     *         return all rows for the given URI.
     * @param selectionArgs You may include ?s in selection, which will be
     *         replaced by the values from selectionArgs, in the order that they
     *         appear in the selection. The values will be bound as Strings.
     * @param sortOrder How to order the rows, formatted as an SQL ORDER BY
     *         clause (excluding the ORDER BY itself). Passing null will use the
     *         default sort order, which may be unordered.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * If the operation is canceled, then {@link OperationCanceledException} will be thrown
     * when the query is executed.
     * @return A Cursor object, which is positioned before the first entry, or null
     * @see Cursor
     */
    public final @Nullable Cursor query(@RequiresPermission.Read @NonNull Uri uri,
            @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder,
            @Nullable CancellationSignal cancellationSignal) {
        Bundle queryArgs = createSqlQueryBundle(selection, selectionArgs, sortOrder);
        return query(uri, projection, queryArgs, cancellationSignal);
    }

    /**
     * Query the given URI, returning a {@link Cursor} over the result set
     * with support for cancellation.
     *
     * <p>For best performance, the caller should follow these guidelines:
     *
     * <li>Provide an explicit projection, to prevent reading data from storage
     * that aren't going to be used.
     *
     * Provider must identify which QUERY_ARG_SORT* arguments were honored during
     * the preparation of the result set by including the respective argument keys
     * in the {@link Cursor} extras {@link Bundle}. See {@link #EXTRA_HONORED_ARGS}
     * for details.
     *
     * @see #QUERY_ARG_SORT_COLUMNS, #QUERY_ARG_SORT_DIRECTION, #QUERY_ARG_SORT_COLLATION.
     *
     * @param uri The URI, using the content:// scheme, for the content to
     *         retrieve.
     * @param projection A list of which columns to return. Passing null will
     *         return all columns, which is inefficient.
     * @param queryArgs A Bundle containing any arguments to the query.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * If the operation is canceled, then {@link OperationCanceledException} will be thrown
     * when the query is executed.
     * @return A Cursor object, which is positioned before the first entry, or null
     * @see Cursor
     */
    public final @Nullable Cursor query(final @RequiresPermission.Read @NonNull Uri uri,
            @Nullable String[] projection, @Nullable Bundle queryArgs,
            @Nullable CancellationSignal cancellationSignal) {
        Preconditions.checkNotNull(uri, "uri");
        IContentProvider unstableProvider = acquireUnstableProvider(uri);
        if (unstableProvider == null) {
            return null;
        }
        IContentProvider stableProvider = null;
        Cursor qCursor = null;
        try {
            long startTime = SystemClock.uptimeMillis();

            ICancellationSignal remoteCancellationSignal = null;
            if (cancellationSignal != null) {
                cancellationSignal.throwIfCanceled();
                remoteCancellationSignal = unstableProvider.createCancellationSignal();
                cancellationSignal.setRemote(remoteCancellationSignal);
            }
            try {
                qCursor = unstableProvider.query(mPackageName, uri, projection,
                        queryArgs, remoteCancellationSignal);
            } catch (DeadObjectException e) {
                // The remote process has died...  but we only hold an unstable
                // reference though, so we might recover!!!  Let's try!!!!
                // This is exciting!!1!!1!!!!1
                unstableProviderDied(unstableProvider);
                stableProvider = acquireProvider(uri);
                if (stableProvider == null) {
                    return null;
                }
                qCursor = stableProvider.query(
                        mPackageName, uri, projection, queryArgs, remoteCancellationSignal);
            }
            if (qCursor == null) {
                return null;
            }

            // Force query execution.  Might fail and throw a runtime exception here.
            qCursor.getCount();
            long durationMillis = SystemClock.uptimeMillis() - startTime;
            maybeLogQueryToEventLog(durationMillis, uri, projection, queryArgs);

            // Wrap the cursor object into CursorWrapperInner object.
            final IContentProvider provider = (stableProvider != null) ? stableProvider
                    : acquireProvider(uri);
            final CursorWrapperInner wrapper = new CursorWrapperInner(qCursor, provider);
            stableProvider = null;
            qCursor = null;
            return wrapper;
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        } finally {
            if (qCursor != null) {
                qCursor.close();
            }
            if (cancellationSignal != null) {
                cancellationSignal.setRemote(null);
            }
            if (unstableProvider != null) {
                releaseUnstableProvider(unstableProvider);
            }
            if (stableProvider != null) {
                releaseProvider(stableProvider);
            }
        }
    }

    /**
     * Transform the given <var>url</var> to a canonical representation of
     * its referenced resource, which can be used across devices, persisted,
     * backed up and restored, etc.  The returned Uri is still a fully capable
     * Uri for use with its content provider, allowing you to do all of the
     * same content provider operations as with the original Uri --
     * {@link #query}, {@link #openInputStream(android.net.Uri)}, etc.  The
     * only difference in behavior between the original and new Uris is that
     * the content provider may need to do some additional work at each call
     * using it to resolve it to the correct resource, especially if the
     * canonical Uri has been moved to a different environment.
     *
     * <p>If you are moving a canonical Uri between environments, you should
     * perform another call to {@link #canonicalize} with that original Uri to
     * re-canonicalize it for the current environment.  Alternatively, you may
     * want to use {@link #uncanonicalize} to transform it to a non-canonical
     * Uri that works only in the current environment but potentially more
     * efficiently than the canonical representation.</p>
     *
     * @param url The {@link Uri} that is to be transformed to a canonical
     * representation.  Like all resolver calls, the input can be either
     * a non-canonical or canonical Uri.
     *
     * @return Returns the official canonical representation of <var>url</var>,
     * or null if the content provider does not support a canonical representation
     * of the given Uri.  Many providers may not support canonicalization of some
     * or all of their Uris.
     *
     * @see #uncanonicalize
     */
    public final @Nullable Uri canonicalize(@NonNull Uri url) {
        Preconditions.checkNotNull(url, "url");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) {
            return null;
        }

        try {
            return provider.canonicalize(mPackageName, url);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        } finally {
            releaseProvider(provider);
        }
    }

    /**
     * Given a canonical Uri previously generated by {@link #canonicalize}, convert
     * it to its local non-canonical form.  This can be useful in some cases where
     * you know that you will only be using the Uri in the current environment and
     * want to avoid any possible overhead when using it with the content
     * provider or want to verify that the referenced data exists at all in the
     * new environment.
     *
     * @param url The canonical {@link Uri} that is to be convered back to its
     * non-canonical form.
     *
     * @return Returns the non-canonical representation of <var>url</var>.  This will
     * return null if data identified by the canonical Uri can not be found in
     * the current environment; callers must always check for null and deal with
     * that by appropriately falling back to an alternative.
     *
     * @see #canonicalize
     */
    public final @Nullable Uri uncanonicalize(@NonNull Uri url) {
        Preconditions.checkNotNull(url, "url");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) {
            return null;
        }

        try {
            return provider.uncanonicalize(mPackageName, url);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        } finally {
            releaseProvider(provider);
        }
    }

    /**
     * This allows clients to request an explicit refresh of content identified by {@code uri}.
     * <p>
     * Client code should only invoke this method when there is a strong indication (such as a user
     * initiated pull to refresh gesture) that the content is stale.
     * <p>
     *
     * @param url The Uri identifying the data to refresh.
     * @param args Additional options from the client. The definitions of these are specific to the
     *            content provider being called.
     * @param cancellationSignal A signal to cancel the operation in progress, or {@code null} if
     *            none. For example, if you called refresh on a particular uri, you should call
     *            {@link CancellationSignal#throwIfCanceled()} to check whether the client has
     *            canceled the refresh request.
     * @return true if the provider actually tried refreshing.
     */
    public final boolean refresh(@NonNull Uri url, @Nullable Bundle args,
            @Nullable CancellationSignal cancellationSignal) {
        Preconditions.checkNotNull(url, "url");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) {
            return false;
        }

        try {
            ICancellationSignal remoteCancellationSignal = null;
            if (cancellationSignal != null) {
                cancellationSignal.throwIfCanceled();
                remoteCancellationSignal = provider.createCancellationSignal();
                cancellationSignal.setRemote(remoteCancellationSignal);
            }
            return provider.refresh(mPackageName, url, args, remoteCancellationSignal);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return false;
        } finally {
            releaseProvider(provider);
        }
    }

    /**
     * Open a stream on to the content associated with a content URI.  If there
     * is no data associated with the URI, FileNotFoundException is thrown.
     *
     * <h5>Accepts the following URI schemes:</h5>
     * <ul>
     * <li>content ({@link #SCHEME_CONTENT})</li>
     * <li>android.resource ({@link #SCHEME_ANDROID_RESOURCE})</li>
     * <li>file ({@link #SCHEME_FILE})</li>
     * </ul>
     *
     * <p>See {@link #openAssetFileDescriptor(Uri, String)} for more information
     * on these schemes.
     *
     * @param uri The desired URI.
     * @return InputStream
     * @throws FileNotFoundException if the provided URI could not be opened.
     * @see #openAssetFileDescriptor(Uri, String)
     */
    public final @Nullable InputStream openInputStream(@NonNull Uri uri)
            throws FileNotFoundException {
        Preconditions.checkNotNull(uri, "uri");
        String scheme = uri.getScheme();
        if (SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            // Note: left here to avoid breaking compatibility.  May be removed
            // with sufficient testing.
            OpenResourceIdResult r = getResourceId(uri);
            try {
                InputStream stream = r.r.openRawResource(r.id);
                return stream;
            } catch (Resources.NotFoundException ex) {
                throw new FileNotFoundException("Resource does not exist: " + uri);
            }
        } else if (SCHEME_FILE.equals(scheme)) {
            // Note: left here to avoid breaking compatibility.  May be removed
            // with sufficient testing.
            return new FileInputStream(uri.getPath());
        } else {
            AssetFileDescriptor fd = openAssetFileDescriptor(uri, "r", null);
            try {
                return fd != null ? fd.createInputStream() : null;
            } catch (IOException e) {
                throw new FileNotFoundException("Unable to create stream");
            }
        }
    }

    /**
     * Synonym for {@link #openOutputStream(Uri, String)
     * openOutputStream(uri, "w")}.
     * @throws FileNotFoundException if the provided URI could not be opened.
     */
    public final @Nullable OutputStream openOutputStream(@NonNull Uri uri)
            throws FileNotFoundException {
        return openOutputStream(uri, "w");
    }

    /**
     * Open a stream on to the content associated with a content URI.  If there
     * is no data associated with the URI, FileNotFoundException is thrown.
     *
     * <h5>Accepts the following URI schemes:</h5>
     * <ul>
     * <li>content ({@link #SCHEME_CONTENT})</li>
     * <li>file ({@link #SCHEME_FILE})</li>
     * </ul>
     *
     * <p>See {@link #openAssetFileDescriptor(Uri, String)} for more information
     * on these schemes.
     *
     * @param uri The desired URI.
     * @param mode May be "w", "wa", "rw", or "rwt".
     * @return OutputStream
     * @throws FileNotFoundException if the provided URI could not be opened.
     * @see #openAssetFileDescriptor(Uri, String)
     */
    public final @Nullable OutputStream openOutputStream(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        AssetFileDescriptor fd = openAssetFileDescriptor(uri, mode, null);
        try {
            return fd != null ? fd.createOutputStream() : null;
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to create stream");
        }
    }

    /**
     * Open a raw file descriptor to access data under a URI.  This
     * is like {@link #openAssetFileDescriptor(Uri, String)}, but uses the
     * underlying {@link ContentProvider#openFile}
     * ContentProvider.openFile()} method, so will <em>not</em> work with
     * providers that return sub-sections of files.  If at all possible,
     * you should use {@link #openAssetFileDescriptor(Uri, String)}.  You
     * will receive a FileNotFoundException exception if the provider returns a
     * sub-section of a file.
     *
     * <h5>Accepts the following URI schemes:</h5>
     * <ul>
     * <li>content ({@link #SCHEME_CONTENT})</li>
     * <li>file ({@link #SCHEME_FILE})</li>
     * </ul>
     *
     * <p>See {@link #openAssetFileDescriptor(Uri, String)} for more information
     * on these schemes.
     * <p>
     * If opening with the exclusive "r" or "w" modes, the returned
     * ParcelFileDescriptor could be a pipe or socket pair to enable streaming
     * of data. Opening with the "rw" mode implies a file on disk that supports
     * seeking. If possible, always use an exclusive mode to give the underlying
     * {@link ContentProvider} the most flexibility.
     * <p>
     * If you are writing a file, and need to communicate an error to the
     * provider, use {@link ParcelFileDescriptor#closeWithError(String)}.
     *
     * @param uri The desired URI to open.
     * @param mode The file mode to use, as per {@link ContentProvider#openFile
     * ContentProvider.openFile}.
     * @return Returns a new ParcelFileDescriptor pointing to the file.  You
     * own this descriptor and are responsible for closing it when done.
     * @throws FileNotFoundException Throws FileNotFoundException if no
     * file exists under the URI or the mode is invalid.
     * @see #openAssetFileDescriptor(Uri, String)
     */
    public final @Nullable ParcelFileDescriptor openFileDescriptor(@NonNull Uri uri,
            @NonNull String mode) throws FileNotFoundException {
        return openFileDescriptor(uri, mode, null);
    }

    /**
     * Open a raw file descriptor to access data under a URI.  This
     * is like {@link #openAssetFileDescriptor(Uri, String)}, but uses the
     * underlying {@link ContentProvider#openFile}
     * ContentProvider.openFile()} method, so will <em>not</em> work with
     * providers that return sub-sections of files.  If at all possible,
     * you should use {@link #openAssetFileDescriptor(Uri, String)}.  You
     * will receive a FileNotFoundException exception if the provider returns a
     * sub-section of a file.
     *
     * <h5>Accepts the following URI schemes:</h5>
     * <ul>
     * <li>content ({@link #SCHEME_CONTENT})</li>
     * <li>file ({@link #SCHEME_FILE})</li>
     * </ul>
     *
     * <p>See {@link #openAssetFileDescriptor(Uri, String)} for more information
     * on these schemes.
     * <p>
     * If opening with the exclusive "r" or "w" modes, the returned
     * ParcelFileDescriptor could be a pipe or socket pair to enable streaming
     * of data. Opening with the "rw" mode implies a file on disk that supports
     * seeking. If possible, always use an exclusive mode to give the underlying
     * {@link ContentProvider} the most flexibility.
     * <p>
     * If you are writing a file, and need to communicate an error to the
     * provider, use {@link ParcelFileDescriptor#closeWithError(String)}.
     *
     * @param uri The desired URI to open.
     * @param mode The file mode to use, as per {@link ContentProvider#openFile
     * ContentProvider.openFile}.
     * @param cancellationSignal A signal to cancel the operation in progress,
     *         or null if none. If the operation is canceled, then
     *         {@link OperationCanceledException} will be thrown.
     * @return Returns a new ParcelFileDescriptor pointing to the file.  You
     * own this descriptor and are responsible for closing it when done.
     * @throws FileNotFoundException Throws FileNotFoundException if no
     * file exists under the URI or the mode is invalid.
     * @see #openAssetFileDescriptor(Uri, String)
     */
    public final @Nullable ParcelFileDescriptor openFileDescriptor(@NonNull Uri uri,
            @NonNull String mode, @Nullable CancellationSignal cancellationSignal)
                    throws FileNotFoundException {
        AssetFileDescriptor afd = openAssetFileDescriptor(uri, mode, cancellationSignal);
        if (afd == null) {
            return null;
        }

        if (afd.getDeclaredLength() < 0) {
            // This is a full file!
            return afd.getParcelFileDescriptor();
        }

        // Client can't handle a sub-section of a file, so close what
        // we got and bail with an exception.
        try {
            afd.close();
        } catch (IOException e) {
        }

        throw new FileNotFoundException("Not a whole file");
    }

    /**
     * Open a raw file descriptor to access data under a URI.  This
     * interacts with the underlying {@link ContentProvider#openAssetFile}
     * method of the provider associated with the given URI, to retrieve any file stored there.
     *
     * <h5>Accepts the following URI schemes:</h5>
     * <ul>
     * <li>content ({@link #SCHEME_CONTENT})</li>
     * <li>android.resource ({@link #SCHEME_ANDROID_RESOURCE})</li>
     * <li>file ({@link #SCHEME_FILE})</li>
     * </ul>
     * <h5>The android.resource ({@link #SCHEME_ANDROID_RESOURCE}) Scheme</h5>
     * <p>
     * A Uri object can be used to reference a resource in an APK file.  The
     * Uri should be one of the following formats:
     * <ul>
     * <li><code>android.resource://package_name/id_number</code><br/>
     * <code>package_name</code> is your package name as listed in your AndroidManifest.xml.
     * For example <code>com.example.myapp</code><br/>
     * <code>id_number</code> is the int form of the ID.<br/>
     * The easiest way to construct this form is
     * <pre>Uri uri = Uri.parse("android.resource://com.example.myapp/" + R.raw.my_resource");</pre>
     * </li>
     * <li><code>android.resource://package_name/type/name</code><br/>
     * <code>package_name</code> is your package name as listed in your AndroidManifest.xml.
     * For example <code>com.example.myapp</code><br/>
     * <code>type</code> is the string form of the resource type.  For example, <code>raw</code>
     * or <code>drawable</code>.
     * <code>name</code> is the string form of the resource name.  That is, whatever the file
     * name was in your res directory, without the type extension.
     * The easiest way to construct this form is
     * <pre>Uri uri = Uri.parse("android.resource://com.example.myapp/raw/my_resource");</pre>
     * </li>
     * </ul>
     *
     * <p>Note that if this function is called for read-only input (mode is "r")
     * on a content: URI, it will instead call {@link #openTypedAssetFileDescriptor}
     * for you with a MIME type of "*&#47;*".  This allows such callers to benefit
     * from any built-in data conversion that a provider implements.
     *
     * @param uri The desired URI to open.
     * @param mode The file mode to use, as per {@link ContentProvider#openAssetFile
     * ContentProvider.openAssetFile}.
     * @return Returns a new ParcelFileDescriptor pointing to the file.  You
     * own this descriptor and are responsible for closing it when done.
     * @throws FileNotFoundException Throws FileNotFoundException of no
     * file exists under the URI or the mode is invalid.
     */
    public final @Nullable AssetFileDescriptor openAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mode) throws FileNotFoundException {
        return openAssetFileDescriptor(uri, mode, null);
    }

    /**
     * Open a raw file descriptor to access data under a URI.  This
     * interacts with the underlying {@link ContentProvider#openAssetFile}
     * method of the provider associated with the given URI, to retrieve any file stored there.
     *
     * <h5>Accepts the following URI schemes:</h5>
     * <ul>
     * <li>content ({@link #SCHEME_CONTENT})</li>
     * <li>android.resource ({@link #SCHEME_ANDROID_RESOURCE})</li>
     * <li>file ({@link #SCHEME_FILE})</li>
     * </ul>
     * <h5>The android.resource ({@link #SCHEME_ANDROID_RESOURCE}) Scheme</h5>
     * <p>
     * A Uri object can be used to reference a resource in an APK file.  The
     * Uri should be one of the following formats:
     * <ul>
     * <li><code>android.resource://package_name/id_number</code><br/>
     * <code>package_name</code> is your package name as listed in your AndroidManifest.xml.
     * For example <code>com.example.myapp</code><br/>
     * <code>id_number</code> is the int form of the ID.<br/>
     * The easiest way to construct this form is
     * <pre>Uri uri = Uri.parse("android.resource://com.example.myapp/" + R.raw.my_resource");</pre>
     * </li>
     * <li><code>android.resource://package_name/type/name</code><br/>
     * <code>package_name</code> is your package name as listed in your AndroidManifest.xml.
     * For example <code>com.example.myapp</code><br/>
     * <code>type</code> is the string form of the resource type.  For example, <code>raw</code>
     * or <code>drawable</code>.
     * <code>name</code> is the string form of the resource name.  That is, whatever the file
     * name was in your res directory, without the type extension.
     * The easiest way to construct this form is
     * <pre>Uri uri = Uri.parse("android.resource://com.example.myapp/raw/my_resource");</pre>
     * </li>
     * </ul>
     *
     * <p>Note that if this function is called for read-only input (mode is "r")
     * on a content: URI, it will instead call {@link #openTypedAssetFileDescriptor}
     * for you with a MIME type of "*&#47;*".  This allows such callers to benefit
     * from any built-in data conversion that a provider implements.
     *
     * @param uri The desired URI to open.
     * @param mode The file mode to use, as per {@link ContentProvider#openAssetFile
     * ContentProvider.openAssetFile}.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if
     *            none. If the operation is canceled, then
     *            {@link OperationCanceledException} will be thrown.
     * @return Returns a new ParcelFileDescriptor pointing to the file.  You
     * own this descriptor and are responsible for closing it when done.
     * @throws FileNotFoundException Throws FileNotFoundException of no
     * file exists under the URI or the mode is invalid.
     */
    public final @Nullable AssetFileDescriptor openAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mode, @Nullable CancellationSignal cancellationSignal)
                    throws FileNotFoundException {
        Preconditions.checkNotNull(uri, "uri");
        Preconditions.checkNotNull(mode, "mode");

        String scheme = uri.getScheme();
        if (SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            if (!"r".equals(mode)) {
                throw new FileNotFoundException("Can't write resources: " + uri);
            }
            OpenResourceIdResult r = getResourceId(uri);
            try {
                return r.r.openRawResourceFd(r.id);
            } catch (Resources.NotFoundException ex) {
                throw new FileNotFoundException("Resource does not exist: " + uri);
            }
        } else if (SCHEME_FILE.equals(scheme)) {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                    new File(uri.getPath()), ParcelFileDescriptor.parseMode(mode));
            return new AssetFileDescriptor(pfd, 0, -1);
        } else {
            if ("r".equals(mode)) {
                return openTypedAssetFileDescriptor(uri, "*/*", null, cancellationSignal);
            } else {
                IContentProvider unstableProvider = acquireUnstableProvider(uri);
                if (unstableProvider == null) {
                    throw new FileNotFoundException("No content provider: " + uri);
                }
                IContentProvider stableProvider = null;
                AssetFileDescriptor fd = null;

                try {
                    ICancellationSignal remoteCancellationSignal = null;
                    if (cancellationSignal != null) {
                        cancellationSignal.throwIfCanceled();
                        remoteCancellationSignal = unstableProvider.createCancellationSignal();
                        cancellationSignal.setRemote(remoteCancellationSignal);
                    }

                    try {
                        fd = unstableProvider.openAssetFile(
                                mPackageName, uri, mode, remoteCancellationSignal);
                        if (fd == null) {
                            // The provider will be released by the finally{} clause
                            return null;
                        }
                    } catch (DeadObjectException e) {
                        // The remote process has died...  but we only hold an unstable
                        // reference though, so we might recover!!!  Let's try!!!!
                        // This is exciting!!1!!1!!!!1
                        unstableProviderDied(unstableProvider);
                        stableProvider = acquireProvider(uri);
                        if (stableProvider == null) {
                            throw new FileNotFoundException("No content provider: " + uri);
                        }
                        fd = stableProvider.openAssetFile(
                                mPackageName, uri, mode, remoteCancellationSignal);
                        if (fd == null) {
                            // The provider will be released by the finally{} clause
                            return null;
                        }
                    }

                    if (stableProvider == null) {
                        stableProvider = acquireProvider(uri);
                    }
                    releaseUnstableProvider(unstableProvider);
                    unstableProvider = null;
                    ParcelFileDescriptor pfd = new ParcelFileDescriptorInner(
                            fd.getParcelFileDescriptor(), stableProvider);

                    // Success!  Don't release the provider when exiting, let
                    // ParcelFileDescriptorInner do that when it is closed.
                    stableProvider = null;

                    return new AssetFileDescriptor(pfd, fd.getStartOffset(),
                            fd.getDeclaredLength());

                } catch (RemoteException e) {
                    // Whatever, whatever, we'll go away.
                    throw new FileNotFoundException(
                            "Failed opening content provider: " + uri);
                } catch (FileNotFoundException e) {
                    throw e;
                } finally {
                    if (cancellationSignal != null) {
                        cancellationSignal.setRemote(null);
                    }
                    if (stableProvider != null) {
                        releaseProvider(stableProvider);
                    }
                    if (unstableProvider != null) {
                        releaseUnstableProvider(unstableProvider);
                    }
                }
            }
        }
    }

    /**
     * Open a raw file descriptor to access (potentially type transformed)
     * data from a "content:" URI.  This interacts with the underlying
     * {@link ContentProvider#openTypedAssetFile} method of the provider
     * associated with the given URI, to retrieve retrieve any appropriate
     * data stream for the data stored there.
     *
     * <p>Unlike {@link #openAssetFileDescriptor}, this function only works
     * with "content:" URIs, because content providers are the only facility
     * with an associated MIME type to ensure that the returned data stream
     * is of the desired type.
     *
     * <p>All text/* streams are encoded in UTF-8.
     *
     * @param uri The desired URI to open.
     * @param mimeType The desired MIME type of the returned data.  This can
     * be a pattern such as *&#47;*, which will allow the content provider to
     * select a type, though there is no way for you to determine what type
     * it is returning.
     * @param opts Additional provider-dependent options.
     * @return Returns a new ParcelFileDescriptor from which you can read the
     * data stream from the provider.  Note that this may be a pipe, meaning
     * you can't seek in it.  The only seek you should do is if the
     * AssetFileDescriptor contains an offset, to move to that offset before
     * reading.  You own this descriptor and are responsible for closing it when done.
     * @throws FileNotFoundException Throws FileNotFoundException of no
     * data of the desired type exists under the URI.
     */
    public final @Nullable AssetFileDescriptor openTypedAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mimeType, @Nullable Bundle opts) throws FileNotFoundException {
        return openTypedAssetFileDescriptor(uri, mimeType, opts, null);
    }

    /**
     * Open a raw file descriptor to access (potentially type transformed)
     * data from a "content:" URI.  This interacts with the underlying
     * {@link ContentProvider#openTypedAssetFile} method of the provider
     * associated with the given URI, to retrieve retrieve any appropriate
     * data stream for the data stored there.
     *
     * <p>Unlike {@link #openAssetFileDescriptor}, this function only works
     * with "content:" URIs, because content providers are the only facility
     * with an associated MIME type to ensure that the returned data stream
     * is of the desired type.
     *
     * <p>All text/* streams are encoded in UTF-8.
     *
     * @param uri The desired URI to open.
     * @param mimeType The desired MIME type of the returned data.  This can
     * be a pattern such as *&#47;*, which will allow the content provider to
     * select a type, though there is no way for you to determine what type
     * it is returning.
     * @param opts Additional provider-dependent options.
     * @param cancellationSignal A signal to cancel the operation in progress,
     *         or null if none. If the operation is canceled, then
     *         {@link OperationCanceledException} will be thrown.
     * @return Returns a new ParcelFileDescriptor from which you can read the
     * data stream from the provider.  Note that this may be a pipe, meaning
     * you can't seek in it.  The only seek you should do is if the
     * AssetFileDescriptor contains an offset, to move to that offset before
     * reading.  You own this descriptor and are responsible for closing it when done.
     * @throws FileNotFoundException Throws FileNotFoundException of no
     * data of the desired type exists under the URI.
     */
    public final @Nullable AssetFileDescriptor openTypedAssetFileDescriptor(@NonNull Uri uri,
            @NonNull String mimeType, @Nullable Bundle opts,
            @Nullable CancellationSignal cancellationSignal) throws FileNotFoundException {
        Preconditions.checkNotNull(uri, "uri");
        Preconditions.checkNotNull(mimeType, "mimeType");

        IContentProvider unstableProvider = acquireUnstableProvider(uri);
        if (unstableProvider == null) {
            throw new FileNotFoundException("No content provider: " + uri);
        }
        IContentProvider stableProvider = null;
        AssetFileDescriptor fd = null;

        try {
            ICancellationSignal remoteCancellationSignal = null;
            if (cancellationSignal != null) {
                cancellationSignal.throwIfCanceled();
                remoteCancellationSignal = unstableProvider.createCancellationSignal();
                cancellationSignal.setRemote(remoteCancellationSignal);
            }

            try {
                fd = unstableProvider.openTypedAssetFile(
                        mPackageName, uri, mimeType, opts, remoteCancellationSignal);
                if (fd == null) {
                    // The provider will be released by the finally{} clause
                    return null;
                }
            } catch (DeadObjectException e) {
                // The remote process has died...  but we only hold an unstable
                // reference though, so we might recover!!!  Let's try!!!!
                // This is exciting!!1!!1!!!!1
                unstableProviderDied(unstableProvider);
                stableProvider = acquireProvider(uri);
                if (stableProvider == null) {
                    throw new FileNotFoundException("No content provider: " + uri);
                }
                fd = stableProvider.openTypedAssetFile(
                        mPackageName, uri, mimeType, opts, remoteCancellationSignal);
                if (fd == null) {
                    // The provider will be released by the finally{} clause
                    return null;
                }
            }

            if (stableProvider == null) {
                stableProvider = acquireProvider(uri);
            }
            releaseUnstableProvider(unstableProvider);
            unstableProvider = null;
            ParcelFileDescriptor pfd = new ParcelFileDescriptorInner(
                    fd.getParcelFileDescriptor(), stableProvider);

            // Success!  Don't release the provider when exiting, let
            // ParcelFileDescriptorInner do that when it is closed.
            stableProvider = null;

            return new AssetFileDescriptor(pfd, fd.getStartOffset(),
                    fd.getDeclaredLength());

        } catch (RemoteException e) {
            // Whatever, whatever, we'll go away.
            throw new FileNotFoundException(
                    "Failed opening content provider: " + uri);
        } catch (FileNotFoundException e) {
            throw e;
        } finally {
            if (cancellationSignal != null) {
                cancellationSignal.setRemote(null);
            }
            if (stableProvider != null) {
                releaseProvider(stableProvider);
            }
            if (unstableProvider != null) {
                releaseUnstableProvider(unstableProvider);
            }
        }
    }

    /**
     * A resource identified by the {@link Resources} that contains it, and a resource id.
     *
     * @hide
     */
    public class OpenResourceIdResult {
        public Resources r;
        public int id;
    }

    /**
     * Resolves an android.resource URI to a {@link Resources} and a resource id.
     *
     * @hide
     */
    public OpenResourceIdResult getResourceId(Uri uri) throws FileNotFoundException {
        String authority = uri.getAuthority();
        Resources r;
        if (TextUtils.isEmpty(authority)) {
            throw new FileNotFoundException("No authority: " + uri);
        } else {
            try {
                r = mContext.getPackageManager().getResourcesForApplication(authority);
            } catch (NameNotFoundException ex) {
                throw new FileNotFoundException("No package found for authority: " + uri);
            }
        }
        List<String> path = uri.getPathSegments();
        if (path == null) {
            throw new FileNotFoundException("No path: " + uri);
        }
        int len = path.size();
        int id;
        if (len == 1) {
            try {
                id = Integer.parseInt(path.get(0));
            } catch (NumberFormatException e) {
                throw new FileNotFoundException("Single path segment is not a resource ID: " + uri);
            }
        } else if (len == 2) {
            id = r.getIdentifier(path.get(1), path.get(0), authority);
        } else {
            throw new FileNotFoundException("More than two path segments: " + uri);
        }
        if (id == 0) {
            throw new FileNotFoundException("No resource found for: " + uri);
        }
        OpenResourceIdResult res = new OpenResourceIdResult();
        res.r = r;
        res.id = id;
        return res;
    }

    /**
     * Inserts a row into a table at the given URL.
     *
     * If the content provider supports transactions the insertion will be atomic.
     *
     * @param url The URL of the table to insert into.
     * @param values The initial values for the newly inserted row. The key is the column name for
     *               the field. Passing an empty ContentValues will create an empty row.
     * @return the URL of the newly created row.
     */
    public final @Nullable Uri insert(@RequiresPermission.Write @NonNull Uri url,
                @Nullable ContentValues values) {
        Preconditions.checkNotNull(url, "url");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown URL " + url);
        }
        try {
            long startTime = SystemClock.uptimeMillis();
            Uri createdRow = provider.insert(mPackageName, url, values);
            long durationMillis = SystemClock.uptimeMillis() - startTime;
            maybeLogUpdateToEventLog(durationMillis, url, "insert", null /* where */);
            return createdRow;
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        } finally {
            releaseProvider(provider);
        }
    }

    /**
     * Applies each of the {@link ContentProviderOperation} objects and returns an array
     * of their results. Passes through OperationApplicationException, which may be thrown
     * by the call to {@link ContentProviderOperation#apply}.
     * If all the applications succeed then a {@link ContentProviderResult} array with the
     * same number of elements as the operations will be returned. It is implementation-specific
     * how many, if any, operations will have been successfully applied if a call to
     * apply results in a {@link OperationApplicationException}.
     * @param authority the authority of the ContentProvider to which this batch should be applied
     * @param operations the operations to apply
     * @return the results of the applications
     * @throws OperationApplicationException thrown if an application fails.
     * See {@link ContentProviderOperation#apply} for more information.
     * @throws RemoteException thrown if a RemoteException is encountered while attempting
     *   to communicate with a remote provider.
     */
    public @NonNull ContentProviderResult[] applyBatch(@NonNull String authority,
            @NonNull ArrayList<ContentProviderOperation> operations)
                    throws RemoteException, OperationApplicationException {
        Preconditions.checkNotNull(authority, "authority");
        Preconditions.checkNotNull(operations, "operations");
        ContentProviderClient provider = acquireContentProviderClient(authority);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown authority " + authority);
        }
        try {
            return provider.applyBatch(operations);
        } finally {
            provider.release();
        }
    }

    /**
     * Inserts multiple rows into a table at the given URL.
     *
     * This function make no guarantees about the atomicity of the insertions.
     *
     * @param url The URL of the table to insert into.
     * @param values The initial values for the newly inserted rows. The key is the column name for
     *               the field. Passing null will create an empty row.
     * @return the number of newly created rows.
     */
    public final int bulkInsert(@RequiresPermission.Write @NonNull Uri url,
                @NonNull ContentValues[] values) {
        Preconditions.checkNotNull(url, "url");
        Preconditions.checkNotNull(values, "values");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown URL " + url);
        }
        try {
            long startTime = SystemClock.uptimeMillis();
            int rowsCreated = provider.bulkInsert(mPackageName, url, values);
            long durationMillis = SystemClock.uptimeMillis() - startTime;
            maybeLogUpdateToEventLog(durationMillis, url, "bulkinsert", null /* where */);
            return rowsCreated;
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return 0;
        } finally {
            releaseProvider(provider);
        }
    }

    /**
     * Deletes row(s) specified by a content URI.
     *
     * If the content provider supports transactions, the deletion will be atomic.
     *
     * @param url The URL of the row to delete.
     * @param where A filter to apply to rows before deleting, formatted as an SQL WHERE clause
                    (excluding the WHERE itself).
     * @return The number of rows deleted.
     */
    public final int delete(@RequiresPermission.Write @NonNull Uri url, @Nullable String where,
            @Nullable String[] selectionArgs) {
        Preconditions.checkNotNull(url, "url");
        IContentProvider provider = acquireProvider(url);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown URL " + url);
        }
        try {
            long startTime = SystemClock.uptimeMillis();
            int rowsDeleted = provider.delete(mPackageName, url, where, selectionArgs);
            long durationMillis = SystemClock.uptimeMillis() - startTime;
            maybeLogUpdateToEventLog(durationMillis, url, "delete", where);
            return rowsDeleted;
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return -1;
        } finally {
            releaseProvider(provider);
        }
    }

    /**
     * Update row(s) in a content URI.
     *
     * If the content provider supports transactions the update will be atomic.
     *
     * @param uri The URI to modify.
     * @param values The new field values. The key is the column name for the field.
                     A null value will remove an existing field value.
     * @param where A filter to apply to rows before updating, formatted as an SQL WHERE clause
                    (excluding the WHERE itself).
     * @return the number of rows updated.
     * @throws NullPointerException if uri or values are null
     */
    public final int update(@RequiresPermission.Write @NonNull Uri uri,
            @Nullable ContentValues values, @Nullable String where,
            @Nullable String[] selectionArgs) {
        Preconditions.checkNotNull(uri, "uri");
        IContentProvider provider = acquireProvider(uri);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        try {
            long startTime = SystemClock.uptimeMillis();
            int rowsUpdated = provider.update(mPackageName, uri, values, where, selectionArgs);
            long durationMillis = SystemClock.uptimeMillis() - startTime;
            maybeLogUpdateToEventLog(durationMillis, uri, "update", where);
            return rowsUpdated;
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return -1;
        } finally {
            releaseProvider(provider);
        }
    }

    /**
     * Call a provider-defined method.  This can be used to implement
     * read or write interfaces which are cheaper than using a Cursor and/or
     * do not fit into the traditional table model.
     *
     * @param method provider-defined method name to call.  Opaque to
     *   framework, but must be non-null.
     * @param arg provider-defined String argument.  May be null.
     * @param extras provider-defined Bundle argument.  May be null.
     * @return a result Bundle, possibly null.  Will be null if the ContentProvider
     *   does not implement call.
     * @throws NullPointerException if uri or method is null
     * @throws IllegalArgumentException if uri is not known
     */
    public final @Nullable Bundle call(@NonNull Uri uri, @NonNull String method,
            @Nullable String arg, @Nullable Bundle extras) {
        Preconditions.checkNotNull(uri, "uri");
        Preconditions.checkNotNull(method, "method");
        IContentProvider provider = acquireProvider(uri);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        try {
            final Bundle res = provider.call(mPackageName, method, arg, extras);
            Bundle.setDefusable(res, true);
            return res;
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        } finally {
            releaseProvider(provider);
        }
    }

    /**
     * Returns the content provider for the given content URI.
     *
     * @param uri The URI to a content provider
     * @return The ContentProvider for the given URI, or null if no content provider is found.
     * @hide
     */
    public final IContentProvider acquireProvider(Uri uri) {
        if (!SCHEME_CONTENT.equals(uri.getScheme())) {
            return null;
        }
        final String auth = uri.getAuthority();
        if (auth != null) {
            return acquireProvider(mContext, auth);
        }
        return null;
    }

    /**
     * Returns the content provider for the given content URI if the process
     * already has a reference on it.
     *
     * @param uri The URI to a content provider
     * @return The ContentProvider for the given URI, or null if no content provider is found.
     * @hide
     */
    public final IContentProvider acquireExistingProvider(Uri uri) {
        if (!SCHEME_CONTENT.equals(uri.getScheme())) {
            return null;
        }
        final String auth = uri.getAuthority();
        if (auth != null) {
            return acquireExistingProvider(mContext, auth);
        }
        return null;
    }

    /**
     * @hide
     */
    public final IContentProvider acquireProvider(String name) {
        if (name == null) {
            return null;
        }
        return acquireProvider(mContext, name);
    }

    /**
     * Returns the content provider for the given content URI.
     *
     * @param uri The URI to a content provider
     * @return The ContentProvider for the given URI, or null if no content provider is found.
     * @hide
     */
    public final IContentProvider acquireUnstableProvider(Uri uri) {
        if (!SCHEME_CONTENT.equals(uri.getScheme())) {
            return null;
        }
        String auth = uri.getAuthority();
        if (auth != null) {
            return acquireUnstableProvider(mContext, uri.getAuthority());
        }
        return null;
    }

    /**
     * @hide
     */
    public final IContentProvider acquireUnstableProvider(String name) {
        if (name == null) {
            return null;
        }
        return acquireUnstableProvider(mContext, name);
    }

    /**
     * Returns a {@link ContentProviderClient} that is associated with the {@link ContentProvider}
     * that services the content at uri, starting the provider if necessary. Returns
     * null if there is no provider associated wih the uri. The caller must indicate that they are
     * done with the provider by calling {@link ContentProviderClient#release} which will allow
     * the system to release the provider if it determines that there is no other reason for
     * keeping it active.
     * @param uri specifies which provider should be acquired
     * @return a {@link ContentProviderClient} that is associated with the {@link ContentProvider}
     * that services the content at uri or null if there isn't one.
     */
    public final @Nullable ContentProviderClient acquireContentProviderClient(@NonNull Uri uri) {
        Preconditions.checkNotNull(uri, "uri");
        IContentProvider provider = acquireProvider(uri);
        if (provider != null) {
            return new ContentProviderClient(this, provider, true);
        }
        return null;
    }

    /**
     * Returns a {@link ContentProviderClient} that is associated with the {@link ContentProvider}
     * with the authority of name, starting the provider if necessary. Returns
     * null if there is no provider associated wih the uri. The caller must indicate that they are
     * done with the provider by calling {@link ContentProviderClient#release} which will allow
     * the system to release the provider if it determines that there is no other reason for
     * keeping it active.
     * @param name specifies which provider should be acquired
     * @return a {@link ContentProviderClient} that is associated with the {@link ContentProvider}
     * with the authority of name or null if there isn't one.
     */
    public final @Nullable ContentProviderClient acquireContentProviderClient(
            @NonNull String name) {
        Preconditions.checkNotNull(name, "name");
        IContentProvider provider = acquireProvider(name);
        if (provider != null) {
            return new ContentProviderClient(this, provider, true);
        }

        return null;
    }

    /**
     * Like {@link #acquireContentProviderClient(Uri)}, but for use when you do
     * not trust the stability of the target content provider.  This turns off
     * the mechanism in the platform clean up processes that are dependent on
     * a content provider if that content provider's process goes away.  Normally
     * you can safely assume that once you have acquired a provider, you can freely
     * use it as needed and it won't disappear, even if your process is in the
     * background.  If using this method, you need to take care to deal with any
     * failures when communicating with the provider, and be sure to close it
     * so that it can be re-opened later.  In particular, catching a
     * {@link android.os.DeadObjectException} from the calls there will let you
     * know that the content provider has gone away; at that point the current
     * ContentProviderClient object is invalid, and you should release it.  You
     * can acquire a new one if you would like to try to restart the provider
     * and perform new operations on it.
     */
    public final @Nullable ContentProviderClient acquireUnstableContentProviderClient(
            @NonNull Uri uri) {
        Preconditions.checkNotNull(uri, "uri");
        IContentProvider provider = acquireUnstableProvider(uri);
        if (provider != null) {
            return new ContentProviderClient(this, provider, false);
        }

        return null;
    }

    /**
     * Like {@link #acquireContentProviderClient(String)}, but for use when you do
     * not trust the stability of the target content provider.  This turns off
     * the mechanism in the platform clean up processes that are dependent on
     * a content provider if that content provider's process goes away.  Normally
     * you can safely assume that once you have acquired a provider, you can freely
     * use it as needed and it won't disappear, even if your process is in the
     * background.  If using this method, you need to take care to deal with any
     * failures when communicating with the provider, and be sure to close it
     * so that it can be re-opened later.  In particular, catching a
     * {@link android.os.DeadObjectException} from the calls there will let you
     * know that the content provider has gone away; at that point the current
     * ContentProviderClient object is invalid, and you should release it.  You
     * can acquire a new one if you would like to try to restart the provider
     * and perform new operations on it.
     */
    public final @Nullable ContentProviderClient acquireUnstableContentProviderClient(
            @NonNull String name) {
        Preconditions.checkNotNull(name, "name");
        IContentProvider provider = acquireUnstableProvider(name);
        if (provider != null) {
            return new ContentProviderClient(this, provider, false);
        }

        return null;
    }

    /**
     * Register an observer class that gets callbacks when data identified by a
     * given content URI changes.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#O}, all content
     * notifications must be backed by a valid {@link ContentProvider}.
     *
     * @param uri The URI to watch for changes. This can be a specific row URI,
     *            or a base URI for a whole class of content.
     * @param notifyForDescendants When false, the observer will be notified
     *            whenever a change occurs to the exact URI specified by
     *            <code>uri</code> or to one of the URI's ancestors in the path
     *            hierarchy. When true, the observer will also be notified
     *            whenever a change occurs to the URI's descendants in the path
     *            hierarchy.
     * @param observer The object that receives callbacks when changes occur.
     * @see #unregisterContentObserver
     */
    public final void registerContentObserver(@NonNull Uri uri, boolean notifyForDescendants,
            @NonNull ContentObserver observer) {
        Preconditions.checkNotNull(uri, "uri");
        Preconditions.checkNotNull(observer, "observer");
        registerContentObserver(
                ContentProvider.getUriWithoutUserId(uri),
                notifyForDescendants,
                observer,
                ContentProvider.getUserIdFromUri(uri, mContext.getUserId()));
    }

    /** @hide - designated user version */
    public final void registerContentObserver(Uri uri, boolean notifyForDescendents,
            ContentObserver observer, @UserIdInt int userHandle) {
        try {
            getContentService().registerContentObserver(uri, notifyForDescendents,
                    observer.getContentObserver(), userHandle, mTargetSdkVersion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a change observer.
     *
     * @param observer The previously registered observer that is no longer needed.
     * @see #registerContentObserver
     */
    public final void unregisterContentObserver(@NonNull ContentObserver observer) {
        Preconditions.checkNotNull(observer, "observer");
        try {
            IContentObserver contentObserver = observer.releaseContentObserver();
            if (contentObserver != null) {
                getContentService().unregisterContentObserver(
                        contentObserver);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notify registered observers that a row was updated and attempt to sync
     * changes to the network.
     * <p>
     * To observe events sent through this call, use
     * {@link #registerContentObserver(Uri, boolean, ContentObserver)}.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#O}, all content
     * notifications must be backed by a valid {@link ContentProvider}.
     *
     * @param uri The uri of the content that was changed.
     * @param observer The observer that originated the change, may be
     *            <code>null</null>. The observer that originated the change
     *            will only receive the notification if it has requested to
     *            receive self-change notifications by implementing
     *            {@link ContentObserver#deliverSelfNotifications()} to return
     *            true.
     */
    public void notifyChange(@NonNull Uri uri, @Nullable ContentObserver observer) {
        notifyChange(uri, observer, true /* sync to network */);
    }

    /**
     * Notify registered observers that a row was updated.
     * <p>
     * To observe events sent through this call, use
     * {@link #registerContentObserver(Uri, boolean, ContentObserver)}.
     * <p>
     * If syncToNetwork is true, this will attempt to schedule a local sync
     * using the sync adapter that's registered for the authority of the
     * provided uri. No account will be passed to the sync adapter, so all
     * matching accounts will be synchronized.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#O}, all content
     * notifications must be backed by a valid {@link ContentProvider}.
     *
     * @param uri The uri of the content that was changed.
     * @param observer The observer that originated the change, may be
     *            <code>null</null>. The observer that originated the change
     *            will only receive the notification if it has requested to
     *            receive self-change notifications by implementing
     *            {@link ContentObserver#deliverSelfNotifications()} to return
     *            true.
     * @param syncToNetwork If true, same as {@link #NOTIFY_SYNC_TO_NETWORK}.
     * @see #requestSync(android.accounts.Account, String, android.os.Bundle)
     */
    public void notifyChange(@NonNull Uri uri, @Nullable ContentObserver observer,
            boolean syncToNetwork) {
        Preconditions.checkNotNull(uri, "uri");
        notifyChange(
                ContentProvider.getUriWithoutUserId(uri),
                observer,
                syncToNetwork,
                ContentProvider.getUserIdFromUri(uri, mContext.getUserId()));
    }

    /**
     * Notify registered observers that a row was updated.
     * <p>
     * To observe events sent through this call, use
     * {@link #registerContentObserver(Uri, boolean, ContentObserver)}.
     * <p>
     * If syncToNetwork is true, this will attempt to schedule a local sync
     * using the sync adapter that's registered for the authority of the
     * provided uri. No account will be passed to the sync adapter, so all
     * matching accounts will be synchronized.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#O}, all content
     * notifications must be backed by a valid {@link ContentProvider}.
     *
     * @param uri The uri of the content that was changed.
     * @param observer The observer that originated the change, may be
     *            <code>null</null>. The observer that originated the change
     *            will only receive the notification if it has requested to
     *            receive self-change notifications by implementing
     *            {@link ContentObserver#deliverSelfNotifications()} to return
     *            true.
     * @param flags Additional flags: {@link #NOTIFY_SYNC_TO_NETWORK}.
     * @see #requestSync(android.accounts.Account, String, android.os.Bundle)
     */
    public void notifyChange(@NonNull Uri uri, @Nullable ContentObserver observer,
            @NotifyFlags int flags) {
        Preconditions.checkNotNull(uri, "uri");
        notifyChange(
                ContentProvider.getUriWithoutUserId(uri),
                observer,
                flags,
                ContentProvider.getUserIdFromUri(uri, mContext.getUserId()));
    }

    /**
     * Notify registered observers within the designated user(s) that a row was updated.
     *
     * @hide
     */
    public void notifyChange(@NonNull Uri uri, ContentObserver observer, boolean syncToNetwork,
            @UserIdInt int userHandle) {
        try {
            getContentService().notifyChange(
                    uri, observer == null ? null : observer.getContentObserver(),
                    observer != null && observer.deliverSelfNotifications(),
                    syncToNetwork ? NOTIFY_SYNC_TO_NETWORK : 0,
                    userHandle, mTargetSdkVersion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notify registered observers within the designated user(s) that a row was updated.
     *
     * @hide
     */
    public void notifyChange(@NonNull Uri uri, ContentObserver observer, @NotifyFlags int flags,
            @UserIdInt int userHandle) {
        try {
            getContentService().notifyChange(
                    uri, observer == null ? null : observer.getContentObserver(),
                    observer != null && observer.deliverSelfNotifications(), flags,
                    userHandle, mTargetSdkVersion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Take a persistable URI permission grant that has been offered. Once
     * taken, the permission grant will be remembered across device reboots.
     * Only URI permissions granted with
     * {@link Intent#FLAG_GRANT_PERSISTABLE_URI_PERMISSION} can be persisted. If
     * the grant has already been persisted, taking it again will touch
     * {@link UriPermission#getPersistedTime()}.
     *
     * @see #getPersistedUriPermissions()
     */
    public void takePersistableUriPermission(@NonNull Uri uri,
            @Intent.AccessUriMode int modeFlags) {
        Preconditions.checkNotNull(uri, "uri");
        try {
            ActivityManager.getService().takePersistableUriPermission(
                    ContentProvider.getUriWithoutUserId(uri), modeFlags, /* toPackage= */ null,
                    resolveUserId(uri));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void takePersistableUriPermission(@NonNull String toPackage, @NonNull Uri uri,
            @Intent.AccessUriMode int modeFlags) {
        Preconditions.checkNotNull(toPackage, "toPackage");
        Preconditions.checkNotNull(uri, "uri");
        try {
            ActivityManager.getService().takePersistableUriPermission(
                    ContentProvider.getUriWithoutUserId(uri), modeFlags, toPackage,
                    resolveUserId(uri));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Relinquish a persisted URI permission grant. The URI must have been
     * previously made persistent with
     * {@link #takePersistableUriPermission(Uri, int)}. Any non-persistent
     * grants to the calling package will remain intact.
     *
     * @see #getPersistedUriPermissions()
     */
    public void releasePersistableUriPermission(@NonNull Uri uri,
            @Intent.AccessUriMode int modeFlags) {
        Preconditions.checkNotNull(uri, "uri");
        try {
            ActivityManager.getService().releasePersistableUriPermission(
                    ContentProvider.getUriWithoutUserId(uri), modeFlags, /* toPackage= */ null,
                    resolveUserId(uri));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all URI permission grants that have been persisted by the
     * calling app. That is, the returned permissions have been granted
     * <em>to</em> the calling app. Only persistable grants taken with
     * {@link #takePersistableUriPermission(Uri, int)} are returned.
     * <p>Note: Some of the returned URIs may not be usable until after the user is unlocked.
     *
     * @see #takePersistableUriPermission(Uri, int)
     * @see #releasePersistableUriPermission(Uri, int)
     */
    public @NonNull List<UriPermission> getPersistedUriPermissions() {
        try {
            return ActivityManager.getService()
                    .getPersistedUriPermissions(mPackageName, true).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all persisted URI permission grants that are hosted by the
     * calling app. That is, the returned permissions have been granted
     * <em>from</em> the calling app. Only grants taken with
     * {@link #takePersistableUriPermission(Uri, int)} are returned.
     * <p>Note: Some of the returned URIs may not be usable until after the user is unlocked.
     */
    public @NonNull List<UriPermission> getOutgoingPersistedUriPermissions() {
        try {
            return ActivityManager.getService()
                    .getPersistedUriPermissions(mPackageName, false).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start an asynchronous sync operation. If you want to monitor the progress
     * of the sync you may register a SyncObserver. Only values of the following
     * types may be used in the extras bundle:
     * <ul>
     * <li>Integer</li>
     * <li>Long</li>
     * <li>Boolean</li>
     * <li>Float</li>
     * <li>Double</li>
     * <li>String</li>
     * <li>Account</li>
     * <li>null</li>
     * </ul>
     *
     * @param uri the uri of the provider to sync or null to sync all providers.
     * @param extras any extras to pass to the SyncAdapter.
     * @deprecated instead use
     * {@link #requestSync(android.accounts.Account, String, android.os.Bundle)}
     */
    @Deprecated
    public void startSync(Uri uri, Bundle extras) {
        Account account = null;
        if (extras != null) {
            String accountName = extras.getString(SYNC_EXTRAS_ACCOUNT);
            if (!TextUtils.isEmpty(accountName)) {
                // TODO: No references to Google in AOSP
                account = new Account(accountName, "com.google");
            }
            extras.remove(SYNC_EXTRAS_ACCOUNT);
        }
        requestSync(account, uri != null ? uri.getAuthority() : null, extras);
    }

    /**
     * Start an asynchronous sync operation. If you want to monitor the progress
     * of the sync you may register a SyncObserver. Only values of the following
     * types may be used in the extras bundle:
     * <ul>
     * <li>Integer</li>
     * <li>Long</li>
     * <li>Boolean</li>
     * <li>Float</li>
     * <li>Double</li>
     * <li>String</li>
     * <li>Account</li>
     * <li>null</li>
     * </ul>
     *
     * @param account which account should be synced
     * @param authority which authority should be synced
     * @param extras any extras to pass to the SyncAdapter.
     */
    public static void requestSync(Account account, String authority, Bundle extras) {
        requestSyncAsUser(account, authority, UserHandle.myUserId(), extras);
    }

    /**
     * @see #requestSync(Account, String, Bundle)
     * @hide
     */
    public static void requestSyncAsUser(Account account, String authority, @UserIdInt int userId,
            Bundle extras) {
        if (extras == null) {
            throw new IllegalArgumentException("Must specify extras.");
        }
        SyncRequest request =
            new SyncRequest.Builder()
                .setSyncAdapter(account, authority)
                .setExtras(extras)
                .syncOnce()     // Immediate sync.
                .build();
        try {
            getContentService().syncAsUser(request, userId);
        } catch(RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register a sync with the SyncManager. These requests are built using the
     * {@link SyncRequest.Builder}.
     */
    public static void requestSync(SyncRequest request) {
        try {
            getContentService().sync(request);
        } catch(RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check that only values of the following types are in the Bundle:
     * <ul>
     * <li>Integer</li>
     * <li>Long</li>
     * <li>Boolean</li>
     * <li>Float</li>
     * <li>Double</li>
     * <li>String</li>
     * <li>Account</li>
     * <li>null</li>
     * </ul>
     * @param extras the Bundle to check
     */
    public static void validateSyncExtrasBundle(Bundle extras) {
        try {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value == null) continue;
                if (value instanceof Long) continue;
                if (value instanceof Integer) continue;
                if (value instanceof Boolean) continue;
                if (value instanceof Float) continue;
                if (value instanceof Double) continue;
                if (value instanceof String) continue;
                if (value instanceof Account) continue;
                throw new IllegalArgumentException("unexpected value type: "
                        + value.getClass().getName());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException("error unparceling Bundle", exc);
        }
    }

    /**
     * Cancel any active or pending syncs that match the Uri. If the uri is null then
     * all syncs will be canceled.
     *
     * @param uri the uri of the provider to sync or null to sync all providers.
     * @deprecated instead use {@link #cancelSync(android.accounts.Account, String)}
     */
    @Deprecated
    public void cancelSync(Uri uri) {
        cancelSync(null /* all accounts */, uri != null ? uri.getAuthority() : null);
    }

    /**
     * Cancel any active or pending syncs that match account and authority. The account and
     * authority can each independently be set to null, which means that syncs with any account
     * or authority, respectively, will match.
     *
     * @param account filters the syncs that match by this account
     * @param authority filters the syncs that match by this authority
     */
    public static void cancelSync(Account account, String authority) {
        try {
            getContentService().cancelSync(account, authority, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see #cancelSync(Account, String)
     * @hide
     */
    public static void cancelSyncAsUser(Account account, String authority, @UserIdInt int userId) {
        try {
            getContentService().cancelSyncAsUser(account, authority, null, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get information about the SyncAdapters that are known to the system.
     * @return an array of SyncAdapters that have registered with the system
     */
    public static SyncAdapterType[] getSyncAdapterTypes() {
        try {
            return getContentService().getSyncAdapterTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see #getSyncAdapterTypes()
     * @hide
     */
    public static SyncAdapterType[] getSyncAdapterTypesAsUser(@UserIdInt int userId) {
        try {
            return getContentService().getSyncAdapterTypesAsUser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns the package names of syncadapters that match a given user and authority.
     */
    @TestApi
    public static String[] getSyncAdapterPackagesForAuthorityAsUser(String authority,
            @UserIdInt int userId) {
        try {
            return getContentService().getSyncAdapterPackagesForAuthorityAsUser(authority, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if the provider should be synced when a network tickle is received
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#READ_SYNC_SETTINGS}.
     *
     * @param account the account whose setting we are querying
     * @param authority the provider whose setting we are querying
     * @return true if the provider should be synced when a network tickle is received
     */
    public static boolean getSyncAutomatically(Account account, String authority) {
        try {
            return getContentService().getSyncAutomatically(account, authority);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see #getSyncAutomatically(Account, String)
     * @hide
     */
    public static boolean getSyncAutomaticallyAsUser(Account account, String authority,
            @UserIdInt int userId) {
        try {
            return getContentService().getSyncAutomaticallyAsUser(account, authority, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether or not the provider is synced when it receives a network tickle.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#WRITE_SYNC_SETTINGS}.
     *
     * @param account the account whose setting we are querying
     * @param authority the provider whose behavior is being controlled
     * @param sync true if the provider should be synced when tickles are received for it
     */
    public static void setSyncAutomatically(Account account, String authority, boolean sync) {
        setSyncAutomaticallyAsUser(account, authority, sync, UserHandle.myUserId());
    }

    /**
     * @see #setSyncAutomatically(Account, String, boolean)
     * @hide
     */
    public static void setSyncAutomaticallyAsUser(Account account, String authority, boolean sync,
            @UserIdInt int userId) {
        try {
            getContentService().setSyncAutomaticallyAsUser(account, authority, sync, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Specifies that a sync should be requested with the specified the account, authority,
     * and extras at the given frequency. If there is already another periodic sync scheduled
     * with the account, authority and extras then a new periodic sync won't be added, instead
     * the frequency of the previous one will be updated.
     * <p>
     * These periodic syncs honor the "syncAutomatically" and "masterSyncAutomatically" settings.
     * Although these sync are scheduled at the specified frequency, it may take longer for it to
     * actually be started if other syncs are ahead of it in the sync operation queue. This means
     * that the actual start time may drift.
     * <p>
     * Periodic syncs are not allowed to have any of {@link #SYNC_EXTRAS_DO_NOT_RETRY},
     * {@link #SYNC_EXTRAS_IGNORE_BACKOFF}, {@link #SYNC_EXTRAS_IGNORE_SETTINGS},
     * {@link #SYNC_EXTRAS_INITIALIZE}, {@link #SYNC_EXTRAS_FORCE},
     * {@link #SYNC_EXTRAS_EXPEDITED}, {@link #SYNC_EXTRAS_MANUAL} set to true.
     * If any are supplied then an {@link IllegalArgumentException} will be thrown.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#WRITE_SYNC_SETTINGS}.
     * <p>The bundle for a periodic sync can be queried by applications with the correct
     * permissions using
     * {@link ContentResolver#getPeriodicSyncs(Account account, String provider)}, so no
     * sensitive data should be transferred here.
     *
     * @param account the account to specify in the sync
     * @param authority the provider to specify in the sync request
     * @param extras extra parameters to go along with the sync request
     * @param pollFrequency how frequently the sync should be performed, in seconds.
     * On Android API level 24 and above, a minmam interval of 15 minutes is enforced.
     * On previous versions, the minimum interval is 1 hour.
     * @throws IllegalArgumentException if an illegal extra was set or if any of the parameters
     * are null.
     */
    public static void addPeriodicSync(Account account, String authority, Bundle extras,
            long pollFrequency) {
        validateSyncExtrasBundle(extras);
        if (invalidPeriodicExtras(extras)) {
            throw new IllegalArgumentException("illegal extras were set");
        }
        try {
             getContentService().addPeriodicSync(account, authority, extras, pollFrequency);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * {@hide}
     * Helper function to throw an <code>IllegalArgumentException</code> if any illegal
     * extras were set for a periodic sync.
     *
     * @param extras bundle to validate.
     */
    public static boolean invalidPeriodicExtras(Bundle extras) {
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_FORCE, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false)) {
            return true;
        }
        return false;
    }

    /**
     * Remove a periodic sync. Has no affect if account, authority and extras don't match
     * an existing periodic sync.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#WRITE_SYNC_SETTINGS}.
     *
     * @param account the account of the periodic sync to remove
     * @param authority the provider of the periodic sync to remove
     * @param extras the extras of the periodic sync to remove
     */
    public static void removePeriodicSync(Account account, String authority, Bundle extras) {
        validateSyncExtrasBundle(extras);
        try {
            getContentService().removePeriodicSync(account, authority, extras);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the specified sync. This will cancel any pending or active syncs. If the request is
     * for a periodic sync, this call will remove any future occurrences.
     * <p>
     *     If a periodic sync is specified, the caller must hold the permission
     *     {@link android.Manifest.permission#WRITE_SYNC_SETTINGS}.
     *</p>
     * It is possible to cancel a sync using a SyncRequest object that is not the same object
     * with which you requested the sync. Do so by building a SyncRequest with the same
     * adapter, frequency, <b>and</b> extras bundle.
     *
     * @param request SyncRequest object containing information about sync to cancel.
     */
    public static void cancelSync(SyncRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        try {
            getContentService().cancelRequest(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the list of information about the periodic syncs for the given account and authority.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#READ_SYNC_SETTINGS}.
     *
     * @param account the account whose periodic syncs we are querying
     * @param authority the provider whose periodic syncs we are querying
     * @return a list of PeriodicSync objects. This list may be empty but will never be null.
     */
    public static List<PeriodicSync> getPeriodicSyncs(Account account, String authority) {
        try {
            return getContentService().getPeriodicSyncs(account, authority, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if this account/provider is syncable.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#READ_SYNC_SETTINGS}.
     * @return >0 if it is syncable, 0 if not, and <0 if the state isn't known yet.
     */
    public static int getIsSyncable(Account account, String authority) {
        try {
            return getContentService().getIsSyncable(account, authority);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see #getIsSyncable(Account, String)
     * @hide
     */
    public static int getIsSyncableAsUser(Account account, String authority,
            @UserIdInt int userId) {
        try {
            return getContentService().getIsSyncableAsUser(account, authority, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether this account/provider is syncable.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#WRITE_SYNC_SETTINGS}.
     * @param syncable >0 denotes syncable, 0 means not syncable, <0 means unknown
     */
    public static void setIsSyncable(Account account, String authority, int syncable) {
        try {
            getContentService().setIsSyncable(account, authority, syncable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the master auto-sync setting that applies to all the providers and accounts.
     * If this is false then the per-provider auto-sync setting is ignored.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#READ_SYNC_SETTINGS}.
     *
     * @return the master auto-sync setting that applies to all the providers and accounts
     */
    public static boolean getMasterSyncAutomatically() {
        try {
            return getContentService().getMasterSyncAutomatically();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see #getMasterSyncAutomatically()
     * @hide
     */
    public static boolean getMasterSyncAutomaticallyAsUser(@UserIdInt int userId) {
        try {
            return getContentService().getMasterSyncAutomaticallyAsUser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the master auto-sync setting that applies to all the providers and accounts.
     * If this is false then the per-provider auto-sync setting is ignored.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#WRITE_SYNC_SETTINGS}.
     *
     * @param sync the master auto-sync setting that applies to all the providers and accounts
     */
    public static void setMasterSyncAutomatically(boolean sync) {
        setMasterSyncAutomaticallyAsUser(sync, UserHandle.myUserId());
    }

    /**
     * @see #setMasterSyncAutomatically(boolean)
     * @hide
     */
    public static void setMasterSyncAutomaticallyAsUser(boolean sync, @UserIdInt int userId) {
        try {
            getContentService().setMasterSyncAutomaticallyAsUser(sync, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if there is currently a sync operation for the given account or authority
     * actively being processed.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#READ_SYNC_STATS}.
     * @param account the account whose setting we are querying
     * @param authority the provider whose behavior is being queried
     * @return true if a sync is active for the given account or authority.
     */
    public static boolean isSyncActive(Account account, String authority) {
        if (account == null) {
            throw new IllegalArgumentException("account must not be null");
        }
        if (authority == null) {
            throw new IllegalArgumentException("authority must not be null");
        }

        try {
            return getContentService().isSyncActive(account, authority, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * If a sync is active returns the information about it, otherwise returns null.
     * <p>
     * This method requires the caller to hold the permission
     * {@link android.Manifest.permission#READ_SYNC_STATS}.
     * <p>
     * @return the SyncInfo for the currently active sync or null if one is not active.
     * @deprecated
     * Since multiple concurrent syncs are now supported you should use
     * {@link #getCurrentSyncs()} to get the accurate list of current syncs.
     * This method returns the first item from the list of current syncs
     * or null if there are none.
     */
    @Deprecated
    public static SyncInfo getCurrentSync() {
        try {
            final List<SyncInfo> syncs = getContentService().getCurrentSyncs();
            if (syncs.isEmpty()) {
                return null;
            }
            return syncs.get(0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list with information about all the active syncs. This list will be empty
     * if there are no active syncs.
     * <p>
     * This method requires the caller to hold the permission
     * {@link android.Manifest.permission#READ_SYNC_STATS}.
     * <p>
     * @return a List of SyncInfo objects for the currently active syncs.
     */
    public static List<SyncInfo> getCurrentSyncs() {
        try {
            return getContentService().getCurrentSyncs();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see #getCurrentSyncs()
     * @hide
     */
    public static List<SyncInfo> getCurrentSyncsAsUser(@UserIdInt int userId) {
        try {
            return getContentService().getCurrentSyncsAsUser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the status that matches the authority.
     * @param account the account whose setting we are querying
     * @param authority the provider whose behavior is being queried
     * @return the SyncStatusInfo for the authority, or null if none exists
     * @hide
     */
    public static SyncStatusInfo getSyncStatus(Account account, String authority) {
        try {
            return getContentService().getSyncStatus(account, authority, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see #getSyncStatus(Account, String)
     * @hide
     */
    public static SyncStatusInfo getSyncStatusAsUser(Account account, String authority,
            @UserIdInt int userId) {
        try {
            return getContentService().getSyncStatusAsUser(account, authority, null, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return true if the pending status is true of any matching authorities.
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#READ_SYNC_STATS}.
     * @param account the account whose setting we are querying
     * @param authority the provider whose behavior is being queried
     * @return true if there is a pending sync with the matching account and authority
     */
    public static boolean isSyncPending(Account account, String authority) {
        return isSyncPendingAsUser(account, authority, UserHandle.myUserId());
    }

    /**
     * @see #requestSync(Account, String, Bundle)
     * @hide
     */
    public static boolean isSyncPendingAsUser(Account account, String authority,
            @UserIdInt int userId) {
        try {
            return getContentService().isSyncPendingAsUser(account, authority, null, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request notifications when the different aspects of the SyncManager change. The
     * different items that can be requested are:
     * <ul>
     * <li> {@link #SYNC_OBSERVER_TYPE_PENDING}
     * <li> {@link #SYNC_OBSERVER_TYPE_ACTIVE}
     * <li> {@link #SYNC_OBSERVER_TYPE_SETTINGS}
     * </ul>
     * The caller can set one or more of the status types in the mask for any
     * given listener registration.
     * @param mask the status change types that will cause the callback to be invoked
     * @param callback observer to be invoked when the status changes
     * @return a handle that can be used to remove the listener at a later time
     */
    public static Object addStatusChangeListener(int mask, final SyncStatusObserver callback) {
        if (callback == null) {
            throw new IllegalArgumentException("you passed in a null callback");
        }
        try {
            ISyncStatusObserver.Stub observer = new ISyncStatusObserver.Stub() {
                @Override
                public void onStatusChanged(int which) throws RemoteException {
                    callback.onStatusChanged(which);
                }
            };
            getContentService().addStatusChangeListener(mask, observer);
            return observer;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a previously registered status change listener.
     * @param handle the handle that was returned by {@link #addStatusChangeListener}
     */
    public static void removeStatusChangeListener(Object handle) {
        if (handle == null) {
            throw new IllegalArgumentException("you passed in a null handle");
        }
        try {
            getContentService().removeStatusChangeListener((ISyncStatusObserver.Stub) handle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void putCache(Uri key, Bundle value) {
        try {
            getContentService().putCache(mContext.getPackageName(), key, value,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public Bundle getCache(Uri key) {
        try {
            final Bundle bundle = getContentService().getCache(mContext.getPackageName(), key,
                    mContext.getUserId());
            if (bundle != null) bundle.setClassLoader(mContext.getClassLoader());
            return bundle;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public int getTargetSdkVersion() {
        return mTargetSdkVersion;
    }

    /**
     * Returns sampling percentage for a given duration.
     *
     * Always returns at least 1%.
     */
    private int samplePercentForDuration(long durationMillis) {
        if (durationMillis >= SLOW_THRESHOLD_MILLIS) {
            return 100;
        }
        return (int) (100 * durationMillis / SLOW_THRESHOLD_MILLIS) + 1;
    }

    private void maybeLogQueryToEventLog(
            long durationMillis, Uri uri, String[] projection, @Nullable Bundle queryArgs) {
        if (!ENABLE_CONTENT_SAMPLE) return;
        int samplePercent = samplePercentForDuration(durationMillis);
        if (samplePercent < 100) {
            synchronized (mRandom) {
                if (mRandom.nextInt(100) >= samplePercent) {
                    return;
                }
            }
        }

        // Ensure a non-null bundle.
        queryArgs = (queryArgs != null) ? queryArgs : Bundle.EMPTY;

        StringBuilder projectionBuffer = new StringBuilder(100);
        if (projection != null) {
            for (int i = 0; i < projection.length; ++i) {
                // Note: not using a comma delimiter here, as the
                // multiple arguments to EventLog.writeEvent later
                // stringify with a comma delimiter, which would make
                // parsing uglier later.
                if (i != 0) projectionBuffer.append('/');
                projectionBuffer.append(projection[i]);
            }
        }

        // ActivityThread.currentPackageName() only returns non-null if the
        // current thread is an application main thread.  This parameter tells
        // us whether an event loop is blocked, and if so, which app it is.
        String blockingPackage = AppGlobals.getInitialPackage();

        EventLog.writeEvent(
            EventLogTags.CONTENT_QUERY_SAMPLE,
            uri.toString(),
            projectionBuffer.toString(),
            queryArgs.getString(QUERY_ARG_SQL_SELECTION, ""),
            queryArgs.getString(QUERY_ARG_SQL_SORT_ORDER, ""),
            durationMillis,
            blockingPackage != null ? blockingPackage : "",
            samplePercent);
    }

    private void maybeLogUpdateToEventLog(
        long durationMillis, Uri uri, String operation, String selection) {
        if (!ENABLE_CONTENT_SAMPLE) return;
        int samplePercent = samplePercentForDuration(durationMillis);
        if (samplePercent < 100) {
            synchronized (mRandom) {
                if (mRandom.nextInt(100) >= samplePercent) {
                    return;
                }
            }
        }
        String blockingPackage = AppGlobals.getInitialPackage();
        EventLog.writeEvent(
            EventLogTags.CONTENT_UPDATE_SAMPLE,
            uri.toString(),
            operation,
            selection != null ? selection : "",
            durationMillis,
            blockingPackage != null ? blockingPackage : "",
            samplePercent);
    }

    private final class CursorWrapperInner extends CrossProcessCursorWrapper {
        private final IContentProvider mContentProvider;
        private final AtomicBoolean mProviderReleased = new AtomicBoolean();

        private final CloseGuard mCloseGuard = CloseGuard.get();

        CursorWrapperInner(Cursor cursor, IContentProvider contentProvider) {
            super(cursor);
            mContentProvider = contentProvider;
            mCloseGuard.open("close");
        }

        @Override
        public void close() {
            mCloseGuard.close();
            super.close();

            if (mProviderReleased.compareAndSet(false, true)) {
                ContentResolver.this.releaseProvider(mContentProvider);
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mCloseGuard != null) {
                    mCloseGuard.warnIfOpen();
                }

                close();
            } finally {
                super.finalize();
            }
        }
    }

    private final class ParcelFileDescriptorInner extends ParcelFileDescriptor {
        private final IContentProvider mContentProvider;
        private final AtomicBoolean mProviderReleased = new AtomicBoolean();

        ParcelFileDescriptorInner(ParcelFileDescriptor pfd, IContentProvider icp) {
            super(pfd);
            mContentProvider = icp;
        }

        @Override
        public void releaseResources() {
            if (mProviderReleased.compareAndSet(false, true)) {
                ContentResolver.this.releaseProvider(mContentProvider);
            }
        }
    }

    /** @hide */
    public static final String CONTENT_SERVICE_NAME = "content";

    /** @hide */
    public static IContentService getContentService() {
        if (sContentService != null) {
            return sContentService;
        }
        IBinder b = ServiceManager.getService(CONTENT_SERVICE_NAME);
        sContentService = IContentService.Stub.asInterface(b);
        return sContentService;
    }

    /** @hide */
    public String getPackageName() {
        return mPackageName;
    }

    private static volatile IContentService sContentService;
    private final Context mContext;

    final String mPackageName;
    final int mTargetSdkVersion;

    private static final String TAG = "ContentResolver";

    /** @hide */
    public int resolveUserId(Uri uri) {
        return ContentProvider.getUserIdFromUri(uri, mContext.getUserId());
    }

    /** @hide */
    public int getUserId() {
        return mContext.getUserId();
    }

    /** @hide */
    public Drawable getTypeDrawable(String mimeType) {
        return MimeIconUtils.loadMimeIcon(mContext, mimeType);
    }

    /**
     * @hide
     */
    public static @Nullable Bundle createSqlQueryBundle(
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {

        if (selection == null && selectionArgs == null && sortOrder == null) {
            return null;
        }

        Bundle queryArgs = new Bundle();
        if (selection != null) {
            queryArgs.putString(QUERY_ARG_SQL_SELECTION, selection);
        }
        if (selectionArgs != null) {
            queryArgs.putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        }
        if (sortOrder != null) {
            queryArgs.putString(QUERY_ARG_SQL_SORT_ORDER, sortOrder);
        }
        return queryArgs;
    }

    /**
     * Returns structured sort args formatted as an SQL sort clause.
     *
     * NOTE: Collator clauses are suitable for use with non text fields. We might
     * choose to omit any collation clause since we don't know the underlying
     * type of data to be collated. Imperical testing shows that sqlite3 doesn't
     * appear to care much about the presence of collate clauses in queries
     * when ordering by numeric fields. For this reason we include collate
     * clause unilaterally when {@link #QUERY_ARG_SORT_COLLATION} is present
     * in query args bundle.
     *
     * TODO: Would be nice to explicitly validate that colums referenced in
     * {@link #QUERY_ARG_SORT_COLUMNS} are present in the associated projection.
     *
     * @hide
     */
    public static String createSqlSortClause(Bundle queryArgs) {
        String[] columns = queryArgs.getStringArray(QUERY_ARG_SORT_COLUMNS);
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("Can't create sort clause without columns.");
        }

        String query = TextUtils.join(", ", columns);

        // Interpret PRIMARY and SECONDARY collation strength as no-case collation based
        // on their javadoc descriptions.
        int collation = queryArgs.getInt(
                ContentResolver.QUERY_ARG_SORT_COLLATION, java.text.Collator.IDENTICAL);
        if (collation == java.text.Collator.PRIMARY || collation == java.text.Collator.SECONDARY) {
            query += " COLLATE NOCASE";
        }

        int sortDir = queryArgs.getInt(QUERY_ARG_SORT_DIRECTION, Integer.MIN_VALUE);
        if (sortDir != Integer.MIN_VALUE) {
            switch (sortDir) {
                case QUERY_SORT_DIRECTION_ASCENDING:
                    query += " ASC";
                    break;
                case QUERY_SORT_DIRECTION_DESCENDING:
                    query += " DESC";
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported sort direction value."
                            + " See ContentResolver documentation for details.");
            }
        }
        return query;
    }
}
