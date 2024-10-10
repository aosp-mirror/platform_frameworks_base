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
 * limitations under the License
 */

package android.provider;

import android.accounts.Account;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Activity;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.CursorEntityIterator;
import android.content.Entity;
import android.content.Entity.NamedContentValues;
import android.content.EntityIterator;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.DatabaseUtils;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.telecom.PhoneAccountHandle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.View;

import com.google.android.collect.Sets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <p>
 * The contract between the contacts provider and applications. Contains
 * definitions for the supported URIs and columns. These APIs supersede
 * {@link Contacts}.
 * </p>
 * <h3>Overview</h3>
 * <p>
 * ContactsContract defines an extensible database of contact-related
 * information. Contact information is stored in a three-tier data model:
 * </p>
 * <ul>
 * <li>
 * A row in the {@link Data} table can store any kind of personal data, such
 * as a phone number or email addresses.  The set of data kinds that can be
 * stored in this table is open-ended. There is a predefined set of common
 * kinds, but any application can add its own data kinds.
 * </li>
 * <li>
 * A row in the {@link RawContacts} table represents a set of data describing a
 * person and associated with a single account (for example, one of the user's
 * Gmail accounts).
 * </li>
 * <li>
 * A row in the {@link Contacts} table represents an aggregate of one or more
 * RawContacts presumably describing the same person.  When data in or associated with
 * the RawContacts table is changed, the affected aggregate contacts are updated as
 * necessary.
 * </li>
 * </ul>
 * <p>
 * Other tables include:
 * </p>
 * <ul>
 * <li>
 * {@link Groups}, which contains information about raw contact groups
 * such as Gmail contact groups.  The
 * current API does not support the notion of groups spanning multiple accounts.
 * </li>
 * <li>
 * {@link StatusUpdates}, which contains social status updates including IM
 * availability.
 * </li>
 * <li>
 * {@link AggregationExceptions}, which is used for manual aggregation and
 * disaggregation of raw contacts
 * </li>
 * <li>
 * {@link Settings}, which contains visibility and sync settings for accounts
 * and groups.
 * </li>
 * <li>
 * {@link SyncState}, which contains free-form data maintained on behalf of sync
 * adapters
 * </li>
 * <li>
 * {@link PhoneLookup}, which is used for quick caller-ID lookup</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class ContactsContract {
    /** The authority for the contacts provider */
    public static final String AUTHORITY = "com.android.contacts";
    /** A content:// style uri to the authority for the contacts provider */
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * Prefix for column names that are not visible to client apps.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final String HIDDEN_COLUMN_PREFIX = "x_";

    /**
     * An optional URI parameter for insert, update, or delete queries
     * that allows the caller
     * to specify that it is a sync adapter. The default value is false. If true
     * {@link RawContacts#DIRTY} is not automatically set and the
     * "syncToNetwork" parameter is set to false when calling
     * {@link
     * ContentResolver#notifyChange(android.net.Uri, android.database.ContentObserver, boolean)}.
     * This prevents an unnecessary extra synchronization, see the discussion of
     * the delete operation in {@link RawContacts}.
     */
    public static final String CALLER_IS_SYNCADAPTER = "caller_is_syncadapter";

    /**
     * Query parameter that should be used by the client to access a specific
     * {@link Directory}. The parameter value should be the _ID of the corresponding
     * directory, e.g.
     * {@code content://com.android.contacts/data/emails/filter/acme?directory=3}
     */
    public static final String DIRECTORY_PARAM_KEY = "directory";

    /**
     * A query parameter that limits the number of results returned for supported URIs. The
     * parameter value should be an integer.
     *
     * <p>This parameter is not supported by all URIs.  Supported URIs include, but not limited to,
     * {@link Contacts#CONTENT_URI},
     * {@link RawContacts#CONTENT_URI},
     * {@link Data#CONTENT_URI},
     * {@link CommonDataKinds.Phone#CONTENT_URI},
     * {@link CommonDataKinds.Callable#CONTENT_URI},
     * {@link CommonDataKinds.Email#CONTENT_URI},
     * {@link CommonDataKinds.Contactables#CONTENT_URI},
     *
     * <p>In order to limit the number of rows returned by a non-supported URI, you can implement a
     * {@link CursorWrapper} and override the {@link CursorWrapper#getCount()} methods.
     */
    public static final String LIMIT_PARAM_KEY = "limit";

    /**
     * A query parameter specifing a primary account. This parameter should be used with
     * {@link #PRIMARY_ACCOUNT_TYPE}. The contacts provider handling a query may rely on
     * this information to optimize its query results.
     *
     * For example, in an email composition screen, its implementation can specify an account when
     * obtaining possible recipients, letting the provider know which account is selected during
     * the composition. The provider may use the "primary account" information to optimize
     * the search result.
     */
    public static final String PRIMARY_ACCOUNT_NAME = "name_for_primary_account";

    /**
     * A query parameter specifing a primary account. This parameter should be used with
     * {@link #PRIMARY_ACCOUNT_NAME}. See the doc in {@link #PRIMARY_ACCOUNT_NAME}.
     */
    public static final String PRIMARY_ACCOUNT_TYPE = "type_for_primary_account";

    /**
     * A boolean parameter for {@link Contacts#CONTENT_STREQUENT_URI} and
     * {@link Contacts#CONTENT_STREQUENT_FILTER_URI}, which requires the ContactsProvider to
     * return only phone-related results.
     */
    public static final String STREQUENT_PHONE_ONLY = "strequent_phone_only";

    /**
     * A key to a boolean in the "extras" bundle of the cursor.
     * The boolean indicates that the provider did not create a snippet and that the client asking
     * for the snippet should do it (true means the snippeting was deferred to the client).
     *
     * @see SearchSnippets
     */
    public static final String DEFERRED_SNIPPETING = "deferred_snippeting";

    /**
     * Key to retrieve the original deferred snippeting from the cursor on the client side.
     *
     * @see SearchSnippets
     * @see #DEFERRED_SNIPPETING
     */
    public static final String DEFERRED_SNIPPETING_QUERY = "deferred_snippeting_query";

    /**
     * A boolean parameter for {@link CommonDataKinds.Phone#CONTENT_URI Phone.CONTENT_URI},
     * {@link CommonDataKinds.Email#CONTENT_URI Email.CONTENT_URI}, and
     * {@link CommonDataKinds.StructuredPostal#CONTENT_URI StructuredPostal.CONTENT_URI}.
     * This enables a content provider to remove duplicate entries in results.
     */
    public static final String REMOVE_DUPLICATE_ENTRIES = "remove_duplicate_entries";

    /**
     * <p>
     * API for obtaining a pre-authorized version of a URI that normally requires special
     * permission (beyond READ_CONTACTS) to read.  The caller obtaining the pre-authorized URI
     * must already have the necessary permissions to access the URI; otherwise a
     * {@link SecurityException} will be thrown. Unlike {@link Context#grantUriPermission},
     * this can be used to grant permissions that aren't explicitly required for the URI inside
     * AndroidManifest.xml. For example, permissions that are only required when reading URIs
     * that refer to the user's profile.
     * </p>
     * <p>
     * The authorized URI returned in the bundle contains an expiring token that allows the
     * caller to execute the query without having the special permissions that would normally
     * be required. The token expires in five minutes.
     * </p>
     * <p>
     * This API does not access disk, and should be safe to invoke from the UI thread.
     * </p>
     * <p>
     * Example usage:
     * <pre>
     * Uri profileUri = ContactsContract.Profile.CONTENT_VCARD_URI;
     * Bundle uriBundle = new Bundle();
     * uriBundle.putParcelable(ContactsContract.Authorization.KEY_URI_TO_AUTHORIZE, uri);
     * Bundle authResponse = getContext().getContentResolver().call(
     *         ContactsContract.AUTHORITY_URI,
     *         ContactsContract.Authorization.AUTHORIZATION_METHOD,
     *         null, // String arg, not used.
     *         uriBundle);
     * if (authResponse != null) {
     *     Uri preauthorizedProfileUri = (Uri) authResponse.getParcelable(
     *             ContactsContract.Authorization.KEY_AUTHORIZED_URI);
     *     // This pre-authorized URI can be queried by a caller without READ_PROFILE
     *     // permission.
     * }
     * </pre>
     * </p>
     *
     * @hide
     */
    public static final class Authorization {
        /**
         * The method to invoke to create a pre-authorized URI out of the input argument.
         */
        public static final String AUTHORIZATION_METHOD = "authorize";

        /**
         * The key to set in the outbound Bundle with the URI that should be authorized.
         */
        public static final String KEY_URI_TO_AUTHORIZE = "uri_to_authorize";

        /**
         * The key to retrieve from the returned Bundle to obtain the pre-authorized URI.
         */
        public static final String KEY_AUTHORIZED_URI = "authorized_uri";
    }

    /**
     * A Directory represents a contacts corpus, e.g. Local contacts,
     * Google Apps Global Address List or Corporate Global Address List.
     * <p>
     * A Directory is implemented as a content provider with its unique authority and
     * the same API as the main Contacts Provider.  However, there is no expectation that
     * every directory provider will implement this Contract in its entirety.  If a
     * directory provider does not have an implementation for a specific request, it
     * should throw an UnsupportedOperationException.
     * </p>
     * <p>
     * The most important use case for Directories is search.  A Directory provider is
     * expected to support at least {@link ContactsContract.Contacts#CONTENT_FILTER_URI
     * Contacts.CONTENT_FILTER_URI}.  If a Directory provider wants to participate
     * in email and phone lookup functionalities, it should also implement
     * {@link CommonDataKinds.Email#CONTENT_FILTER_URI CommonDataKinds.Email.CONTENT_FILTER_URI}
     * and
     * {@link CommonDataKinds.Phone#CONTENT_FILTER_URI CommonDataKinds.Phone.CONTENT_FILTER_URI}.
     * </p>
     * <p>
     * A directory provider should return NULL for every projection field it does not
     * recognize, rather than throwing an exception.  This way it will not be broken
     * if ContactsContract is extended with new fields in the future.
     * </p>
     * <p>
     * The client interacts with a directory via Contacts Provider by supplying an
     * optional {@code directory=} query parameter.
     * <p>
     * <p>
     * When the Contacts Provider receives the request, it transforms the URI and forwards
     * the request to the corresponding directory content provider.
     * The URI is transformed in the following fashion:
     * <ul>
     * <li>The URI authority is replaced with the corresponding {@link #DIRECTORY_AUTHORITY}.</li>
     * <li>The {@code accountName=} and {@code accountType=} parameters are added or
     * replaced using the corresponding {@link #ACCOUNT_TYPE} and {@link #ACCOUNT_NAME} values.</li>
     * </ul>
     * </p>
     * <p>
     * Clients should send directory requests to Contacts Provider and let it
     * forward them to the respective providers rather than constructing
     * directory provider URIs by themselves. This level of indirection allows
     * Contacts Provider to implement additional system-level features and
     * optimizations. Access to Contacts Provider is protected by the
     * READ_CONTACTS permission, but access to the directory provider is protected by
     * BIND_DIRECTORY_SEARCH. This permission was introduced at the API level 17, for previous
     * platform versions the provider should perform the following check to make sure the call
     * is coming from the ContactsProvider:
     * <pre>
     * private boolean isCallerAllowed() {
     *   PackageManager pm = getContext().getPackageManager();
     *   for (String packageName: pm.getPackagesForUid(Binder.getCallingUid())) {
     *     if (packageName.equals("com.android.providers.contacts")) {
     *       return true;
     *     }
     *   }
     *   return false;
     * }
     * </pre>
     * </p>
     * <p>
     * The Directory table is read-only and is maintained by the Contacts Provider
     * automatically.
     * </p>
     * <p>It always has at least these two rows:
     * <ul>
     * <li>
     * The local directory. It has {@link Directory#_ID Directory._ID} =
     * {@link Directory#DEFAULT Directory.DEFAULT}. This directory can be used to access locally
     * stored contacts. The same can be achieved by omitting the {@code directory=}
     * parameter altogether.
     * </li>
     * <li>
     * The local invisible contacts. The corresponding directory ID is
     * {@link Directory#LOCAL_INVISIBLE Directory.LOCAL_INVISIBLE}.
     * </li>
     * </ul>
     * </p>
     * <p>Custom Directories are discovered by the Contacts Provider following this procedure:
     * <ul>
     * <li>It finds all installed content providers with meta data identifying them
     * as directory providers in AndroidManifest.xml:
     * <code>
     * &lt;meta-data android:name="android.content.ContactDirectory"
     *               android:value="true" /&gt;
     * </code>
     * <p>
     * This tag should be placed inside the corresponding content provider declaration.
     * </p>
     * </li>
     * <li>
     * Then Contacts Provider sends a {@link Directory#CONTENT_URI Directory.CONTENT_URI}
     * query to each of the directory authorities.  A directory provider must implement
     * this query and return a list of directories.  Each directory returned by
     * the provider must have a unique combination for the {@link #ACCOUNT_NAME} and
     * {@link #ACCOUNT_TYPE} columns (nulls are allowed).  Since directory IDs are assigned
     * automatically, the _ID field will not be part of the query projection.
     * </li>
     * <li>Contacts Provider compiles directory lists received from all directory
     * providers into one, assigns each individual directory a globally unique ID and
     * stores all directory records in the Directory table.
     * </li>
     * </ul>
     * </p>
     * <p>Contacts Provider automatically interrogates newly installed or replaced packages.
     * Thus simply installing a package containing a directory provider is sufficient
     * to have that provider registered.  A package supplying a directory provider does
     * not have to contain launchable activities.
     * </p>
     * <p>
     * Every row in the Directory table is automatically associated with the corresponding package
     * (apk).  If the package is later uninstalled, all corresponding directory rows
     * are automatically removed from the Contacts Provider.
     * </p>
     * <p>
     * When the list of directories handled by a directory provider changes
     * (for instance when the user adds a new Directory account), the directory provider
     * should call {@link #notifyDirectoryChange} to notify the Contacts Provider of the change.
     * In response, the Contacts Provider will requery the directory provider to obtain the
     * new list of directories.
     * </p>
     * <p>
     * A directory row can be optionally associated with an existing account
     * (see {@link android.accounts.AccountManager}). If the account is later removed,
     * the corresponding directory rows are automatically removed from the Contacts Provider.
     * </p>
     */
    public static final class Directory implements BaseColumns {

        /**
         * Not instantiable.
         */
        private Directory() {
        }

        /**
         * The content:// style URI for this table.  Requests to this URI can be
         * performed on the UI thread because they are always unblocking.
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "directories");

        /**
         * URI used for getting all directories from both the calling user and the managed profile
         * that is linked to it.
         * <p>
         * It supports the same semantics as {@link #CONTENT_URI} and returns the same columns.<br>
         * If the device has no managed profile that is linked to the calling user, it behaves
         * in the exact same way as {@link #CONTENT_URI}.<br>
         * If there is a managed profile linked to the calling user, it will return merged results
         * from both.
         * <p>
         * Note: this query returns the calling user results before the managed profile results,
         * and this order is not affected by the sorting parameter.
         *
         */
        public static final Uri ENTERPRISE_CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "directories_enterprise");

        /**
         * Access file provided by remote directory. It allows both calling user and managed profile
         * remote directory, but not local and invisible directory.
         * <p>
         * It is supported only by a few specific places for referring to contact pictures in the
         * remote directory. Contact picture URIs, e.g.
         * {@link PhoneLookup#ENTERPRISE_CONTENT_FILTER_URI}, may contain this kind of URI.
         *
         * @hide
         */
        public static final Uri ENTERPRISE_FILE_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "directory_file_enterprise");


        /**
         * The MIME-type of {@link #CONTENT_URI} providing a directory of
         * contact directories.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/contact_directories";

        /**
         * The MIME type of a {@link #CONTENT_URI} item.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/contact_directory";

        /**
         * _ID of the default directory, which represents locally stored contacts.
         * <b>This is only supported by {@link ContactsContract.Contacts#CONTENT_URI} and
         * {@link ContactsContract.Contacts#CONTENT_FILTER_URI}.
         * Other URLs do not support the concept of "visible" or "invisible" contacts.
         */
        public static final long DEFAULT = 0;

        /**
         * _ID of the directory that represents locally stored invisible contacts.
         */
        public static final long LOCAL_INVISIBLE = 1;

        /**
         * _ID of the managed profile default directory, which represents locally stored contacts.
         */
        public static final long ENTERPRISE_DEFAULT = Directory.ENTERPRISE_DIRECTORY_ID_BASE
                + DEFAULT;

        /**
         * _ID of the managed profile directory that represents locally stored invisible contacts.
         */
        public static final long ENTERPRISE_LOCAL_INVISIBLE = Directory.ENTERPRISE_DIRECTORY_ID_BASE
                + LOCAL_INVISIBLE;

        /**
         * The name of the package that owns this directory. Contacts Provider
         * fill it in with the name of the package containing the directory provider.
         * If the package is later uninstalled, the directories it owns are
         * automatically removed from this table.
         *
         * <p>TYPE: TEXT</p>
         */
        public static final String PACKAGE_NAME = "packageName";

        /**
         * The type of directory captured as a resource ID in the context of the
         * package {@link #PACKAGE_NAME}, e.g. "Corporate Directory"
         *
         * <p>TYPE: INTEGER</p>
         */
        public static final String TYPE_RESOURCE_ID = "typeResourceId";

        /**
         * An optional name that can be used in the UI to represent this directory,
         * e.g. "Acme Corp"
         * <p>TYPE: text</p>
         */
        public static final String DISPLAY_NAME = "displayName";

        /**
         * <p>
         * The authority of the Directory Provider. Contacts Provider will
         * use this authority to forward requests to the directory provider.
         * A directory provider can leave this column empty - Contacts Provider will fill it in.
         * </p>
         * <p>
         * Clients of this API should not send requests directly to this authority.
         * All directory requests must be routed through Contacts Provider.
         * </p>
         *
         * <p>TYPE: text</p>
         */
        public static final String DIRECTORY_AUTHORITY = "authority";

        /**
         * The account type which this directory is associated.
         *
         * <p>TYPE: text</p>
         */
        public static final String ACCOUNT_TYPE = "accountType";

        /**
         * The account with which this directory is associated. If the account is later
         * removed, the directories it owns are automatically removed from this table.
         *
         * <p>TYPE: text</p>
         */
        public static final String ACCOUNT_NAME = "accountName";

        /**
         * Mimimal ID for managed profile directory returned from
         * {@link Directory#ENTERPRISE_CONTENT_URI}.
         *
         * @hide
         */
        // slightly smaller than 2 ** 30
        public static final long ENTERPRISE_DIRECTORY_ID_BASE = 1000000000;

        /**
         * One of {@link #EXPORT_SUPPORT_NONE}, {@link #EXPORT_SUPPORT_ANY_ACCOUNT},
         * {@link #EXPORT_SUPPORT_SAME_ACCOUNT_ONLY}. This is the expectation the
         * directory has for data exported from it.  Clients must obey this setting.
         */
        public static final String EXPORT_SUPPORT = "exportSupport";

        /**
         * An {@link #EXPORT_SUPPORT} setting that indicates that the directory
         * does not allow any data to be copied out of it.
         */
        public static final int EXPORT_SUPPORT_NONE = 0;

        /**
         * An {@link #EXPORT_SUPPORT} setting that indicates that the directory
         * allow its data copied only to the account specified by
         * {@link #ACCOUNT_TYPE}/{@link #ACCOUNT_NAME}.
         */
        public static final int EXPORT_SUPPORT_SAME_ACCOUNT_ONLY = 1;

        /**
         * An {@link #EXPORT_SUPPORT} setting that indicates that the directory
         * allow its data copied to any contacts account.
         */
        public static final int EXPORT_SUPPORT_ANY_ACCOUNT = 2;

        /**
         * One of {@link #SHORTCUT_SUPPORT_NONE}, {@link #SHORTCUT_SUPPORT_DATA_ITEMS_ONLY},
         * {@link #SHORTCUT_SUPPORT_FULL}. This is the expectation the directory
         * has for shortcuts created for its elements. Clients must obey this setting.
         */
        public static final String SHORTCUT_SUPPORT = "shortcutSupport";

        /**
         * An {@link #SHORTCUT_SUPPORT} setting that indicates that the directory
         * does not allow any shortcuts created for its contacts.
         */
        public static final int SHORTCUT_SUPPORT_NONE = 0;

        /**
         * An {@link #SHORTCUT_SUPPORT} setting that indicates that the directory
         * allow creation of shortcuts for data items like email, phone or postal address,
         * but not the entire contact.
         */
        public static final int SHORTCUT_SUPPORT_DATA_ITEMS_ONLY = 1;

        /**
         * An {@link #SHORTCUT_SUPPORT} setting that indicates that the directory
         * allow creation of shortcuts for contact as well as their constituent elements.
         */
        public static final int SHORTCUT_SUPPORT_FULL = 2;

        /**
         * One of {@link #PHOTO_SUPPORT_NONE}, {@link #PHOTO_SUPPORT_THUMBNAIL_ONLY},
         * {@link #PHOTO_SUPPORT_FULL}. This is a feature flag indicating the extent
         * to which the directory supports contact photos.
         */
        public static final String PHOTO_SUPPORT = "photoSupport";

        /**
         * An {@link #PHOTO_SUPPORT} setting that indicates that the directory
         * does not provide any photos.
         */
        public static final int PHOTO_SUPPORT_NONE = 0;

        /**
         * An {@link #PHOTO_SUPPORT} setting that indicates that the directory
         * can only produce small size thumbnails of contact photos.
         */
        public static final int PHOTO_SUPPORT_THUMBNAIL_ONLY = 1;

        /**
         * An {@link #PHOTO_SUPPORT} setting that indicates that the directory
         * has full-size contact photos, but cannot provide scaled thumbnails.
         */
        public static final int PHOTO_SUPPORT_FULL_SIZE_ONLY = 2;

        /**
         * An {@link #PHOTO_SUPPORT} setting that indicates that the directory
         * can produce thumbnails as well as full-size contact photos.
         */
        public static final int PHOTO_SUPPORT_FULL = 3;

        /**
         * Return TRUE if it is a remote stored directory.
         */
        public static boolean isRemoteDirectoryId(long directoryId) {
            return directoryId != Directory.DEFAULT
                    && directoryId != Directory.LOCAL_INVISIBLE
                    && directoryId != Directory.ENTERPRISE_DEFAULT
                    && directoryId != Directory.ENTERPRISE_LOCAL_INVISIBLE;
        }

        /**
         * Return TRUE if it is a remote stored directory. TODO: Remove this method once all
         * internal apps are not using this API.
         *
         * @hide
         */
        public static boolean isRemoteDirectory(long directoryId) {
            return isRemoteDirectoryId(directoryId);
        }

        /**
         * Return TRUE if a directory ID is from the contacts provider on the enterprise profile.
         *
         */
        public static boolean isEnterpriseDirectoryId(long directoryId) {
            return directoryId >= ENTERPRISE_DIRECTORY_ID_BASE;
        }

        /**
         * Notifies the system of a change in the list of directories handled by
         * a particular directory provider. The Contacts provider will turn around
         * and send a query to the directory provider for the full list of directories,
         * which will replace the previous list.
         */
        public static void notifyDirectoryChange(ContentResolver resolver) {
            // This is done to trigger a query by Contacts Provider back to the directory provider.
            // No data needs to be sent back, because the provider can infer the calling
            // package from binder.
            ContentValues contentValues = new ContentValues();
            resolver.update(Directory.CONTENT_URI, contentValues, null, null);
        }

        /**
         * A query parameter that's passed to directory providers which indicates the client
         * package name that has made the query requests.
         */
        public static final String CALLER_PACKAGE_PARAM_KEY = "callerPackage";
    }

    /**
     * @hide should be removed when users are updated to refer to SyncState
     * @deprecated use SyncState instead
     */
    @Deprecated
    public interface SyncStateColumns extends SyncStateContract.Columns {
    }

    /**
     * A table provided for sync adapters to use for storing private sync state data for contacts.
     *
     * @see SyncStateContract
     */
    public static final class SyncState implements SyncStateContract.Columns {
        /**
         * This utility class cannot be instantiated
         */
        private SyncState() {}

        public static final String CONTENT_DIRECTORY =
                SyncStateContract.Constants.CONTENT_DIRECTORY;

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, CONTENT_DIRECTORY);

        /**
         * @see android.provider.SyncStateContract.Helpers#get
         */
        public static byte[] get(ContentProviderClient provider, Account account)
                throws RemoteException {
            return SyncStateContract.Helpers.get(provider, CONTENT_URI, account);
        }

        /**
         * @see android.provider.SyncStateContract.Helpers#get
         */
        public static Pair<Uri, byte[]> getWithUri(ContentProviderClient provider, Account account)
                throws RemoteException {
            return SyncStateContract.Helpers.getWithUri(provider, CONTENT_URI, account);
        }

        /**
         * @see android.provider.SyncStateContract.Helpers#set
         */
        public static void set(ContentProviderClient provider, Account account, byte[] data)
                throws RemoteException {
            SyncStateContract.Helpers.set(provider, CONTENT_URI, account, data);
        }

        /**
         * @see android.provider.SyncStateContract.Helpers#newSetOperation
         */
        public static ContentProviderOperation newSetOperation(Account account, byte[] data) {
            return SyncStateContract.Helpers.newSetOperation(CONTENT_URI, account, data);
        }
    }


    /**
     * A table provided for sync adapters to use for storing private sync state data for the
     * user's personal profile.
     *
     * @see SyncStateContract
     */
    public static final class ProfileSyncState implements SyncStateContract.Columns {
        /**
         * This utility class cannot be instantiated
         */
        private ProfileSyncState() {}

        public static final String CONTENT_DIRECTORY =
                SyncStateContract.Constants.CONTENT_DIRECTORY;

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(Profile.CONTENT_URI, CONTENT_DIRECTORY);

        /**
         * @see android.provider.SyncStateContract.Helpers#get
         */
        public static byte[] get(ContentProviderClient provider, Account account)
                throws RemoteException {
            return SyncStateContract.Helpers.get(provider, CONTENT_URI, account);
        }

        /**
         * @see android.provider.SyncStateContract.Helpers#get
         */
        public static Pair<Uri, byte[]> getWithUri(ContentProviderClient provider, Account account)
                throws RemoteException {
            return SyncStateContract.Helpers.getWithUri(provider, CONTENT_URI, account);
        }

        /**
         * @see android.provider.SyncStateContract.Helpers#set
         */
        public static void set(ContentProviderClient provider, Account account, byte[] data)
                throws RemoteException {
            SyncStateContract.Helpers.set(provider, CONTENT_URI, account, data);
        }

        /**
         * @see android.provider.SyncStateContract.Helpers#newSetOperation
         */
        public static ContentProviderOperation newSetOperation(Account account, byte[] data) {
            return SyncStateContract.Helpers.newSetOperation(CONTENT_URI, account, data);
        }
    }

    /**
     * Generic columns for use by sync adapters. The specific functions of
     * these columns are private to the sync adapter. Other clients of the API
     * should not attempt to either read or write this column.
     *
     * @see RawContacts
     * @see Groups
     */
    protected interface BaseSyncColumns {

        /** Generic column for use by sync adapters. */
        public static final String SYNC1 = "sync1";
        /** Generic column for use by sync adapters. */
        public static final String SYNC2 = "sync2";
        /** Generic column for use by sync adapters. */
        public static final String SYNC3 = "sync3";
        /** Generic column for use by sync adapters. */
        public static final String SYNC4 = "sync4";
    }

    /**
     * Columns that appear when each row of a table belongs to a specific
     * account, including sync information that an account may need.
     *
     * @see RawContacts
     * @see Groups
     */
    protected interface SyncColumns extends BaseSyncColumns {
        /**
         * The name of the account instance to which this row belongs, which when paired with
         * {@link #ACCOUNT_TYPE} identifies a specific account.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * The type of account to which this row belongs, which when paired with
         * {@link #ACCOUNT_NAME} identifies a specific account.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_TYPE = "account_type";

        /**
         * String that uniquely identifies this row to its source account.
         * <P>Type: TEXT</P>
         */
        public static final String SOURCE_ID = "sourceid";

        /**
         * Version number that is updated whenever this row or its related data
         * changes.
         * <P>Type: INTEGER</P>
         */
        public static final String VERSION = "version";

        /**
         * Flag indicating that {@link #VERSION} has changed, and this row needs
         * to be synchronized by its owning account.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String DIRTY = "dirty";
    }

    /**
     * Columns of {@link ContactsContract.Contacts} that track the user's
     * preferences for, or interactions with, the contact.
     *
     * @see Contacts
     * @see RawContacts
     * @see ContactsContract.Data
     * @see PhoneLookup
     * @see ContactsContract.Contacts.AggregationSuggestions
     */
    protected interface ContactOptionsColumns {
        /**
         * The number of times a contact has been contacted.
         * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
         * this field is obsolete, regardless of Android version. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.</p>
         * <P>Type: INTEGER</P>
         *
         * @deprecated Contacts affinity information is no longer supported as of
         * Android version {@link android.os.Build.VERSION_CODES#Q}. This column
         * always contains 0.
         */
        @Deprecated
        public static final String TIMES_CONTACTED = "times_contacted";

        /**
         * The last time a contact was contacted.
         * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
         * this field is obsolete, regardless of Android version. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.</p>
         * <P>Type: INTEGER</P>
         *
         * @deprecated Contacts affinity information is no longer supported as of
         * Android version {@link android.os.Build.VERSION_CODES#Q}. This column
         * always contains 0.
         */
        @Deprecated
        public static final String LAST_TIME_CONTACTED = "last_time_contacted";

        /** @hide Raw value. */
        public static final String RAW_TIMES_CONTACTED = HIDDEN_COLUMN_PREFIX + TIMES_CONTACTED;

        /** @hide Raw value. */
        public static final String RAW_LAST_TIME_CONTACTED =
                HIDDEN_COLUMN_PREFIX + LAST_TIME_CONTACTED;

        /**
         * @hide
         * Low res version.  Same as {@link #TIMES_CONTACTED} but use it in CP2 for clarification.
         */
        public static final String LR_TIMES_CONTACTED = TIMES_CONTACTED;

        /**
         * @hide
         * Low res version.  Same as {@link #TIMES_CONTACTED} but use it in CP2 for clarification.
         */
        public static final String LR_LAST_TIME_CONTACTED = LAST_TIME_CONTACTED;

        /**
         * Is the contact starred?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String STARRED = "starred";

        /**
         * The position at which the contact is pinned. If {@link PinnedPositions#UNPINNED},
         * the contact is not pinned. Also see {@link PinnedPositions}.
         * <P>Type: INTEGER </P>
         */
        public static final String PINNED = "pinned";

        /**
         * URI for a custom ringtone associated with the contact. If null or missing,
         * the default ringtone is used.
         * <P>Type: TEXT (URI to the ringtone)</P>
         */
        public static final String CUSTOM_RINGTONE = "custom_ringtone";

        /**
         * Whether the contact should always be sent to voicemail. If missing,
         * defaults to false.
         * <P>Type: INTEGER (0 for false, 1 for true)</P>
         */
        public static final String SEND_TO_VOICEMAIL = "send_to_voicemail";
    }

    /**
     * Columns of {@link ContactsContract.Contacts} that refer to intrinsic
     * properties of the contact, as opposed to the user-specified options
     * found in {@link ContactOptionsColumns}.
     *
     * @see Contacts
     * @see ContactsContract.Data
     * @see PhoneLookup
     * @see ContactsContract.Contacts.AggregationSuggestions
     */
    protected interface ContactsColumns {
        /**
         * The display name for the contact.
         * <P>Type: TEXT</P>
         */
        public static final String DISPLAY_NAME = ContactNameColumns.DISPLAY_NAME_PRIMARY;

        /**
         * Reference to the row in the RawContacts table holding the contact name.
         * <P>Type: INTEGER REFERENCES raw_contacts(_id)</P>
         */
        public static final String NAME_RAW_CONTACT_ID = "name_raw_contact_id";

        /**
         * Reference to the row in the data table holding the photo.  A photo can
         * be referred to either by ID (this field) or by URI (see {@link #PHOTO_THUMBNAIL_URI}
         * and {@link #PHOTO_URI}).
         * If PHOTO_ID is null, consult {@link #PHOTO_URI} or {@link #PHOTO_THUMBNAIL_URI},
         * which is a more generic mechanism for referencing the contact photo, especially for
         * contacts returned by non-local directories (see {@link Directory}).
         *
         * <P>Type: INTEGER REFERENCES data(_id)</P>
         */
        public static final String PHOTO_ID = "photo_id";

        /**
         * Photo file ID of the full-size photo.  If present, this will be used to populate
         * {@link #PHOTO_URI}.  The ID can also be used with
         * {@link ContactsContract.DisplayPhoto#CONTENT_URI} to create a URI to the photo.
         * If this is present, {@link #PHOTO_ID} is also guaranteed to be populated.
         *
         * <P>Type: INTEGER</P>
         */
        public static final String PHOTO_FILE_ID = "photo_file_id";

        /**
         * A URI that can be used to retrieve the contact's full-size photo.
         * If PHOTO_FILE_ID is not null, this will be populated with a URI based off
         * {@link ContactsContract.DisplayPhoto#CONTENT_URI}.  Otherwise, this will
         * be populated with the same value as {@link #PHOTO_THUMBNAIL_URI}.
         * A photo can be referred to either by a URI (this field) or by ID
         * (see {@link #PHOTO_ID}). If either PHOTO_FILE_ID or PHOTO_ID is not null,
         * PHOTO_URI and PHOTO_THUMBNAIL_URI shall not be null (but not necessarily
         * vice versa).  Thus using PHOTO_URI is a more robust method of retrieving
         * contact photos.
         *
         * <P>Type: TEXT</P>
         */
        public static final String PHOTO_URI = "photo_uri";

        /**
         * A URI that can be used to retrieve a thumbnail of the contact's photo.
         * A photo can be referred to either by a URI (this field or {@link #PHOTO_URI})
         * or by ID (see {@link #PHOTO_ID}). If PHOTO_ID is not null, PHOTO_URI and
         * PHOTO_THUMBNAIL_URI shall not be null (but not necessarily vice versa).
         * If the content provider does not differentiate between full-size photos
         * and thumbnail photos, PHOTO_THUMBNAIL_URI and {@link #PHOTO_URI} can contain
         * the same value, but either both shall be null or both not null.
         *
         * <P>Type: TEXT</P>
         */
        public static final String PHOTO_THUMBNAIL_URI = "photo_thumb_uri";

        /**
         * Flag that reflects whether the contact exists inside the default directory.
         * Ie, whether the contact is designed to only be visible outside search.
         */
        public static final String IN_DEFAULT_DIRECTORY = "in_default_directory";

        /**
         * Flag that reflects the {@link Groups#GROUP_VISIBLE} state of any
         * {@link CommonDataKinds.GroupMembership} for this contact.
         */
        public static final String IN_VISIBLE_GROUP = "in_visible_group";

        /**
         * Flag that reflects whether this contact represents the user's
         * personal profile entry.
         */
        public static final String IS_USER_PROFILE = "is_user_profile";

        /**
         * An indicator of whether this contact has at least one phone number. "1" if there is
         * at least one phone number, "0" otherwise.
         * <P>Type: INTEGER</P>
         */
        public static final String HAS_PHONE_NUMBER = "has_phone_number";

        /**
         * An opaque value that contains hints on how to find the contact if
         * its row id changed as a result of a sync or aggregation.
         */
        public static final String LOOKUP_KEY = "lookup";

        /**
         * Timestamp (milliseconds since epoch) of when this contact was last updated.  This
         * includes updates to all data associated with this contact including raw contacts.  Any
         * modification (including deletes and inserts) of underlying contact data are also
         * reflected in this timestamp.
         */
        public static final String CONTACT_LAST_UPDATED_TIMESTAMP =
                "contact_last_updated_timestamp";
    }

    /**
     * @see Contacts
     */
    protected interface ContactStatusColumns {
        /**
         * Contact presence status. See {@link StatusUpdates} for individual status
         * definitions.
         * <p>Type: NUMBER</p>
         */
        public static final String CONTACT_PRESENCE = "contact_presence";

        /**
         * Contact Chat Capabilities. See {@link StatusUpdates} for individual
         * definitions.
         * <p>Type: NUMBER</p>
         */
        public static final String CONTACT_CHAT_CAPABILITY = "contact_chat_capability";

        /**
         * Contact's latest status update.
         * <p>Type: TEXT</p>
         */
        public static final String CONTACT_STATUS = "contact_status";

        /**
         * The absolute time in milliseconds when the latest status was
         * inserted/updated.
         * <p>Type: NUMBER</p>
         */
        public static final String CONTACT_STATUS_TIMESTAMP = "contact_status_ts";

        /**
         * The package containing resources for this status: label and icon.
         * <p>Type: TEXT</p>
         */
        public static final String CONTACT_STATUS_RES_PACKAGE = "contact_status_res_package";

        /**
         * The resource ID of the label describing the source of contact
         * status, e.g. "Google Talk". This resource is scoped by the
         * {@link #CONTACT_STATUS_RES_PACKAGE}.
         * <p>Type: NUMBER</p>
         */
        public static final String CONTACT_STATUS_LABEL = "contact_status_label";

        /**
         * The resource ID of the icon for the source of contact status. This
         * resource is scoped by the {@link #CONTACT_STATUS_RES_PACKAGE}.
         * <p>Type: NUMBER</p>
         */
        public static final String CONTACT_STATUS_ICON = "contact_status_icon";
    }

    /**
     * Constants for various styles of combining given name, family name etc into
     * a full name.  For example, the western tradition follows the pattern
     * 'given name' 'middle name' 'family name' with the alternative pattern being
     * 'family name', 'given name' 'middle name'.  The CJK tradition is
     * 'family name' 'middle name' 'given name', with Japanese favoring a space between
     * the names and Chinese omitting the space.
     */
    public interface FullNameStyle {
        public static final int UNDEFINED = 0;
        public static final int WESTERN = 1;

        /**
         * Used if the name is written in Hanzi/Kanji/Hanja and we could not determine
         * which specific language it belongs to: Chinese, Japanese or Korean.
         */
        public static final int CJK = 2;

        public static final int CHINESE = 3;
        public static final int JAPANESE = 4;
        public static final int KOREAN = 5;
    }

    /**
     * Constants for various styles of capturing the pronunciation of a person's name.
     */
    public interface PhoneticNameStyle {
        public static final int UNDEFINED = 0;

        /**
         * Pinyin is a phonetic method of entering Chinese characters. Typically not explicitly
         * shown in UIs, but used for searches and sorting.
         */
        public static final int PINYIN = 3;

        /**
         * Hiragana and Katakana are two common styles of writing out the pronunciation
         * of a Japanese names.
         */
        public static final int JAPANESE = 4;

        /**
         * Hangul is the Korean phonetic alphabet.
         */
        public static final int KOREAN = 5;
    }

    /**
     * Types of data used to produce the display name for a contact. In the order
     * of increasing priority: {@link #EMAIL}, {@link #PHONE},
     * {@link #ORGANIZATION}, {@link #NICKNAME}, {@link #STRUCTURED_PHONETIC_NAME},
     * {@link #STRUCTURED_NAME}.
     */
    public interface DisplayNameSources {
        public static final int UNDEFINED = 0;
        public static final int EMAIL = 10;
        public static final int PHONE = 20;
        public static final int ORGANIZATION = 30;
        public static final int NICKNAME = 35;
        /** Display name comes from a structured name that only has phonetic components. */
        public static final int STRUCTURED_PHONETIC_NAME = 37;
        public static final int STRUCTURED_NAME = 40;
    }

    /**
     * Contact name and contact name metadata columns in the RawContacts table.
     *
     * @see Contacts
     * @see RawContacts
     */
    protected interface ContactNameColumns {

        /**
         * The kind of data that is used as the display name for the contact, such as
         * structured name or email address.  See {@link DisplayNameSources}.
         */
        public static final String DISPLAY_NAME_SOURCE = "display_name_source";

        /**
         * <p>
         * The standard text shown as the contact's display name, based on the best
         * available information for the contact (for example, it might be the email address
         * if the name is not available).
         * The information actually used to compute the name is stored in
         * {@link #DISPLAY_NAME_SOURCE}.
         * </p>
         * <p>
         * A contacts provider is free to choose whatever representation makes most
         * sense for its target market.
         * For example in the default Android Open Source Project implementation,
         * if the display name is
         * based on the structured name and the structured name follows
         * the Western full-name style, then this field contains the "given name first"
         * version of the full name.
         * <p>
         *
         * @see ContactsContract.ContactNameColumns#DISPLAY_NAME_ALTERNATIVE
         */
        public static final String DISPLAY_NAME_PRIMARY = "display_name";

        /**
         * <p>
         * An alternative representation of the display name, such as "family name first"
         * instead of "given name first" for Western names.  If an alternative is not
         * available, the values should be the same as {@link #DISPLAY_NAME_PRIMARY}.
         * </p>
         * <p>
         * A contacts provider is free to provide alternatives as necessary for
         * its target market.
         * For example the default Android Open Source Project contacts provider
         * currently provides an
         * alternative in a single case:  if the display name is
         * based on the structured name and the structured name follows
         * the Western full name style, then the field contains the "family name first"
         * version of the full name.
         * Other cases may be added later.
         * </p>
         */
        public static final String DISPLAY_NAME_ALTERNATIVE = "display_name_alt";

        /**
         * The phonetic alphabet used to represent the {@link #PHONETIC_NAME}.  See
         * {@link PhoneticNameStyle}.
         */
        public static final String PHONETIC_NAME_STYLE = "phonetic_name_style";

        /**
         * <p>
         * Pronunciation of the full name in the phonetic alphabet specified by
         * {@link #PHONETIC_NAME_STYLE}.
         * </p>
         * <p>
         * The value may be set manually by the user. This capability is of
         * interest only in countries with commonly used phonetic alphabets,
         * such as Japan and Korea. See {@link PhoneticNameStyle}.
         * </p>
         */
        public static final String PHONETIC_NAME = "phonetic_name";

        /**
         * Sort key that takes into account locale-based traditions for sorting
         * names in address books.  The default
         * sort key is {@link #DISPLAY_NAME_PRIMARY}.  For Chinese names
         * the sort key is the name's Pinyin spelling, and for Japanese names
         * it is the Hiragana version of the phonetic name.
         */
        public static final String SORT_KEY_PRIMARY = "sort_key";

        /**
         * Sort key based on the alternative representation of the full name,
         * {@link #DISPLAY_NAME_ALTERNATIVE}.  Thus for Western names,
         * it is the one using the "family name first" format.
         */
        public static final String SORT_KEY_ALTERNATIVE = "sort_key_alt";
    }

    interface ContactCounts {

        /**
         * Add this query parameter to a URI to get back row counts grouped by the address book
         * index as cursor extras. For most languages it is the first letter of the sort key. This
         * parameter does not affect the main content of the cursor.
         *
         * <p>
         * <pre>
         * Example:
         *
         * import android.provider.ContactsContract.Contacts;
         *
         * Uri uri = Contacts.CONTENT_URI.buildUpon()
         *          .appendQueryParameter(Contacts.EXTRA_ADDRESS_BOOK_INDEX, "true")
         *          .build();
         * Cursor cursor = getContentResolver().query(uri,
         *          new String[] {Contacts.DISPLAY_NAME},
         *          null, null, null);
         * Bundle bundle = cursor.getExtras();
         * if (bundle.containsKey(Contacts.EXTRA_ADDRESS_BOOK_INDEX_TITLES) &&
         *         bundle.containsKey(Contacts.EXTRA_ADDRESS_BOOK_INDEX_COUNTS)) {
         *     String sections[] =
         *             bundle.getStringArray(Contacts.EXTRA_ADDRESS_BOOK_INDEX_TITLES);
         *     int counts[] = bundle.getIntArray(Contacts.EXTRA_ADDRESS_BOOK_INDEX_COUNTS);
         * }
         * </pre>
         * </p>
         */
        public static final String EXTRA_ADDRESS_BOOK_INDEX =
                "android.provider.extra.ADDRESS_BOOK_INDEX";

        /**
         * The array of address book index titles, which are returned in the
         * same order as the data in the cursor.
         * <p>TYPE: String[]</p>
         */
        public static final String EXTRA_ADDRESS_BOOK_INDEX_TITLES =
                "android.provider.extra.ADDRESS_BOOK_INDEX_TITLES";

        /**
         * The array of group counts for the corresponding group.  Contains the same number
         * of elements as the EXTRA_ADDRESS_BOOK_INDEX_TITLES array.
         * <p>TYPE: int[]</p>
         */
        public static final String EXTRA_ADDRESS_BOOK_INDEX_COUNTS =
                "android.provider.extra.ADDRESS_BOOK_INDEX_COUNTS";
    }

    /**
     * Constants for the contacts table, which contains a record per aggregate
     * of raw contacts representing the same person.
     * <h3>Operations</h3>
     * <dl>
     * <dt><b>Insert</b></dt>
     * <dd>A Contact cannot be created explicitly. When a raw contact is
     * inserted, the provider will first try to find a Contact representing the
     * same person. If one is found, the raw contact's
     * {@link RawContacts#CONTACT_ID} column gets the _ID of the aggregate
     * Contact. If no match is found, the provider automatically inserts a new
     * Contact and puts its _ID into the {@link RawContacts#CONTACT_ID} column
     * of the newly inserted raw contact.</dd>
     * <dt><b>Update</b></dt>
     * <dd>Only certain columns of Contact are modifiable:
     * {@link #STARRED}, {@link #CUSTOM_RINGTONE}, {@link #SEND_TO_VOICEMAIL}. Changing any of
     * these columns on the Contact also changes them on all constituent raw
     * contacts.</dd>
     * <dt><b>Delete</b></dt>
     * <dd>Be careful with deleting Contacts! Deleting an aggregate contact
     * deletes all constituent raw contacts. The corresponding sync adapters
     * will notice the deletions of their respective raw contacts and remove
     * them from their back end storage.</dd>
     * <dt><b>Query</b></dt>
     * <dd>
     * <ul>
     * <li>If you need to read an individual contact, consider using
     * {@link #CONTENT_LOOKUP_URI} instead of {@link #CONTENT_URI}.</li>
     * <li>If you need to look up a contact by the phone number, use
     * {@link PhoneLookup#CONTENT_FILTER_URI PhoneLookup.CONTENT_FILTER_URI},
     * which is optimized for this purpose.</li>
     * <li>If you need to look up a contact by partial name, e.g. to produce
     * filter-as-you-type suggestions, use the {@link #CONTENT_FILTER_URI} URI.
     * <li>If you need to look up a contact by some data element like email
     * address, nickname, etc, use a query against the {@link ContactsContract.Data} table.
     * The result will contain contact ID, name etc.
     * </ul>
     * </dd>
     * </dl>
     * <h2>Columns</h2>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Contacts</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #_ID}</td>
     * <td>read-only</td>
     * <td>Row ID. Consider using {@link #LOOKUP_KEY} instead.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #LOOKUP_KEY}</td>
     * <td>read-only</td>
     * <td>An opaque value that contains hints on how to find the contact if its
     * row id changed as a result of a sync or aggregation.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>NAME_RAW_CONTACT_ID</td>
     * <td>read-only</td>
     * <td>The ID of the raw contact that contributes the display name
     * to the aggregate contact. During aggregation one of the constituent
     * raw contacts is chosen using a heuristic: a longer name or a name
     * with more diacritic marks or more upper case characters is chosen.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>DISPLAY_NAME_PRIMARY</td>
     * <td>read-only</td>
     * <td>The display name for the contact. It is the display name
     * contributed by the raw contact referred to by the NAME_RAW_CONTACT_ID
     * column.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PHOTO_ID}</td>
     * <td>read-only</td>
     * <td>Reference to the row in the {@link ContactsContract.Data} table holding the photo.
     * That row has the mime type
     * {@link CommonDataKinds.Photo#CONTENT_ITEM_TYPE}. The value of this field
     * is computed automatically based on the
     * {@link CommonDataKinds.Photo#IS_SUPER_PRIMARY} field of the data rows of
     * that mime type.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PHOTO_URI}</td>
     * <td>read-only</td>
     * <td>A URI that can be used to retrieve the contact's full-size photo. This
     * column is the preferred method of retrieving the contact photo.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PHOTO_THUMBNAIL_URI}</td>
     * <td>read-only</td>
     * <td>A URI that can be used to retrieve the thumbnail of contact's photo.  This
     * column is the preferred method of retrieving the contact photo.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IN_VISIBLE_GROUP}</td>
     * <td>read-only</td>
     * <td>An indicator of whether this contact is supposed to be visible in the
     * UI. "1" if the contact has at least one raw contact that belongs to a
     * visible group; "0" otherwise.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #HAS_PHONE_NUMBER}</td>
     * <td>read-only</td>
     * <td>An indicator of whether this contact has at least one phone number.
     * "1" if there is at least one phone number, "0" otherwise.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #STARRED}</td>
     * <td>read/write</td>
     * <td>An indicator for favorite contacts: '1' if favorite, '0' otherwise.
     * When raw contacts are aggregated, this field is automatically computed:
     * if any constituent raw contacts are starred, then this field is set to
     * '1'. Setting this field automatically changes the corresponding field on
     * all constituent raw contacts.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_RINGTONE}</td>
     * <td>read/write</td>
     * <td>A custom ringtone associated with a contact. Typically this is the
     * URI returned by an activity launched with the
     * {@link android.media.RingtoneManager#ACTION_RINGTONE_PICKER} intent.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SEND_TO_VOICEMAIL}</td>
     * <td>read/write</td>
     * <td>An indicator of whether calls from this contact should be forwarded
     * directly to voice mail ('1') or not ('0'). When raw contacts are
     * aggregated, this field is automatically computed: if <i>all</i>
     * constituent raw contacts have SEND_TO_VOICEMAIL=1, then this field is set
     * to '1'. Setting this field automatically changes the corresponding field
     * on all constituent raw contacts.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #CONTACT_PRESENCE}</td>
     * <td>read-only</td>
     * <td>Contact IM presence status. See {@link StatusUpdates} for individual
     * status definitions. Automatically computed as the highest presence of all
     * constituent raw contacts. The provider may choose not to store this value
     * in persistent storage. The expectation is that presence status will be
     * updated on a regular basis.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CONTACT_STATUS}</td>
     * <td>read-only</td>
     * <td>Contact's latest status update. Automatically computed as the latest
     * of all constituent raw contacts' status updates.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_TIMESTAMP}</td>
     * <td>read-only</td>
     * <td>The absolute time in milliseconds when the latest status was
     * inserted/updated.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CONTACT_STATUS_RES_PACKAGE}</td>
     * <td>read-only</td>
     * <td> The package containing resources for this status: label and icon.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_LABEL}</td>
     * <td>read-only</td>
     * <td>The resource ID of the label describing the source of contact status,
     * e.g. "Google Talk". This resource is scoped by the
     * {@link #CONTACT_STATUS_RES_PACKAGE}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_ICON}</td>
     * <td>read-only</td>
     * <td>The resource ID of the icon for the source of contact status. This
     * resource is scoped by the {@link #CONTACT_STATUS_RES_PACKAGE}.</td>
     * </tr>
     * </table>
     */
    public static class Contacts implements BaseColumns, ContactsColumns,
            ContactOptionsColumns, ContactNameColumns, ContactStatusColumns, ContactCounts {
        /**
         * This utility class cannot be instantiated
         */
        private Contacts()  {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "contacts");

        /**
         * URI used for getting all contacts from both the calling user and the managed profile
         * that is linked to it.
         * <p>
         * It supports the same semantics as {@link #CONTENT_URI} and returns the same columns.<br>
         * If the calling user has no managed profile, it behaves in the exact same way as
         * {@link #CONTENT_URI}.<br>
         * If there is a managed profile linked to the calling user, it will return merged results
         * from both.
         * <p>
         * Note: this query returns the calling user results before the managed profile results,
         * and this order is not affected by the sorting parameter.
         * <p>
         * If a result is from the managed profile, the following changes are made to the data:
         * <ul>
         *     <li>{@link #PHOTO_THUMBNAIL_URI} and {@link #PHOTO_URI} will be rewritten to special
         *     URIs. Use {@link ContentResolver#openAssetFileDescriptor} or its siblings to
         *     load pictures from them.
         *     <li>{@link #PHOTO_ID} and {@link #PHOTO_FILE_ID} will be set to null. Don't use them.
         *     <li>{@link #_ID} and {@link #LOOKUP_KEY} will be replaced with artificial values.
         *     These values will be consistent across multiple queries, but do not use them in
         *     places that do not explicitly say they accept them. If they are used in the
         *     {@code selection} param in {@link android.content.ContentProvider#query}, the result
         *     is undefined.
         *     <li>In order to tell whether a contact is from the managed profile, use
         *     {@link ContactsContract.Contacts#isEnterpriseContactId(long)}.
         */
        public static final @NonNull Uri ENTERPRISE_CONTENT_URI = Uri.withAppendedPath(
                CONTENT_URI, "enterprise");

        /**
         * Special contacts URI to refer to contacts on the managed profile from the calling user.
         * <p>
         * It's supported only by a few specific places for referring to contact pictures that
         * are in the managed profile provider for enterprise caller-ID. Contact picture URIs
         * returned from {@link PhoneLookup#ENTERPRISE_CONTENT_FILTER_URI} and similar APIs may
         * contain this kind of URI.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public static final Uri CORP_CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "contacts_corp");

        /**
         * A content:// style URI for this table that should be used to create
         * shortcuts or otherwise create long-term links to contacts. This URI
         * should always be followed by a "/" and the contact's {@link #LOOKUP_KEY}.
         * It can optionally also have a "/" and last known contact ID appended after
         * that. This "complete" format is an important optimization and is highly recommended.
         * <p>
         * As long as the contact's row ID remains the same, this URI is
         * equivalent to {@link #CONTENT_URI}. If the contact's row ID changes
         * as a result of a sync or aggregation, this URI will look up the
         * contact using indirect information (sync IDs or constituent raw
         * contacts).
         * <p>
         * Lookup key should be appended unencoded - it is stored in the encoded
         * form, ready for use in a URI.
         */
        public static final Uri CONTENT_LOOKUP_URI = Uri.withAppendedPath(CONTENT_URI,
                "lookup");

        /**
         * Base {@link Uri} for referencing a single {@link Contacts} entry,
         * created by appending {@link #LOOKUP_KEY} using
         * {@link Uri#withAppendedPath(Uri, String)}. Provides
         * {@link OpenableColumns} columns when queried, or returns the
         * referenced contact formatted as a vCard when opened through
         * {@link ContentResolver#openAssetFileDescriptor(Uri, String)}.
         */
        public static final Uri CONTENT_VCARD_URI = Uri.withAppendedPath(CONTENT_URI,
                "as_vcard");

       /**
        * Boolean parameter that may be used with {@link #CONTENT_VCARD_URI}
        * and {@link #CONTENT_MULTI_VCARD_URI} to indicate that the returned
        * vcard should not contain a photo.
        *
        * This is useful for obtaining a space efficient vcard.
        */
        public static final String QUERY_PARAMETER_VCARD_NO_PHOTO = "no_photo";

        /**
         * Base {@link Uri} for referencing multiple {@link Contacts} entry,
         * created by appending {@link #LOOKUP_KEY} using
         * {@link Uri#withAppendedPath(Uri, String)}. The lookup keys have to be
         * joined with the colon (":") separator, and the resulting string encoded.
         *
         * Provides {@link OpenableColumns} columns when queried, or returns the
         * referenced contact formatted as a vCard when opened through
         * {@link ContentResolver#openAssetFileDescriptor(Uri, String)}.
         *
         * <p>
         * Usage example:
         * <dl>
         * <dt>The following code snippet creates a multi-vcard URI that references all the
         * contacts in a user's database.</dt>
         * <dd>
         *
         * <pre>
         * public Uri getAllContactsVcardUri() {
         *     Cursor cursor = getActivity().getContentResolver().query(Contacts.CONTENT_URI,
         *         new String[] {Contacts.LOOKUP_KEY}, null, null, null);
         *     if (cursor == null) {
         *         return null;
         *     }
         *     try {
         *         StringBuilder uriListBuilder = new StringBuilder();
         *         int index = 0;
         *         while (cursor.moveToNext()) {
         *             if (index != 0) uriListBuilder.append(':');
         *             uriListBuilder.append(cursor.getString(0));
         *             index++;
         *         }
         *         return Uri.withAppendedPath(Contacts.CONTENT_MULTI_VCARD_URI,
         *                 Uri.encode(uriListBuilder.toString()));
         *     } finally {
         *         cursor.close();
         *     }
         * }
         * </pre>
         *
         * </p>
         */
        public static final Uri CONTENT_MULTI_VCARD_URI = Uri.withAppendedPath(CONTENT_URI,
                "as_multi_vcard");

        /**
         * Builds a {@link #CONTENT_LOOKUP_URI} style {@link Uri} describing the
         * requested {@link Contacts} entry.
         *
         * @param contactUri A {@link #CONTENT_URI} row, or an existing
         *            {@link #CONTENT_LOOKUP_URI} to attempt refreshing.
         */
        public static Uri getLookupUri(ContentResolver resolver, Uri contactUri) {
            final Cursor c = resolver.query(contactUri, new String[] {
                    Contacts.LOOKUP_KEY, Contacts._ID
            }, null, null, null);
            if (c == null) {
                return null;
            }

            try {
                if (c.moveToFirst()) {
                    final String lookupKey = c.getString(0);
                    final long contactId = c.getLong(1);
                    return getLookupUri(contactId, lookupKey);
                }
            } finally {
                c.close();
            }
            return null;
        }

        /**
         * Build a {@link #CONTENT_LOOKUP_URI} lookup {@link Uri} using the
         * given {@link ContactsContract.Contacts#_ID} and {@link #LOOKUP_KEY}.
         * <p>
         * Returns null if unable to construct a valid lookup URI from the
         * provided parameters.
         */
        public static Uri getLookupUri(long contactId, String lookupKey) {
            if (TextUtils.isEmpty(lookupKey)) {
                return null;
            }
            return ContentUris.withAppendedId(Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI,
                    lookupKey), contactId);
        }

        /**
         * Computes a content URI (see {@link #CONTENT_URI}) given a lookup URI.
         * <p>
         * Returns null if the contact cannot be found.
         */
        public static Uri lookupContact(ContentResolver resolver, Uri lookupUri) {
            if (lookupUri == null) {
                return null;
            }

            Cursor c = resolver.query(lookupUri, new String[]{Contacts._ID}, null, null, null);
            if (c == null) {
                return null;
            }

            try {
                if (c.moveToFirst()) {
                    long contactId = c.getLong(0);
                    return ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId);
                }
            } finally {
                c.close();
            }
            return null;
        }

        /**
         * Mark a contact as having been contacted. Updates two fields:
         * {@link #TIMES_CONTACTED} and {@link #LAST_TIME_CONTACTED}. The
         * TIMES_CONTACTED field is incremented by 1 and the LAST_TIME_CONTACTED
         * field is populated with the current system time.
         *
         * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
         * this field is obsolete, regardless of Android version. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.</p>
         *
         * @param resolver the ContentResolver to use
         * @param contactId the person who was contacted
         *
         * @deprecated Contacts affinity information is no longer supported as of
         * Android version {@link android.os.Build.VERSION_CODES#Q}. This method
         * is no-op.
         */
        @Deprecated
        public static void markAsContacted(ContentResolver resolver, long contactId) {
        }

        /**
         * The content:// style URI used for "type-to-filter" functionality on the
         * {@link #CONTENT_URI} URI. The filter string will be used to match
         * various parts of the contact name. The filter argument should be passed
         * as an additional path segment after this URI.
         */
        public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(
                CONTENT_URI, "filter");

        /**
         * It supports the similar semantics as {@link #CONTENT_FILTER_URI} and returns the same
         * columns. This URI requires {@link ContactsContract#DIRECTORY_PARAM_KEY} in parameters,
         * otherwise it will throw IllegalArgumentException. The passed directory can belong either
         * to the calling user or to a managed profile that is linked to it.
         */
        public static final Uri ENTERPRISE_CONTENT_FILTER_URI = Uri.withAppendedPath(
                CONTENT_URI, "filter_enterprise");

        /**
         * The content:// style URI for this table joined with useful data from
         * {@link ContactsContract.Data}, filtered to include only starred contacts.
         * Frequent contacts are no longer included in the result as of
         * Android version {@link android.os.Build.VERSION_CODES#Q}.
         *
         * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store, this
         * field doesn't sort results based on contacts frequency. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.
         */
        public static final Uri CONTENT_STREQUENT_URI = Uri.withAppendedPath(
                CONTENT_URI, "strequent");

        /**
         * The content:// style URI for showing a list of frequently contacted people.
         *
         * @deprecated Frequent contacts are no longer supported as of
         * Android version {@link android.os.Build.VERSION_CODES#Q}.
         * This URI always returns an empty cursor.
         *
         * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store, this
         * field doesn't sort results based on contacts frequency. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.
         */
        @Deprecated
        public static final Uri CONTENT_FREQUENT_URI = Uri.withAppendedPath(
                CONTENT_URI, "frequent");

        /**
         * <p>The content:// style URI used for "type-to-filter" functionality on the
         * {@link #CONTENT_STREQUENT_URI} URI. The filter string will be used to match
         * various parts of the contact name. The filter argument should be passed
         * as an additional path segment after this URI.
         *
         * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store, this
         * field doesn't sort results based on contacts frequency. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.
         */
        public static final Uri CONTENT_STREQUENT_FILTER_URI = Uri.withAppendedPath(
                CONTENT_STREQUENT_URI, "filter");

        public static final Uri CONTENT_GROUP_URI = Uri.withAppendedPath(
                CONTENT_URI, "group");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/contact";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contact";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_VCARD_TYPE = "text/x-vcard";

        /**
         * Mimimal ID for managed profile contacts returned from
         * {@link PhoneLookup#ENTERPRISE_CONTENT_FILTER_URI} and similar APIs
         *
         * @hide
         */
        public static long ENTERPRISE_CONTACT_ID_BASE = 1000000000; // slightly smaller than 2 ** 30

        /**
         * Prefix for managed profile contacts returned from
         * {@link PhoneLookup#ENTERPRISE_CONTENT_FILTER_URI} and similar APIs.
         *
         * @hide
         */
        public static String ENTERPRISE_CONTACT_LOOKUP_PREFIX = "c-";

        /**
         * Return {@code true} if a contact ID is from the contacts provider on the managed profile.
         *
         * {@link PhoneLookup#ENTERPRISE_CONTENT_FILTER_URI} and similar APIs may return such IDs.
         */
        public static boolean isEnterpriseContactId(long contactId) {
            return (contactId >= ENTERPRISE_CONTACT_ID_BASE) && (contactId < Profile.MIN_ID);
        }

        /**
         * A sub-directory of a single contact that contains all of the constituent raw contact
         * {@link ContactsContract.Data} rows.  This directory can be used either
         * with a {@link #CONTENT_URI} or {@link #CONTENT_LOOKUP_URI}.
         */
        public static final class Data implements BaseColumns, DataColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Data() {}

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "data";
        }

        /**
         * <p>
         * A sub-directory of a contact that contains all of its
         * {@link ContactsContract.RawContacts} as well as
         * {@link ContactsContract.Data} rows. To access this directory append
         * {@link #CONTENT_DIRECTORY} to the contact URI.
         * </p>
         * <p>
         * Entity has three ID fields: {@link #CONTACT_ID} for the contact,
         * {@link #RAW_CONTACT_ID} for the raw contact and {@link #DATA_ID} for
         * the data rows. Entity always contains at least one row per
         * constituent raw contact, even if there are no actual data rows. In
         * this case the {@link #DATA_ID} field will be null.
         * </p>
         * <p>
         * Entity reads all data for the entire contact in one transaction, to
         * guarantee consistency.  There is significant data duplication
         * in the Entity (each row repeats all Contact columns and all RawContact
         * columns), so the benefits of transactional consistency should be weighed
         * against the cost of transferring large amounts of denormalized data
         * from the Provider.
         * </p>
         * <p>
         * To reduce the amount of data duplication the contacts provider and directory
         * providers implementing this protocol are allowed to provide common Contacts
         * and RawContacts fields in the first row returned for each raw contact only and
         * leave them as null in subsequent rows.
         * </p>
         */
        public static final class Entity implements BaseColumns, ContactsColumns,
                ContactNameColumns, RawContactsColumns, BaseSyncColumns, SyncColumns, DataColumns,
                StatusColumns, ContactOptionsColumns, ContactStatusColumns, DataUsageStatColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Entity() {
            }

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "entities";

            /**
             * The ID of the raw contact row.
             * <P>Type: INTEGER</P>
             */
            public static final String RAW_CONTACT_ID = "raw_contact_id";

            /**
             * The ID of the data row. The value will be null if this raw contact has no
             * data rows.
             * <P>Type: INTEGER</P>
             */
            public static final String DATA_ID = "data_id";
        }

        /**
         * <p>
         * A sub-directory of a single contact that contains all of the constituent raw contact
         * {@link ContactsContract.StreamItems} rows.  This directory can be used either
         * with a {@link #CONTENT_URI} or {@link #CONTENT_LOOKUP_URI}.
         * </p>
         * <p>
         * Querying for social stream data requires android.permission.READ_SOCIAL_STREAM
         * permission.
         * </p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         *
         * @removed
         */
        @Deprecated
        public static final class StreamItems implements StreamItemsColumns {
            /**
             * no public constructor since this is a utility class
             *
             * @deprecated - Do not use. This will not be supported in the future. In the future,
             * cursors returned from related queries will be empty.
             */
            @Deprecated
            private StreamItems() {}

            /**
             * The directory twig for this sub-table
             *
             * @deprecated - Do not use. This will not be supported in the future. In the future,
             * cursors returned from related queries will be empty.
             */
            @Deprecated
            public static final String CONTENT_DIRECTORY = "stream_items";
        }

        /**
         * <p>
         * A <i>read-only</i> sub-directory of a single contact aggregate that
         * contains all aggregation suggestions (other contacts). The
         * aggregation suggestions are computed based on approximate data
         * matches with this contact.
         * </p>
         * <p>
         * <i>Note: this query may be expensive! If you need to use it in bulk,
         * make sure the user experience is acceptable when the query runs for a
         * long time.</i>
         * <p>
         * Usage example:
         *
         * <pre>
         * Uri uri = Contacts.CONTENT_URI.buildUpon()
         *          .appendEncodedPath(String.valueOf(contactId))
         *          .appendPath(Contacts.AggregationSuggestions.CONTENT_DIRECTORY)
         *          .appendQueryParameter(&quot;limit&quot;, &quot;3&quot;)
         *          .build()
         * Cursor cursor = getContentResolver().query(suggestionsUri,
         *          new String[] {Contacts.DISPLAY_NAME, Contacts._ID, Contacts.LOOKUP_KEY},
         *          null, null, null);
         * </pre>
         *
         * </p>
         * <p>
         * This directory can be used either with a {@link #CONTENT_URI} or
         * {@link #CONTENT_LOOKUP_URI}.
         * </p>
         */
        public static final class AggregationSuggestions implements BaseColumns, ContactsColumns,
                ContactOptionsColumns, ContactStatusColumns {
            /**
             * No public constructor since this is a utility class
             */
            private AggregationSuggestions() {}

            /**
             * The directory twig for this sub-table. The URI can be followed by an optional
             * type-to-filter, similar to
             * {@link android.provider.ContactsContract.Contacts#CONTENT_FILTER_URI}.
             */
            public static final String CONTENT_DIRECTORY = "suggestions";

            /**
             * Used to specify what kind of data is supplied for the suggestion query.
             *
             * @hide
             */
            public static final String PARAMETER_MATCH_NAME = "name";

            /**
             * A convenience builder for aggregation suggestion content URIs.
             */
            public static final class Builder {
                private long mContactId;
                private final ArrayList<String> mValues = new ArrayList<String>();
                private int mLimit;

                /**
                 * Optional existing contact ID.  If it is not provided, the search
                 * will be based exclusively on the values supplied with {@link #addNameParameter}.
                 *
                 * @param contactId contact to find aggregation suggestions for
                 * @return This Builder object to allow for chaining of calls to builder methods
                 */
                public Builder setContactId(long contactId) {
                    this.mContactId = contactId;
                    return this;
                }

                /**
                 * Add a name to be used when searching for aggregation suggestions.
                 *
                 * @param name name to find aggregation suggestions for
                 * @return This Builder object to allow for chaining of calls to builder methods
                 */
                public Builder addNameParameter(String name) {
                    mValues.add(name);
                    return this;
                }

                /**
                 * Sets the Maximum number of suggested aggregations that should be returned.
                 * @param limit The maximum number of suggested aggregations
                 *
                 * @return This Builder object to allow for chaining of calls to builder methods
                 */
                public Builder setLimit(int limit) {
                    mLimit = limit;
                    return this;
                }

                /**
                 * Combine all of the options that have been set and return a new {@link Uri}
                 * object for fetching aggregation suggestions.
                 */
                public Uri build() {
                    android.net.Uri.Builder builder = Contacts.CONTENT_URI.buildUpon();
                    builder.appendEncodedPath(String.valueOf(mContactId));
                    builder.appendPath(Contacts.AggregationSuggestions.CONTENT_DIRECTORY);
                    if (mLimit != 0) {
                        builder.appendQueryParameter("limit", String.valueOf(mLimit));
                    }

                    int count = mValues.size();
                    for (int i = 0; i < count; i++) {
                        builder.appendQueryParameter("query", PARAMETER_MATCH_NAME
                                + ":" + mValues.get(i));
                    }

                    return builder.build();
                }
            }

            /**
             * @hide
             */
            @UnsupportedAppUsage
            public static final Builder builder() {
                return new Builder();
            }
        }

        /**
         * A <i>read-only</i> sub-directory of a single contact that contains
         * the contact's primary photo.  The photo may be stored in up to two ways -
         * the default "photo" is a thumbnail-sized image stored directly in the data
         * row, while the "display photo", if present, is a larger version stored as
         * a file.
         * <p>
         * Usage example:
         * <dl>
         * <dt>Retrieving the thumbnail-sized photo</dt>
         * <dd>
         * <pre>
         * public InputStream openPhoto(long contactId) {
         *     Uri contactUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId);
         *     Uri photoUri = Uri.withAppendedPath(contactUri, Contacts.Photo.CONTENT_DIRECTORY);
         *     Cursor cursor = getContentResolver().query(photoUri,
         *          new String[] {Contacts.Photo.PHOTO}, null, null, null);
         *     if (cursor == null) {
         *         return null;
         *     }
         *     try {
         *         if (cursor.moveToFirst()) {
         *             byte[] data = cursor.getBlob(0);
         *             if (data != null) {
         *                 return new ByteArrayInputStream(data);
         *             }
         *         }
         *     } finally {
         *         cursor.close();
         *     }
         *     return null;
         * }
         * </pre>
         * </dd>
         * <dt>Retrieving the larger photo version</dt>
         * <dd>
         * <pre>
         * public InputStream openDisplayPhoto(long contactId) {
         *     Uri contactUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId);
         *     Uri displayPhotoUri = Uri.withAppendedPath(contactUri, Contacts.Photo.DISPLAY_PHOTO);
         *     try {
         *         AssetFileDescriptor fd =
         *             getContentResolver().openAssetFileDescriptor(displayPhotoUri, "r");
         *         return fd.createInputStream();
         *     } catch (IOException e) {
         *         return null;
         *     }
         * }
         * </pre>
         * </dd>
         * </dl>
         *
         * </p>
         * <p>You may also consider using the convenience method
         * {@link ContactsContract.Contacts#openContactPhotoInputStream(ContentResolver, Uri, boolean)}
         * to retrieve the raw photo contents of either the thumbnail-sized or the full-sized photo.
         * </p>
         * <p>
         * This directory can be used either with a {@link #CONTENT_URI} or
         * {@link #CONTENT_LOOKUP_URI}.
         * </p>
         */
        public static final class Photo implements BaseColumns, DataColumnsWithJoins {
            /**
             * no public constructor since this is a utility class
             */
            private Photo() {}

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "photo";

            /**
             * The directory twig for retrieving the full-size display photo.
             */
            public static final String DISPLAY_PHOTO = "display_photo";

            /**
             * Full-size photo file ID of the raw contact.
             * See {@link ContactsContract.DisplayPhoto}.
             * <p>
             * Type: NUMBER
             */
            public static final String PHOTO_FILE_ID = DATA14;

            /**
             * Thumbnail photo of the raw contact. This is the raw bytes of an image
             * that could be inflated using {@link android.graphics.BitmapFactory}.
             * <p>
             * Type: BLOB
             */
            public static final String PHOTO = DATA15;
        }

        /**
         * Opens an InputStream for the contacts's photo and returns the
         * photo as a byte stream.
         * @param cr The content resolver to use for querying
         * @param contactUri the contact whose photo should be used. This can be used with
         * either a {@link #CONTENT_URI} or a {@link #CONTENT_LOOKUP_URI} URI.
         * @param preferHighres If this is true and the contact has a higher resolution photo
         * available, it is returned. If false, this function always tries to get the thumbnail
         * @return an InputStream of the photo, or null if no photo is present
         */
        public static InputStream openContactPhotoInputStream(ContentResolver cr, Uri contactUri,
                boolean preferHighres) {
            if (preferHighres) {
                final Uri displayPhotoUri = Uri.withAppendedPath(contactUri,
                        Contacts.Photo.DISPLAY_PHOTO);
                try {
                    AssetFileDescriptor fd = cr.openAssetFileDescriptor(displayPhotoUri, "r");
                    if (fd != null) {
                        return fd.createInputStream();
                    }
                } catch (IOException e) {
                    // fallback to the thumbnail code
                }
           }

            Uri photoUri = Uri.withAppendedPath(contactUri, Photo.CONTENT_DIRECTORY);
            if (photoUri == null) {
                return null;
            }
            Cursor cursor = cr.query(photoUri,
                    new String[] {
                        ContactsContract.CommonDataKinds.Photo.PHOTO
                    }, null, null, null);
            try {
                if (cursor == null || !cursor.moveToNext()) {
                    return null;
                }
                byte[] data = cursor.getBlob(0);
                if (data == null) {
                    return null;
                }
                return new ByteArrayInputStream(data);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        /**
         * Opens an InputStream for the contacts's thumbnail photo and returns the
         * photo as a byte stream.
         * @param cr The content resolver to use for querying
         * @param contactUri the contact whose photo should be used. This can be used with
         * either a {@link #CONTENT_URI} or a {@link #CONTENT_LOOKUP_URI} URI.
         * @return an InputStream of the photo, or null if no photo is present
         * @see #openContactPhotoInputStream(ContentResolver, Uri, boolean), if instead
         * of the thumbnail the high-res picture is preferred
         */
        public static InputStream openContactPhotoInputStream(ContentResolver cr, Uri contactUri) {
            return openContactPhotoInputStream(cr, contactUri, false);
        }

        /**
         * Creates and returns a corp lookup URI from the given enterprise lookup URI by removing
         * {@link #ENTERPRISE_CONTACT_LOOKUP_PREFIX} from the key. Returns {@code null} if the given
         * URI is not an enterprise lookup URI.
         *
         * @hide
         */
        @Nullable
        public static Uri createCorpLookupUriFromEnterpriseLookupUri(
                @NonNull Uri enterpriseLookupUri) {
            final List<String> pathSegments = enterpriseLookupUri.getPathSegments();
            if (pathSegments == null || pathSegments.size() <= 2) {
                return null;
            }
            final String key = pathSegments.get(2);
            if (TextUtils.isEmpty(key) || !key.startsWith(ENTERPRISE_CONTACT_LOOKUP_PREFIX)) {
                return null;
            }
            final String actualKey = key.substring(ENTERPRISE_CONTACT_LOOKUP_PREFIX.length());
            return Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, actualKey);
        }
    }

    /**
     * <p>
     * Constants for the user's profile data, which is represented as a single contact on
     * the device that represents the user.  The profile contact is not aggregated
     * together automatically in the same way that normal contacts are; instead, each
     * account (including data set, if applicable) on the device may contribute a single
     * raw contact representing the user's personal profile data from that source.
     * </p>
     * <p>
     * Access to the profile entry through these URIs (or incidental access to parts of
     * the profile if retrieved directly via ID) requires additional permissions beyond
     * the read/write contact permissions required by the provider.  Querying for profile
     * data requires android.permission.READ_PROFILE permission, and inserting or
     * updating profile data requires android.permission.WRITE_PROFILE permission.
     * </p>
     * <h3>Operations</h3>
     * <dl>
     * <dt><b>Insert</b></dt>
     * <dd>The user's profile entry cannot be created explicitly (attempting to do so
     * will throw an exception). When a raw contact is inserted into the profile, the
     * provider will check for the existence of a profile on the device.  If one is
     * found, the raw contact's {@link RawContacts#CONTACT_ID} column gets the _ID of
     * the profile Contact. If no match is found, the profile Contact is created and
     * its _ID is put into the {@link RawContacts#CONTACT_ID} column of the newly
     * inserted raw contact.</dd>
     * <dt><b>Update</b></dt>
     * <dd>The profile Contact has the same update restrictions as Contacts in general,
     * but requires the android.permission.WRITE_PROFILE permission.</dd>
     * <dt><b>Delete</b></dt>
     * <dd>The profile Contact cannot be explicitly deleted.  It will be removed
     * automatically if all of its constituent raw contact entries are deleted.</dd>
     * <dt><b>Query</b></dt>
     * <dd>
     * <ul>
     * <li>The {@link #CONTENT_URI} for profiles behaves in much the same way as
     * retrieving a contact by ID, except that it will only ever return the user's
     * profile contact.
     * </li>
     * <li>
     * The profile contact supports all of the same sub-paths as an individual contact
     * does - the content of the profile contact can be retrieved as entities or
     * data rows.  Similarly, specific raw contact entries can be retrieved by appending
     * the desired raw contact ID within the profile.
     * </li>
     * </ul>
     * </dd>
     * </dl>
     */
    public static final class Profile implements BaseColumns, ContactsColumns,
            ContactOptionsColumns, ContactNameColumns, ContactStatusColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Profile() {
        }

        /**
         * The content:// style URI for this table, which requests the contact entry
         * representing the user's personal profile data.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "profile");

        /**
         * {@link Uri} for referencing the user's profile {@link Contacts} entry,
         * Provides {@link OpenableColumns} columns when queried, or returns the
         * user's profile contact formatted as a vCard when opened through
         * {@link ContentResolver#openAssetFileDescriptor(Uri, String)}.
         */
        public static final Uri CONTENT_VCARD_URI = Uri.withAppendedPath(CONTENT_URI,
                "as_vcard");

        /**
         * {@link Uri} for referencing the raw contacts that make up the user's profile
         * {@link Contacts} entry.  An individual raw contact entry within the profile
         * can be addressed by appending the raw contact ID.  The entities or data within
         * that specific raw contact can be requested by appending the entity or data
         * path as well.
         */
        public static final Uri CONTENT_RAW_CONTACTS_URI = Uri.withAppendedPath(CONTENT_URI,
                "raw_contacts");

        /**
         * The minimum ID for any entity that belongs to the profile.  This essentially
         * defines an ID-space in which profile data is stored, and is used by the provider
         * to determine whether a request via a non-profile-specific URI should be directed
         * to the profile data rather than general contacts data, along with all the special
         * permission checks that entails.
         *
         * Callers may use {@link #isProfileId} to check whether a specific ID falls into
         * the set of data intended for the profile.
         */
        public static final long MIN_ID = Long.MAX_VALUE - (long) Integer.MAX_VALUE;
    }

    /**
     * This method can be used to identify whether the given ID is associated with profile
     * data.  It does not necessarily indicate that the ID is tied to valid data, merely
     * that accessing data using this ID will result in profile access checks and will only
     * return data from the profile.
     *
     * @param id The ID to check.
     * @return Whether the ID is associated with profile data.
     */
    public static boolean isProfileId(long id) {
        return id >= Profile.MIN_ID;
    }

    protected interface DeletedContactsColumns {

        /**
         * A reference to the {@link ContactsContract.Contacts#_ID} that was deleted.
         * <P>Type: INTEGER</P>
         */
        public static final String CONTACT_ID = "contact_id";

        /**
         * Time (milliseconds since epoch) that the contact was deleted.
         */
        public static final String CONTACT_DELETED_TIMESTAMP = "contact_deleted_timestamp";
    }

    /**
     * Constants for the deleted contact table.  This table holds a log of deleted contacts.
     * <p>
     * Log older than {@link #DAYS_KEPT_MILLISECONDS} may be deleted.
     */
    public static final class DeletedContacts implements DeletedContactsColumns {

        /**
         * This utility class cannot be instantiated
         */
        private DeletedContacts() {
        }

        /**
         * The content:// style URI for this table, which requests a directory of raw contact rows
         * matching the selection criteria.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "deleted_contacts");

        /**
         * Number of days that the delete log will be kept.  After this time, delete records may be
         * deleted.
         *
         * @hide
         */
        private static final int DAYS_KEPT = 30;

        /**
         * Milliseconds that the delete log will be kept.  After this time, delete records may be
         * deleted.
         */
        public static final long DAYS_KEPT_MILLISECONDS = 1000L * 60L * 60L * 24L * (long)DAYS_KEPT;
    }

    protected interface RawContactsColumns {
        /**
         * A reference to the {@link ContactsContract.Contacts#_ID} that this
         * data belongs to.
         * <P>Type: INTEGER</P>
         */
        public static final String CONTACT_ID = "contact_id";

        /**
         * Persistent unique id for each raw_contact within its account.
         * This id is provided by its own data source, and can be used to backup metadata
         * to the server.
         * This should be unique within each set of account_name/account_type/data_set
         */
        public static final String BACKUP_ID = "backup_id";

        /**
         * The data set within the account that this row belongs to.  This allows
         * multiple sync adapters for the same account type to distinguish between
         * each others' data.
         *
         * This is empty by default, and is completely optional.  It only needs to
         * be populated if multiple sync adapters are entering distinct data for
         * the same account type and account name.
         * <P>Type: TEXT</P>
         */
        public static final String DATA_SET = "data_set";

        /**
         * A concatenation of the account type and data set (delimited by a forward
         * slash) - if the data set is empty, this will be the same as the account
         * type.  For applications that need to be aware of the data set, this can
         * be used instead of account type to distinguish sets of data.  This is
         * never intended to be used for specifying accounts.
         * <p>
         * This column does *not* escape forward slashes in the account type or the data set.
         * If this is an issue, consider using
         * {@link ContactsContract.RawContacts#ACCOUNT_TYPE} and
         * {@link ContactsContract.RawContacts#DATA_SET} directly.
         */
        public static final String ACCOUNT_TYPE_AND_DATA_SET = "account_type_and_data_set";

        /**
         * The aggregation mode for this contact.
         * <P>Type: INTEGER</P>
         */
        public static final String AGGREGATION_MODE = "aggregation_mode";

        /**
         * The "deleted" flag: "0" by default, "1" if the row has been marked
         * for deletion. When {@link android.content.ContentResolver#delete} is
         * called on a raw contact, it is marked for deletion and removed from its
         * aggregate contact. The sync adaptor deletes the raw contact on the server and
         * then calls ContactResolver.delete once more, this time passing the
         * {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter to finalize
         * the data removal.
         * <P>Type: INTEGER</P>
         */
        public static final String DELETED = "deleted";

        /**
         * The "read-only" flag: "0" by default, "1" if the row cannot be modified or
         * deleted except by a sync adapter.  See {@link ContactsContract#CALLER_IS_SYNCADAPTER}.
         * <P>Type: INTEGER</P>
         */
        public static final String RAW_CONTACT_IS_READ_ONLY = "raw_contact_is_read_only";

        /**
         * Flag that reflects whether this raw contact belongs to the user's
         * personal profile entry.
         */
        public static final String RAW_CONTACT_IS_USER_PROFILE = "raw_contact_is_user_profile";

        /**
         * Flag indicating that a raw contact's metadata has changed, and its metadata
         * needs to be synchronized by the server.
         * <P>Type: INTEGER (boolean)</P>
         *
         * @deprecated This column never actually worked since added. It will not supported as
         * of Android version {@link android.os.Build.VERSION_CODES#R}.
         */
        @Deprecated
        public static final String METADATA_DIRTY = "metadata_dirty";
    }

    /**
     * Constants for the raw contacts table, which contains one row of contact
     * information for each person in each synced account. Sync adapters and
     * contact management apps
     * are the primary consumers of this API.
     *
     * <h3>Aggregation</h3>
     * <p>
     * As soon as a raw contact is inserted or whenever its constituent data
     * changes, the provider will check if the raw contact matches other
     * existing raw contacts and if so will aggregate it with those. The
     * aggregation is reflected in the {@link RawContacts} table by the change of the
     * {@link #CONTACT_ID} field, which is the reference to the aggregate contact.
     * </p>
     * <p>
     * Changes to the structured name, organization, phone number, email address,
     * or nickname trigger a re-aggregation.
     * </p>
     * <p>
     * See also {@link AggregationExceptions} for a mechanism to control
     * aggregation programmatically.
     * </p>
     *
     * <h3>Operations</h3>
     * <dl>
     * <dt><b>Insert</b></dt>
     * <dd>
     * <p>
     * Raw contacts can be inserted incrementally or in a batch.
     * The incremental method is more traditional but less efficient.
     * It should be used
     * only if no {@link Data} values are available at the time the raw contact is created:
     * <pre>
     * ContentValues values = new ContentValues();
     * values.put(RawContacts.ACCOUNT_TYPE, accountType);
     * values.put(RawContacts.ACCOUNT_NAME, accountName);
     * Uri rawContactUri = getContentResolver().insert(RawContacts.CONTENT_URI, values);
     * long rawContactId = ContentUris.parseId(rawContactUri);
     * </pre>
     * </p>
     * <p>
     * Once {@link Data} values become available, insert those.
     * For example, here's how you would insert a name:
     *
     * <pre>
     * values.clear();
     * values.put(Data.RAW_CONTACT_ID, rawContactId);
     * values.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
     * values.put(StructuredName.DISPLAY_NAME, &quot;Mike Sullivan&quot;);
     * getContentResolver().insert(Data.CONTENT_URI, values);
     * </pre>
     * </p>
     * <p>
     * The batch method is by far preferred.  It inserts the raw contact and its
     * constituent data rows in a single database transaction
     * and causes at most one aggregation pass.
     * <pre>
     * ArrayList&lt;ContentProviderOperation&gt; ops =
     *          new ArrayList&lt;ContentProviderOperation&gt;();
     * ...
     * int rawContactInsertIndex = ops.size();
     * ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
     *          .withValue(RawContacts.ACCOUNT_TYPE, accountType)
     *          .withValue(RawContacts.ACCOUNT_NAME, accountName)
     *          .build());
     *
     * ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
     *          .withValueBackReference(Data.RAW_CONTACT_ID, rawContactInsertIndex)
     *          .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
     *          .withValue(StructuredName.DISPLAY_NAME, &quot;Mike Sullivan&quot;)
     *          .build());
     *
     * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
     * </pre>
     * </p>
     * <p>
     * Note the use of {@link ContentProviderOperation.Builder#withValueBackReference(String, int)}
     * to refer to the as-yet-unknown index value of the raw contact inserted in the
     * first operation.
     * </p>
     *
     * <dt><b>Update</b></dt>
     * <dd><p>
     * Raw contacts can be updated incrementally or in a batch.
     * Batch mode should be used whenever possible.
     * The procedures and considerations are analogous to those documented above for inserts.
     * </p></dd>
     * <dt><b>Delete</b></dt>
     * <dd><p>When a raw contact is deleted, all of its Data rows as well as StatusUpdates,
     * AggregationExceptions, PhoneLookup rows are deleted automatically. When all raw
     * contacts associated with a {@link Contacts} row are deleted, the {@link Contacts} row
     * itself is also deleted automatically.
     * </p>
     * <p>
     * The invocation of {@code resolver.delete(...)}, does not immediately delete
     * a raw contacts row.
     * Instead, it sets the {@link #DELETED} flag on the raw contact and
     * removes the raw contact from its aggregate contact.
     * The sync adapter then deletes the raw contact from the server and
     * finalizes phone-side deletion by calling {@code resolver.delete(...)}
     * again and passing the {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter.<p>
     * <p>Some sync adapters are read-only, meaning that they only sync server-side
     * changes to the phone, but not the reverse.  If one of those raw contacts
     * is marked for deletion, it will remain on the phone.  However it will be
     * effectively invisible, because it will not be part of any aggregate contact.
     * </dd>
     *
     * <dt><b>Query</b></dt>
     * <dd>
     * <p>
     * It is easy to find all raw contacts in a Contact:
     * <pre>
     * Cursor c = getContentResolver().query(RawContacts.CONTENT_URI,
     *          new String[]{RawContacts._ID},
     *          RawContacts.CONTACT_ID + "=?",
     *          new String[]{String.valueOf(contactId)}, null);
     * </pre>
     * </p>
     * <p>
     * To find raw contacts within a specific account,
     * you can either put the account name and type in the selection or pass them as query
     * parameters.  The latter approach is preferable, especially when you can reuse the
     * URI:
     * <pre>
     * Uri rawContactUri = RawContacts.CONTENT_URI.buildUpon()
     *          .appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName)
     *          .appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType)
     *          .build();
     * Cursor c1 = getContentResolver().query(rawContactUri,
     *          RawContacts.STARRED + "&lt;&gt;0", null, null, null);
     * ...
     * Cursor c2 = getContentResolver().query(rawContactUri,
     *          RawContacts.DELETED + "&lt;&gt;0", null, null, null);
     * </pre>
     * </p>
     * <p>The best way to read a raw contact along with all the data associated with it is
     * by using the {@link Entity} directory. If the raw contact has data rows,
     * the Entity cursor will contain a row for each data row.  If the raw contact has no
     * data rows, the cursor will still contain one row with the raw contact-level information.
     * <pre>
     * Uri rawContactUri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId);
     * Uri entityUri = Uri.withAppendedPath(rawContactUri, Entity.CONTENT_DIRECTORY);
     * Cursor c = getContentResolver().query(entityUri,
     *          new String[]{RawContacts.SOURCE_ID, Entity.DATA_ID, Entity.MIMETYPE, Entity.DATA1},
     *          null, null, null);
     * try {
     *     while (c.moveToNext()) {
     *         String sourceId = c.getString(0);
     *         if (!c.isNull(1)) {
     *             String mimeType = c.getString(2);
     *             String data = c.getString(3);
     *             ...
     *         }
     *     }
     * } finally {
     *     c.close();
     * }
     * </pre>
     * </p>
     * </dd>
     * </dl>
     * <h2>Columns</h2>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>RawContacts</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #_ID}</td>
     * <td>read-only</td>
     * <td>Row ID. Sync adapters should try to preserve row IDs during updates. In other words,
     * it is much better for a sync adapter to update a raw contact rather than to delete and
     * re-insert it.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_ID}</td>
     * <td>read-only</td>
     * <td>The ID of the row in the {@link ContactsContract.Contacts} table
     * that this raw contact belongs
     * to. Raw contacts are linked to contacts by the aggregation process, which can be controlled
     * by the {@link #AGGREGATION_MODE} field and {@link AggregationExceptions}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #AGGREGATION_MODE}</td>
     * <td>read/write</td>
     * <td>A mechanism that allows programmatic control of the aggregation process. The allowed
     * values are {@link #AGGREGATION_MODE_DEFAULT}, {@link #AGGREGATION_MODE_DISABLED}
     * and {@link #AGGREGATION_MODE_SUSPENDED}. See also {@link AggregationExceptions}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DELETED}</td>
     * <td>read/write</td>
     * <td>The "deleted" flag: "0" by default, "1" if the row has been marked
     * for deletion. When {@link android.content.ContentResolver#delete} is
     * called on a raw contact, it is marked for deletion and removed from its
     * aggregate contact. The sync adaptor deletes the raw contact on the server and
     * then calls ContactResolver.delete once more, this time passing the
     * {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter to finalize
     * the data removal.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #STARRED}</td>
     * <td>read/write</td>
     * <td>An indicator for favorite contacts: '1' if favorite, '0' otherwise.
     * Changing this field immediately affects the corresponding aggregate contact:
     * if any raw contacts in that aggregate contact are starred, then the contact
     * itself is marked as starred.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_RINGTONE}</td>
     * <td>read/write</td>
     * <td>A custom ringtone associated with a raw contact. Typically this is the
     * URI returned by an activity launched with the
     * {@link android.media.RingtoneManager#ACTION_RINGTONE_PICKER} intent.
     * To have an effect on the corresponding value of the aggregate contact, this field
     * should be set at the time the raw contact is inserted. To set a custom
     * ringtone on a contact, use the field {@link ContactsContract.Contacts#CUSTOM_RINGTONE
     * Contacts.CUSTOM_RINGTONE}
     * instead.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SEND_TO_VOICEMAIL}</td>
     * <td>read/write</td>
     * <td>An indicator of whether calls from this raw contact should be forwarded
     * directly to voice mail ('1') or not ('0'). To have an effect
     * on the corresponding value of the aggregate contact, this field
     * should be set at the time the raw contact is inserted.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #ACCOUNT_NAME}</td>
     * <td>read/write-once</td>
     * <td>The name of the account instance to which this row belongs, which when paired with
     * {@link #ACCOUNT_TYPE} identifies a specific account.
     * For example, this will be the Gmail address if it is a Google account.
     * It should be set at the time the raw contact is inserted and never
     * changed afterwards.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #ACCOUNT_TYPE}</td>
     * <td>read/write-once</td>
     * <td>
     * <p>
     * The type of account to which this row belongs, which when paired with
     * {@link #ACCOUNT_NAME} identifies a specific account.
     * It should be set at the time the raw contact is inserted and never
     * changed afterwards.
     * </p>
     * <p>
     * To ensure uniqueness, new account types should be chosen according to the
     * Java package naming convention.  Thus a Google account is of type "com.google".
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #DATA_SET}</td>
     * <td>read/write-once</td>
     * <td>
     * <p>
     * The data set within the account that this row belongs to.  This allows
     * multiple sync adapters for the same account type to distinguish between
     * each others' data.  The combination of {@link #ACCOUNT_TYPE},
     * {@link #ACCOUNT_NAME}, and {@link #DATA_SET} identifies a set of data
     * that is associated with a single sync adapter.
     * </p>
     * <p>
     * This is empty by default, and is completely optional.  It only needs to
     * be populated if multiple sync adapters are entering distinct data for
     * the same account type and account name.
     * </p>
     * <p>
     * It should be set at the time the raw contact is inserted and never
     * changed afterwards.
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SOURCE_ID}</td>
     * <td>read/write</td>
     * <td>String that uniquely identifies this row to its source account.
     * Typically it is set at the time the raw contact is inserted and never
     * changed afterwards. The one notable exception is a new raw contact: it
     * will have an account name and type (and possibly a data set), but no
     * source id. This indicates to the sync adapter that a new contact needs
     * to be created server-side and its ID stored in the corresponding
     * SOURCE_ID field on the phone.
     * </td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #VERSION}</td>
     * <td>read-only</td>
     * <td>Version number that is updated whenever this row or its related data
     * changes. This field can be used for optimistic locking of a raw contact.
     * </td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DIRTY}</td>
     * <td>read/write</td>
     * <td>Flag indicating that {@link #VERSION} has changed, and this row needs
     * to be synchronized by its owning account.  The value is set to "1" automatically
     * whenever the raw contact changes, unless the URI has the
     * {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter specified.
     * The sync adapter should always supply this query parameter to prevent
     * unnecessary synchronization: user changes some data on the server,
     * the sync adapter updates the contact on the phone (without the
     * CALLER_IS_SYNCADAPTER flag) flag, which sets the DIRTY flag,
     * which triggers a sync to bring the changes to the server.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYNC1}</td>
     * <td>read/write</td>
     * <td>Generic column provided for arbitrary use by sync adapters.
     * The content provider
     * stores this information on behalf of the sync adapter but does not
     * interpret it in any way.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYNC2}</td>
     * <td>read/write</td>
     * <td>Generic column for use by sync adapters.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYNC3}</td>
     * <td>read/write</td>
     * <td>Generic column for use by sync adapters.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYNC4}</td>
     * <td>read/write</td>
     * <td>Generic column for use by sync adapters.
     * </td>
     * </tr>
     * </table>
     */
    public static final class RawContacts implements BaseColumns, RawContactsColumns,
            ContactOptionsColumns, ContactNameColumns, SyncColumns  {
        /**
         * This utility class cannot be instantiated
         */
        private RawContacts() {
        }

        /**
         * The content:// style URI for this table, which requests a directory of
         * raw contact rows matching the selection criteria.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "raw_contacts");

        /**
         * The MIME type of the results from {@link #CONTENT_URI} when a specific
         * ID value is not provided, and multiple raw contacts may be returned.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/raw_contact";

        /**
         * The MIME type of the results when a raw contact ID is appended to {@link #CONTENT_URI},
         * yielding a subdirectory of a single person.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/raw_contact";

        /**
         * Aggregation mode: aggregate immediately after insert or update operation(s) are complete.
         */
        public static final int AGGREGATION_MODE_DEFAULT = 0;

        /**
         * Aggregation mode: aggregate at the time the raw contact is inserted/updated.
         * @deprecated Aggregation is synchronous, this historic value is a no-op
         */
        @Deprecated
        public static final int AGGREGATION_MODE_IMMEDIATE = 1;

        /**
         * <p>
         * Aggregation mode: aggregation suspended temporarily, and is likely to be resumed later.
         * Changes to the raw contact will update the associated aggregate contact but will not
         * result in any change in how the contact is aggregated. Similar to
         * {@link #AGGREGATION_MODE_DISABLED}, but maintains a link to the corresponding
         * {@link Contacts} aggregate.
         * </p>
         * <p>
         * This can be used to postpone aggregation until after a series of updates, for better
         * performance and/or user experience.
         * </p>
         * <p>
         * Note that changing
         * {@link #AGGREGATION_MODE} from {@link #AGGREGATION_MODE_SUSPENDED} to
         * {@link #AGGREGATION_MODE_DEFAULT} does not trigger an aggregation pass, but any
         * subsequent
         * change to the raw contact's data will.
         * </p>
         */
        public static final int AGGREGATION_MODE_SUSPENDED = 2;

        /**
         * <p>
         * Aggregation mode: never aggregate this raw contact.  The raw contact will not
         * have a corresponding {@link Contacts} aggregate and therefore will not be included in
         * {@link Contacts} query results.
         * </p>
         * <p>
         * For example, this mode can be used for a raw contact that is marked for deletion while
         * waiting for the deletion to occur on the server side.
         * </p>
         *
         * @see #AGGREGATION_MODE_SUSPENDED
         */
        public static final int AGGREGATION_MODE_DISABLED = 3;

        /**
         * Build a {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI}
         * style {@link Uri} for the parent {@link android.provider.ContactsContract.Contacts}
         * entry of the given {@link RawContacts} entry.
         */
        public static Uri getContactLookupUri(ContentResolver resolver, Uri rawContactUri) {
            // TODO: use a lighter query by joining rawcontacts with contacts in provider
            final Uri dataUri = Uri.withAppendedPath(rawContactUri, Data.CONTENT_DIRECTORY);
            final Cursor cursor = resolver.query(dataUri, new String[] {
                    RawContacts.CONTACT_ID, Contacts.LOOKUP_KEY
            }, null, null, null);

            Uri lookupUri = null;
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    final long contactId = cursor.getLong(0);
                    final String lookupKey = cursor.getString(1);
                    return Contacts.getLookupUri(contactId, lookupKey);
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            return lookupUri;
        }

        /**
         * The default value used for {@link #ACCOUNT_NAME} of raw contacts when they are inserted
         * without a value for this column.
         *
         * <p>This account is used to identify contacts that are only stored locally in the
         * contacts database instead of being associated with an {@link Account} managed by an
         * installed application.
         *
         * <p>When this returns null then {@link #getLocalAccountType} will also return null and
         * when it is non-null {@link #getLocalAccountType} will also return a non-null value.
         */
        @Nullable
        public static String getLocalAccountName(@NonNull Context context) {
            //  config_rawContactsLocalAccountName is defined in
            //  platform/frameworks/base/core/res/res/values/config.xml
            return TextUtils.nullIfEmpty(context.getString(
                    com.android.internal.R.string.config_rawContactsLocalAccountName));
        }

        /**
         * The default value used for {@link #ACCOUNT_TYPE} of raw contacts when they are inserted
         * without a value for this column.
         *
         * <p>This account is used to identify contacts that are only stored locally in the
         * contacts database instead of being associated with an {@link Account} managed by an
         * installed application.
         *
         * <p>When this returns null then {@link #getLocalAccountName} will also return null and
         * when it is non-null {@link #getLocalAccountName} will also return a non-null value.
         */
        @Nullable
        public static String getLocalAccountType(@NonNull Context context) {
            //  config_rawContactsLocalAccountType is defined in
            //  platform/frameworks/base/core/res/res/values/config.xml
            return TextUtils.nullIfEmpty(context.getString(
                    com.android.internal.R.string.config_rawContactsLocalAccountType));
        }



        /**
         * Class containing utility methods around the default account.
         * New raw contacts requested to be inserted without a specified {@link Account} will be
         * saved in the default account.
         */
        @FlaggedApi(Flags.FLAG_NEW_DEFAULT_ACCOUNT_API_ENABLED)
        public static final class DefaultAccount {
            /**
             * no public constructor since this is a utility class
             */
            private DefaultAccount() {

            }

            /**
             * Key in the outgoing Bundle for the default account list.
             *
             * @hide
             */
            public static final String KEY_ELIGIBLE_DEFAULT_ACCOUNTS =
                    "key_eligible_default_accounts";
            /**
             * The method to invoke in order to query eligiblie default accounts.
             *
             * @hide
             */
            public static final String QUERY_ELIGIBLE_DEFAULT_ACCOUNTS_METHOD =
                    "queryEligibleDefaultAccounts";
            /**
             * Key in the Bundle for the default account state.
             *
             * @hide
             */
            public static final String KEY_DEFAULT_ACCOUNT_STATE =
                    "key_default_account_state";
            /**
             * The method to invoke in order to set the default account.
             *
             * @hide
             */
            public static final String SET_DEFAULT_ACCOUNT_FOR_NEW_CONTACTS_METHOD =
                    "setDefaultAccountForNewContacts";
            /**
             * The method to invoke in order to query the default account.
             *
             * @hide
             */
            public static final String QUERY_DEFAULT_ACCOUNT_FOR_NEW_CONTACTS_METHOD =
                    "queryDefaultAccountForNewContacts";

            /**
             * Represents the state of the default account, and the actual {@link Account} if it's
             * a cloud account.
             * If the default account is set to {@link #DEFAULT_ACCOUNT_STATE_LOCAL} or
             * {@link #DEFAULT_ACCOUNT_STATE_CLOUD}, new raw contacts requested for insertion
             * without a
             * specified {@link Account} will be saved in the default account.
             * The default account can have one of the following four states:
             * <ul>
             * <li> {@link #DEFAULT_ACCOUNT_STATE_NOT_SET}: The default account has not
             * been set by the user. </li>
             * <li> {@link #DEFAULT_ACCOUNT_STATE_LOCAL}: The default account is set to
             * the local device storage. New raw contacts requested for insertion without a
             * specified
             * {@link Account} will be saved in a null or custom local account. </li>
             * <li> {@link #DEFAULT_ACCOUNT_STATE_CLOUD}: The default account is set to a
             * cloud-synced account. New raw contacts requested for insertion without a specified
             * {@link Account} will be saved in the default cloud account. </li>
             * <li> {@link #DEFAULT_ACCOUNT_STATE_SIM}: The default account is set to a
             * account that is associated with one of
             * {@link SimContacts#getSimAccounts(ContentResolver)}. New raw contacts requested
             * for insertion without a specified {@link Account} will be
             * saved in this SIM account. </li>
             * </ul>
             */
            @FlaggedApi(Flags.FLAG_NEW_DEFAULT_ACCOUNT_API_ENABLED)
            public static final class DefaultAccountAndState {
                /** A state indicating that default account is not set. */
                public static final int DEFAULT_ACCOUNT_STATE_NOT_SET = 1;

                /** A state indicating that default account is set to local device storage. */
                public static final int DEFAULT_ACCOUNT_STATE_LOCAL = 2;

                /**
                 * A state indicating that the default account is set as an account that is synced
                 * to the cloud.
                 */
                public static final int DEFAULT_ACCOUNT_STATE_CLOUD = 3;

                /**
                 * A state indicating that the default account is set as an account that is
                 * associated with one of {@link SimContacts#getSimAccounts(ContentResolver)}.
                 */
                public static final int DEFAULT_ACCOUNT_STATE_SIM = 4;

                /**
                 * The state of the default account. One of
                 * {@link #DEFAULT_ACCOUNT_STATE_NOT_SET},
                 * {@link #DEFAULT_ACCOUNT_STATE_LOCAL},
                 * {@link #DEFAULT_ACCOUNT_STATE_CLOUD}
                 * {@link #DEFAULT_ACCOUNT_STATE_SIM}.
                 */
                @DefaultAccountState
                private final int mState;

                /**
                 * The account of the default account, when {@link #mState} is {
                 *
                 * @link #DEFAULT_ACCOUNT_STATE_CLOUD} or {@link #DEFAULT_ACCOUNT_STATE_SIM}, or
                 * null otherwise.
                 */
                private final Account mAccount;

                /**
                 * Constructs a new `DefaultAccountAndState` instance with the specified state and
                 * cloud
                 * account.
                 *
                 * @param state   The state of the default account.
                 * @param account The account associated with the default account if the state is
                 *                {@link #DEFAULT_ACCOUNT_STATE_CLOUD} or
                 *                {@link #DEFAULT_ACCOUNT_STATE_SIM}, or null otherwise.
                 */
                public DefaultAccountAndState(@DefaultAccountState int state,
                        @Nullable Account account) {
                    if (!isValidDefaultAccountState(state)) {
                        throw new IllegalArgumentException("Invalid default account state.");
                    }
                    if (isCloudOrSimAccount(state) != (account != null)) {
                        throw new IllegalArgumentException(
                                "Default account can be set to cloud or SIM if and only if the "
                                        + "account is provided.");
                    }
                    this.mState = state;
                    this.mAccount = isCloudOrSimAccount(state) ? account : null;
                }

                /**
                 * Creates a `DefaultAccountAndState` instance representing a default account
                 * that is set to the cloud and associated with the specified cloud account.
                 *
                 * @param cloudAccount The non-null cloud account associated with the default
                 *                     contacts
                 *                     account.
                 * @return A new `DefaultAccountAndState` instance with state
                 * {@link #DEFAULT_ACCOUNT_STATE_CLOUD}.
                 */
                public static @NonNull DefaultAccountAndState ofCloud(
                        @NonNull Account cloudAccount) {
                    return new DefaultAccountAndState(DEFAULT_ACCOUNT_STATE_CLOUD, cloudAccount);
                }


                /**
                 * Creates a `DefaultAccountAndState` instance representing a default account
                 * that is set to the sim and associated with the specified sim account.
                 *
                 * @param simAccount The non-null sim account associated with the default
                 *                   contacts account.
                 * @return A new `DefaultAccountAndState` instance with state
                 * {@link #DEFAULT_ACCOUNT_STATE_SIM}.
                 */
                public static @NonNull DefaultAccountAndState ofSim(
                        @NonNull Account simAccount) {
                    return new DefaultAccountAndState(DEFAULT_ACCOUNT_STATE_SIM, simAccount);
                }

                /**
                 * Creates a `DefaultAccountAndState` instance representing a default account
                 * that is set to the local device storage.
                 *
                 * @return A new `DefaultAccountAndState` instance with state
                 * {@link #DEFAULT_ACCOUNT_STATE_LOCAL}.
                 */
                public static @NonNull DefaultAccountAndState ofLocal() {
                    return new DefaultAccountAndState(DEFAULT_ACCOUNT_STATE_LOCAL, null);
                }

                /**
                 * Creates a `DefaultAccountAndState` instance representing a default account
                 * that is not set.
                 *
                 * @return A new `DefaultAccountAndState` instance with state
                 * {@link #DEFAULT_ACCOUNT_STATE_NOT_SET}.
                 */
                public static @NonNull DefaultAccountAndState ofNotSet() {
                    return new DefaultAccountAndState(DEFAULT_ACCOUNT_STATE_NOT_SET, null);
                }

                /**
                 *
                 * @hide
                 */
                public static boolean isCloudOrSimAccount(@DefaultAccountState int state) {
                    return state == DEFAULT_ACCOUNT_STATE_CLOUD
                            || state == DEFAULT_ACCOUNT_STATE_SIM;
                }

                private static boolean isValidDefaultAccountState(int state) {
                    return state == DEFAULT_ACCOUNT_STATE_NOT_SET
                            || state == DEFAULT_ACCOUNT_STATE_LOCAL
                            || state == DEFAULT_ACCOUNT_STATE_CLOUD
                            || state == DEFAULT_ACCOUNT_STATE_SIM;
                }

                /**
                 * @return the state of the default account.
                 */
                @DefaultAccountState
                public int getState() {
                    return mState;
                }

                /**
                 * @return the cloud account associated with the default account if the
                 * state is {@link #DEFAULT_ACCOUNT_STATE_CLOUD} or
                 * {@link #DEFAULT_ACCOUNT_STATE_SIM}.
                 */
                public @Nullable Account getAccount() {
                    return mAccount;
                }

                @Override
                public int hashCode() {
                    return Objects.hash(mState, mAccount);
                }

                @Override
                public boolean equals(Object obj) {
                    if (this == obj) {
                        return true;
                    }
                    if (!(obj instanceof DefaultAccountAndState that)) {
                        return false;
                    }

                    return mState == that.mState && Objects.equals(mAccount,
                            that.mAccount);
                }

                /**
                 * Annotation for all default account states.
                 *
                 * @hide
                 */
                @Retention(RetentionPolicy.SOURCE)
                @IntDef(
                        prefix = {"DEFAULT_ACCOUNT_STATE_"},
                        value = {DEFAULT_ACCOUNT_STATE_NOT_SET,
                                DEFAULT_ACCOUNT_STATE_LOCAL, DEFAULT_ACCOUNT_STATE_CLOUD,
                                DEFAULT_ACCOUNT_STATE_SIM})
                public @interface DefaultAccountState {
                }
            }

            /**
             * Get the account that is set as the default account for new contacts, which should be
             * initially selected when creating a new contact on contact management apps.
             *
             * @param resolver the ContentResolver to query.
             *
             * @return the default account state for new contacts.
             * @throws RuntimeException if failed to look up the default account.
             * @throws IllegalStateException if the default account is in an invalid state.
             */
            @FlaggedApi(Flags.FLAG_NEW_DEFAULT_ACCOUNT_API_ENABLED)
            public static @NonNull DefaultAccountAndState getDefaultAccountForNewContacts(
                    @NonNull ContentResolver resolver) {
                Bundle response = nullSafeCall(resolver, ContactsContract.AUTHORITY_URI,
                        QUERY_DEFAULT_ACCOUNT_FOR_NEW_CONTACTS_METHOD, null, null);

                int defaultAccountState = response.getInt(KEY_DEFAULT_ACCOUNT_STATE, -1);
                if (DefaultAccountAndState.isCloudOrSimAccount(defaultAccountState)) {
                    String accountName = response.getString(Settings.ACCOUNT_NAME);
                    String accountType = response.getString(Settings.ACCOUNT_TYPE);
                    if (TextUtils.isEmpty(accountName) || TextUtils.isEmpty(accountType)) {
                        throw new IllegalStateException(
                                "account name and type cannot be null or empty");
                    }
                    return new DefaultAccountAndState(defaultAccountState,
                            new Account(accountName, accountType));
                } else if (defaultAccountState == DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_LOCAL
                        || defaultAccountState
                        == DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_NOT_SET) {
                    return new DefaultAccountAndState(defaultAccountState, /*account=*/
                            null);
                } else {
                    throw new IllegalStateException("Invalid default account state");
                }
            }

            /**
             * Sets the default account that should be initially selected when creating a new
             * contact on
             * contact management apps. Apps can only set one of
             * The following accounts as the default account:
             * <ol>
             *   <li> local account
             *   <li> cloud account that are eligible to be set as default account.
             * </ol>
             *
             * @param resolver               the ContentResolver to query.
             * @param defaultAccountAndState the default account and state to be set. To set the
             *                               local
             *                               account as the
             *                               default account, this parameter should be
             *                               {@link DefaultAccountAndState#ofLocal()}. To set the a
             *                               cloud
             *                               account as the default account, this parameter should
             *                               be
             *                               {@link DefaultAccountAndState#ofCloud(Account)}. To
             *                               set
             *                               the
             *                               default account to a "not set" state, this parameter
             *                               should
             *                               be {@link DefaultAccountAndState#ofNotSet()}.
             *
             * @throws RuntimeException if it fails to set the default account.
             *
             * @hide
             */
            @RequiresPermission(android.Manifest.permission.SET_DEFAULT_ACCOUNT_FOR_CONTACTS)
            @FlaggedApi(Flags.FLAG_NEW_DEFAULT_ACCOUNT_API_ENABLED)
            @SystemApi
            public static void setDefaultAccountForNewContacts(@NonNull ContentResolver resolver,
                    @NonNull DefaultAccountAndState defaultAccountAndState) {
                Bundle extras = new Bundle();

                extras.putInt(KEY_DEFAULT_ACCOUNT_STATE, defaultAccountAndState.getState());
                if (DefaultAccountAndState.isCloudOrSimAccount(defaultAccountAndState.getState())) {
                    Account account = defaultAccountAndState.getAccount();
                    assert account != null;
                    extras.putString(Settings.ACCOUNT_NAME, account.name);
                    extras.putString(Settings.ACCOUNT_TYPE, account.type);
                }
                nullSafeCall(resolver, ContactsContract.AUTHORITY_URI,
                        SET_DEFAULT_ACCOUNT_FOR_NEW_CONTACTS_METHOD, null, extras);
            }

            /**
             * Get a list of cloud accounts that is eligible to set as default account with state of
             * {@link DefaultAccountAndState#DEFAULT_ACCOUNT_STATE_CLOUD}. May be empty but never
             * null.
             *
             * @param resolver content resolver to query.
             * @return a of cloud accounts that is eligible to set as default account with state of
             * {@link DefaultAccountAndState#DEFAULT_ACCOUNT_STATE_CLOUD}.
             * @throws RuntimeException if the query fails.
             *
             * @hide
             */
            @RequiresPermission(android.Manifest.permission.SET_DEFAULT_ACCOUNT_FOR_CONTACTS)
            @FlaggedApi(Flags.FLAG_NEW_DEFAULT_ACCOUNT_API_ENABLED)
            @SystemApi
            public static @NonNull List<Account> getEligibleCloudAccounts(
                    @NonNull ContentResolver resolver) {
                Bundle response = nullSafeCall(resolver, ContactsContract.AUTHORITY_URI,
                        QUERY_ELIGIBLE_DEFAULT_ACCOUNTS_METHOD, null, null);
                List<Account> result = response.getParcelableArrayList(
                        KEY_ELIGIBLE_DEFAULT_ACCOUNTS, Account.class);
                if (result == null) {
                    return new ArrayList<>();
                }
                return result;
            }



            /**
             * The method to invoke to move local {@link RawContacts} and {@link Groups} from local
             * account(s) to the Cloud Default Account (if any).
             *
             * @hide
             */
            public static final String MOVE_LOCAL_CONTACTS_TO_CLOUD_DEFAULT_ACCOUNT_METHOD =
                    "moveLocalContactsToCloudDefaultAccount";

            /**
             * Move {@link RawContacts} and {@link Groups} (if any) from the local account to the
             * Cloud Default Account (if any).
             * @param resolver the ContentResolver to query.
             * @throws RuntimeException if it fails to move contacts to the default account.
             *
             * @hide
             */
            @SystemApi
            @FlaggedApi(Flags.FLAG_NEW_DEFAULT_ACCOUNT_API_ENABLED)
            @RequiresPermission(allOf = {android.Manifest.permission.WRITE_CONTACTS,
                    android.Manifest.permission.SET_DEFAULT_ACCOUNT_FOR_CONTACTS})
            public static void moveLocalContactsToCloudDefaultAccount(
                    @NonNull ContentResolver resolver) {

                Bundle extras = new Bundle();
                Bundle result = nullSafeCall(
                        resolver,
                        ContactsContract.AUTHORITY_URI,
                        MOVE_LOCAL_CONTACTS_TO_CLOUD_DEFAULT_ACCOUNT_METHOD,
                        null,
                        extras);
            }

            /**
             * The method to invoke to move {@link RawContacts} and {@link Groups} from SIM
             * account(s) to the Cloud Default Account (if any).
             *
             * @hide
             */
            public static final String MOVE_SIM_CONTACTS_TO_CLOUD_DEFAULT_ACCOUNT_METHOD =
                    "moveSimContactsToCloudDefaultAccount";

            /**
             * Move {@link RawContacts} and {@link Groups} (if any) from the local account to the
             * Cloud Default Account (if any).
             * @param resolver the ContentResolver to query.
             * @throws RuntimeException if it fails to move contacts to the default account.
             *
             * @hide
             */
            @SystemApi
            @FlaggedApi(Flags.FLAG_NEW_DEFAULT_ACCOUNT_API_ENABLED)
            @RequiresPermission(allOf = {android.Manifest.permission.WRITE_CONTACTS,
                    android.Manifest.permission.SET_DEFAULT_ACCOUNT_FOR_CONTACTS})
            public static void moveSimContactsToCloudDefaultAccount(
                    @NonNull ContentResolver resolver) {
                Bundle result = nullSafeCall(
                        resolver,
                        ContactsContract.AUTHORITY_URI,
                        MOVE_SIM_CONTACTS_TO_CLOUD_DEFAULT_ACCOUNT_METHOD,
                        /* arg= */ null,
                        /* extras= */ null);
            }

            /**
             * The method to invoke to get the number of {@link RawContacts} that are in local
             * account(s) and movable to the Cloud Default Account (if any).
             *
             * @hide
             */
            public static final String GET_NUMBER_OF_MOVABLE_LOCAL_CONTACTS_METHOD =
                    "getNumberOfMovableLocalContacts";

            /**
             * The result key for moving local {@link RawContacts} and {@link Groups} from SIM
             * account(s) to the Cloud Default Account (if any).
             *
             * @hide
             */
            public static final String KEY_NUMBER_OF_MOVABLE_LOCAL_CONTACTS =
                    "key_number_of_movable_local_contacts";

            /**
             * Gets the number of {@link RawContacts} in the local account(s) which may be moved
             * using {@link DefaultAccount#moveLocalContactsToCloudDefaultAccount} (if any).
             * @param resolver the ContentResolver to query.
             * @return the number of {@link RawContacts} in the local account(s), or 0 if there is
             * no Cloud Default Account.
             * @throws RuntimeException if it fails get the number of movable local contacts.
             *
             * @hide
             */
            @SystemApi
            @FlaggedApi(Flags.FLAG_NEW_DEFAULT_ACCOUNT_API_ENABLED)
            @RequiresPermission(allOf = {android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.SET_DEFAULT_ACCOUNT_FOR_CONTACTS})
            public static int getNumberOfMovableLocalContacts(
                    @NonNull ContentResolver resolver) {
                Bundle result = nullSafeCall(
                        resolver,
                        ContactsContract.AUTHORITY_URI,
                        GET_NUMBER_OF_MOVABLE_LOCAL_CONTACTS_METHOD,
                        /* arg= */ null,
                        /* extras= */ null);
                return result.getInt(KEY_NUMBER_OF_MOVABLE_LOCAL_CONTACTS,
                        /* defaultValue= */ 0);
            }

            /**
             * The method to invoke to get the number of {@link RawContacts} that are in SIM
             * account(s) and movable to the Cloud Default Account (if any).
             *
             * @hide
             */
            public static final String GET_NUMBER_OF_MOVABLE_SIM_CONTACTS_METHOD =
                    "getNumberOfMovableSimContacts";

            /**
             * The result key for moving local {@link RawContacts} and {@link Groups} from SIM
             * account(s) to the Cloud Default Account (if any).
             *
             * @hide
             */
            public static final String KEY_NUMBER_OF_MOVABLE_SIM_CONTACTS =
                    "key_number_of_movable_sim_contacts";

            /**
             * Gets the number of {@link RawContacts} in the SIM account(s) which may be moved using
             * {@link DefaultAccount#moveSimContactsToCloudDefaultAccount} (if any).
             * @param resolver the ContentResolver to query.
             * @return the number of {@link RawContacts} in the SIM account(s), or 0 if there is
             * no Cloud Default Account.
             * @throws RuntimeException if it fails get the number of movable sim contacts.
             *
             * @hide
             */
            @SystemApi
            @FlaggedApi(Flags.FLAG_NEW_DEFAULT_ACCOUNT_API_ENABLED)
            @RequiresPermission(allOf = {android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.SET_DEFAULT_ACCOUNT_FOR_CONTACTS})
            public static int getNumberOfMovableSimContacts(
                    @NonNull ContentResolver resolver) {
                Bundle result = nullSafeCall(
                        resolver,
                        ContactsContract.AUTHORITY_URI,
                        GET_NUMBER_OF_MOVABLE_SIM_CONTACTS_METHOD,
                        /* arg= */ null,
                        /* extras= */ null);
                return result.getInt(KEY_NUMBER_OF_MOVABLE_SIM_CONTACTS,
                        /* defaultValue= */ 0);
            }

        }

        /**
         * A sub-directory of a single raw contact that contains all of its
         * {@link ContactsContract.Data} rows. To access this directory
         * append {@link Data#CONTENT_DIRECTORY} to the raw contact URI.
         */
        public static final class Data implements BaseColumns, DataColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Data() {
            }

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "data";
        }

        /**
         * <p>
         * A sub-directory of a single raw contact that contains all of its
         * {@link ContactsContract.Data} rows. To access this directory append
         * {@link RawContacts.Entity#CONTENT_DIRECTORY} to the raw contact URI. See
         * {@link RawContactsEntity} for a stand-alone table containing the same
         * data.
         * </p>
         * <p>
         * Entity has two ID fields: {@link #_ID} for the raw contact
         * and {@link #DATA_ID} for the data rows.
         * Entity always contains at least one row, even if there are no
         * actual data rows. In this case the {@link #DATA_ID} field will be
         * null.
         * </p>
         * <p>
         * Using Entity should be preferred to using two separate queries:
         * RawContacts followed by Data. The reason is that Entity reads all
         * data for a raw contact in one transaction, so there is no possibility
         * of the data changing between the two queries.
         */
        public static final class Entity implements BaseColumns, DataColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Entity() {
            }

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "entity";

            /**
             * The ID of the data row. The value will be null if this raw contact has no
             * data rows.
             * <P>Type: INTEGER</P>
             */
            public static final String DATA_ID = "data_id";
        }

        /**
         * <p>
         * A sub-directory of a single raw contact that contains all of its
         * {@link ContactsContract.StreamItems} rows. To access this directory append
         * {@link RawContacts.StreamItems#CONTENT_DIRECTORY} to the raw contact URI. See
         * {@link ContactsContract.StreamItems} for a stand-alone table containing the
         * same data.
         * </p>
         * <p>
         * Access to the social stream through this sub-directory requires additional permissions
         * beyond the read/write contact permissions required by the provider.  Querying for
         * social stream data requires android.permission.READ_SOCIAL_STREAM permission, and
         * inserting or updating social stream items requires android.permission.WRITE_SOCIAL_STREAM
         * permission.
         * </p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         *
         * @removed
         */
        @Deprecated
        public static final class StreamItems implements BaseColumns, StreamItemsColumns {
            /**
             * No public constructor since this is a utility class
             *
             * @deprecated - Do not use. This will not be supported in the future. In the future,
             * cursors returned from related queries will be empty.
             */
            @Deprecated
            private StreamItems() {
            }

            /**
             * The directory twig for this sub-table
             *
             * @deprecated - Do not use. This will not be supported in the future. In the future,
             * cursors returned from related queries will be empty.
             */
            @Deprecated
            public static final String CONTENT_DIRECTORY = "stream_items";
        }

        /**
         * <p>
         * A sub-directory of a single raw contact that represents its primary
         * display photo.  To access this directory append
         * {@link RawContacts.DisplayPhoto#CONTENT_DIRECTORY} to the raw contact URI.
         * The resulting URI represents an image file, and should be interacted with
         * using ContentResolver.openAssetFileDescriptor.
         * <p>
         * <p>
         * Note that this sub-directory also supports opening the photo as an asset file
         * in write mode.  Callers can create or replace the primary photo associated
         * with this raw contact by opening the asset file and writing the full-size
         * photo contents into it.  When the file is closed, the image will be parsed,
         * sized down if necessary for the full-size display photo and thumbnail
         * dimensions, and stored.
         * </p>
         * <p>
         * Usage example:
         * <pre>
         * public void writeDisplayPhoto(long rawContactId, byte[] photo) {
         *     Uri rawContactPhotoUri = Uri.withAppendedPath(
         *             ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId),
         *             RawContacts.DisplayPhoto.CONTENT_DIRECTORY);
         *     try {
         *         AssetFileDescriptor fd =
         *             getContentResolver().openAssetFileDescriptor(rawContactPhotoUri, "rw");
         *         OutputStream os = fd.createOutputStream();
         *         os.write(photo);
         *         os.close();
         *         fd.close();
         *     } catch (IOException e) {
         *         // Handle error cases.
         *     }
         * }
         * </pre>
         * </p>
         */
        public static final class DisplayPhoto {
            /**
             * No public constructor since this is a utility class
             */
            private DisplayPhoto() {
            }

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "display_photo";
        }

        /**
         * TODO: javadoc
         * @param cursor
         * @return
         */
        public static EntityIterator newEntityIterator(Cursor cursor) {
            return new EntityIteratorImpl(cursor);
        }

        private static class EntityIteratorImpl extends CursorEntityIterator {
            private static final String[] DATA_KEYS = new String[]{
                    Data.DATA1,
                    Data.DATA2,
                    Data.DATA3,
                    Data.DATA4,
                    Data.DATA5,
                    Data.DATA6,
                    Data.DATA7,
                    Data.DATA8,
                    Data.DATA9,
                    Data.DATA10,
                    Data.DATA11,
                    Data.DATA12,
                    Data.DATA13,
                    Data.DATA14,
                    Data.DATA15,
                    Data.SYNC1,
                    Data.SYNC2,
                    Data.SYNC3,
                    Data.SYNC4};

            public EntityIteratorImpl(Cursor cursor) {
                super(cursor);
            }

            @Override
            public android.content.Entity getEntityAndIncrementCursor(Cursor cursor)
                    throws RemoteException {
                final int columnRawContactId = cursor.getColumnIndexOrThrow(RawContacts._ID);
                final long rawContactId = cursor.getLong(columnRawContactId);

                // we expect the cursor is already at the row we need to read from
                ContentValues cv = new ContentValues();
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ACCOUNT_NAME);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ACCOUNT_TYPE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, DATA_SET);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, _ID);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DIRTY);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, VERSION);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SOURCE_ID);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC1);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC2);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC3);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC4);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DELETED);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, CONTACT_ID);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, STARRED);
                android.content.Entity contact = new android.content.Entity(cv);

                // read data rows until the contact id changes
                do {
                    if (rawContactId != cursor.getLong(columnRawContactId)) {
                        break;
                    }
                    // add the data to to the contact
                    cv = new ContentValues();
                    cv.put(Data._ID, cursor.getLong(cursor.getColumnIndexOrThrow(Entity.DATA_ID)));
                    DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                            Data.RES_PACKAGE);
                    DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, Data.MIMETYPE);
                    DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, Data.IS_PRIMARY);
                    DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv,
                            Data.IS_SUPER_PRIMARY);
                    DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, Data.DATA_VERSION);
                    DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                            CommonDataKinds.GroupMembership.GROUP_SOURCE_ID);
                    DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                            Data.DATA_VERSION);
                    for (String key : DATA_KEYS) {
                        final int columnIndex = cursor.getColumnIndexOrThrow(key);
                        switch (cursor.getType(columnIndex)) {
                            case Cursor.FIELD_TYPE_NULL:
                                // don't put anything
                                break;
                            case Cursor.FIELD_TYPE_INTEGER:
                            case Cursor.FIELD_TYPE_FLOAT:
                            case Cursor.FIELD_TYPE_STRING:
                                cv.put(key, cursor.getString(columnIndex));
                                break;
                            case Cursor.FIELD_TYPE_BLOB:
                                cv.put(key, cursor.getBlob(columnIndex));
                                break;
                            default:
                                throw new IllegalStateException("Invalid or unhandled data type");
                        }
                    }
                    contact.addSubValue(ContactsContract.Data.CONTENT_URI, cv);
                } while (cursor.moveToNext());

                return contact;
            }

        }
    }

    /**
     * Social status update columns.
     *
     * @see StatusUpdates
     * @see ContactsContract.Data
     */
    protected interface StatusColumns {
        /**
         * Contact's latest presence level.
         * <P>Type: INTEGER (one of the values below)</P>
         */
        public static final String PRESENCE = "mode";

        /**
         * @deprecated use {@link #PRESENCE}
         */
        @Deprecated
        public static final String PRESENCE_STATUS = PRESENCE;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int OFFLINE = 0;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int INVISIBLE = 1;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int AWAY = 2;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int IDLE = 3;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int DO_NOT_DISTURB = 4;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int AVAILABLE = 5;

        /**
         * Contact latest status update.
         * <p>Type: TEXT</p>
         */
        public static final String STATUS = "status";

        /**
         * @deprecated use {@link #STATUS}
         */
        @Deprecated
        public static final String PRESENCE_CUSTOM_STATUS = STATUS;

        /**
         * The absolute time in milliseconds when the latest status was inserted/updated.
         * <p>Type: NUMBER</p>
         */
        public static final String STATUS_TIMESTAMP = "status_ts";

        /**
         * The package containing resources for this status: label and icon.
         * <p>Type: TEXT</p>
         */
        public static final String STATUS_RES_PACKAGE = "status_res_package";

        /**
         * The resource ID of the label describing the source of the status update, e.g. "Google
         * Talk".  This resource should be scoped by the {@link #STATUS_RES_PACKAGE}.
         * <p>Type: NUMBER</p>
         */
        public static final String STATUS_LABEL = "status_label";

        /**
         * The resource ID of the icon for the source of the status update.
         * This resource should be scoped by the {@link #STATUS_RES_PACKAGE}.
         * <p>Type: NUMBER</p>
         */
        public static final String STATUS_ICON = "status_icon";

        /**
         * Contact's audio/video chat capability level.
         * <P>Type: INTEGER (one of the values below)</P>
         */
        public static final String CHAT_CAPABILITY = "chat_capability";

        /**
         * An allowed flag of {@link #CHAT_CAPABILITY}. Indicates audio-chat capability (microphone
         * and speaker)
         */
        public static final int CAPABILITY_HAS_VOICE = 1;

        /**
         * An allowed flag of {@link #CHAT_CAPABILITY}. Indicates that the contact's device can
         * display a video feed.
         */
        public static final int CAPABILITY_HAS_VIDEO = 2;

        /**
         * An allowed flag of {@link #CHAT_CAPABILITY}. Indicates that the contact's device has a
         * camera that can be used for video chat (e.g. a front-facing camera on a phone).
         */
        public static final int CAPABILITY_HAS_CAMERA = 4;
    }

    /**
     * <p>
     * Constants for the stream_items table, which contains social stream updates from
     * the user's contact list.
     * </p>
     * <p>
     * Only a certain number of stream items will ever be stored under a given raw contact.
     * Users of this API can query {@link ContactsContract.StreamItems#CONTENT_LIMIT_URI} to
     * determine this limit, and should restrict the number of items inserted in any given
     * transaction correspondingly.  Insertion of more items beyond the limit will
     * automatically lead to deletion of the oldest items, by {@link StreamItems#TIMESTAMP}.
     * </p>
     * <p>
     * Access to the social stream through these URIs requires additional permissions beyond the
     * read/write contact permissions required by the provider.  Querying for social stream data
     * requires android.permission.READ_SOCIAL_STREAM permission, and inserting or updating social
     * stream items requires android.permission.WRITE_SOCIAL_STREAM permission.
     * </p>
     * <h3>Account check</h3>
     * <p>
     * The content URIs to the insert, update and delete operations are required to have the account
     * information matching that of the owning raw contact as query parameters, namely
     * {@link RawContacts#ACCOUNT_TYPE} and {@link RawContacts#ACCOUNT_NAME}.
     * {@link RawContacts#DATA_SET} isn't required.
     * </p>
     * <h3>Operations</h3>
     * <dl>
     * <dt><b>Insert</b></dt>
     * <dd>
     * <p>Social stream updates are always associated with a raw contact.  There are a couple
     * of ways to insert these entries.
     * <dl>
     * <dt>Via the {@link RawContacts.StreamItems#CONTENT_DIRECTORY} sub-path of a raw contact:</dt>
     * <dd>
     * <pre>
     * ContentValues values = new ContentValues();
     * values.put(StreamItems.TEXT, "Breakfasted at Tiffanys");
     * values.put(StreamItems.TIMESTAMP, timestamp);
     * values.put(StreamItems.COMMENTS, "3 people reshared this");
     * Uri.Builder builder = RawContacts.CONTENT_URI.buildUpon();
     * ContentUris.appendId(builder, rawContactId);
     * builder.appendEncodedPath(RawContacts.StreamItems.CONTENT_DIRECTORY);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType);
     * Uri streamItemUri = getContentResolver().insert(builder.build(), values);
     * long streamItemId = ContentUris.parseId(streamItemUri);
     * </pre>
     * </dd>
     * <dt>Via {@link StreamItems#CONTENT_URI}:</dt>
     * <dd>
     *<pre>
     * ContentValues values = new ContentValues();
     * values.put(StreamItems.RAW_CONTACT_ID, rawContactId);
     * values.put(StreamItems.TEXT, "Breakfasted at Tiffanys");
     * values.put(StreamItems.TIMESTAMP, timestamp);
     * values.put(StreamItems.COMMENTS, "3 people reshared this");
     * Uri.Builder builder = StreamItems.CONTENT_URI.buildUpon();
     * builder.appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType);
     * Uri streamItemUri = getContentResolver().insert(builder.build(), values);
     * long streamItemId = ContentUris.parseId(streamItemUri);
     *</pre>
     * </dd>
     * </dl>
     * </dd>
     * </p>
     * <p>
     * Once a {@link StreamItems} entry has been inserted, photos associated with that
     * social update can be inserted.  For example, after one of the insertions above,
     * photos could be added to the stream item in one of the following ways:
     * <dl>
     * <dt>Via a URI including the stream item ID:</dt>
     * <dd>
     * <pre>
     * values.clear();
     * values.put(StreamItemPhotos.SORT_INDEX, 1);
     * values.put(StreamItemPhotos.PHOTO, photoData);
     * getContentResolver().insert(Uri.withAppendedPath(
     *     ContentUris.withAppendedId(StreamItems.CONTENT_URI, streamItemId),
     *     StreamItems.StreamItemPhotos.CONTENT_DIRECTORY), values);
     * </pre>
     * </dd>
     * <dt>Via {@link ContactsContract.StreamItems#CONTENT_PHOTO_URI}:</dt>
     * <dd>
     * <pre>
     * values.clear();
     * values.put(StreamItemPhotos.STREAM_ITEM_ID, streamItemId);
     * values.put(StreamItemPhotos.SORT_INDEX, 1);
     * values.put(StreamItemPhotos.PHOTO, photoData);
     * getContentResolver().insert(StreamItems.CONTENT_PHOTO_URI, values);
     * </pre>
     * <p>Note that this latter form allows the insertion of a stream item and its
     * photos in a single transaction, by using {@link ContentProviderOperation} with
     * back references to populate the stream item ID in the {@link ContentValues}.
     * </dd>
     * </dl>
     * </p>
     * </dd>
     * <dt><b>Update</b></dt>
     * <dd>Updates can be performed by appending the stream item ID to the
     * {@link StreamItems#CONTENT_URI} URI.  Only social stream entries that were
     * created by the calling package can be updated.</dd>
     * <dt><b>Delete</b></dt>
     * <dd>Deletes can be performed by appending the stream item ID to the
     * {@link StreamItems#CONTENT_URI} URI.  Only social stream entries that were
     * created by the calling package can be deleted.</dd>
     * <dt><b>Query</b></dt>
     * <dl>
     * <dt>Finding all social stream updates for a given contact</dt>
     * <dd>By Contact ID:
     * <pre>
     * Cursor c = getContentResolver().query(Uri.withAppendedPath(
     *          ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
     *          Contacts.StreamItems.CONTENT_DIRECTORY),
     *          null, null, null, null);
     * </pre>
     * </dd>
     * <dd>By lookup key:
     * <pre>
     * Cursor c = getContentResolver().query(Contacts.CONTENT_URI.buildUpon()
     *          .appendPath(lookupKey)
     *          .appendPath(Contacts.StreamItems.CONTENT_DIRECTORY).build(),
     *          null, null, null, null);
     * </pre>
     * </dd>
     * <dt>Finding all social stream updates for a given raw contact</dt>
     * <dd>
     * <pre>
     * Cursor c = getContentResolver().query(Uri.withAppendedPath(
     *          ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId),
     *          RawContacts.StreamItems.CONTENT_DIRECTORY)),
     *          null, null, null, null);
     * </pre>
     * </dd>
     * <dt>Querying for a specific stream item by ID</dt>
     * <dd>
     * <pre>
     * Cursor c = getContentResolver().query(ContentUris.withAppendedId(
     *          StreamItems.CONTENT_URI, streamItemId),
     *          null, null, null, null);
     * </pre>
     * </dd>
     * </dl>
     *
     * @deprecated - Do not use. This will not be supported in the future. In the future,
     * cursors returned from related queries will be empty.
     *
     * @removed
     */
    @Deprecated
    public static final class StreamItems implements BaseColumns, StreamItemsColumns {
        /**
         * This utility class cannot be instantiated
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        private StreamItems() {
        }

        /**
         * The content:// style URI for this table, which handles social network stream
         * updates for the user's contacts.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "stream_items");

        /**
         * <p>
         * A content:// style URI for the photos stored in a sub-table underneath
         * stream items.  This is only used for inserts, and updates - queries and deletes
         * for photos should be performed by appending
         * {@link StreamItems.StreamItemPhotos#CONTENT_DIRECTORY} path to URIs for a
         * specific stream item.
         * </p>
         * <p>
         * When using this URI, the stream item ID for the photo(s) must be identified
         * in the {@link ContentValues} passed in.
         * </p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final Uri CONTENT_PHOTO_URI = Uri.withAppendedPath(CONTENT_URI, "photo");

        /**
         * This URI allows the caller to query for the maximum number of stream items
         * that will be stored under any single raw contact.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final Uri CONTENT_LIMIT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "stream_items_limit");

        /**
         * The MIME type of a directory of stream items.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/stream_item";

        /**
         * The MIME type of a single stream item.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/stream_item";

        /**
         * Queries to {@link ContactsContract.StreamItems#CONTENT_LIMIT_URI} will
         * contain this column, with the value indicating the maximum number of
         * stream items that will be stored under any single raw contact.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String MAX_ITEMS = "max_items";

        /**
         * <p>
         * A sub-directory of a single stream item entry that contains all of its
         * photo rows. To access this
         * directory append {@link StreamItems.StreamItemPhotos#CONTENT_DIRECTORY} to
         * an individual stream item URI.
         * </p>
         * <p>
         * Access to social stream photos requires additional permissions beyond the read/write
         * contact permissions required by the provider.  Querying for social stream photos
         * requires android.permission.READ_SOCIAL_STREAM permission, and inserting or updating
         * social stream photos requires android.permission.WRITE_SOCIAL_STREAM permission.
         * </p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         *
         * @removed
         */
        @Deprecated
        public static final class StreamItemPhotos
                implements BaseColumns, StreamItemPhotosColumns {
            /**
             * No public constructor since this is a utility class
             *
             * @deprecated - Do not use. This will not be supported in the future. In the future,
             * cursors returned from related queries will be empty.
             */
            @Deprecated
            private StreamItemPhotos() {
            }

            /**
             * The directory twig for this sub-table
             *
             * @deprecated - Do not use. This will not be supported in the future. In the future,
             * cursors returned from related queries will be empty.
             */
            @Deprecated
            public static final String CONTENT_DIRECTORY = "photo";

            /**
             * The MIME type of a directory of stream item photos.
             *
             * @deprecated - Do not use. This will not be supported in the future. In the future,
             * cursors returned from related queries will be empty.
             */
            @Deprecated
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/stream_item_photo";

            /**
             * The MIME type of a single stream item photo.
             *
             * @deprecated - Do not use. This will not be supported in the future. In the future,
             * cursors returned from related queries will be empty.
             */
            @Deprecated
            public static final String CONTENT_ITEM_TYPE
                    = "vnd.android.cursor.item/stream_item_photo";
        }
    }

    /**
     * Columns in the StreamItems table.
     *
     * @see ContactsContract.StreamItems
     * @deprecated - Do not use. This will not be supported in the future. In the future,
     * cursors returned from related queries will be empty.
     *
     * @removed
     */
    @Deprecated
    protected interface StreamItemsColumns {
        /**
         * A reference to the {@link android.provider.ContactsContract.Contacts#_ID}
         * that this stream item belongs to.
         *
         * <p>Type: INTEGER</p>
         * <p>read-only</p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String CONTACT_ID = "contact_id";

        /**
         * A reference to the {@link android.provider.ContactsContract.Contacts#LOOKUP_KEY}
         * that this stream item belongs to.
         *
         * <p>Type: TEXT</p>
         * <p>read-only</p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String CONTACT_LOOKUP_KEY = "contact_lookup";

        /**
         * A reference to the {@link RawContacts#_ID}
         * that this stream item belongs to.
         * <p>Type: INTEGER</p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String RAW_CONTACT_ID = "raw_contact_id";

        /**
         * The package name to use when creating {@link Resources} objects for
         * this stream item. This value is only designed for use when building
         * user interfaces, and should not be used to infer the owner.
         * <P>Type: TEXT</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String RES_PACKAGE = "res_package";

        /**
         * The account type to which the raw_contact of this item is associated. See
         * {@link RawContacts#ACCOUNT_TYPE}
         *
         * <p>Type: TEXT</p>
         * <p>read-only</p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String ACCOUNT_TYPE = "account_type";

        /**
         * The account name to which the raw_contact of this item is associated. See
         * {@link RawContacts#ACCOUNT_NAME}
         *
         * <p>Type: TEXT</p>
         * <p>read-only</p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * The data set within the account that the raw_contact of this row belongs to. This allows
         * multiple sync adapters for the same account type to distinguish between
         * each others' data.
         * {@link RawContacts#DATA_SET}
         *
         * <P>Type: TEXT</P>
         * <p>read-only</p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String DATA_SET = "data_set";

        /**
         * The source_id of the raw_contact that this row belongs to.
         * {@link RawContacts#SOURCE_ID}
         *
         * <P>Type: TEXT</P>
         * <p>read-only</p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String RAW_CONTACT_SOURCE_ID = "raw_contact_source_id";

        /**
         * The resource name of the icon for the source of the stream item.
         * This resource should be scoped by the {@link #RES_PACKAGE}. As this can only reference
         * drawables, the "@drawable/" prefix must be omitted.
         * <P>Type: TEXT</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String RES_ICON = "icon";

        /**
         * The resource name of the label describing the source of the status update, e.g. "Google
         * Talk". This resource should be scoped by the {@link #RES_PACKAGE}. As this can only
         * reference strings, the "@string/" prefix must be omitted.
         * <p>Type: TEXT</p>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String RES_LABEL = "label";

        /**
         * <P>
         * The main textual contents of the item. Typically this is content
         * that was posted by the source of this stream item, but it can also
         * be a textual representation of an action (e.g. ”Checked in at Joe's”).
         * This text is displayed to the user and allows formatting and embedded
         * resource images via HTML (as parseable via
         * {@link android.text.Html#fromHtml}).
         * </P>
         * <P>
         * Long content may be truncated and/or ellipsized - the exact behavior
         * is unspecified, but it should not break tags.
         * </P>
         * <P>Type: TEXT</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String TEXT = "text";

        /**
         * The absolute time (milliseconds since epoch) when this stream item was
         * inserted/updated.
         * <P>Type: NUMBER</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String TIMESTAMP = "timestamp";

        /**
         * <P>
         * Summary information about the stream item, for example to indicate how
         * many people have reshared it, how many have liked it, how many thumbs
         * up and/or thumbs down it has, what the original source was, etc.
         * </P>
         * <P>
         * This text is displayed to the user and allows simple formatting via
         * HTML, in the same manner as {@link #TEXT} allows.
         * </P>
         * <P>
         * Long content may be truncated and/or ellipsized - the exact behavior
         * is unspecified, but it should not break tags.
         * </P>
         * <P>Type: TEXT</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String COMMENTS = "comments";

        /**
         * Generic column for use by sync adapters.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String SYNC1 = "stream_item_sync1";
        /**
         * Generic column for use by sync adapters.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String SYNC2 = "stream_item_sync2";
        /**
         * Generic column for use by sync adapters.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String SYNC3 = "stream_item_sync3";
        /**
         * Generic column for use by sync adapters.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String SYNC4 = "stream_item_sync4";
    }

    /**
     * <p>
     * Constants for the stream_item_photos table, which contains photos associated with
     * social stream updates.
     * </p>
     * <p>
     * Access to social stream photos requires additional permissions beyond the read/write
     * contact permissions required by the provider.  Querying for social stream photos
     * requires android.permission.READ_SOCIAL_STREAM permission, and inserting or updating
     * social stream photos requires android.permission.WRITE_SOCIAL_STREAM permission.
     * </p>
     * <h3>Account check</h3>
     * <p>
     * The content URIs to the insert, update and delete operations are required to have the account
     * information matching that of the owning raw contact as query parameters, namely
     * {@link RawContacts#ACCOUNT_TYPE} and {@link RawContacts#ACCOUNT_NAME}.
     * {@link RawContacts#DATA_SET} isn't required.
     * </p>
     * <h3>Operations</h3>
     * <dl>
     * <dt><b>Insert</b></dt>
     * <dd>
     * <p>Social stream photo entries are associated with a social stream item.  Photos
     * can be inserted into a social stream item in a couple of ways:
     * <dl>
     * <dt>
     * Via the {@link StreamItems.StreamItemPhotos#CONTENT_DIRECTORY} sub-path of a
     * stream item:
     * </dt>
     * <dd>
     * <pre>
     * ContentValues values = new ContentValues();
     * values.put(StreamItemPhotos.SORT_INDEX, 1);
     * values.put(StreamItemPhotos.PHOTO, photoData);
     * Uri.Builder builder = StreamItems.CONTENT_URI.buildUpon();
     * ContentUris.appendId(builder, streamItemId);
     * builder.appendEncodedPath(StreamItems.StreamItemPhotos.CONTENT_DIRECTORY);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType);
     * Uri photoUri = getContentResolver().insert(builder.build(), values);
     * long photoId = ContentUris.parseId(photoUri);
     * </pre>
     * </dd>
     * <dt>Via the {@link ContactsContract.StreamItems#CONTENT_PHOTO_URI} URI:</dt>
     * <dd>
     * <pre>
     * ContentValues values = new ContentValues();
     * values.put(StreamItemPhotos.STREAM_ITEM_ID, streamItemId);
     * values.put(StreamItemPhotos.SORT_INDEX, 1);
     * values.put(StreamItemPhotos.PHOTO, photoData);
     * Uri.Builder builder = StreamItems.CONTENT_PHOTO_URI.buildUpon();
     * builder.appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType);
     * Uri photoUri = getContentResolver().insert(builder.build(), values);
     * long photoId = ContentUris.parseId(photoUri);
     * </pre>
     * </dd>
     * </dl>
     * </p>
     * </dd>
     * <dt><b>Update</b></dt>
     * <dd>
     * <p>Updates can only be made against a specific {@link StreamItemPhotos} entry,
     * identified by both the stream item ID it belongs to and the stream item photo ID.
     * This can be specified in two ways.
     * <dl>
     * <dt>Via the {@link StreamItems.StreamItemPhotos#CONTENT_DIRECTORY} sub-path of a
     * stream item:
     * </dt>
     * <dd>
     * <pre>
     * ContentValues values = new ContentValues();
     * values.put(StreamItemPhotos.PHOTO, newPhotoData);
     * Uri.Builder builder = StreamItems.CONTENT_URI.buildUpon();
     * ContentUris.appendId(builder, streamItemId);
     * builder.appendEncodedPath(StreamItems.StreamItemPhotos.CONTENT_DIRECTORY);
     * ContentUris.appendId(builder, streamItemPhotoId);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType);
     * getContentResolver().update(builder.build(), values, null, null);
     * </pre>
     * </dd>
     * <dt>Via the {@link ContactsContract.StreamItems#CONTENT_PHOTO_URI} URI:</dt>
     * <dd>
     * <pre>
     * ContentValues values = new ContentValues();
     * values.put(StreamItemPhotos.STREAM_ITEM_ID, streamItemId);
     * values.put(StreamItemPhotos.PHOTO, newPhotoData);
     * Uri.Builder builder = StreamItems.CONTENT_PHOTO_URI.buildUpon();
     * builder.appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType);
     * getContentResolver().update(builder.build(), values);
     * </pre>
     * </dd>
     * </dl>
     * </p>
     * </dd>
     * <dt><b>Delete</b></dt>
     * <dd>Deletes can be made against either a specific photo item in a stream item, or
     * against all or a selected subset of photo items under a stream item.
     * For example:
     * <dl>
     * <dt>Deleting a single photo via the
     * {@link StreamItems.StreamItemPhotos#CONTENT_DIRECTORY} sub-path of a stream item:
     * </dt>
     * <dd>
     * <pre>
     * Uri.Builder builder = StreamItems.CONTENT_URI.buildUpon();
     * ContentUris.appendId(builder, streamItemId);
     * builder.appendEncodedPath(StreamItems.StreamItemPhotos.CONTENT_DIRECTORY);
     * ContentUris.appendId(builder, streamItemPhotoId);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType);
     * getContentResolver().delete(builder.build(), null, null);
     * </pre>
     * </dd>
     * <dt>Deleting all photos under a stream item</dt>
     * <dd>
     * <pre>
     * Uri.Builder builder = StreamItems.CONTENT_URI.buildUpon();
     * ContentUris.appendId(builder, streamItemId);
     * builder.appendEncodedPath(StreamItems.StreamItemPhotos.CONTENT_DIRECTORY);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName);
     * builder.appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType);
     * getContentResolver().delete(builder.build(), null, null);
     * </pre>
     * </dd>
     * </dl>
     * </dd>
     * <dt><b>Query</b></dt>
     * <dl>
     * <dt>Querying for a specific photo in a stream item</dt>
     * <dd>
     * <pre>
     * Cursor c = getContentResolver().query(
     *     ContentUris.withAppendedId(
     *         Uri.withAppendedPath(
     *             ContentUris.withAppendedId(StreamItems.CONTENT_URI, streamItemId)
     *             StreamItems.StreamItemPhotos#CONTENT_DIRECTORY),
     *         streamItemPhotoId), null, null, null, null);
     * </pre>
     * </dd>
     * <dt>Querying for all photos in a stream item</dt>
     * <dd>
     * <pre>
     * Cursor c = getContentResolver().query(
     *     Uri.withAppendedPath(
     *         ContentUris.withAppendedId(StreamItems.CONTENT_URI, streamItemId)
     *         StreamItems.StreamItemPhotos#CONTENT_DIRECTORY),
     *     null, null, null, StreamItemPhotos.SORT_INDEX);
     * </pre>
     * </dl>
     * The record will contain both a {@link StreamItemPhotos#PHOTO_FILE_ID} and a
     * {@link StreamItemPhotos#PHOTO_URI}.  The {@link StreamItemPhotos#PHOTO_FILE_ID}
     * can be used in conjunction with the {@link ContactsContract.DisplayPhoto} API to
     * retrieve photo content, or you can open the {@link StreamItemPhotos#PHOTO_URI} as
     * an asset file, as follows:
     * <pre>
     * public InputStream openDisplayPhoto(String photoUri) {
     *     try {
     *         AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(photoUri, "r");
     *         return fd.createInputStream();
     *     } catch (IOException e) {
     *         return null;
     *     }
     * }
     * <pre>
     * </dd>
     * </dl>
     *
     * @deprecated - Do not use. This will not be supported in the future. In the future,
     * cursors returned from related queries will be empty.
     *
     * @removed
     */
    @Deprecated
    public static final class StreamItemPhotos implements BaseColumns, StreamItemPhotosColumns {
        /**
         * No public constructor since this is a utility class
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        private StreamItemPhotos() {
        }

        /**
         * <p>
         * The binary representation of the photo.  Any size photo can be inserted;
         * the provider will resize it appropriately for storage and display.
         * </p>
         * <p>
         * This is only intended for use when inserting or updating a stream item photo.
         * To retrieve the photo that was stored, open {@link StreamItemPhotos#PHOTO_URI}
         * as an asset file.
         * </p>
         * <P>Type: BLOB</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String PHOTO = "photo";
    }

    /**
     * Columns in the StreamItemPhotos table.
     *
     * @see ContactsContract.StreamItemPhotos
     * @deprecated - Do not use. This will not be supported in the future. In the future,
     * cursors returned from related queries will be empty.
     *
     * @removed
     */
    @Deprecated
    protected interface StreamItemPhotosColumns {
        /**
         * A reference to the {@link StreamItems#_ID} this photo is associated with.
         * <P>Type: NUMBER</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String STREAM_ITEM_ID = "stream_item_id";

        /**
         * An integer to use for sort order for photos in the stream item.  If not
         * specified, the {@link StreamItemPhotos#_ID} will be used for sorting.
         * <P>Type: NUMBER</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String SORT_INDEX = "sort_index";

        /**
         * Photo file ID for the photo.
         * See {@link ContactsContract.DisplayPhoto}.
         * <P>Type: NUMBER</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String PHOTO_FILE_ID = "photo_file_id";

        /**
         * URI for retrieving the photo content, automatically populated.  Callers
         * may retrieve the photo content by opening this URI as an asset file.
         * <P>Type: TEXT</P>
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String PHOTO_URI = "photo_uri";

        /**
         * Generic column for use by sync adapters.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String SYNC1 = "stream_item_photo_sync1";
        /**
         * Generic column for use by sync adapters.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String SYNC2 = "stream_item_photo_sync2";
        /**
         * Generic column for use by sync adapters.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String SYNC3 = "stream_item_photo_sync3";
        /**
         * Generic column for use by sync adapters.
         *
         * @deprecated - Do not use. This will not be supported in the future. In the future,
         * cursors returned from related queries will be empty.
         */
        @Deprecated
        public static final String SYNC4 = "stream_item_photo_sync4";
    }

    /**
     * <p>
     * Constants for the photo files table, which tracks metadata for hi-res photos
     * stored in the file system.
     * </p>
     *
     * @hide
     */
    public static final class PhotoFiles implements BaseColumns, PhotoFilesColumns {
        /**
         * No public constructor since this is a utility class
         */
        private PhotoFiles() {
        }
    }

    /**
     * Columns in the PhotoFiles table.
     *
     * @see ContactsContract.PhotoFiles
     *
     * @hide
     */
    protected interface PhotoFilesColumns {

        /**
         * The height, in pixels, of the photo this entry is associated with.
         * <P>Type: NUMBER</P>
         */
        public static final String HEIGHT = "height";

        /**
         * The width, in pixels, of the photo this entry is associated with.
         * <P>Type: NUMBER</P>
         */
        public static final String WIDTH = "width";

        /**
         * The size, in bytes, of the photo stored on disk.
         * <P>Type: NUMBER</P>
         */
        public static final String FILESIZE = "filesize";
    }

    /**
     * Columns in the Data table.
     *
     * @see ContactsContract.Data
     */
    protected interface DataColumns {
        /**
         * The package name to use when creating {@link Resources} objects for
         * this data row. This value is only designed for use when building user
         * interfaces, and should not be used to infer the owner.
         */
        public static final String RES_PACKAGE = "res_package";

        /**
         * The MIME type of the item represented by this row.
         */
        public static final String MIMETYPE = "mimetype";

        /**
         * Hash id on the data fields, used for backup and restore.
         *
         * @hide
         * @deprecated This column was never public since added. It will not be supported
         * as of Android version {@link android.os.Build.VERSION_CODES#R}.
         */
        @Deprecated
        public static final String HASH_ID = "hash_id";

        /**
         * A reference to the {@link RawContacts#_ID}
         * that this data belongs to.
         */
        public static final String RAW_CONTACT_ID = "raw_contact_id";

        /**
         * Whether this is the primary entry of its kind for the raw contact it belongs to.
         * <P>Type: INTEGER (if set, non-0 means true)</P>
         */
        public static final String IS_PRIMARY = "is_primary";

        /**
         * Whether this is the primary entry of its kind for the aggregate
         * contact it belongs to. Any data record that is "super primary" must
         * also be "primary".
         * <P>Type: INTEGER (if set, non-0 means true)</P>
         */
        public static final String IS_SUPER_PRIMARY = "is_super_primary";

        /**
         * The "read-only" flag: "0" by default, "1" if the row cannot be modified or
         * deleted except by a sync adapter.  See {@link ContactsContract#CALLER_IS_SYNCADAPTER}.
         * <P>Type: INTEGER</P>
         */
        public static final String IS_READ_ONLY = "is_read_only";

        /**
         * The version of this data record. This is a read-only value. The data column is
         * guaranteed to not change without the version going up. This value is monotonically
         * increasing.
         * <P>Type: INTEGER</P>
         */
        public static final String DATA_VERSION = "data_version";

        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA1 = "data1";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA2 = "data2";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA3 = "data3";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA4 = "data4";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA5 = "data5";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA6 = "data6";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA7 = "data7";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA8 = "data8";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA9 = "data9";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA10 = "data10";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA11 = "data11";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA12 = "data12";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA13 = "data13";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA14 = "data14";
        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific. By convention,
         * this field is used to store BLOBs (binary data).
         */
        public static final String DATA15 = "data15";

        /** Generic column for use by sync adapters. */
        public static final String SYNC1 = "data_sync1";
        /** Generic column for use by sync adapters. */
        public static final String SYNC2 = "data_sync2";
        /** Generic column for use by sync adapters. */
        public static final String SYNC3 = "data_sync3";
        /** Generic column for use by sync adapters. */
        public static final String SYNC4 = "data_sync4";

        /**
         * Carrier presence information.
         * <P>
         * Type: INTEGER (A bitmask of CARRIER_PRESENCE_* fields)
         * </P>
         *
         * @deprecated The contacts database will only show presence
         * information on devices where
         * {@link android.telephony.CarrierConfigManager#KEY_USE_RCS_PRESENCE_BOOL} is true,
         * otherwise use {@link android.telephony.ims.RcsUceAdapter}.
         */
        @Deprecated
        public static final String CARRIER_PRESENCE = "carrier_presence";

        /**
         * Indicates that the entry is Video Telephony (VT) capable on the
         * current carrier. An allowed bitmask of {@link #CARRIER_PRESENCE}.
         *
         * @deprecated Same as {@link DataColumns#CARRIER_PRESENCE}.
         *
         */
        @Deprecated
        public static final int CARRIER_PRESENCE_VT_CAPABLE = 0x01;

        /**
         * A reference to indicate whether phone account migration process is pending.
         *
         * Before Android 13, {@link PhoneAccountHandle#getId()} returns the ICCID for Telephony
         * PhoneAccountHandle. Starting from Android 13, {@link PhoneAccountHandle#getId()} returns
         * the Subscription ID for Telephony PhoneAccountHandle. A phone account migration process
         * is to ensure this PhoneAccountHandle migration process cross the Android versions in
         * the ContactsContract database.
         *
         * <p>Type: INTEGER</p>
         * @hide
         */
        String IS_PHONE_ACCOUNT_MIGRATION_PENDING = "is_preferred_phone_account_migration_pending";

        /**
         * The flattened {@link android.content.ComponentName} of a  {@link
         * android.telecom.PhoneAccountHandle} that is the preferred {@code PhoneAccountHandle} to
         * call the contact with.
         *
         * <p> On a multi-SIM device this field can be used in a {@link CommonDataKinds.Phone} row
         * to indicate the {@link PhoneAccountHandle} to call the number with, instead of using
         * {@link android.telecom.TelecomManager#getDefaultOutgoingPhoneAccount(String)} or asking
         * every time.
         *
         * <p>{@link android.telecom.TelecomManager#placeCall(Uri, android.os.Bundle)}
         * should be called with {@link android.telecom.TelecomManager#EXTRA_PHONE_ACCOUNT_HANDLE}
         * set to the {@link PhoneAccountHandle} using the {@link ComponentName} from this field.
         *
         * @see #PREFERRED_PHONE_ACCOUNT_ID
         * @see PhoneAccountHandle#getComponentName()
         * @see ComponentName#flattenToString()
         */
        String PREFERRED_PHONE_ACCOUNT_COMPONENT_NAME = "preferred_phone_account_component_name";

        /**
         * The ID of a {@link
         * android.telecom.PhoneAccountHandle} that is the preferred {@code PhoneAccountHandle} to
         * call the contact with. Used by {@link CommonDataKinds.Phone}.
         *
         * <p> On a multi-SIM device this field can be used in a {@link CommonDataKinds.Phone} row
         * to indicate the {@link PhoneAccountHandle} to call the number with, instead of using
         * {@link android.telecom.TelecomManager#getDefaultOutgoingPhoneAccount(String)} or asking
         * every time.
         *
         * <p>{@link android.telecom.TelecomManager#placeCall(Uri, android.os.Bundle)}
         * should be called with {@link android.telecom.TelecomManager#EXTRA_PHONE_ACCOUNT_HANDLE}
         * set to the {@link PhoneAccountHandle} using the id from this field.
         *
         * @see #PREFERRED_PHONE_ACCOUNT_COMPONENT_NAME
         * @see PhoneAccountHandle#getId()
         */
        String PREFERRED_PHONE_ACCOUNT_ID = "preferred_phone_account_id";
    }

    /**
     * Columns in the Data_Usage_Stat table
     */
    protected interface DataUsageStatColumns {
        /**
         * The last time (in milliseconds) this {@link Data} was used.
         * @deprecated Contacts affinity information is no longer supported as of
         * Android version {@link android.os.Build.VERSION_CODES#Q}.
         * This column always contains 0.
         *
         * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
         * this field is obsolete, regardless of Android version. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.</p>
         */
        @Deprecated
        public static final String LAST_TIME_USED = "last_time_used";

        /**
         * The number of times the referenced {@link Data} has been used.
         * @deprecated Contacts affinity information is no longer supported as of
         * Android version {@link android.os.Build.VERSION_CODES#Q}.
         * This column always contains 0.
         *
         * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
         * this field is obsolete, regardless of Android version. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.</p>
         */
        @Deprecated
        public static final String TIMES_USED = "times_used";

        /** @hide Raw value. */
        public static final String RAW_LAST_TIME_USED = HIDDEN_COLUMN_PREFIX + LAST_TIME_USED;

        /** @hide Raw value. */
        public static final String RAW_TIMES_USED = HIDDEN_COLUMN_PREFIX + TIMES_USED;

        /**
         * @hide
         * Low res version.  Same as {@link #LAST_TIME_USED} but use it in CP2 for clarification.
         */
        public static final String LR_LAST_TIME_USED = LAST_TIME_USED;

        /**
         * @hide
         * Low res version.  Same as {@link #TIMES_USED} but use it in CP2 for clarification.
         */
        public static final String LR_TIMES_USED = TIMES_USED;
    }

    /**
     * Combines all columns returned by {@link ContactsContract.Data} table queries.
     *
     * @see ContactsContract.Data
     */
    protected interface DataColumnsWithJoins extends BaseColumns, DataColumns, StatusColumns,
            RawContactsColumns, ContactsColumns, ContactNameColumns, ContactOptionsColumns,
            ContactStatusColumns, DataUsageStatColumns {
    }

    /**
     * <p>
     * Constants for the data table, which contains data points tied to a raw
     * contact.  Each row of the data table is typically used to store a single
     * piece of contact
     * information (such as a phone number) and its
     * associated metadata (such as whether it is a work or home number).
     * </p>
     * <h3>Data kinds</h3>
     * <p>
     * Data is a generic table that can hold any kind of contact data.
     * The kind of data stored in a given row is specified by the row's
     * {@link #MIMETYPE} value, which determines the meaning of the
     * generic columns {@link #DATA1} through
     * {@link #DATA15}.
     * For example, if the data kind is
     * {@link CommonDataKinds.Phone Phone.CONTENT_ITEM_TYPE}, then the column
     * {@link #DATA1} stores the
     * phone number, but if the data kind is
     * {@link CommonDataKinds.Email Email.CONTENT_ITEM_TYPE}, then {@link #DATA1}
     * stores the email address.
     * Sync adapters and applications can introduce their own data kinds.
     * </p>
     * <p>
     * ContactsContract defines a small number of pre-defined data kinds, e.g.
     * {@link CommonDataKinds.Phone}, {@link CommonDataKinds.Email} etc. As a
     * convenience, these classes define data kind specific aliases for DATA1 etc.
     * For example, {@link CommonDataKinds.Phone Phone.NUMBER} is the same as
     * {@link ContactsContract.Data Data.DATA1}.
     * </p>
     * <p>
     * {@link #DATA1} is an indexed column and should be used for the data element that is
     * expected to be most frequently used in query selections. For example, in the
     * case of a row representing email addresses {@link #DATA1} should probably
     * be used for the email address itself, while {@link #DATA2} etc can be
     * used for auxiliary information like type of email address.
     * <p>
     * <p>
     * By convention, {@link #DATA15} is used for storing BLOBs (binary data).
     * </p>
     * <p>
     * The sync adapter for a given account type must correctly handle every data type
     * used in the corresponding raw contacts.  Otherwise it could result in lost or
     * corrupted data.
     * </p>
     * <p>
     * Similarly, you should refrain from introducing new kinds of data for an other
     * party's account types. For example, if you add a data row for
     * "favorite song" to a raw contact owned by a Google account, it will not
     * get synced to the server, because the Google sync adapter does not know
     * how to handle this data kind. Thus new data kinds are typically
     * introduced along with new account types, i.e. new sync adapters.
     * </p>
     * <h3>Batch operations</h3>
     * <p>
     * Data rows can be inserted/updated/deleted using the traditional
     * {@link ContentResolver#insert}, {@link ContentResolver#update} and
     * {@link ContentResolver#delete} methods, however the newer mechanism based
     * on a batch of {@link ContentProviderOperation} will prove to be a better
     * choice in almost all cases. All operations in a batch are executed in a
     * single transaction, which ensures that the phone-side and server-side
     * state of a raw contact are always consistent. Also, the batch-based
     * approach is far more efficient: not only are the database operations
     * faster when executed in a single transaction, but also sending a batch of
     * commands to the content provider saves a lot of time on context switching
     * between your process and the process in which the content provider runs.
     * </p>
     * <p>
     * The flip side of using batched operations is that a large batch may lock
     * up the database for a long time preventing other applications from
     * accessing data and potentially causing ANRs ("Application Not Responding"
     * dialogs.)
     * </p>
     * <p>
     * To avoid such lockups of the database, make sure to insert "yield points"
     * in the batch. A yield point indicates to the content provider that before
     * executing the next operation it can commit the changes that have already
     * been made, yield to other requests, open another transaction and continue
     * processing operations. A yield point will not automatically commit the
     * transaction, but only if there is another request waiting on the
     * database. Normally a sync adapter should insert a yield point at the
     * beginning of each raw contact operation sequence in the batch. See
     * {@link ContentProviderOperation.Builder#withYieldAllowed(boolean)}.
     * </p>
     * <h3>Operations</h3>
     * <dl>
     * <dt><b>Insert</b></dt>
     * <dd>
     * <p>
     * An individual data row can be inserted using the traditional
     * {@link ContentResolver#insert(Uri, ContentValues)} method. Multiple rows
     * should always be inserted as a batch.
     * </p>
     * <p>
     * An example of a traditional insert:
     * <pre>
     * ContentValues values = new ContentValues();
     * values.put(Data.RAW_CONTACT_ID, rawContactId);
     * values.put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
     * values.put(Phone.NUMBER, "1-800-GOOG-411");
     * values.put(Phone.TYPE, Phone.TYPE_CUSTOM);
     * values.put(Phone.LABEL, "free directory assistance");
     * Uri dataUri = getContentResolver().insert(Data.CONTENT_URI, values);
     * </pre>
     * <p>
     * The same done using ContentProviderOperations:
     * <pre>
     * ArrayList&lt;ContentProviderOperation&gt; ops =
     *          new ArrayList&lt;ContentProviderOperation&gt;();
     *
     * ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
     *          .withValue(Data.RAW_CONTACT_ID, rawContactId)
     *          .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
     *          .withValue(Phone.NUMBER, "1-800-GOOG-411")
     *          .withValue(Phone.TYPE, Phone.TYPE_CUSTOM)
     *          .withValue(Phone.LABEL, "free directory assistance")
     *          .build());
     * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
     * </pre>
     * </p>
     * <dt><b>Update</b></dt>
     * <dd>
     * <p>
     * Just as with insert, update can be done incrementally or as a batch,
     * the batch mode being the preferred method:
     * <pre>
     * ArrayList&lt;ContentProviderOperation&gt; ops =
     *          new ArrayList&lt;ContentProviderOperation&gt;();
     *
     * ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
     *          .withSelection(Data._ID + "=?", new String[]{String.valueOf(dataId)})
     *          .withValue(Email.DATA, "somebody@android.com")
     *          .build());
     * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
     * </pre>
     * </p>
     * </dd>
     * <dt><b>Delete</b></dt>
     * <dd>
     * <p>
     * Just as with insert and update, deletion can be done either using the
     * {@link ContentResolver#delete} method or using a ContentProviderOperation:
     * <pre>
     * ArrayList&lt;ContentProviderOperation&gt; ops =
     *          new ArrayList&lt;ContentProviderOperation&gt;();
     *
     * ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
     *          .withSelection(Data._ID + "=?", new String[]{String.valueOf(dataId)})
     *          .build());
     * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
     * </pre>
     * </p>
     * </dd>
     * <dt><b>Query</b></dt>
     * <dd>
     * <p>
     * <dl>
     * <dt>Finding all Data of a given type for a given contact</dt>
     * <dd>
     * <pre>
     * Cursor c = getContentResolver().query(Data.CONTENT_URI,
     *          new String[] {Data._ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL},
     *          Data.CONTACT_ID + &quot;=?&quot; + " AND "
     *                  + Data.MIMETYPE + "='" + Phone.CONTENT_ITEM_TYPE + "'",
     *          new String[] {String.valueOf(contactId)}, null);
     * </pre>
     * </p>
     * <p>
     * </dd>
     * <dt>Finding all Data of a given type for a given raw contact</dt>
     * <dd>
     * <pre>
     * Cursor c = getContentResolver().query(Data.CONTENT_URI,
     *          new String[] {Data._ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL},
     *          Data.RAW_CONTACT_ID + &quot;=?&quot; + " AND "
     *                  + Data.MIMETYPE + "='" + Phone.CONTENT_ITEM_TYPE + "'",
     *          new String[] {String.valueOf(rawContactId)}, null);
     * </pre>
     * </dd>
     * <dt>Finding all Data for a given raw contact</dt>
     * <dd>
     * Most sync adapters will want to read all data rows for a raw contact
     * along with the raw contact itself.  For that you should use the
     * {@link RawContactsEntity}. See also {@link RawContacts}.
     * </dd>
     * </dl>
     * </p>
     * </dd>
     * </dl>
     * <h2>Columns</h2>
     * <p>
     * Many columns are available via a {@link Data#CONTENT_URI} query.  For best performance you
     * should explicitly specify a projection to only those columns that you need.
     * </p>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Data</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">long</td>
     * <td style="width: 20em;">{@link #_ID}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>Row ID. Sync adapter should try to preserve row IDs during updates. In other words,
     * it would be a bad idea to delete and reinsert a data row. A sync adapter should
     * always do an update instead.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #MIMETYPE}</td>
     * <td>read/write-once</td>
     * <td>
     * <p>The MIME type of the item represented by this row. Examples of common
     * MIME types are:
     * <ul>
     * <li>{@link CommonDataKinds.StructuredName StructuredName.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Phone Phone.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Email Email.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Photo Photo.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Organization Organization.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Im Im.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Nickname Nickname.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Note Note.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.StructuredPostal StructuredPostal.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.GroupMembership GroupMembership.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Website Website.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Event Event.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Relation Relation.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.SipAddress SipAddress.CONTENT_ITEM_TYPE}</li>
     * </ul>
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #RAW_CONTACT_ID}</td>
     * <td>read/write-once</td>
     * <td>The id of the row in the {@link RawContacts} table that this data belongs to.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IS_PRIMARY}</td>
     * <td>read/write</td>
     * <td>Whether this is the primary entry of its kind for the raw contact it belongs to.
     * "1" if true, "0" if false.
     * </td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IS_SUPER_PRIMARY}</td>
     * <td>read/write</td>
     * <td>Whether this is the primary entry of its kind for the aggregate
     * contact it belongs to. Any data record that is "super primary" must
     * also be "primary".  For example, the super-primary entry may be
     * interpreted as the default contact value of its kind (for example,
     * the default phone number to use for the contact).</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DATA_VERSION}</td>
     * <td>read-only</td>
     * <td>The version of this data record. Whenever the data row changes
     * the version goes up. This value is monotonically increasing.</td>
     * </tr>
     * <tr>
     * <td>Any type</td>
     * <td>
     * {@link #DATA1}<br>
     * {@link #DATA2}<br>
     * {@link #DATA3}<br>
     * {@link #DATA4}<br>
     * {@link #DATA5}<br>
     * {@link #DATA6}<br>
     * {@link #DATA7}<br>
     * {@link #DATA8}<br>
     * {@link #DATA9}<br>
     * {@link #DATA10}<br>
     * {@link #DATA11}<br>
     * {@link #DATA12}<br>
     * {@link #DATA13}<br>
     * {@link #DATA14}<br>
     * {@link #DATA15}
     * </td>
     * <td>read/write</td>
     * <td>
     * <p>
     * Generic data columns.  The meaning of each column is determined by the
     * {@link #MIMETYPE}.  By convention, {@link #DATA15} is used for storing
     * BLOBs (binary data).
     * </p>
     * <p>
     * Data columns whose meaning is not explicitly defined for a given MIMETYPE
     * should not be used.  There is no guarantee that any sync adapter will
     * preserve them.  Sync adapters themselves should not use such columns either,
     * but should instead use {@link #SYNC1}-{@link #SYNC4}.
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>Any type</td>
     * <td>
     * {@link #SYNC1}<br>
     * {@link #SYNC2}<br>
     * {@link #SYNC3}<br>
     * {@link #SYNC4}
     * </td>
     * <td>read/write</td>
     * <td>Generic columns for use by sync adapters. For example, a Photo row
     * may store the image URL in SYNC1, a status (not loaded, loading, loaded, error)
     * in SYNC2, server-side version number in SYNC3 and error code in SYNC4.</td>
     * </tr>
     * </table>
     *
     * <p>
     * Some columns from the most recent associated status update are also available
     * through an implicit join.
     * </p>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Join with {@link StatusUpdates}</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">int</td>
     * <td style="width: 20em;">{@link #PRESENCE}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>IM presence status linked to this data row. Compare with
     * {@link #CONTACT_PRESENCE}, which contains the contact's presence across
     * all IM rows. See {@link StatusUpdates} for individual status definitions.
     * The provider may choose not to store this value
     * in persistent storage. The expectation is that presence status will be
     * updated on a regular basis.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #STATUS}</td>
     * <td>read-only</td>
     * <td>Latest status update linked with this data row.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_TIMESTAMP}</td>
     * <td>read-only</td>
     * <td>The absolute time in milliseconds when the latest status was
     * inserted/updated for this data row.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #STATUS_RES_PACKAGE}</td>
     * <td>read-only</td>
     * <td>The package containing resources for this status: label and icon.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_LABEL}</td>
     * <td>read-only</td>
     * <td>The resource ID of the label describing the source of status update linked
     * to this data row. This resource is scoped by the {@link #STATUS_RES_PACKAGE}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_ICON}</td>
     * <td>read-only</td>
     * <td>The resource ID of the icon for the source of the status update linked
     * to this data row. This resource is scoped by the {@link #STATUS_RES_PACKAGE}.</td>
     * </tr>
     * </table>
     *
     * <p>
     * Some columns from the associated raw contact are also available through an
     * implicit join.  The other columns are excluded as uninteresting in this
     * context.
     * </p>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Join with {@link ContactsContract.RawContacts}</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">long</td>
     * <td style="width: 20em;">{@link #CONTACT_ID}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>The id of the row in the {@link Contacts} table that this data belongs
     * to.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #AGGREGATION_MODE}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DELETED}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * </table>
     *
     * <p>
     * The ID column for the associated aggregated contact table
     * {@link ContactsContract.Contacts} is available
     * via the implicit join to the {@link RawContacts} table, see above.
     * The remaining columns from this table are also
     * available, through an implicit join.  This
     * facilitates lookup by
     * the value of a single data element, such as the email address.
     * </p>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Join with {@link ContactsContract.Contacts}</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">String</td>
     * <td style="width: 20em;">{@link #LOOKUP_KEY}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>See {@link ContactsContract.Contacts}</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #DISPLAY_NAME}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PHOTO_ID}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IN_VISIBLE_GROUP}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #HAS_PHONE_NUMBER}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #STARRED}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_RINGTONE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SEND_TO_VOICEMAIL}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #CONTACT_PRESENCE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CONTACT_STATUS}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_TIMESTAMP}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CONTACT_STATUS_RES_PACKAGE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_LABEL}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_ICON}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * </table>
     */
    public final static class Data implements DataColumnsWithJoins, ContactCounts {
        /**
         * This utility class cannot be instantiated
         */
        private Data() {}

        /**
         * The content:// style URI for this table, which requests a directory
         * of data rows matching the selection criteria.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "data");

        /**
        * The content:// style URI for this table in managed profile, which requests a directory
        * of data rows matching the selection criteria.
        *
        * @hide
        */
        static final Uri ENTERPRISE_CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "data_enterprise");

        /**
         * A boolean parameter for {@link Data#CONTENT_URI}.
         * This specifies whether or not the returned data items should be filtered to show
         * data items belonging to visible contacts only.
         */
        public static final String VISIBLE_CONTACTS_ONLY = "visible_contacts_only";

        /**
         * The MIME type of the results from {@link #CONTENT_URI}.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/data";

        /**
         * <p>
         * Build a {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI}
         * style {@link Uri} for the parent {@link android.provider.ContactsContract.Contacts}
         * entry of the given {@link ContactsContract.Data} entry.
         * </p>
         * <p>
         * Returns the Uri for the contact in the first entry returned by
         * {@link ContentResolver#query(Uri, String[], String, String[], String)}
         * for the provided {@code dataUri}.  If the query returns null or empty
         * results, silently returns null.
         * </p>
         */
        public static Uri getContactLookupUri(ContentResolver resolver, Uri dataUri) {
            final Cursor cursor = resolver.query(dataUri, new String[] {
                    RawContacts.CONTACT_ID, Contacts.LOOKUP_KEY
            }, null, null, null);

            Uri lookupUri = null;
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    final long contactId = cursor.getLong(0);
                    final String lookupKey = cursor.getString(1);
                    return Contacts.getLookupUri(contactId, lookupKey);
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            return lookupUri;
        }
    }

    /**
     * <p>
     * Constants for the raw contacts entities table, which can be thought of as
     * an outer join of the raw_contacts table with the data table.  It is a strictly
     * read-only table.
     * </p>
     * <p>
     * If a raw contact has data rows, the RawContactsEntity cursor will contain
     * a one row for each data row. If the raw contact has no data rows, the
     * cursor will still contain one row with the raw contact-level information
     * and nulls for data columns.
     *
     * <pre>
     * Uri entityUri = ContentUris.withAppendedId(RawContactsEntity.CONTENT_URI, rawContactId);
     * Cursor c = getContentResolver().query(entityUri,
     *          new String[]{
     *              RawContactsEntity.SOURCE_ID,
     *              RawContactsEntity.DATA_ID,
     *              RawContactsEntity.MIMETYPE,
     *              RawContactsEntity.DATA1
     *          }, null, null, null);
     * try {
     *     while (c.moveToNext()) {
     *         String sourceId = c.getString(0);
     *         if (!c.isNull(1)) {
     *             String mimeType = c.getString(2);
     *             String data = c.getString(3);
     *             ...
     *         }
     *     }
     * } finally {
     *     c.close();
     * }
     * </pre>
     *
     * <h3>Columns</h3>
     * RawContactsEntity has a combination of RawContact and Data columns.
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>RawContacts</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">long</td>
     * <td style="width: 20em;">{@link #_ID}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>Raw contact row ID. See {@link RawContacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_ID}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #AGGREGATION_MODE}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DELETED}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * </table>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Data</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">long</td>
     * <td style="width: 20em;">{@link #DATA_ID}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>Data row ID. It will be null if the raw contact has no data rows.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #MIMETYPE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IS_PRIMARY}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IS_SUPER_PRIMARY}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DATA_VERSION}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>Any type</td>
     * <td>
     * {@link #DATA1}<br>
     * {@link #DATA2}<br>
     * {@link #DATA3}<br>
     * {@link #DATA4}<br>
     * {@link #DATA5}<br>
     * {@link #DATA6}<br>
     * {@link #DATA7}<br>
     * {@link #DATA8}<br>
     * {@link #DATA9}<br>
     * {@link #DATA10}<br>
     * {@link #DATA11}<br>
     * {@link #DATA12}<br>
     * {@link #DATA13}<br>
     * {@link #DATA14}<br>
     * {@link #DATA15}
     * </td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>Any type</td>
     * <td>
     * {@link #SYNC1}<br>
     * {@link #SYNC2}<br>
     * {@link #SYNC3}<br>
     * {@link #SYNC4}
     * </td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * </table>
     */
    public final static class RawContactsEntity
            implements BaseColumns, DataColumns, RawContactsColumns {
        private static final String TAG = "ContactsContract.RawContactsEntity";

        /**
         * This utility class cannot be instantiated
         */
        private RawContactsEntity() {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "raw_contact_entities");

        /**
        * The content:// style URI for this table in the managed profile
        *
        * @hide
        */
        @TestApi
        public static final Uri CORP_CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "raw_contact_entities_corp");

        /**
         * The content:// style URI for this table, specific to the user's profile.
         */
        public static final Uri PROFILE_CONTENT_URI =
                Uri.withAppendedPath(Profile.CONTENT_URI, "raw_contact_entities");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of raw contact entities.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/raw_contact_entity";

        /**
         * If {@link #FOR_EXPORT_ONLY} is explicitly set to "1", returned Cursor toward
         * Data.CONTENT_URI contains only exportable data.
         *
         * This flag is useful (currently) only for vCard exporter in Contacts app, which
         * needs to exclude "un-exportable" data from available data to export, while
         * Contacts app itself has priviledge to access all data including "un-expotable"
         * ones and providers return all of them regardless of the callers' intention.
         * <P>Type: INTEGER</p>
         *
         * @hide Maybe available only in Eclair and not really ready for public use.
         * TODO: remove, or implement this feature completely. As of now (Eclair),
         * we only use this flag in queryEntities(), not query().
         */
        public static final String FOR_EXPORT_ONLY = "for_export_only";

        /**
         * The ID of the data column. The value will be null if this raw contact has no data rows.
         * <P>Type: INTEGER</P>
         */
        public static final String DATA_ID = "data_id";

        /**
         * Query raw contacts entity by a contact ID, which can potentially be a managed profile
         * contact ID.
         * <p>
         * @param contentResolver The content resolver to query
         * @param contactId Contact ID, which can potentially be a managed profile contact ID.
         * @return A map from a mimetype to a list of the entity content values.
         *
         * {@hide}
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
        public static @NonNull Map<String, List<ContentValues>> queryRawContactEntity(
                @NonNull ContentResolver contentResolver, long contactId) {
            Uri uri = RawContactsEntity.CONTENT_URI;
            long realContactId = contactId;

            if (Contacts.isEnterpriseContactId(contactId)) {
                uri = RawContactsEntity.CORP_CONTENT_URI;
                realContactId = contactId - Contacts.ENTERPRISE_CONTACT_ID_BASE;
            }
            final Map<String, List<ContentValues>> contentValuesListMap =
                    new HashMap<String, List<ContentValues>>();
            // The resolver may return the entity iterator with no data. It is possible.
            // e.g. If all the data in the contact of the given contact id are not exportable ones,
            //      they are hidden from the view of this method, though contact id itself exists.
            EntityIterator entityIterator = null;
            try {
                final String selection = Data.CONTACT_ID + "=?";
                final String[] selectionArgs = new String[] {String.valueOf(realContactId)};

                entityIterator = RawContacts.newEntityIterator(contentResolver.query(
                            uri, null, selection, selectionArgs, null));

                if (entityIterator == null) {
                    Log.e(TAG, "EntityIterator is null");
                    return contentValuesListMap;
                }

                if (!entityIterator.hasNext()) {
                    Log.w(TAG, "Data does not exist. contactId: " + realContactId);
                    return contentValuesListMap;
                }

                while (entityIterator.hasNext()) {
                    Entity entity = entityIterator.next();
                    for (NamedContentValues namedContentValues : entity.getSubValues()) {
                        ContentValues contentValues = namedContentValues.values;
                        String key = contentValues.getAsString(Data.MIMETYPE);
                        if (key != null) {
                            List<ContentValues> contentValuesList = contentValuesListMap.get(key);
                            if (contentValuesList == null) {
                                contentValuesList = new ArrayList<ContentValues>();
                                contentValuesListMap.put(key, contentValuesList);
                            }
                            contentValuesList.add(contentValues);
                        }
                    }
                }
            } finally {
                if (entityIterator != null) {
                    entityIterator.close();
                }
            }
            return contentValuesListMap;
        }
    }

    /**
     * @see PhoneLookup
     */
    protected interface PhoneLookupColumns {
        /**
         *  The ID of the data row.
         *  <P>Type: INTEGER</P>
         */
        public static final String DATA_ID = "data_id";
        /**
         * A reference to the {@link ContactsContract.Contacts#_ID} that this
         * data belongs to.
         * <P>Type: INTEGER</P>
         */
        public static final String CONTACT_ID = "contact_id";
        /**
         * The phone number as the user entered it.
         * <P>Type: TEXT</P>
         */
        public static final String NUMBER = "number";

        /**
         * The type of phone number, for example Home or Work.
         * <P>Type: INTEGER</P>
         */
        public static final String TYPE = "type";

        /**
         * The user defined label for the phone number.
         * <P>Type: TEXT</P>
         */
        public static final String LABEL = "label";

        /**
         * The phone number's E164 representation.
         * <P>Type: TEXT</P>
         */
        public static final String NORMALIZED_NUMBER = "normalized_number";
    }

    /**
     * A table that represents the result of looking up a phone number, for
     * example for caller ID. To perform a lookup you must append the number you
     * want to find to {@link #CONTENT_FILTER_URI}.  This query is highly
     * optimized.
     * <pre>
     * Uri uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
     * resolver.query(uri, new String[]{PhoneLookup.DISPLAY_NAME,...
     * </pre>
     *
     * <h3>Columns</h3>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>PhoneLookup</th>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #NUMBER}</td>
     * <td>read-only</td>
     * <td>Phone number.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #TYPE}</td>
     * <td>read-only</td>
     * <td>Phone number type. See {@link CommonDataKinds.Phone}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #LABEL}</td>
     * <td>read-only</td>
     * <td>Custom label for the phone number. See {@link CommonDataKinds.Phone}.</td>
     * </tr>
     * </table>
     * <p>
     * Columns from the Contacts table are also available through a join.
     * </p>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Join with {@link Contacts}</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #_ID}</td>
     * <td>read-only</td>
     * <td>Contact ID.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_ID}</td>
     * <td>read-only</td>
     * <td>Contact ID.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #DATA_ID}</td>
     * <td>read-only</td>
     * <td>Data ID.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #LOOKUP_KEY}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #DISPLAY_NAME}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PHOTO_ID}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IN_VISIBLE_GROUP}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #HAS_PHONE_NUMBER}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #STARRED}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_RINGTONE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SEND_TO_VOICEMAIL}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * </table>
     */
    public static final class PhoneLookup implements BaseColumns, PhoneLookupColumns,
            ContactsColumns, ContactOptionsColumns, ContactNameColumns {
        /**
         * This utility class cannot be instantiated
         */
        private PhoneLookup() {}

        /**
         * The content:// style URI for this table.
         *
         * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store, this
         * field doesn't sort results based on contacts frequency. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.
         *
         * Append the phone number you want to lookup
         * to this URI and query it to perform a lookup. For example:
         * <pre>
         * Uri lookupUri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
         *         Uri.encode(phoneNumber));
         * </pre>
         */
        public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "phone_lookup");

        /**
         * URI used for looking up contacts by phone number on the contact databases of both the
         * calling user and the managed profile that is linked to it.
         * <p>
         * It supports the same semantics as {@link #CONTENT_FILTER_URI} and returns the same
         * columns.<br>
         * If the device has no managed profile that is linked to the calling user, it behaves in
         * the exact same way as {@link #CONTENT_FILTER_URI}.<br>
         * If there is a managed profile linked to the calling user, it first queries the calling
         * user's contact database, and only if no matching contacts are found there it then queries
         * the managed profile database.
         * <p class="caution">
         * <b>Caution: </b>If you publish your app to the Google Play Store, this field doesn't sort
         * results based on contacts frequency. For more information, see the
         * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
         * page.
         * <p>
         * If a result is from the managed profile, the following changes are made to the data:
         * <ul>
         *     <li>{@link #PHOTO_THUMBNAIL_URI} and {@link #PHOTO_URI} will be rewritten to special
         *     URIs. Use {@link ContentResolver#openAssetFileDescriptor} or its siblings to
         *     load pictures from them.
         *     <li>{@link #PHOTO_ID} and {@link #PHOTO_FILE_ID} will be set to null. Don't use them.
         *     <li>{@link #CONTACT_ID} and {@link #LOOKUP_KEY} will be replaced with artificial
         *     values. These values will be consistent across multiple queries, but do not use them
         *     in places that do not explicitly say they accept them. If they are used in the
         *     {@code selection} param in {@link android.content.ContentProvider#query}, the result
         *     is undefined.
         *     <li>In order to tell whether a contact is from the managed profile, use
         *     {@link ContactsContract.Contacts#isEnterpriseContactId(long)}.
         * <p>
         * A contact lookup URL built by
         * {@link ContactsContract.Contacts#getLookupUri(long, String)}
         * with a {@link #CONTACT_ID} and a {@link #LOOKUP_KEY} returned by this API can be passed
         * to {@link ContactsContract.QuickContact#showQuickContact} even if a contact is from the
         * managed profile.
         * <pre>
         * Uri lookupUri = Uri.withAppendedPath(PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI,
         *         Uri.encode(phoneNumber));
         */
        public static final Uri ENTERPRISE_CONTENT_FILTER_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "phone_lookup_enterprise");

        /**
         * The MIME type of {@link #CONTENT_FILTER_URI} providing a directory of phone lookup rows.
         *
         * @hide
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/phone_lookup";

        /**
         * If this boolean parameter is set to true, then the appended query is treated as a
         * SIP address and the lookup will be performed against SIP addresses in the user's
         * contacts.
         */
        public static final String QUERY_PARAMETER_SIP_ADDRESS = "sip";
    }

    /**
     * Additional data mixed in with {@link StatusColumns} to link
     * back to specific {@link ContactsContract.Data#_ID} entries.
     *
     * @see StatusUpdates
     */
    protected interface PresenceColumns {

        /**
         * Reference to the {@link Data#_ID} entry that owns this presence.
         * <P>Type: INTEGER</P>
         */
        public static final String DATA_ID = "presence_data_id";

        /**
         * See {@link CommonDataKinds.Im} for a list of defined protocol constants.
         * <p>Type: NUMBER</p>
         */
        public static final String PROTOCOL = "protocol";

        /**
         * Name of the custom protocol.  Should be supplied along with the {@link #PROTOCOL} value
         * {@link ContactsContract.CommonDataKinds.Im#PROTOCOL_CUSTOM}.  Should be null or
         * omitted if {@link #PROTOCOL} value is not
         * {@link ContactsContract.CommonDataKinds.Im#PROTOCOL_CUSTOM}.
         *
         * <p>Type: NUMBER</p>
         */
        public static final String CUSTOM_PROTOCOL = "custom_protocol";

        /**
         * The IM handle the presence item is for. The handle is scoped to
         * {@link #PROTOCOL}.
         * <P>Type: TEXT</P>
         */
        public static final String IM_HANDLE = "im_handle";

        /**
         * The IM account for the local user that the presence data came from.
         * <P>Type: TEXT</P>
         */
        public static final String IM_ACCOUNT = "im_account";
    }

    /**
     * <p>
     * A status update is linked to a {@link ContactsContract.Data} row and captures
     * the user's latest status update via the corresponding source, e.g.
     * "Having lunch" via "Google Talk".
     * </p>
     * <p>
     * There are two ways a status update can be inserted: by explicitly linking
     * it to a Data row using {@link #DATA_ID} or indirectly linking it to a data row
     * using a combination of {@link #PROTOCOL} (or {@link #CUSTOM_PROTOCOL}) and
     * {@link #IM_HANDLE}.  There is no difference between insert and update, you can use
     * either.
     * </p>
     * <p>
     * Inserting or updating a status update for the user's profile requires either using
     * the {@link #DATA_ID} to identify the data row to attach the update to, or
     * {@link StatusUpdates#PROFILE_CONTENT_URI} to ensure that the change is scoped to the
     * profile.
     * </p>
     * <p>
     * You cannot use {@link ContentResolver#update} to change a status, but
     * {@link ContentResolver#insert} will replace the latests status if it already
     * exists.
     * </p>
     * <p>
     * Use {@link ContentResolver#bulkInsert(Uri, ContentValues[])} to insert/update statuses
     * for multiple contacts at once.
     * </p>
     *
     * <h3>Columns</h3>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>StatusUpdates</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #DATA_ID}</td>
     * <td>read/write</td>
     * <td>Reference to the {@link Data#_ID} entry that owns this presence. If this
     * field is <i>not</i> specified, the provider will attempt to find a data row
     * that matches the {@link #PROTOCOL} (or {@link #CUSTOM_PROTOCOL}) and
     * {@link #IM_HANDLE} columns.
     * </td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PROTOCOL}</td>
     * <td>read/write</td>
     * <td>See {@link CommonDataKinds.Im} for a list of defined protocol constants.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_PROTOCOL}</td>
     * <td>read/write</td>
     * <td>Name of the custom protocol.  Should be supplied along with the {@link #PROTOCOL} value
     * {@link ContactsContract.CommonDataKinds.Im#PROTOCOL_CUSTOM}.  Should be null or
     * omitted if {@link #PROTOCOL} value is not
     * {@link ContactsContract.CommonDataKinds.Im#PROTOCOL_CUSTOM}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #IM_HANDLE}</td>
     * <td>read/write</td>
     * <td> The IM handle the presence item is for. The handle is scoped to
     * {@link #PROTOCOL}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #IM_ACCOUNT}</td>
     * <td>read/write</td>
     * <td>The IM account for the local user that the presence data came from.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #PRESENCE}</td>
     * <td>read/write</td>
     * <td>Contact IM presence status. The allowed values are:
     * <p>
     * <ul>
     * <li>{@link #OFFLINE}</li>
     * <li>{@link #INVISIBLE}</li>
     * <li>{@link #AWAY}</li>
     * <li>{@link #IDLE}</li>
     * <li>{@link #DO_NOT_DISTURB}</li>
     * <li>{@link #AVAILABLE}</li>
     * </ul>
     * </p>
     * <p>
     * Since presence status is inherently volatile, the content provider
     * may choose not to store this field in long-term storage.
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #CHAT_CAPABILITY}</td>
     * <td>read/write</td>
     * <td>Contact IM chat compatibility value. The allowed values combinations of the following
     * flags. If None of these flags is set, the device can only do text messaging.
     * <p>
     * <ul>
     * <li>{@link #CAPABILITY_HAS_VIDEO}</li>
     * <li>{@link #CAPABILITY_HAS_VOICE}</li>
     * <li>{@link #CAPABILITY_HAS_CAMERA}</li>
     * </ul>
     * </p>
     * <p>
     * Since chat compatibility is inherently volatile as the contact's availability moves from
     * one device to another, the content provider may choose not to store this field in long-term
     * storage.
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #STATUS}</td>
     * <td>read/write</td>
     * <td>Contact's latest status update, e.g. "having toast for breakfast"</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_TIMESTAMP}</td>
     * <td>read/write</td>
     * <td>The absolute time in milliseconds when the status was
     * entered by the user. If this value is not provided, the provider will follow
     * this logic: if there was no prior status update, the value will be left as null.
     * If there was a prior status update, the provider will default this field
     * to the current time.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #STATUS_RES_PACKAGE}</td>
     * <td>read/write</td>
     * <td> The package containing resources for this status: label and icon.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_LABEL}</td>
     * <td>read/write</td>
     * <td>The resource ID of the label describing the source of contact status,
     * e.g. "Google Talk". This resource is scoped by the
     * {@link #STATUS_RES_PACKAGE}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_ICON}</td>
     * <td>read/write</td>
     * <td>The resource ID of the icon for the source of contact status. This
     * resource is scoped by the {@link #STATUS_RES_PACKAGE}.</td>
     * </tr>
     * </table>
     */
    public static class StatusUpdates implements StatusColumns, PresenceColumns {

        /**
         * This utility class cannot be instantiated
         */
        private StatusUpdates() {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "status_updates");

        /**
         * The content:// style URI for this table, specific to the user's profile.
         */
        public static final Uri PROFILE_CONTENT_URI =
                Uri.withAppendedPath(Profile.CONTENT_URI, "status_updates");

        /**
         * Gets the resource ID for the proper presence icon.
         *
         * @param status the status to get the icon for
         * @return the resource ID for the proper presence icon
         */
        public static final int getPresenceIconResourceId(int status) {
            switch (status) {
                case AVAILABLE:
                    return android.R.drawable.presence_online;
                case IDLE:
                case AWAY:
                    return android.R.drawable.presence_away;
                case DO_NOT_DISTURB:
                    return android.R.drawable.presence_busy;
                case INVISIBLE:
                    return android.R.drawable.presence_invisible;
                case OFFLINE:
                default:
                    return android.R.drawable.presence_offline;
            }
        }

        /**
         * Returns the precedence of the status code the higher number being the higher precedence.
         *
         * @param status The status code.
         * @return An integer representing the precedence, 0 being the lowest.
         */
        public static final int getPresencePrecedence(int status) {
            // Keep this function here incase we want to enforce a different precedence than the
            // natural order of the status constants.
            return status;
        }

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * status update details.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/status-update";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * status update detail.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/status-update";
    }

    /**
     * @deprecated This old name was never meant to be made public. Do not use.
     */
    @Deprecated
    public static final class Presence extends StatusUpdates {

    }

    /**
     * Additional column returned by
     * {@link ContactsContract.Contacts#CONTENT_FILTER_URI Contacts.CONTENT_FILTER_URI} explaining
     * why the filter matched the contact. This column will contain extracts from the contact's
     * constituent {@link Data Data} items, formatted in a way that indicates the section of the
     * snippet that matched the filter.
     *
     * <p>
     * The following example searches for all contacts that match the query "presi" and requests
     * the snippet column as well.
     * <pre>
     * Builder builder = Contacts.CONTENT_FILTER_URI.buildUpon();
     * builder.appendPath("presi");
     * // Defer snippeting to the client side if possible, for performance reasons.
     * builder.appendQueryParameter(SearchSnippets.DEFERRED_SNIPPETING_KEY,"1");
     *
     * Cursor cursor = getContentResolver().query(builder.build());
     *
     * Bundle extras = cursor.getExtras();
     * if (extras.getBoolean(ContactsContract.DEFERRED_SNIPPETING)) {
     *     // Do our own snippet formatting.
     *     // For a contact with the email address (president@organization.com), the snippet
     *     // column will contain the string "president@organization.com".
     * } else {
     *     // The snippet has already been pre-formatted, we can display it as is.
     *     // For a contact with the email address (president@organization.com), the snippet
     *     // column will contain the string "[presi]dent@organization.com".
     * }
     * </pre>
     * </p>
     */
    public static class SearchSnippets {

        /**
         * The search snippet constructed by SQLite snippeting functionality.
         * <p>
         * The snippet may contain (parts of) several data elements belonging to the contact,
         * with the matching parts optionally surrounded by special characters that indicate the
         * start and end of matching text.
         *
         * For example, if a contact has an address "123 Main Street", using a filter "mai" would
         * return the formatted snippet "123 [Mai]n street".
         *
         * @see <a href="http://www.sqlite.org/fts3.html#snippet">
         *         http://www.sqlite.org/fts3.html#snippet</a>
         */
        public static final String SNIPPET = "snippet";

        /**
         * Comma-separated parameters for the generation of the snippet:
         * <ul>
         * <li>The "start match" text. Default is '['</li>
         * <li>The "end match" text. Default is ']'</li>
         * <li>The "ellipsis" text. Default is "..."</li>
         * <li>Maximum number of tokens to include in the snippet. Can be either
         * a positive or a negative number: A positive number indicates how many
         * tokens can be returned in total. A negative number indicates how many
         * tokens can be returned per occurrence of the search terms.</li>
         * </ul>
         *
         * @hide
         */
        public static final String SNIPPET_ARGS_PARAM_KEY = "snippet_args";

        /**
         * The key to ask the provider to defer the formatting of the snippet to the client if
         * possible, for performance reasons.
         * A value of 1 indicates true, 0 indicates false. False is the default.
         * When a cursor is returned to the client, it should check for an extra with the name
         * {@link ContactsContract#DEFERRED_SNIPPETING} in the cursor. If it exists, the client
         * should do its own formatting of the snippet. If it doesn't exist, the snippet column
         * in the cursor should already contain a formatted snippet.
         */
        public static final String DEFERRED_SNIPPETING_KEY = "deferred_snippeting";
    }

    /**
     * Container for definitions of common data types stored in the {@link ContactsContract.Data}
     * table.
     */
    public static final class CommonDataKinds {
        /**
         * This utility class cannot be instantiated
         */
        private CommonDataKinds() {}

        /**
         * The {@link Data#RES_PACKAGE} value for common data that should be
         * shown using a default style.
         *
         * @hide RES_PACKAGE is hidden
         */
        public static final String PACKAGE_COMMON = "common";

        /**
         * The base types that all "Typed" data kinds support.
         */
        public interface BaseTypes {
            /**
             * A custom type. The custom label should be supplied by user.
             */
            public static int TYPE_CUSTOM = 0;
        }

        /**
         * Columns common across the specific types.
         */
        protected interface CommonColumns extends BaseTypes {
            /**
             * The data for the contact method.
             * <P>Type: TEXT</P>
             */
            public static final String DATA = DataColumns.DATA1;

            /**
             * The type of data, for example Home or Work.
             * <P>Type: INTEGER</P>
             */
            public static final String TYPE = DataColumns.DATA2;

            /**
             * The user defined label for the the contact method.
             * <P>Type: TEXT</P>
             */
            public static final String LABEL = DataColumns.DATA3;
        }

        /**
         * A data kind representing the contact's proper name. You can use all
         * columns defined for {@link ContactsContract.Data} as well as the following aliases.
         *
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th><th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #DISPLAY_NAME}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #GIVEN_NAME}</td>
         * <td>{@link #DATA2}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #FAMILY_NAME}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PREFIX}</td>
         * <td>{@link #DATA4}</td>
         * <td>Common prefixes in English names are "Mr", "Ms", "Dr" etc.</td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #MIDDLE_NAME}</td>
         * <td>{@link #DATA5}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #SUFFIX}</td>
         * <td>{@link #DATA6}</td>
         * <td>Common suffixes in English names are "Sr", "Jr", "III" etc.</td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PHONETIC_GIVEN_NAME}</td>
         * <td>{@link #DATA7}</td>
         * <td>Used for phonetic spelling of the name, e.g. Pinyin, Katakana, Hiragana</td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PHONETIC_MIDDLE_NAME}</td>
         * <td>{@link #DATA8}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PHONETIC_FAMILY_NAME}</td>
         * <td>{@link #DATA9}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class StructuredName implements DataColumnsWithJoins, ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private StructuredName() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/name";

            /**
             * The name that should be used to display the contact.
             * <i>Unstructured component of the name should be consistent with
             * its structured representation.</i>
             * <p>
             * Type: TEXT
             */
            public static final String DISPLAY_NAME = DATA1;

            /**
             * The given name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String GIVEN_NAME = DATA2;

            /**
             * The family name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String FAMILY_NAME = DATA3;

            /**
             * The contact's honorific prefix, e.g. "Sir"
             * <P>Type: TEXT</P>
             */
            public static final String PREFIX = DATA4;

            /**
             * The contact's middle name
             * <P>Type: TEXT</P>
             */
            public static final String MIDDLE_NAME = DATA5;

            /**
             * The contact's honorific suffix, e.g. "Jr"
             */
            public static final String SUFFIX = DATA6;

            /**
             * The phonetic version of the given name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_GIVEN_NAME = DATA7;

            /**
             * The phonetic version of the additional name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_MIDDLE_NAME = DATA8;

            /**
             * The phonetic version of the family name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_FAMILY_NAME = DATA9;

            /**
             * The style used for combining given/middle/family name into a full name.
             * See {@link ContactsContract.FullNameStyle}.
             */
            public static final String FULL_NAME_STYLE = DATA10;

            /**
             * The alphabet used for capturing the phonetic name.
             * See ContactsContract.PhoneticNameStyle.
             */
            public static final String PHONETIC_NAME_STYLE = DATA11;
        }

        /**
         * <p>A data kind representing the contact's nickname. For example, for
         * Bob Parr ("Mr. Incredible"):
         * <pre>
         * ArrayList&lt;ContentProviderOperation&gt; ops =
         *          new ArrayList&lt;ContentProviderOperation&gt;();
         *
         * ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
         *          .withValue(Data.RAW_CONTACT_ID, rawContactId)
         *          .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
         *          .withValue(StructuredName.DISPLAY_NAME, &quot;Bob Parr&quot;)
         *          .build());
         *
         * ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
         *          .withValue(Data.RAW_CONTACT_ID, rawContactId)
         *          .withValue(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
         *          .withValue(Nickname.NAME, "Mr. Incredible")
         *          .withValue(Nickname.TYPE, Nickname.TYPE_CUSTOM)
         *          .withValue(Nickname.LABEL, "Superhero")
         *          .build());
         *
         * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
         * </pre>
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as well as the
         * following aliases.
         * </p>
         *
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th><th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NAME}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>
         * Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_DEFAULT}</li>
         * <li>{@link #TYPE_OTHER_NAME}</li>
         * <li>{@link #TYPE_MAIDEN_NAME}</li>
         * <li>{@link #TYPE_SHORT_NAME}</li>
         * <li>{@link #TYPE_INITIALS}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Nickname implements DataColumnsWithJoins, CommonColumns,
                ContactCounts{
            /**
             * This utility class cannot be instantiated
             */
            private Nickname() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/nickname";

            public static final int TYPE_DEFAULT = 1;
            public static final int TYPE_OTHER_NAME = 2;
            public static final int TYPE_MAIDEN_NAME = 3;
            /** @deprecated Use TYPE_MAIDEN_NAME instead. */
            @Deprecated
            public static final int TYPE_MAINDEN_NAME = 3;
            public static final int TYPE_SHORT_NAME = 4;
            public static final int TYPE_INITIALS = 5;

            /**
             * The name itself
             */
            public static final String NAME = DATA;
        }

        /**
         * <p>
         * A data kind representing a telephone number.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NUMBER}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_MOBILE}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_FAX_WORK}</li>
         * <li>{@link #TYPE_FAX_HOME}</li>
         * <li>{@link #TYPE_PAGER}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * <li>{@link #TYPE_CALLBACK}</li>
         * <li>{@link #TYPE_CAR}</li>
         * <li>{@link #TYPE_COMPANY_MAIN}</li>
         * <li>{@link #TYPE_ISDN}</li>
         * <li>{@link #TYPE_MAIN}</li>
         * <li>{@link #TYPE_OTHER_FAX}</li>
         * <li>{@link #TYPE_RADIO}</li>
         * <li>{@link #TYPE_TELEX}</li>
         * <li>{@link #TYPE_TTY_TDD}</li>
         * <li>{@link #TYPE_WORK_MOBILE}</li>
         * <li>{@link #TYPE_WORK_PAGER}</li>
         * <li>{@link #TYPE_ASSISTANT}</li>
         * <li>{@link #TYPE_MMS}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Phone implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private Phone() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/phone_v2";

            /**
             * The MIME type of {@link #CONTENT_URI} providing a directory of
             * phones.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/phone_v2";

            /**
             * The content:// style URI for all data records of the
             * {@link #CONTENT_ITEM_TYPE} MIME type, combined with the
             * associated raw contact and aggregate contact data.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Data.CONTENT_URI,
                    "phones");

            /**
             * URI used for getting all data records of the {@link #CONTENT_ITEM_TYPE} MIME type,
             * combined with the associated raw contact and aggregate contact data, from both the
             * calling user and the managed profile that is linked to it.
             * <p>
             * It supports the same semantics as {@link #CONTENT_URI} and returns the same
             * columns.<br>
             * If the device has no managed profile that is linked to the calling user, it behaves
             * in the exact same way as {@link #CONTENT_URI}.<br>
             * If there is a managed profile linked to the calling user, it will return merged
             * results from both.
             * <p>
             * If a result is from the managed profile, the following changes are made to the data:
             * <ul>
             *     <li>{@link #PHOTO_THUMBNAIL_URI} and {@link #PHOTO_URI} will be rewritten to
             *     special URIs. Use {@link ContentResolver#openAssetFileDescriptor} or its siblings
             *     to load pictures from them.
             *     <li>{@link #PHOTO_ID} and {@link #PHOTO_FILE_ID} will be set to null. Don't use
             *     them.
             *     <li>{@link #CONTACT_ID} and {@link #LOOKUP_KEY} will be replaced with artificial
             *     values. These values will be consistent across multiple queries, but do not use
             *     them in places that don't explicitly say they accept them. If they are used in
             *     the {@code selection} param in {@link android.content.ContentProvider#query}, the
             *     result is undefined.
             *     <li>In order to tell whether a contact is from the managed profile, use
             *     {@link ContactsContract.Contacts#isEnterpriseContactId(long)}.
             */
            public static final @NonNull Uri ENTERPRISE_CONTENT_URI =
                    Uri.withAppendedPath(Data.ENTERPRISE_CONTENT_URI, "phones");

            /**
             * <p>The content:// style URL for phone lookup using a filter. The filter returns
             * records of MIME type {@link #CONTENT_ITEM_TYPE}. The filter is applied
             * to display names as well as phone numbers. The filter argument should be passed
             * as an additional path segment after this URI.
             *
             * <p class="caution"><b>Caution: </b>This field doesn't sort results based on contacts
             * frequency. For more information, see the
             * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
             * page.
             */
            public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(CONTENT_URI,
                    "filter");

            /**
             * <p>It supports the similar semantics as {@link #CONTENT_FILTER_URI} and returns the
             * same columns. This URI requires {@link ContactsContract#DIRECTORY_PARAM_KEY} in
             * parameters, otherwise it will throw IllegalArgumentException.
             *
             * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
             * this field doesn't sort results based on contacts frequency. For more information,
             * see the
             * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
             * page.
             */
            public static final Uri ENTERPRISE_CONTENT_FILTER_URI = Uri.withAppendedPath(
                    CONTENT_URI, "filter_enterprise");

            /**
             * A boolean query parameter that can be used with {@link #CONTENT_FILTER_URI}.
             * If "1" or "true", display names are searched.  If "0" or "false", display names
             * are not searched.  Default is "1".
             */
            public static final String SEARCH_DISPLAY_NAME_KEY = "search_display_name";

            /**
             * A boolean query parameter that can be used with {@link #CONTENT_FILTER_URI}.
             * If "1" or "true", phone numbers are searched.  If "0" or "false", phone numbers
             * are not searched.  Default is "1".
             */
            public static final String SEARCH_PHONE_NUMBER_KEY = "search_phone_number";

            public static final int TYPE_HOME = 1;
            public static final int TYPE_MOBILE = 2;
            public static final int TYPE_WORK = 3;
            public static final int TYPE_FAX_WORK = 4;
            public static final int TYPE_FAX_HOME = 5;
            public static final int TYPE_PAGER = 6;
            public static final int TYPE_OTHER = 7;
            public static final int TYPE_CALLBACK = 8;
            public static final int TYPE_CAR = 9;
            public static final int TYPE_COMPANY_MAIN = 10;
            public static final int TYPE_ISDN = 11;
            public static final int TYPE_MAIN = 12;
            public static final int TYPE_OTHER_FAX = 13;
            public static final int TYPE_RADIO = 14;
            public static final int TYPE_TELEX = 15;
            public static final int TYPE_TTY_TDD = 16;
            public static final int TYPE_WORK_MOBILE = 17;
            public static final int TYPE_WORK_PAGER = 18;
            public static final int TYPE_ASSISTANT = 19;
            public static final int TYPE_MMS = 20;

            /**
             * The phone number as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String NUMBER = DATA;

            /**
             * The phone number's E164 representation. This value can be omitted in which
             * case the provider will try to automatically infer it.  (It'll be left null if the
             * provider fails to infer.)
             * If present, {@link #NUMBER} has to be set as well (it will be ignored otherwise).
             * <P>Type: TEXT</P>
             */
            public static final String NORMALIZED_NUMBER = DATA4;

            /**
             * @deprecated use {@link #getTypeLabel(Resources, int, CharSequence)} instead.
             * @hide
             */
            @Deprecated
            public static final CharSequence getDisplayLabel(Context context, int type,
                    CharSequence label, CharSequence[] labelArray) {
                return getTypeLabel(context.getResources(), type, label);
            }

            /**
             * @deprecated use {@link #getTypeLabel(Resources, int, CharSequence)} instead.
             * @hide
             */
            @Deprecated
            @UnsupportedAppUsage
            public static final CharSequence getDisplayLabel(Context context, int type,
                    CharSequence label) {
                return getTypeLabel(context.getResources(), type, label);
            }

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_HOME: return com.android.internal.R.string.phoneTypeHome;
                    case TYPE_MOBILE: return com.android.internal.R.string.phoneTypeMobile;
                    case TYPE_WORK: return com.android.internal.R.string.phoneTypeWork;
                    case TYPE_FAX_WORK: return com.android.internal.R.string.phoneTypeFaxWork;
                    case TYPE_FAX_HOME: return com.android.internal.R.string.phoneTypeFaxHome;
                    case TYPE_PAGER: return com.android.internal.R.string.phoneTypePager;
                    case TYPE_OTHER: return com.android.internal.R.string.phoneTypeOther;
                    case TYPE_CALLBACK: return com.android.internal.R.string.phoneTypeCallback;
                    case TYPE_CAR: return com.android.internal.R.string.phoneTypeCar;
                    case TYPE_COMPANY_MAIN: return com.android.internal.R.string.phoneTypeCompanyMain;
                    case TYPE_ISDN: return com.android.internal.R.string.phoneTypeIsdn;
                    case TYPE_MAIN: return com.android.internal.R.string.phoneTypeMain;
                    case TYPE_OTHER_FAX: return com.android.internal.R.string.phoneTypeOtherFax;
                    case TYPE_RADIO: return com.android.internal.R.string.phoneTypeRadio;
                    case TYPE_TELEX: return com.android.internal.R.string.phoneTypeTelex;
                    case TYPE_TTY_TDD: return com.android.internal.R.string.phoneTypeTtyTdd;
                    case TYPE_WORK_MOBILE: return com.android.internal.R.string.phoneTypeWorkMobile;
                    case TYPE_WORK_PAGER: return com.android.internal.R.string.phoneTypeWorkPager;
                    case TYPE_ASSISTANT: return com.android.internal.R.string.phoneTypeAssistant;
                    case TYPE_MMS: return com.android.internal.R.string.phoneTypeMms;
                    default: return com.android.internal.R.string.phoneTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    @Nullable CharSequence label) {
                if ((type == TYPE_CUSTOM || type == TYPE_ASSISTANT) && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing an email address.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #ADDRESS}</td>
         * <td>{@link #DATA1}</td>
         * <td>Email address itself.</td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * <li>{@link #TYPE_MOBILE}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Email implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /*
             * This utility class cannot be instantiated
             */
            private Email() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/email_v2";

            /**
             * The MIME type of {@link #CONTENT_URI} providing a directory of email addresses.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/email_v2";

            /**
             * The content:// style URI for all data records of the
             * {@link #CONTENT_ITEM_TYPE} MIME type, combined with the
             * associated raw contact and aggregate contact data.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Data.CONTENT_URI,
                    "emails");

            /**
             * <p>
             * The content:// style URL for looking up data rows by email address. The
             * lookup argument, an email address, should be passed as an additional path segment
             * after this URI.
             * </p>
             * <p>Example:
             * <pre>
             * Uri uri = Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(email));
             * Cursor c = getContentResolver().query(uri,
             *          new String[]{Email.CONTACT_ID, Email.DISPLAY_NAME, Email.DATA},
             *          null, null, null);
             * </pre>
             * </p>
             */
            public static final Uri CONTENT_LOOKUP_URI = Uri.withAppendedPath(CONTENT_URI,
                    "lookup");

            /**
             * URI used for looking up contacts by email on the contact databases of both the
             * calling user and the managed profile that is linked to it.
             * <p>
             * It supports the same semantics as {@link #CONTENT_LOOKUP_URI} and returns the same
             * columns.<br>
             * If the device has no managed profile that is linked to the calling user, it behaves
             * in the exact same way as {@link #CONTENT_LOOKUP_URI}.<br>
             * If there is a managed profile linked to the calling user, it first queries the
             * calling user's contact database, and only if no matching contacts are found there it
             * then queries the managed profile database.
             * <p class="caution">
             * If a result is from the managed profile, the following changes are made to the data:
             * <ul>
             *     <li>{@link #PHOTO_THUMBNAIL_URI} and {@link #PHOTO_URI} will be rewritten to
             *     specialURIs. Use {@link ContentResolver#openAssetFileDescriptor} or its siblings
             *     to load pictures from them.
             *     <li>{@link #PHOTO_ID} and {@link #PHOTO_FILE_ID} will be set to null. Don't use
             *     them.
             *     <li>{@link #CONTACT_ID} and {@link #LOOKUP_KEY} will be replaced with artificial
             *     values. These values will be consistent across multiple queries, but do not use
             *     them in places that do not explicitly say they accept them. If they are used in
             *     the {@code selection} param in {@link android.content.ContentProvider#query}, the
             *     result is undefined.
             *     <li>In order to tell whether a contact is from the managed profile, use
             *     {@link ContactsContract.Contacts#isEnterpriseContactId(long)}.
             * <p>
             * A contact lookup URL built by
             * {@link ContactsContract.Contacts#getLookupUri(long, String)}
             * with a {@link #CONTACT_ID} and a {@link #LOOKUP_KEY} returned by this API can be
             * passed to {@link ContactsContract.QuickContact#showQuickContact} even if a contact is
             * from the managed profile.
             * <pre>
             * Uri lookupUri = Uri.withAppendedPath(Email.ENTERPRISE_CONTENT_LOOKUP_URI,
             *         Uri.encode(email));
             */
            public static final Uri ENTERPRISE_CONTENT_LOOKUP_URI =
                    Uri.withAppendedPath(CONTENT_URI, "lookup_enterprise");

            /**
             * <p>The content:// style URL for email lookup using a filter. The filter returns
             * records of MIME type {@link #CONTENT_ITEM_TYPE}. The filter is applied
             * to display names as well as email addresses. The filter argument should be passed
             * as an additional path segment after this URI.
             * </p>
             *
             * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
             * this field doesn't sort results based on contacts frequency. For more information,
             * see the
             * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
             * page.</p>
             *
             * <p>The query in the following example will return "Robert Parr (bob@incredibles.com)"
             * as well as "Bob Parr (incredible@android.com)".
             * <pre>
             * Uri uri = Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode("bob"));
             * Cursor c = getContentResolver().query(uri,
             *          new String[]{Email.DISPLAY_NAME, Email.DATA},
             *          null, null, null);
             * </pre>
             * </p>
             */
            public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(CONTENT_URI,
                    "filter");

            /**
             * <p>It supports the similar semantics as {@link #CONTENT_FILTER_URI} and returns the
             * same columns. This URI requires {@link ContactsContract#DIRECTORY_PARAM_KEY} in
             * parameters, otherwise it will throw IllegalArgumentException. The passed directory
             * can belong either to the calling user or to a managed profile that is linked to it.
             * <p class="caution">
             * <b>Caution: </b>If you publish your app to the Google Play Store,
             * this field doesn't sort results based on contacts frequency. For more information,
             * see the
             * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
             * page.
             */
            public static final Uri ENTERPRISE_CONTENT_FILTER_URI = Uri.withAppendedPath(
                    CONTENT_URI, "filter_enterprise");

            /**
             * The email address.
             * <P>Type: TEXT</P>
             */
            public static final String ADDRESS = DATA1;

            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;
            public static final int TYPE_MOBILE = 4;

            /**
             * The display name for the email address
             * <P>Type: TEXT</P>
             */
            public static final String DISPLAY_NAME = DATA4;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_HOME: return com.android.internal.R.string.emailTypeHome;
                    case TYPE_WORK: return com.android.internal.R.string.emailTypeWork;
                    case TYPE_OTHER: return com.android.internal.R.string.emailTypeOther;
                    case TYPE_MOBILE: return com.android.internal.R.string.emailTypeMobile;
                    default: return com.android.internal.R.string.emailTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    @Nullable CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing a postal addresses.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #FORMATTED_ADDRESS}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #STREET}</td>
         * <td>{@link #DATA4}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #POBOX}</td>
         * <td>{@link #DATA5}</td>
         * <td>Post Office Box number</td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NEIGHBORHOOD}</td>
         * <td>{@link #DATA6}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #CITY}</td>
         * <td>{@link #DATA7}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #REGION}</td>
         * <td>{@link #DATA8}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #POSTCODE}</td>
         * <td>{@link #DATA9}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #COUNTRY}</td>
         * <td>{@link #DATA10}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class StructuredPostal implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private StructuredPostal() {
            }

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE =
                    "vnd.android.cursor.item/postal-address_v2";

            /**
             * The MIME type of {@link #CONTENT_URI} providing a directory of
             * postal addresses.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/postal-address_v2";

            /**
             * The content:// style URI for all data records of the
             * {@link StructuredPostal#CONTENT_ITEM_TYPE} MIME type.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Data.CONTENT_URI,
                    "postals");

            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;

            /**
             * The full, unstructured postal address. <i>This field must be
             * consistent with any structured data.</i>
             * <p>
             * Type: TEXT
             */
            public static final String FORMATTED_ADDRESS = DATA;

            /**
             * Can be street, avenue, road, etc. This element also includes the
             * house number and room/apartment/flat/floor number.
             * <p>
             * Type: TEXT
             */
            public static final String STREET = DATA4;

            /**
             * Covers actual P.O. boxes, drawers, locked bags, etc. This is
             * usually but not always mutually exclusive with street.
             * <p>
             * Type: TEXT
             */
            public static final String POBOX = DATA5;

            /**
             * This is used to disambiguate a street address when a city
             * contains more than one street with the same name, or to specify a
             * small place whose mail is routed through a larger postal town. In
             * China it could be a county or a minor city.
             * <p>
             * Type: TEXT
             */
            public static final String NEIGHBORHOOD = DATA6;

            /**
             * Can be city, village, town, borough, etc. This is the postal town
             * and not necessarily the place of residence or place of business.
             * <p>
             * Type: TEXT
             */
            public static final String CITY = DATA7;

            /**
             * A state, province, county (in Ireland), Land (in Germany),
             * departement (in France), etc.
             * <p>
             * Type: TEXT
             */
            public static final String REGION = DATA8;

            /**
             * Postal code. Usually country-wide, but sometimes specific to the
             * city (e.g. "2" in "Dublin 2, Ireland" addresses).
             * <p>
             * Type: TEXT
             */
            public static final String POSTCODE = DATA9;

            /**
             * The name or code of the country.
             * <p>
             * Type: TEXT
             */
            public static final String COUNTRY = DATA10;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_HOME: return com.android.internal.R.string.postalTypeHome;
                    case TYPE_WORK: return com.android.internal.R.string.postalTypeWork;
                    case TYPE_OTHER: return com.android.internal.R.string.postalTypeOther;
                    default: return com.android.internal.R.string.postalTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    @Nullable CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing an IM address
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #DATA}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PROTOCOL}</td>
         * <td>{@link #DATA5}</td>
         * <td>
         * <p>
         * Allowed value: {@link #PROTOCOL_CUSTOM}. Also provide the actual protocol name
         * as {@link #CUSTOM_PROTOCOL}.
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #CUSTOM_PROTOCOL}</td>
         * <td>{@link #DATA6}</td>
         * <td></td>
         * </tr>
         * </table>
         *
         * @deprecated This field may not be well supported by some contacts apps and is discouraged
         * to use.
         */
        @Deprecated
        public static final class Im implements DataColumnsWithJoins, CommonColumns, ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private Im() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/im";

            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;

            /**
             * This column should always be set to {@link #PROTOCOL_CUSTOM} and
             * the {@link #CUSTOM_PROTOCOL} should contain the name of the custom protocol.
             * The other predefined protocols are deprecated and should not be used.
             */
            public static final String PROTOCOL = DATA5;

            public static final String CUSTOM_PROTOCOL = DATA6;

            /*
             * The predefined IM protocol types.
             */
            public static final int PROTOCOL_CUSTOM = -1;
            /**
             * @deprecated Use {@link #PROTOCOL_CUSTOM} with {@link #CUSTOM_PROTOCOL}.
             */
            @Deprecated
            public static final int PROTOCOL_AIM = 0;
            /**
             * @deprecated Use {@link #PROTOCOL_CUSTOM} with {@link #CUSTOM_PROTOCOL}.
             */
            @Deprecated
            public static final int PROTOCOL_MSN = 1;
            /**
             * @deprecated Use {@link #PROTOCOL_CUSTOM} with {@link #CUSTOM_PROTOCOL}.
             */
            @Deprecated
            public static final int PROTOCOL_YAHOO = 2;
            /**
             * @deprecated Use {@link #PROTOCOL_CUSTOM} with {@link #CUSTOM_PROTOCOL}.
             */
            @Deprecated
            public static final int PROTOCOL_SKYPE = 3;
            /**
             * @deprecated Use {@link #PROTOCOL_CUSTOM} with {@link #CUSTOM_PROTOCOL}.
             */
            @Deprecated
            public static final int PROTOCOL_QQ = 4;
            /**
             * @deprecated Use {@link #PROTOCOL_CUSTOM} with {@link #CUSTOM_PROTOCOL}.
             */
            @Deprecated
            public static final int PROTOCOL_GOOGLE_TALK = 5;
            /**
             * @deprecated Use {@link #PROTOCOL_CUSTOM} with {@link #CUSTOM_PROTOCOL}.
             */
            @Deprecated
            public static final int PROTOCOL_ICQ = 6;
            /**
             * @deprecated Use {@link #PROTOCOL_CUSTOM} with {@link #CUSTOM_PROTOCOL}.
             */
            @Deprecated
            public static final int PROTOCOL_JABBER = 7;
            /**
             * @deprecated Use {@link #PROTOCOL_CUSTOM} with {@link #CUSTOM_PROTOCOL}.
             */
            @Deprecated
            public static final int PROTOCOL_NETMEETING = 8;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_HOME: return com.android.internal.R.string.imTypeHome;
                    case TYPE_WORK: return com.android.internal.R.string.imTypeWork;
                    case TYPE_OTHER: return com.android.internal.R.string.imTypeOther;
                    default: return com.android.internal.R.string.imTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    @Nullable CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }

            /**
             * Return the string resource that best describes the given
             * {@link #PROTOCOL}. Will always return a valid resource.
             */
            public static final int getProtocolLabelResource(int type) {
                switch (type) {
                    case PROTOCOL_AIM: return com.android.internal.R.string.imProtocolAim;
                    case PROTOCOL_MSN: return com.android.internal.R.string.imProtocolMsn;
                    case PROTOCOL_YAHOO: return com.android.internal.R.string.imProtocolYahoo;
                    case PROTOCOL_SKYPE: return com.android.internal.R.string.imProtocolSkype;
                    case PROTOCOL_QQ: return com.android.internal.R.string.imProtocolQq;
                    case PROTOCOL_GOOGLE_TALK: return com.android.internal.R.string.imProtocolGoogleTalk;
                    case PROTOCOL_ICQ: return com.android.internal.R.string.imProtocolIcq;
                    case PROTOCOL_JABBER: return com.android.internal.R.string.imProtocolJabber;
                    case PROTOCOL_NETMEETING: return com.android.internal.R.string.imProtocolNetMeeting;
                    default: return com.android.internal.R.string.imProtocolCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given
             * protocol, possibly substituting the given
             * {@link #CUSTOM_PROTOCOL} value for {@link #PROTOCOL_CUSTOM}.
             */
            public static final CharSequence getProtocolLabel(Resources res, int type,
                    CharSequence label) {
                if (type == PROTOCOL_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getProtocolLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing an organization.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #COMPANY}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #TITLE}</td>
         * <td>{@link #DATA4}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #DEPARTMENT}</td>
         * <td>{@link #DATA5}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #JOB_DESCRIPTION}</td>
         * <td>{@link #DATA6}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #SYMBOL}</td>
         * <td>{@link #DATA7}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PHONETIC_NAME}</td>
         * <td>{@link #DATA8}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #OFFICE_LOCATION}</td>
         * <td>{@link #DATA9}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>PHONETIC_NAME_STYLE</td>
         * <td>{@link #DATA10}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Organization implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private Organization() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/organization";

            public static final int TYPE_WORK = 1;
            public static final int TYPE_OTHER = 2;

            /**
             * The company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String COMPANY = DATA;

            /**
             * The position title at this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String TITLE = DATA4;

            /**
             * The department at this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String DEPARTMENT = DATA5;

            /**
             * The job description at this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String JOB_DESCRIPTION = DATA6;

            /**
             * The symbol of this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String SYMBOL = DATA7;

            /**
             * The phonetic name of this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_NAME = DATA8;

            /**
             * The office location of this organization.
             * <P>Type: TEXT</P>
             */
            public static final String OFFICE_LOCATION = DATA9;

            /**
             * The alphabet used for capturing the phonetic name.
             * See {@link ContactsContract.PhoneticNameStyle}.
             */
            public static final String PHONETIC_NAME_STYLE = DATA10;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_WORK: return com.android.internal.R.string.orgTypeWork;
                    case TYPE_OTHER: return com.android.internal.R.string.orgTypeOther;
                    default: return com.android.internal.R.string.orgTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    @Nullable CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing a relation.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NAME}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_ASSISTANT}</li>
         * <li>{@link #TYPE_BROTHER}</li>
         * <li>{@link #TYPE_CHILD}</li>
         * <li>{@link #TYPE_DOMESTIC_PARTNER}</li>
         * <li>{@link #TYPE_FATHER}</li>
         * <li>{@link #TYPE_FRIEND}</li>
         * <li>{@link #TYPE_MANAGER}</li>
         * <li>{@link #TYPE_MOTHER}</li>
         * <li>{@link #TYPE_PARENT}</li>
         * <li>{@link #TYPE_PARTNER}</li>
         * <li>{@link #TYPE_REFERRED_BY}</li>
         * <li>{@link #TYPE_RELATIVE}</li>
         * <li>{@link #TYPE_SISTER}</li>
         * <li>{@link #TYPE_SPOUSE}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Relation implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private Relation() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/relation";

            public static final int TYPE_ASSISTANT = 1;
            public static final int TYPE_BROTHER = 2;
            public static final int TYPE_CHILD = 3;
            public static final int TYPE_DOMESTIC_PARTNER = 4;
            public static final int TYPE_FATHER = 5;
            public static final int TYPE_FRIEND = 6;
            public static final int TYPE_MANAGER = 7;
            public static final int TYPE_MOTHER = 8;
            public static final int TYPE_PARENT = 9;
            public static final int TYPE_PARTNER = 10;
            public static final int TYPE_REFERRED_BY = 11;
            public static final int TYPE_RELATIVE = 12;
            public static final int TYPE_SISTER = 13;
            public static final int TYPE_SPOUSE = 14;

            /**
             * The name of the relative as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String NAME = DATA;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_ASSISTANT: return com.android.internal.R.string.relationTypeAssistant;
                    case TYPE_BROTHER: return com.android.internal.R.string.relationTypeBrother;
                    case TYPE_CHILD: return com.android.internal.R.string.relationTypeChild;
                    case TYPE_DOMESTIC_PARTNER:
                            return com.android.internal.R.string.relationTypeDomesticPartner;
                    case TYPE_FATHER: return com.android.internal.R.string.relationTypeFather;
                    case TYPE_FRIEND: return com.android.internal.R.string.relationTypeFriend;
                    case TYPE_MANAGER: return com.android.internal.R.string.relationTypeManager;
                    case TYPE_MOTHER: return com.android.internal.R.string.relationTypeMother;
                    case TYPE_PARENT: return com.android.internal.R.string.relationTypeParent;
                    case TYPE_PARTNER: return com.android.internal.R.string.relationTypePartner;
                    case TYPE_REFERRED_BY:
                            return com.android.internal.R.string.relationTypeReferredBy;
                    case TYPE_RELATIVE: return com.android.internal.R.string.relationTypeRelative;
                    case TYPE_SISTER: return com.android.internal.R.string.relationTypeSister;
                    case TYPE_SPOUSE: return com.android.internal.R.string.relationTypeSpouse;
                    default: return com.android.internal.R.string.orgTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    @Nullable CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing an event.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #START_DATE}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_ANNIVERSARY}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * <li>{@link #TYPE_BIRTHDAY}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Event implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private Event() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contact_event";

            public static final int TYPE_ANNIVERSARY = 1;
            public static final int TYPE_OTHER = 2;
            public static final int TYPE_BIRTHDAY = 3;

            /**
             * The event start date as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String START_DATE = DATA;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static int getTypeResource(Integer type) {
                if (type == null) {
                    return com.android.internal.R.string.eventTypeOther;
                }
                switch (type) {
                    case TYPE_ANNIVERSARY:
                        return com.android.internal.R.string.eventTypeAnniversary;
                    case TYPE_BIRTHDAY: return com.android.internal.R.string.eventTypeBirthday;
                    case TYPE_OTHER: return com.android.internal.R.string.eventTypeOther;
                    default: return com.android.internal.R.string.eventTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    @Nullable CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing a photo for the contact.
         * </p>
         * <p>
         * Some sync adapters will choose to download photos in a separate
         * pass. A common pattern is to use columns {@link ContactsContract.Data#SYNC1}
         * through {@link ContactsContract.Data#SYNC4} to store temporary
         * data, e.g. the image URL or ID, state of download, server-side version
         * of the image.  It is allowed for the {@link #PHOTO} to be null.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>NUMBER</td>
         * <td>{@link #PHOTO_FILE_ID}</td>
         * <td>{@link #DATA14}</td>
         * <td>ID of the hi-res photo file.</td>
         * </tr>
         * <tr>
         * <td>BLOB</td>
         * <td>{@link #PHOTO}</td>
         * <td>{@link #DATA15}</td>
         * <td>By convention, binary data is stored in DATA15.  The thumbnail of the
         * photo is stored in this column.</td>
         * </tr>
         * </table>
         */
        public static final class Photo implements DataColumnsWithJoins, ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private Photo() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/photo";

            /**
             * Photo file ID for the display photo of the raw contact.
             * See {@link ContactsContract.DisplayPhoto}.
             * <p>
             * Type: NUMBER
             */
            public static final String PHOTO_FILE_ID = DATA14;

            /**
             * Thumbnail photo of the raw contact. This is the raw bytes of an image
             * that could be inflated using {@link android.graphics.BitmapFactory}.
             * <p>
             * Type: BLOB
             */
            public static final String PHOTO = DATA15;
        }

        /**
         * <p>
         * Notes about the contact.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NOTE}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Note implements DataColumnsWithJoins, ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private Note() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/note";

            /**
             * The note text.
             * <P>Type: TEXT</P>
             */
            public static final String NOTE = DATA1;
        }

        /**
         * <p>
         * Group Membership.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>long</td>
         * <td>{@link #GROUP_ROW_ID}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #GROUP_SOURCE_ID}</td>
         * <td>none</td>
         * <td>
         * <p>
         * The sourceid of the group that this group membership refers to.
         * Exactly one of this or {@link #GROUP_ROW_ID} must be set when
         * inserting a row.
         * </p>
         * <p>
         * If this field is specified, the provider will first try to
         * look up a group with this {@link Groups Groups.SOURCE_ID}.  If such a group
         * is found, it will use the corresponding row id.  If the group is not
         * found, it will create one.
         * </td>
         * </tr>
         * </table>
         */
        public static final class GroupMembership implements DataColumnsWithJoins, ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private GroupMembership() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE =
                    "vnd.android.cursor.item/group_membership";

            /**
             * The row id of the group that this group membership refers to. Exactly one of
             * this or {@link #GROUP_SOURCE_ID} must be set when inserting a row.
             * <P>Type: INTEGER</P>
             */
            public static final String GROUP_ROW_ID = DATA1;

            /**
             * The sourceid of the group that this group membership refers to.  Exactly one of
             * this or {@link #GROUP_ROW_ID} must be set when inserting a row.
             * <P>Type: TEXT</P>
             */
            public static final String GROUP_SOURCE_ID = "group_sourceid";
        }

        /**
         * <p>
         * A data kind representing a website related to the contact.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #URL}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOMEPAGE}</li>
         * <li>{@link #TYPE_BLOG}</li>
         * <li>{@link #TYPE_PROFILE}</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_FTP}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Website implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private Website() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/website";

            public static final int TYPE_HOMEPAGE = 1;
            public static final int TYPE_BLOG = 2;
            public static final int TYPE_PROFILE = 3;
            public static final int TYPE_HOME = 4;
            public static final int TYPE_WORK = 5;
            public static final int TYPE_FTP = 6;
            public static final int TYPE_OTHER = 7;

            /**
             * The website URL string.
             * <P>Type: TEXT</P>
             */
            public static final String URL = DATA;
        }

        /**
         * <p>
         * A data kind representing a SIP address for the contact.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #SIP_ADDRESS}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         *
         * @deprecated This field may not be well supported by some contacts apps and is discouraged
         * to use.
         */
        @Deprecated
        public static final class SipAddress implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private SipAddress() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/sip_address";

            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;

            /**
             * The SIP address.
             * <P>Type: TEXT</P>
             */
            public static final String SIP_ADDRESS = DATA1;
            // ...and TYPE and LABEL come from the CommonColumns interface.

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_HOME: return com.android.internal.R.string.sipAddressTypeHome;
                    case TYPE_WORK: return com.android.internal.R.string.sipAddressTypeWork;
                    case TYPE_OTHER: return com.android.internal.R.string.sipAddressTypeOther;
                    default: return com.android.internal.R.string.sipAddressTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    @Nullable CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * A data kind representing an Identity related to the contact.
         * <p>
         * This can be used as a signal by the aggregator to combine raw contacts into
         * contacts, e.g. if two contacts have Identity rows with
         * the same NAMESPACE and IDENTITY values the aggregator can know that they refer
         * to the same person.
         * </p>
         */
        public static final class Identity implements DataColumnsWithJoins, ContactCounts {
            /**
             * This utility class cannot be instantiated
             */
            private Identity() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/identity";

            /**
             * The identity string.
             * <P>Type: TEXT</P>
             */
            public static final String IDENTITY = DataColumns.DATA1;

            /**
             * The namespace of the identity string, e.g. "com.google"
             * <P>Type: TEXT</P>
             */
            public static final String NAMESPACE = DataColumns.DATA2;
        }

        /**
         * <p>
         * Convenient functionalities for "callable" data. Note that, this is NOT a separate data
         * kind.
         * </p>
         * <p>
         * This URI allows the ContactsProvider to return a unified result for "callable" data
         * that users can use for calling purposes. {@link Phone} and {@link SipAddress} are the
         * current examples for "callable", but may be expanded to the other types.
         * </p>
         * <p>
         * Each returned row may have a different MIMETYPE and thus different interpretation for
         * each column. For example the meaning for {@link Phone}'s type is different than
         * {@link SipAddress}'s.
         * </p>
         */
        public static final class Callable implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /**
             * Similar to {@link Phone#CONTENT_URI}, but returns callable data instead of only
             * phone numbers.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Data.CONTENT_URI,
                    "callables");
            /**
             * <p>Similar to {@link Phone#CONTENT_FILTER_URI}, but allows users to filter callable
             * data.
             *
             * <p class="caution"><b>Caution: </b>This field no longer sorts results based on
             * contacts frequency. For more information, see the
             * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
             * page.
             */
            public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(CONTENT_URI,
                    "filter");

            /**
             * <p>Similar to {@link Phone#ENTERPRISE_CONTENT_FILTER_URI}, but allows users to filter
             * callable data. This URI requires {@link ContactsContract#DIRECTORY_PARAM_KEY} in
             * parameters, otherwise it will throw IllegalArgumentException.
             *
             * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
             * this field doesn't sort results based on contacts frequency. For more information,
             * see the
             * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
             * page.</p>
             */
            public static final Uri ENTERPRISE_CONTENT_FILTER_URI = Uri.withAppendedPath(
                    CONTENT_URI, "filter_enterprise");
        }

        /**
         * A special class of data items, used to refer to types of data that can be used to attempt
         * to start communicating with a person ({@link Phone} and {@link Email}). Note that this
         * is NOT a separate data kind.
         *
         * This URI allows the ContactsProvider to return a unified result for data items that users
         * can use to initiate communications with another contact. {@link Phone} and {@link Email}
         * are the current data types in this category.
         */
        public static final class Contactables implements DataColumnsWithJoins, CommonColumns,
                ContactCounts {
            /**
             * The content:// style URI for these data items, which requests a directory of data
             * rows matching the selection criteria.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Data.CONTENT_URI,
                    "contactables");

            /**
             * <p>The content:// style URI for these data items, which allows for a query parameter
             * to be appended onto the end to filter for data items matching the query.
             *
             * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
             * this field doesn't sort results based on contacts frequency. For more information,
             * see the
             * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
             * page.
             */
            public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(
                    Contactables.CONTENT_URI, "filter");

            /**
             * A boolean parameter for {@link Data#CONTENT_URI}.
             * This specifies whether or not the returned data items should be filtered to show
             * data items belonging to visible contacts only.
             */
            public static final String VISIBLE_CONTACTS_ONLY = "visible_contacts_only";
        }
    }

    /**
     * @see Groups
     */
    protected interface GroupsColumns {
        /**
         * The data set within the account that this group belongs to.  This allows
         * multiple sync adapters for the same account type to distinguish between
         * each others' group data.
         *
         * This is empty by default, and is completely optional.  It only needs to
         * be populated if multiple sync adapters are entering distinct group data
         * for the same account type and account name.
         * <P>Type: TEXT</P>
         */
        public static final String DATA_SET = "data_set";

        /**
         * A concatenation of the account type and data set (delimited by a forward
         * slash) - if the data set is empty, this will be the same as the account
         * type.  For applications that need to be aware of the data set, this can
         * be used instead of account type to distinguish sets of data.  This is
         * never intended to be used for specifying accounts.
         * @hide
         */
        public static final String ACCOUNT_TYPE_AND_DATA_SET = "account_type_and_data_set";

        /**
         * The display title of this group.
         * <p>
         * Type: TEXT
         */
        public static final String TITLE = "title";

        /**
         * The package name to use when creating {@link Resources} objects for
         * this group. This value is only designed for use when building user
         * interfaces, and should not be used to infer the owner.
         */
        public static final String RES_PACKAGE = "res_package";

        /**
         * The display title of this group to load as a resource from
         * {@link #RES_PACKAGE}, which may be localized.
         * <P>Type: TEXT</P>
         */
        public static final String TITLE_RES = "title_res";

        /**
         * Notes about the group.
         * <p>
         * Type: TEXT
         */
        public static final String NOTES = "notes";

        /**
         * The ID of this group if it is a System Group, i.e. a group that has a special meaning
         * to the sync adapter, null otherwise.
         * <P>Type: TEXT</P>
         */
        public static final String SYSTEM_ID = "system_id";

        /**
         * The total number of {@link Contacts} that have
         * {@link CommonDataKinds.GroupMembership} in this group. Read-only value that is only
         * present when querying {@link Groups#CONTENT_SUMMARY_URI}.
         * <p>
         * Type: INTEGER
         */
        public static final String SUMMARY_COUNT = "summ_count";

        /**
         * A boolean query parameter that can be used with {@link Groups#CONTENT_SUMMARY_URI}.
         * It will additionally return {@link #SUMMARY_GROUP_COUNT_PER_ACCOUNT}.
         *
         * @hide
         */
        public static final String PARAM_RETURN_GROUP_COUNT_PER_ACCOUNT =
                "return_group_count_per_account";

        /**
         * The total number of groups of the account that a group belongs to.
         * This column is available only when the parameter
         * {@link #PARAM_RETURN_GROUP_COUNT_PER_ACCOUNT} is specified in
         * {@link Groups#CONTENT_SUMMARY_URI}.
         *
         * For example, when the account "A" has two groups "group1" and "group2", and the account
         * "B" has a group "group3", the rows for "group1" and "group2" return "2" and the row for
         * "group3" returns "1" for this column.
         *
         * Note: This counts only non-favorites, non-auto-add, and not deleted groups.
         *
         * Type: INTEGER
         * @hide
         */
        public static final String SUMMARY_GROUP_COUNT_PER_ACCOUNT = "group_count_per_account";

        /**
         * The total number of {@link Contacts} that have both
         * {@link CommonDataKinds.GroupMembership} in this group, and also have phone numbers.
         * Read-only value that is only present when querying
         * {@link Groups#CONTENT_SUMMARY_URI}.
         * <p>
         * Type: INTEGER
         */
        public static final String SUMMARY_WITH_PHONES = "summ_phones";

        /**
         * Flag indicating if the contacts belonging to this group should be
         * visible in any user interface.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String GROUP_VISIBLE = "group_visible";

        /**
         * The "deleted" flag: "0" by default, "1" if the row has been marked
         * for deletion. When {@link android.content.ContentResolver#delete} is
         * called on a group, it is marked for deletion. The sync adaptor
         * deletes the group on the server and then calls ContactResolver.delete
         * once more, this time setting the the
         * {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter to
         * finalize the data removal.
         * <P>Type: INTEGER</P>
         */
        public static final String DELETED = "deleted";

        /**
         * Whether this group should be synced if the SYNC_EVERYTHING settings
         * is false for this group's account.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String SHOULD_SYNC = "should_sync";

        /**
         * Any newly created contacts will automatically be added to groups that have this
         * flag set to true.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String AUTO_ADD = "auto_add";

        /**
         * When a contacts is marked as a favorites it will be automatically added
         * to the groups that have this flag set, and when it is removed from favorites
         * it will be removed from these groups.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String FAVORITES = "favorites";

        /**
         * The "read-only" flag: "0" by default, "1" if the row cannot be modified or
         * deleted except by a sync adapter.  See {@link ContactsContract#CALLER_IS_SYNCADAPTER}.
         * <P>Type: INTEGER</P>
         */
        public static final String GROUP_IS_READ_ONLY = "group_is_read_only";
    }

    /**
     * Constants for the groups table. Only per-account groups are supported.
     * <h2>Columns</h2>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Groups</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #_ID}</td>
     * <td>read-only</td>
     * <td>Row ID. Sync adapter should try to preserve row IDs during updates.
     * In other words, it would be a really bad idea to delete and reinsert a
     * group. A sync adapter should always do an update instead.</td>
     * </tr>
     # <tr>
     * <td>String</td>
     * <td>{@link #DATA_SET}</td>
     * <td>read/write-once</td>
     * <td>
     * <p>
     * The data set within the account that this group belongs to.  This allows
     * multiple sync adapters for the same account type to distinguish between
     * each others' group data.  The combination of {@link #ACCOUNT_TYPE},
     * {@link #ACCOUNT_NAME}, and {@link #DATA_SET} identifies a set of data
     * that is associated with a single sync adapter.
     * </p>
     * <p>
     * This is empty by default, and is completely optional.  It only needs to
     * be populated if multiple sync adapters are entering distinct data for
     * the same account type and account name.
     * </p>
     * <p>
     * It should be set at the time the group is inserted and never changed
     * afterwards.
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #TITLE}</td>
     * <td>read/write</td>
     * <td>The display title of this group.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #NOTES}</td>
     * <td>read/write</td>
     * <td>Notes about the group.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYSTEM_ID}</td>
     * <td>read/write</td>
     * <td>The ID of this group if it is a System Group, i.e. a group that has a
     * special meaning to the sync adapter, null otherwise.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SUMMARY_COUNT}</td>
     * <td>read-only</td>
     * <td>The total number of {@link Contacts} that have
     * {@link CommonDataKinds.GroupMembership} in this group. Read-only value
     * that is only present when querying {@link Groups#CONTENT_SUMMARY_URI}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SUMMARY_WITH_PHONES}</td>
     * <td>read-only</td>
     * <td>The total number of {@link Contacts} that have both
     * {@link CommonDataKinds.GroupMembership} in this group, and also have
     * phone numbers. Read-only value that is only present when querying
     * {@link Groups#CONTENT_SUMMARY_URI}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #GROUP_VISIBLE}</td>
     * <td>read-only</td>
     * <td>Flag indicating if the contacts belonging to this group should be
     * visible in any user interface. Allowed values: 0 and 1.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DELETED}</td>
     * <td>read/write</td>
     * <td>The "deleted" flag: "0" by default, "1" if the row has been marked
     * for deletion. When {@link android.content.ContentResolver#delete} is
     * called on a group, it is marked for deletion. The sync adaptor deletes
     * the group on the server and then calls ContactResolver.delete once more,
     * this time setting the the {@link ContactsContract#CALLER_IS_SYNCADAPTER}
     * query parameter to finalize the data removal.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SHOULD_SYNC}</td>
     * <td>read/write</td>
     * <td>Whether this group should be synced if the SYNC_EVERYTHING settings
     * is false for this group's account.</td>
     * </tr>
     * </table>
     */
    public static final class Groups implements BaseColumns, GroupsColumns, SyncColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Groups() {
        }

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "groups");

        /**
         * The content:// style URI for this table joined with details data from
         * {@link ContactsContract.Data}.
         */
        public static final Uri CONTENT_SUMMARY_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "groups_summary");

        /**
         * The MIME type of a directory of groups.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/group";

        /**
         * The MIME type of a single group.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/group";

        public static EntityIterator newEntityIterator(Cursor cursor) {
            return new EntityIteratorImpl(cursor);
        }

        private static class EntityIteratorImpl extends CursorEntityIterator {
            public EntityIteratorImpl(Cursor cursor) {
                super(cursor);
            }

            @Override
            public Entity getEntityAndIncrementCursor(Cursor cursor) throws RemoteException {
                // we expect the cursor is already at the row we need to read from
                final ContentValues values = new ContentValues();
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, _ID);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, ACCOUNT_NAME);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, ACCOUNT_TYPE);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, DIRTY);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, VERSION);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SOURCE_ID);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, RES_PACKAGE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, TITLE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, TITLE_RES);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, GROUP_VISIBLE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYNC1);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYNC2);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYNC3);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYNC4);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYSTEM_ID);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, DELETED);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, NOTES);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SHOULD_SYNC);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, FAVORITES);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, AUTO_ADD);
                cursor.moveToNext();
                return new Entity(values);
            }
        }
    }

    /**
     * <p>
     * Constants for the contact aggregation exceptions table, which contains
     * aggregation rules overriding those used by automatic aggregation. This
     * type only supports query and update. Neither insert nor delete are
     * supported.
     * </p>
     * <h2>Columns</h2>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>AggregationExceptions</th>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #TYPE}</td>
     * <td>read/write</td>
     * <td>The type of exception: {@link #TYPE_KEEP_TOGETHER},
     * {@link #TYPE_KEEP_SEPARATE} or {@link #TYPE_AUTOMATIC}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #RAW_CONTACT_ID1}</td>
     * <td>read/write</td>
     * <td>A reference to the {@link RawContacts#_ID} of the raw contact that
     * the rule applies to.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #RAW_CONTACT_ID2}</td>
     * <td>read/write</td>
     * <td>A reference to the other {@link RawContacts#_ID} of the raw contact
     * that the rule applies to.</td>
     * </tr>
     * </table>
     */
    public static final class AggregationExceptions implements BaseColumns {
        /**
         * This utility class cannot be instantiated
         */
        private AggregationExceptions() {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "aggregation_exceptions");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of data.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/aggregation_exception";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of an aggregation exception
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/aggregation_exception";

        /**
         * The type of exception: {@link #TYPE_KEEP_TOGETHER}, {@link #TYPE_KEEP_SEPARATE} or
         * {@link #TYPE_AUTOMATIC}.
         *
         * <P>Type: INTEGER</P>
         */
        public static final String TYPE = "type";

        /**
         * Allows the provider to automatically decide whether the specified raw contacts should
         * be included in the same aggregate contact or not.
         */
        public static final int TYPE_AUTOMATIC = 0;

        /**
         * Makes sure that the specified raw contacts are included in the same
         * aggregate contact.
         */
        public static final int TYPE_KEEP_TOGETHER = 1;

        /**
         * Makes sure that the specified raw contacts are NOT included in the same
         * aggregate contact.
         */
        public static final int TYPE_KEEP_SEPARATE = 2;

        /**
         * A reference to the {@link RawContacts#_ID} of the raw contact that the rule applies to.
         */
        public static final String RAW_CONTACT_ID1 = "raw_contact_id1";

        /**
         * A reference to the other {@link RawContacts#_ID} of the raw contact that the rule
         * applies to.
         */
        public static final String RAW_CONTACT_ID2 = "raw_contact_id2";
    }

    /**
     * Class containing utility methods around determine what accounts in the ContactsProvider are
     * related to the SIM cards in the device.
     * <p>
     * Apps interested in managing contacts from SIM cards can query the ContactsProvider using
     * {@link #getSimAccounts(ContentResolver)} to get all accounts that relate to SIM cards. They
     * can also register a receiver for the {@link #ACTION_SIM_ACCOUNTS_CHANGED} broadcast to be
     * notified when these accounts change.
     */
    public static final class SimContacts {
        /**
         * This utility class cannot be instantiated
         */
        private SimContacts() {
        }

        /**
         * The method to invoke in order to add a new SIM account for a newly inserted SIM card.
         *
         * @hide
         */
        public static final String ADD_SIM_ACCOUNT_METHOD = "addSimAccount";

        /**
         * The method to invoke in order to remove a SIM account once the corresponding SIM card is
         * ejected.
         *
         * @hide
         */
        public static final String REMOVE_SIM_ACCOUNT_METHOD = "removeSimAccount";

        /**
         * The method to invoke in order to query all SIM accounts.
         *
         * @hide
         */
        public static final String QUERY_SIM_ACCOUNTS_METHOD = "querySimAccounts";

        /**
         * Key to add in the outgoing Bundle for the SIM slot.
         *
         * @hide
         */
        public static final String KEY_SIM_SLOT_INDEX = "key_sim_slot_index";

        /**
         * Key to add in the outgoing Bundle for the SIM account's EF type.
         * See {@link SimAccount#mEfType} for more information.
         *
         * @hide
         */
        public static final String KEY_SIM_EF_TYPE = "key_sim_ef_type";

        /**
         * Key to add in the outgoing Bundle for the account name.
         *
         * @hide
         */
        public static final String KEY_ACCOUNT_NAME = "key_sim_account_name";

        /**
         * Key to add in the outgoing Bundle for the account type.
         *
         * @hide
         */
        public static final String KEY_ACCOUNT_TYPE = "key_sim_account_type";

        /**
         * Key in the incoming Bundle for the all the SIM accounts.
         *
         * @hide
         */
        public static final String KEY_SIM_ACCOUNTS = "key_sim_accounts";

        /**
         * Broadcast Action: SIM accounts have changed, call
         * {@link #getSimAccounts(ContentResolver)} to get the latest.
         */
        @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
        public static final String ACTION_SIM_ACCOUNTS_CHANGED =
                "android.provider.action.SIM_ACCOUNTS_CHANGED";

        /**
         * Adds a new SIM account that maps to the corresponding SIM slot.
         *
         * @param accountName     accountName value for the account
         * @param accountType     accountType value for the account
         * @param contentResolver to perform the operation on.
         * @param simSlotIndex    the SIM slot index of this new account.
         * @param efType          the EF type of this new account.
         * @hide
         */
        @SystemApi
        @RequiresPermission("android.contacts.permission.MANAGE_SIM_ACCOUNTS")
        public static void addSimAccount(@NonNull ContentResolver contentResolver,
                @NonNull String accountName,
                @NonNull String accountType,
                int simSlotIndex,
                int efType) {
            if (simSlotIndex < 0) {
                throw new IllegalArgumentException("Sim slot is negative");
            }
            if (!SimAccount.getValidEfTypes().contains(efType)) {
                throw new IllegalArgumentException("Invalid EF type");
            }
            if (TextUtils.isEmpty(accountName) || TextUtils.isEmpty(accountType)) {
                throw new IllegalArgumentException("Account name or type is empty");
            }

            Bundle extras = new Bundle();
            extras.putInt(KEY_SIM_SLOT_INDEX, simSlotIndex);
            extras.putInt(KEY_SIM_EF_TYPE, efType);
            extras.putString(KEY_ACCOUNT_NAME, accountName);
            extras.putString(KEY_ACCOUNT_TYPE, accountType);

            nullSafeCall(contentResolver, ContactsContract.AUTHORITY_URI,
                    ContactsContract.SimContacts.ADD_SIM_ACCOUNT_METHOD,
                    null, extras);
        }

        /**
         * Removes all SIM accounts that map to the corresponding SIM slot.
         *
         * @param contentResolver to perform the operation on.
         * @param simSlotIndex    the SIM slot index of the accounts to remove.
         * @hide
         */
        @SystemApi
        @RequiresPermission("android.contacts.permission.MANAGE_SIM_ACCOUNTS")
        public static void removeSimAccounts(@NonNull ContentResolver contentResolver,
                int simSlotIndex) {
            if (simSlotIndex < 0) {
                throw new IllegalArgumentException("Sim slot is negative");
            }

            Bundle extras = new Bundle();
            extras.putInt(KEY_SIM_SLOT_INDEX, simSlotIndex);

            nullSafeCall(contentResolver, ContactsContract.AUTHORITY_URI,
                    ContactsContract.SimContacts.REMOVE_SIM_ACCOUNT_METHOD,
                    null, extras);
        }

        /**
         * Returns all known SIM accounts. May be empty but never null.
         *
         * @param contentResolver content resolver to query.
         */
        public static @NonNull List<SimAccount> getSimAccounts(
                @NonNull ContentResolver contentResolver) {
            Bundle response = nullSafeCall(contentResolver, ContactsContract.AUTHORITY_URI,
                    ContactsContract.SimContacts.QUERY_SIM_ACCOUNTS_METHOD,
                    null, null);
            List<SimAccount> result = response.getParcelableArrayList(KEY_SIM_ACCOUNTS, android.provider.ContactsContract.SimAccount.class);

            if (result == null) {
                result = new ArrayList<>();
            }

            return result;
        }
    }

    /**
     * A parcelable class encapsulating account data for contacts that originate from a SIM card.
     */
    public static final class SimAccount implements Parcelable {
        /** An invalid EF type identifier. */
        public static final int UNKNOWN_EF_TYPE = 0;
        /** EF type identifier for the ADN partition. */
        public static final int ADN_EF_TYPE = 1;
        /** EF type identifier for the FDN partition. */
        public static final int FDN_EF_TYPE = 2;
        /** EF type identifier for the SDN partition. */
        public static final int SDN_EF_TYPE = 3;

        /**
         * The account_name of this SIM account. See {@link RawContacts#ACCOUNT_NAME}.
         */
        private final String mAccountName;

        /**
         * The account_type of this SIM account. See {@link RawContacts#ACCOUNT_TYPE}.
         */
        private final String mAccountType;

        /**
         * The slot index of the SIM card this account maps to. See {@link
         * android.telephony.SubscriptionInfo#getSimSlotIndex()}.
         */
        private final int mSimSlotIndex;

        /**
         * The EF type of the contacts stored in this account. One of
         * {@link #ADN_EF_TYPE}, {@link #SDN_EF_TYPE} or {@link #FDN_EF_TYPE}.
         *
         * EF type is the Elementary File type of the partition these contacts come from within the
         * SIM card.
         *
         * ADN is the "abbreviated dialing numbers" or the user managed SIM contacts.
         *
         * SDN is the "service dialing numbers" which are usually preloaded onto the SIM by the
         * carrier.
         *
         * FDN is the "fixed dialing numbers" which are contacts which can only be dialed from that
         * SIM, used in cases such as parental control.
         */
        private final int mEfType;

        /**
         * @return A set containing all known EF type values
         * @hide
         */
        public static @NonNull Set<Integer> getValidEfTypes() {
            return Sets.newArraySet(ADN_EF_TYPE, SDN_EF_TYPE, FDN_EF_TYPE);
        }

        /**
         * @hide
         */
        public SimAccount(@NonNull String accountName, @NonNull String accountType,
                int simSlotIndex,
                int efType) {
            this.mAccountName = accountName;
            this.mAccountType = accountType;
            this.mSimSlotIndex = simSlotIndex;
            this.mEfType = efType;
        }

        /**
         * @return The account_name of this SIM account. See {@link RawContacts#ACCOUNT_NAME}.
         */
        public @NonNull String getAccountName() {
            return mAccountName;
        }

        /**
         * @return The account_type of this SIM account. See {@link RawContacts#ACCOUNT_TYPE}.
         */
        public @NonNull String getAccountType() {
            return mAccountType;
        }

        /**
         * @return The slot index of the SIM card this account maps to. See
         * {@link android.telephony.SubscriptionInfo#getSimSlotIndex()}.
         */
        public int getSimSlotIndex() {
            return mSimSlotIndex;
        }

        /**
         * @return The EF type of the contacts stored in this account.
         */
        public int getEfType() {
            return mEfType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAccountName, mAccountType, mSimSlotIndex, mEfType);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) return false;
            if (obj == this) return true;

            SimAccount toCompare;
            try {
                toCompare = (SimAccount) obj;
            } catch (ClassCastException ex) {
                return false;
            }

            return mSimSlotIndex == toCompare.mSimSlotIndex
                    && mEfType == toCompare.mEfType
                    && Objects.equals(mAccountName, toCompare.mAccountName)
                    && Objects.equals(mAccountType, toCompare.mAccountType);
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(mAccountName);
            dest.writeString(mAccountType);
            dest.writeInt(mSimSlotIndex);
            dest.writeInt(mEfType);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @NonNull Parcelable.Creator<SimAccount> CREATOR =
                new Parcelable.Creator<SimAccount>() {
                    @Override
                    public SimAccount createFromParcel(Parcel source) {
                        String accountName = source.readString();
                        String accountType = source.readString();
                        int simSlot = source.readInt();
                        int efType = source.readInt();
                        SimAccount simAccount = new SimAccount(accountName, accountType, simSlot,
                                efType);
                        return simAccount;
                    }

                    @Override
                    public SimAccount[] newArray(int size) {
                        return new SimAccount[size];
                    }
                };
    }

    /**
     * @see Settings
     */
    protected interface SettingsColumns {
        /**
         * The name of the account instance to which this row belongs.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * The type of account to which this row belongs, which when paired with
         * {@link #ACCOUNT_NAME} identifies a specific account.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_TYPE = "account_type";

        /**
         * The data set within the account that this row belongs to.  This allows
         * multiple sync adapters for the same account type to distinguish between
         * each others' data.
         *
         * This is empty by default, and is completely optional.  It only needs to
         * be populated if multiple sync adapters are entering distinct data for
         * the same account type and account name.
         * <P>Type: TEXT</P>
         */
        public static final String DATA_SET = "data_set";

        /**
         * Depending on the mode defined by the sync-adapter, this flag controls
         * the top-level sync behavior for this data source.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String SHOULD_SYNC = "should_sync";

        /**
         * Flag indicating if contacts without any {@link CommonDataKinds.GroupMembership}
         * entries should be visible in any user interface.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String UNGROUPED_VISIBLE = "ungrouped_visible";

        /**
         * Read-only flag indicating if this {@link #SHOULD_SYNC} or any
         * {@link Groups#SHOULD_SYNC} under this account have been marked as
         * unsynced.
         */
        public static final String ANY_UNSYNCED = "any_unsynced";

        /**
         * Read-only count of {@link Contacts} from a specific source that have
         * no {@link CommonDataKinds.GroupMembership} entries.
         * <p>
         * Type: INTEGER
         */
        public static final String UNGROUPED_COUNT = "summ_count";

        /**
         * Read-only count of {@link Contacts} from a specific source that have
         * no {@link CommonDataKinds.GroupMembership} entries, and also have phone numbers.
         * <p>
         * Type: INTEGER
         */
        public static final String UNGROUPED_WITH_PHONES = "summ_phones";

        /**
         * Flag indicating if the account is the default account for new contacts. At most one
         * account has this flag set at a time. It can only be set to 1 on a row with null data set.
         * <p>
         * Type: INTEGER (boolean)
         * @hide
         */
        String IS_DEFAULT = "x_is_default";
    }

    /**
     * <p>
     * Contacts-specific settings for various {@link Account}'s.
     * </p>
     * <p>
     * A settings entry for an account is created automatically when a raw contact or group
     * is inserted that references it. Settings entries cannot be deleted as long as raw
     * contacts or groups continue to reference it; in order to delete a settings entry all
     * raw contacts and groups referencing the account must be deleted first.
     * </p>
     * <h2>Columns</h2>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Settings</th>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #ACCOUNT_NAME}</td>
     * <td>read/write-once</td>
     * <td>The name of the account instance to which this row belongs.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #ACCOUNT_TYPE}</td>
     * <td>read/write-once</td>
     * <td>The type of account to which this row belongs, which when paired with
     * {@link #ACCOUNT_NAME} identifies a specific account.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SHOULD_SYNC}</td>
     * <td>read/write</td>
     * <td>Depending on the mode defined by the sync-adapter, this flag controls
     * the top-level sync behavior for this data source.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #UNGROUPED_VISIBLE}</td>
     * <td>read/write</td>
     * <td>Flag indicating if contacts without any
     * {@link CommonDataKinds.GroupMembership} entries should be visible in any
     * user interface.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #ANY_UNSYNCED}</td>
     * <td>read-only</td>
     * <td>Read-only flag indicating if this {@link #SHOULD_SYNC} or any
     * {@link Groups#SHOULD_SYNC} under this account have been marked as
     * unsynced.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #UNGROUPED_COUNT}</td>
     * <td>read-only</td>
     * <td>Read-only count of {@link Contacts} from a specific source that have
     * no {@link CommonDataKinds.GroupMembership} entries.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #UNGROUPED_WITH_PHONES}</td>
     * <td>read-only</td>
     * <td>Read-only count of {@link Contacts} from a specific source that have
     * no {@link CommonDataKinds.GroupMembership} entries, and also have phone
     * numbers.</td>
     * </tr>
     * </table>
     */
    public static final class Settings implements SettingsColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Settings() {
        }

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "settings");

        /**
         * The MIME-type of {@link #CONTENT_URI} providing a directory of
         * settings.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/setting";

        /**
         * The MIME-type of {@link #CONTENT_URI} providing a single setting.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/setting";

        /**
         * Action used to launch the UI to set the default account for new contacts.
         */
        @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_SET_DEFAULT_ACCOUNT =
                "android.provider.action.SET_DEFAULT_ACCOUNT";

        /**
         * The method to invoke in order to set the default account for new contacts.
         *
         * @hide
         */
        public static final String SET_DEFAULT_ACCOUNT_METHOD = "setDefaultAccount";

        /**
         * The method to invoke in order to query the default account for new contacts.
         *
         * @hide
         */
        public static final String QUERY_DEFAULT_ACCOUNT_METHOD = "queryDefaultAccount";

        /**
         * Key in the incoming Bundle for the default account.
         *
         * @hide
         */
        public static final String KEY_DEFAULT_ACCOUNT = "key_default_account";

        /**
         * Get the account that is set as the default account for new contacts, which should be
         * initially selected when creating a new contact on contact management apps.
         * If the setting has not been set by any app, it will return null. Once the setting
         * is set to non-null Account, it can still be set to null in the future.
         *
         * @param resolver the ContentResolver to query.
         * @return the default account for new contacts, or null if it's not set or set to NULL
         * account.
         */
        @Nullable
        public static Account getDefaultAccount(@NonNull ContentResolver resolver) {
            Bundle response = resolver.call(ContactsContract.AUTHORITY_URI,
                    QUERY_DEFAULT_ACCOUNT_METHOD, null, null);
            return response.getParcelable(KEY_DEFAULT_ACCOUNT, android.accounts.Account.class);
        }

        /**
         * Sets the account as the default account that should be initially selected
         * when creating a new contact on contact management apps. Apps can only set one of
         * the following accounts as the default account:
         * <ol>
         *   <li>null or custom local account
         *   <li>SIM account
         *   <li>AccountManager accounts
         * </ol>
         *
         * @param resolver the ContentResolver to query.
         * @param account the account to be set to default.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.SET_DEFAULT_ACCOUNT_FOR_CONTACTS)
        public static void setDefaultAccount(@NonNull ContentResolver resolver,
                @Nullable Account account) {
            Bundle extras = new Bundle();
            if (account != null) {
                extras.putString(ACCOUNT_NAME, account.name);
                extras.putString(ACCOUNT_TYPE, account.type);
            }

            resolver.call(ContactsContract.AUTHORITY_URI, SET_DEFAULT_ACCOUNT_METHOD, null, extras);
        }
    }

    /**
     * API for inquiring about the general status of the provider.
     */
    public static final class ProviderStatus {

        /**
         * Not instantiable.
         */
        private ProviderStatus() {
        }

        /**
         * The content:// style URI for this table.  Requests to this URI can be
         * performed on the UI thread because they are always unblocking.
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "provider_status");

        /**
         * The MIME-type of {@link #CONTENT_URI} providing a directory of
         * settings.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/provider_status";

        /**
         * An integer representing the current status of the provider.
         */
        public static final String STATUS = "status";

        /**
         * Default status of the provider.
         */
        public static final int STATUS_NORMAL = 0;

        /**
         * The provider won't respond to queries. It is in the middle of a long running task, such
         * as a database upgrade or locale change.
         */
        public static final int STATUS_BUSY = 1;

        /**
         * The status that indicates that there are no accounts and no contacts
         * on the device.
         */
        public static final int STATUS_EMPTY = 2;

        /**
         * Timestamp (milliseconds since epoch) of when the provider's database was created.
         *
         * <P>Type: long
         */
        public static final String DATABASE_CREATION_TIMESTAMP = "database_creation_timestamp";
    }

    /**
     * <p>
     * API allowing applications to send usage information for each {@link Data} row to the
     * Contacts Provider.  Applications can also clear all usage information.
     * </p>
     * <p class="caution"><b>Caution: </b>If you publish your app to the Google Play Store,
     * this field is obsolete, regardless of Android version. For more information, see the
     * <a href="/guide/topics/providers/contacts-provider#ObsoleteData">Contacts Provider</a>
     * page.</p>
     * <p>
     * With the feedback, Contacts Provider may return more contextually appropriate results for
     * Data listing, typically supplied with
     * {@link ContactsContract.Contacts#CONTENT_FILTER_URI},
     * {@link ContactsContract.CommonDataKinds.Email#CONTENT_FILTER_URI},
     * {@link ContactsContract.CommonDataKinds.Phone#CONTENT_FILTER_URI}, and users can benefit
     * from better ranked (sorted) lists in applications that show auto-complete list.
     * </p>
     * <p>
     * There is no guarantee for how this feedback is used, or even whether it is used at all.
     * The ranking algorithm will make best efforts to use the feedback data, but the exact
     * implementation, the storage data structures as well as the resulting sort order is device
     * and version specific and can change over time.
     * </p>
     * <p>
     * When updating usage information, users of this API need to use
     * {@link ContentResolver#update(Uri, ContentValues, String, String[])} with a Uri constructed
     * from {@link DataUsageFeedback#FEEDBACK_URI}. The Uri must contain one or more data id(s) as
     * its last path. They also need to append a query parameter to the Uri, to specify the type of
     * the communication, which enables the Contacts Provider to differentiate between kinds of
     * interactions using the same contact data field (for example a phone number can be used to
     * make phone calls or send SMS).
     * </p>
     * <p>
     * Selection and selectionArgs are ignored and must be set to null. To get data ids,
     * you may need to call {@link ContentResolver#query(Uri, String[], String, String[], String)}
     * toward {@link Data#CONTENT_URI}.
     * </p>
     * <p>
     * {@link ContentResolver#update(Uri, ContentValues, String, String[])} returns a positive
     * integer when successful, and returns 0 if no contact with that id was found.
     * </p>
     * <p>
     * Example:
     * <pre>
     * Uri uri = DataUsageFeedback.FEEDBACK_URI.buildUpon()
     *         .appendPath(TextUtils.join(",", dataIds))
     *         .appendQueryParameter(DataUsageFeedback.USAGE_TYPE,
     *                 DataUsageFeedback.USAGE_TYPE_CALL)
     *         .build();
     * boolean successful = resolver.update(uri, new ContentValues(), null, null) > 0;
     * </pre>
     * </p>
     * <p>
     * Applications can also clear all usage information with:
     * <pre>
     * boolean successful = resolver.delete(DataUsageFeedback.DELETE_USAGE_URI, null, null) > 0;
     * </pre>
     * </p>
     *
     * @deprecated Contacts affinity information is no longer supported as of
     * Android version {@link android.os.Build.VERSION_CODES#Q}.
     * Both update and delete calls are always ignored.
     */
    @Deprecated
    public static final class DataUsageFeedback {

        /**
         * The content:// style URI for sending usage feedback.
         * Must be used with {@link ContentResolver#update(Uri, ContentValues, String, String[])}.
         */
        public static final Uri FEEDBACK_URI =
                Uri.withAppendedPath(Data.CONTENT_URI, "usagefeedback");

        /**
         * The content:// style URI for deleting all usage information.
         * Must be used with {@link ContentResolver#delete(Uri, String, String[])}.
         * The {@code where} and {@code selectionArgs} parameters are ignored.
         */
        public static final Uri DELETE_USAGE_URI =
                Uri.withAppendedPath(Contacts.CONTENT_URI, "delete_usage");

        /**
         * <p>
         * Name for query parameter specifying the type of data usage.
         * </p>
         */
        public static final String USAGE_TYPE = "type";

        /**
         * <p>
         * Type of usage for voice interaction, which includes phone call, voice chat, and
         * video chat.
         * </p>
         */
        public static final String USAGE_TYPE_CALL = "call";

        /**
         * <p>
         * Type of usage for text interaction involving longer messages, which includes email.
         * </p>
         */
        public static final String USAGE_TYPE_LONG_TEXT = "long_text";

        /**
         * <p>
         * Type of usage for text interaction involving shorter messages, which includes SMS,
         * text chat with email addresses.
         * </p>
         */
        public static final String USAGE_TYPE_SHORT_TEXT = "short_text";
    }

    /**
     * <p>
     * Contact-specific information about whether or not a contact has been pinned by the user
     * at a particular position within the system contact application's user interface.
     * </p>
     *
     * <p>
     * This pinning information can be used by individual applications to customize how
     * they order particular pinned contacts. For example, a Dialer application could
     * use pinned information to order user-pinned contacts in a top row of favorites.
     * </p>
     *
     * <p>
     * It is possible for two or more contacts to occupy the same pinned position (due
     * to aggregation and sync), so this pinning information should be used on a best-effort
     * basis to order contacts in-application rather than an absolute guide on where a contact
     * should be positioned. Contacts returned by the ContactsProvider will not be ordered based
     * on this information, so it is up to the client application to reorder these contacts within
     * their own UI adhering to (or ignoring as appropriate) information stored in the pinned
     * column.
     * </p>
     *
     * <p>
     * By default, unpinned contacts will have a pinned position of
     * {@link PinnedPositions#UNPINNED}. Client-provided pinned positions can be positive
     * integers that are greater than 1.
     * </p>
     */
    public static final class PinnedPositions {
        /**
         * The method to invoke in order to undemote a formerly demoted contact. The contact id of
         * the contact must be provided as an argument. If the contact was not previously demoted,
         * nothing will be done.
         * @hide
         */
        @TestApi
        public static final String UNDEMOTE_METHOD = "undemote";

        /**
         * Undemotes a formerly demoted contact. If the contact was not previously demoted, nothing
         * will be done.
         *
         * @param contentResolver to perform the undemote operation on.
         * @param contactId the id of the contact to undemote.
         */
        public static void undemote(ContentResolver contentResolver, long contactId) {
            nullSafeCall(contentResolver, ContactsContract.AUTHORITY_URI,
                    PinnedPositions.UNDEMOTE_METHOD,
                    String.valueOf(contactId), null);
        }

        /**
         * Pins a contact at a provided position, or unpins a contact.
         *
         * @param contentResolver to perform the pinning operation on.
         * @param pinnedPosition the position to pin the contact at. To unpin a contact, use
         *         {@link PinnedPositions#UNPINNED}.
         */
        public static void pin(
                ContentResolver contentResolver, long contactId, int pinnedPosition) {
            final Uri uri = Uri.withAppendedPath(Contacts.CONTENT_URI, String.valueOf(contactId));
            final ContentValues values = new ContentValues();
            values.put(Contacts.PINNED, pinnedPosition);
            contentResolver.update(uri, values, null, null);
        }

        /**
         * Default value for the pinned position of an unpinned contact.
         */
        public static final int UNPINNED = 0;

        /**
         * Value of pinned position for a contact that a user has indicated should be considered
         * of the lowest priority. It is up to the client application to determine how to present
         * such a contact - for example all the way at the bottom of a contact list, or simply
         * just hidden from view.
         */
        public static final int DEMOTED = -1;
    }

    /**
     * Helper methods to display QuickContact dialogs that display all the information belonging to
     * a specific {@link Contacts} entry.
     */
    public static final class QuickContact {
        /**
         * Action used to launch the system contacts application and bring up a QuickContact dialog
         * for the provided {@link Contacts} entry.
         */
        @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_QUICK_CONTACT =
                "android.provider.action.QUICK_CONTACT";

        /**
         * Extra used to specify pivot dialog location in screen coordinates.
         * @deprecated Use {@link Intent#setSourceBounds(Rect)} instead.
         * @hide
         */
        @Deprecated
        public static final String EXTRA_TARGET_RECT = "android.provider.extra.TARGET_RECT";

        /**
         * Extra used to specify size of QuickContacts. Not all implementations of QuickContacts
         * will respect this extra's value.
         *
         * One of {@link #MODE_SMALL}, {@link #MODE_MEDIUM}, or {@link #MODE_LARGE}.
         */
        public static final String EXTRA_MODE = "android.provider.extra.MODE";

        /**
         * Extra used to specify which mimetype should be prioritized in the QuickContacts UI.
         * For example, passing the value {@link CommonDataKinds.Phone#CONTENT_ITEM_TYPE} can
         * cause phone numbers to be displayed more prominently in QuickContacts.
         */
        public static final String EXTRA_PRIORITIZED_MIMETYPE
                = "android.provider.extra.PRIORITIZED_MIMETYPE";

        /**
         * Extra used to indicate a list of specific MIME-types to exclude and not display in the
         * QuickContacts dialog. Stored as a {@link String} array.
         */
        public static final String EXTRA_EXCLUDE_MIMES = "android.provider.extra.EXCLUDE_MIMES";

        /**
         * Small QuickContact mode, usually presented with minimal actions.
         */
        public static final int MODE_SMALL = 1;

        /**
         * Medium QuickContact mode, includes actions and light summary describing
         * the {@link Contacts} entry being shown. This may include social
         * status and presence details.
         */
        public static final int MODE_MEDIUM = 2;

        /**
         * Large QuickContact mode, includes actions and larger, card-like summary
         * of the {@link Contacts} entry being shown. This may include detailed
         * information, such as a photo.
         */
        public static final int MODE_LARGE = 3;

        /** @hide */
        public static final int MODE_DEFAULT = MODE_LARGE;

        /**
         * Constructs the QuickContacts intent with a view's rect.
         * @hide
         */
        public static Intent composeQuickContactsIntent(Context context, View target, Uri lookupUri,
                int mode, String[] excludeMimes) {
            // Find location and bounds of target view, adjusting based on the
            // assumed local density.
            final float appScale = context.getResources().getCompatibilityInfo().applicationScale;
            final int[] pos = new int[2];
            target.getLocationOnScreen(pos);

            final Rect rect = new Rect();
            rect.left = (int) (pos[0] * appScale + 0.5f);
            rect.top = (int) (pos[1] * appScale + 0.5f);
            rect.right = (int) ((pos[0] + target.getWidth()) * appScale + 0.5f);
            rect.bottom = (int) ((pos[1] + target.getHeight()) * appScale + 0.5f);

            return composeQuickContactsIntent(context, rect, lookupUri, mode, excludeMimes);
        }

        /**
         * Constructs the QuickContacts intent.
         * @hide
         */
        @UnsupportedAppUsage
        public static Intent composeQuickContactsIntent(Context context, Rect target,
                Uri lookupUri, int mode, String[] excludeMimes) {
            // When launching from an Activiy, we don't want to start a new task, but otherwise
            // we *must* start a new task.  (Otherwise startActivity() would crash.)
            Context actualContext = context;
            while ((actualContext instanceof ContextWrapper)
                    && !(actualContext instanceof Activity)) {
                actualContext = ((ContextWrapper) actualContext).getBaseContext();
            }
            final int intentFlags = ((actualContext instanceof Activity)
                    ? 0 : Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    // Workaround for b/16898764. Declaring singleTop in manifest doesn't work.
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP;

            // Launch pivot dialog through intent for now
            final Intent intent = new Intent(ACTION_QUICK_CONTACT).addFlags(intentFlags);

            // NOTE: This logic and rebuildManagedQuickContactsIntent() must be in sync.
            intent.setData(lookupUri);
            intent.setSourceBounds(target);
            intent.putExtra(EXTRA_MODE, mode);
            intent.putExtra(EXTRA_EXCLUDE_MIMES, excludeMimes);
            return intent;
        }

        /**
         * Constructs a QuickContacts intent based on an incoming intent for DevicePolicyManager
         * to strip off anything not necessary.
         *
         * @hide
         */
        public static Intent rebuildManagedQuickContactsIntent(String lookupKey, long contactId,
                boolean isContactIdIgnored, long directoryId, Intent originalIntent) {
            final Intent intent = new Intent(ACTION_QUICK_CONTACT);
            // Rebuild the URI from a lookup key and a contact ID.
            Uri uri = null;
            if (!TextUtils.isEmpty(lookupKey)) {
                uri = isContactIdIgnored
                        ? Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, lookupKey)
                        : Contacts.getLookupUri(contactId, lookupKey);
            }
            if (uri != null && directoryId != Directory.DEFAULT) {
                uri = uri.buildUpon().appendQueryParameter(
                        ContactsContract.DIRECTORY_PARAM_KEY, String.valueOf(directoryId)).build();
            }
            intent.setData(uri);

            // Copy flags and always set NEW_TASK because it won't have a parent activity.
            intent.setFlags(originalIntent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);

            // Copy extras.
            intent.setSourceBounds(originalIntent.getSourceBounds());
            intent.putExtra(EXTRA_MODE, originalIntent.getIntExtra(EXTRA_MODE, MODE_DEFAULT));
            intent.putExtra(EXTRA_EXCLUDE_MIMES,
                    originalIntent.getStringArrayExtra(EXTRA_EXCLUDE_MIMES));
            return intent;
        }


        /**
         * Trigger a dialog that lists the various methods of interacting with
         * the requested {@link Contacts} entry. This may be based on available
         * {@link ContactsContract.Data} rows under that contact, and may also
         * include social status and presence details.
         *
         * @param context The parent {@link Context} that may be used as the
         *            parent for this dialog.
         * @param target Specific {@link View} from your layout that this dialog
         *            should be centered around. In particular, if the dialog
         *            has a "callout" arrow, it will be pointed and centered
         *            around this {@link View}.
         * @param lookupUri A {@link ContactsContract.Contacts#CONTENT_LOOKUP_URI} style
         *            {@link Uri} that describes a specific contact to feature
         *            in this dialog. A managed profile lookup uri is supported here,
         *            see {@link CommonDataKinds.Email#ENTERPRISE_CONTENT_LOOKUP_URI} and
         *            {@link PhoneLookup#ENTERPRISE_CONTENT_FILTER_URI}.
         * @param mode Any of {@link #MODE_SMALL}, {@link #MODE_MEDIUM}, or
         *            {@link #MODE_LARGE}, indicating the desired dialog size,
         *            when supported.
         * @param excludeMimes Optional list of {@link Data#MIMETYPE} MIME-types
         *            to exclude when showing this dialog. For example, when
         *            already viewing the contact details card, this can be used
         *            to omit the details entry from the dialog.
         */
        public static void showQuickContact(Context context, View target, Uri lookupUri, int mode,
                String[] excludeMimes) {
            // Trigger with obtained rectangle
            Intent intent = composeQuickContactsIntent(context, target, lookupUri, mode,
                    excludeMimes);
            ContactsInternal.startQuickContactWithErrorToast(context, intent);
        }

        /**
         * Trigger a dialog that lists the various methods of interacting with
         * the requested {@link Contacts} entry. This may be based on available
         * {@link ContactsContract.Data} rows under that contact, and may also
         * include social status and presence details.
         *
         * @param context The parent {@link Context} that may be used as the
         *            parent for this dialog.
         * @param target Specific {@link Rect} that this dialog should be
         *            centered around, in screen coordinates. In particular, if
         *            the dialog has a "callout" arrow, it will be pointed and
         *            centered around this {@link Rect}. If you are running at a
         *            non-native density, you need to manually adjust using
         *            {@link DisplayMetrics#density} before calling.
         * @param lookupUri A
         *            {@link ContactsContract.Contacts#CONTENT_LOOKUP_URI} style
         *            {@link Uri} that describes a specific contact to feature
         *            in this dialog. A managed profile lookup uri is supported here,
         *            see {@link CommonDataKinds.Email#ENTERPRISE_CONTENT_LOOKUP_URI} and
         *            {@link PhoneLookup#ENTERPRISE_CONTENT_FILTER_URI}.
         * @param mode Any of {@link #MODE_SMALL}, {@link #MODE_MEDIUM}, or
         *            {@link #MODE_LARGE}, indicating the desired dialog size,
         *            when supported.
         * @param excludeMimes Optional list of {@link Data#MIMETYPE} MIME-types
         *            to exclude when showing this dialog. For example, when
         *            already viewing the contact details card, this can be used
         *            to omit the details entry from the dialog.
         */
        public static void showQuickContact(Context context, Rect target, Uri lookupUri, int mode,
                String[] excludeMimes) {
            Intent intent = composeQuickContactsIntent(context, target, lookupUri, mode,
                    excludeMimes);
            ContactsInternal.startQuickContactWithErrorToast(context, intent);
        }

        /**
         * Trigger a dialog that lists the various methods of interacting with
         * the requested {@link Contacts} entry. This may be based on available
         * {@link ContactsContract.Data} rows under that contact, and may also
         * include social status and presence details.
         *
         * @param context The parent {@link Context} that may be used as the
         *            parent for this dialog.
         * @param target Specific {@link View} from your layout that this dialog
         *            should be centered around. In particular, if the dialog
         *            has a "callout" arrow, it will be pointed and centered
         *            around this {@link View}.
         * @param lookupUri A
         *            {@link ContactsContract.Contacts#CONTENT_LOOKUP_URI} style
         *            {@link Uri} that describes a specific contact to feature
         *            in this dialog. A managed profile lookup uri is supported here,
         *            see {@link CommonDataKinds.Email#ENTERPRISE_CONTENT_LOOKUP_URI} and
         *            {@link PhoneLookup#ENTERPRISE_CONTENT_FILTER_URI}.
         * @param excludeMimes Optional list of {@link Data#MIMETYPE} MIME-types
         *            to exclude when showing this dialog. For example, when
         *            already viewing the contact details card, this can be used
         *            to omit the details entry from the dialog.
         * @param prioritizedMimeType This mimetype should be prioritized in the QuickContacts UI.
         *             For example, passing the value
         *             {@link CommonDataKinds.Phone#CONTENT_ITEM_TYPE} can cause phone numbers to be
         *             displayed more prominently in QuickContacts.
         */
        public static void showQuickContact(Context context, View target, Uri lookupUri,
                String[] excludeMimes, String prioritizedMimeType) {
            // Use MODE_LARGE instead of accepting mode as a parameter. The different mode
            // values defined in ContactsContract only affect very old implementations
            // of QuickContacts.
            Intent intent = composeQuickContactsIntent(context, target, lookupUri, MODE_DEFAULT,
                    excludeMimes);
            intent.putExtra(EXTRA_PRIORITIZED_MIMETYPE, prioritizedMimeType);
            ContactsInternal.startQuickContactWithErrorToast(context, intent);
        }

        /**
         * Trigger a dialog that lists the various methods of interacting with
         * the requested {@link Contacts} entry. This may be based on available
         * {@link ContactsContract.Data} rows under that contact, and may also
         * include social status and presence details.
         *
         * @param context The parent {@link Context} that may be used as the
         *            parent for this dialog.
         * @param target Specific {@link Rect} that this dialog should be
         *            centered around, in screen coordinates. In particular, if
         *            the dialog has a "callout" arrow, it will be pointed and
         *            centered around this {@link Rect}. If you are running at a
         *            non-native density, you need to manually adjust using
         *            {@link DisplayMetrics#density} before calling.
         * @param lookupUri A
         *            {@link ContactsContract.Contacts#CONTENT_LOOKUP_URI} style
         *            {@link Uri} that describes a specific contact to feature
         *            in this dialog. A managed profile lookup uri is supported here,
         *            see {@link CommonDataKinds.Email#ENTERPRISE_CONTENT_LOOKUP_URI} and
         *            {@link PhoneLookup#ENTERPRISE_CONTENT_FILTER_URI}.
         * @param excludeMimes Optional list of {@link Data#MIMETYPE} MIME-types
         *            to exclude when showing this dialog. For example, when
         *            already viewing the contact details card, this can be used
         *            to omit the details entry from the dialog.
         * @param prioritizedMimeType This mimetype should be prioritized in the QuickContacts UI.
         *             For example, passing the value
         *             {@link CommonDataKinds.Phone#CONTENT_ITEM_TYPE} can cause phone numbers to be
         *             displayed more prominently in QuickContacts.
         */
        public static void showQuickContact(Context context, Rect target, Uri lookupUri,
                String[] excludeMimes, String prioritizedMimeType) {
            // Use MODE_LARGE instead of accepting mode as a parameter. The different mode
            // values defined in ContactsContract only affect very old implementations
            // of QuickContacts.
            Intent intent = composeQuickContactsIntent(context, target, lookupUri, MODE_DEFAULT,
                    excludeMimes);
            intent.putExtra(EXTRA_PRIORITIZED_MIMETYPE, prioritizedMimeType);
            ContactsInternal.startQuickContactWithErrorToast(context, intent);
        }
    }

    /**
     * Helper class for accessing full-size photos by photo file ID.
     * <p>
     * Usage example:
     * <dl>
     * <dt>Retrieving a full-size photo by photo file ID (see
     * {@link ContactsContract.ContactsColumns#PHOTO_FILE_ID})
     * </dt>
     * <dd>
     * <pre>
     * public InputStream openDisplayPhoto(long photoFileId) {
     *     Uri displayPhotoUri = ContentUris.withAppendedId(DisplayPhoto.CONTENT_URI, photoKey);
     *     try {
     *         AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(
     *             displayPhotoUri, "r");
     *         return fd.createInputStream();
     *     } catch (IOException e) {
     *         return null;
     *     }
     * }
     * </pre>
     * </dd>
     * </dl>
     * </p>
     */
    public static final class DisplayPhoto {
        /**
         * no public constructor since this is a utility class
         */
        private DisplayPhoto() {}

        /**
         * The content:// style URI for this class, which allows access to full-size photos,
         * given a key.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "display_photo");

        /**
         * This URI allows the caller to query for the maximum dimensions of a display photo
         * or thumbnail.  Requests to this URI can be performed on the UI thread because
         * they are always unblocking.
         */
        public static final Uri CONTENT_MAX_DIMENSIONS_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "photo_dimensions");

        /**
         * Queries to {@link ContactsContract.DisplayPhoto#CONTENT_MAX_DIMENSIONS_URI} will
         * contain this column, populated with the maximum height and width (in pixels)
         * that will be stored for a display photo.  Larger photos will be down-sized to
         * fit within a square of this many pixels.
         */
        public static final String DISPLAY_MAX_DIM = "display_max_dim";

        /**
         * Queries to {@link ContactsContract.DisplayPhoto#CONTENT_MAX_DIMENSIONS_URI} will
         * contain this column, populated with the height and width (in pixels) for photo
         * thumbnails.
         */
        public static final String THUMBNAIL_MAX_DIM = "thumbnail_max_dim";
    }

    /**
     * Contains helper classes used to create or manage {@link android.content.Intent Intents}
     * that involve contacts.
     */
    public static final class Intents {
        /**
         * This is the intent that is fired when a search suggestion is clicked on.
         */
        public static final String SEARCH_SUGGESTION_CLICKED =
                "android.provider.Contacts.SEARCH_SUGGESTION_CLICKED";

        /**
         * This is the intent that is fired when a search suggestion for dialing a number
         * is clicked on.
         */
        public static final String SEARCH_SUGGESTION_DIAL_NUMBER_CLICKED =
                "android.provider.Contacts.SEARCH_SUGGESTION_DIAL_NUMBER_CLICKED";

        /**
         * This is the intent that is fired when a search suggestion for creating a contact
         * is clicked on.
         */
        public static final String SEARCH_SUGGESTION_CREATE_CONTACT_CLICKED =
                "android.provider.Contacts.SEARCH_SUGGESTION_CREATE_CONTACT_CLICKED";

        /**
         * This is the intent that is fired when the contacts database is created. <p> The
         * READ_CONTACT permission is required to receive these broadcasts.
         *
         * <p>Because this is an implicit broadcast, apps targeting Android O will no longer
         * receive this broadcast via a manifest broadcast receiver.  (Broadcast receivers
         * registered at runtime with
         * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)} will still receive it.)
         * Instead, an app can use {@link ProviderStatus#DATABASE_CREATION_TIMESTAMP} to see if the
         * contacts database has been initialized when it starts.
         */
        public static final String CONTACTS_DATABASE_CREATED =
                "android.provider.Contacts.DATABASE_CREATED";

        /**
         * Starts an Activity that lets the user pick a contact to attach an image to.
         * After picking the contact it launches the image cropper in face detection mode.
         */
        public static final String ATTACH_IMAGE =
                "com.android.contacts.action.ATTACH_IMAGE";

        /**
         * This is the intent that is fired when the user clicks the "invite to the network" button
         * on a contact.  Only sent to an activity which is explicitly registered by a contact
         * provider which supports the "invite to the network" feature.
         * <p>
         * {@link Intent#getData()} contains the lookup URI for the contact.
         */
        public static final String INVITE_CONTACT =
                "com.android.contacts.action.INVITE_CONTACT";

        /**
         * Takes as input a data URI with a mailto: or tel: scheme. If a single
         * contact exists with the given data it will be shown. If no contact
         * exists, a dialog will ask the user if they want to create a new
         * contact with the provided details filled in. If multiple contacts
         * share the data the user will be prompted to pick which contact they
         * want to view.
         * <p>
         * For <code>mailto:</code> URIs, the scheme specific portion must be a
         * raw email address, such as one built using
         * {@link Uri#fromParts(String, String, String)}.
         * <p>
         * For <code>tel:</code> URIs, the scheme specific portion is compared
         * to existing numbers using the standard caller ID lookup algorithm.
         * The number must be properly encoded, for example using
         * {@link Uri#fromParts(String, String, String)}.
         * <p>
         * Any extras from the {@link Insert} class will be passed along to the
         * create activity if there are no contacts to show.
         * <p>
         * Passing true for the {@link #EXTRA_FORCE_CREATE} extra will skip
         * prompting the user when the contact doesn't exist.
         */
        public static final String SHOW_OR_CREATE_CONTACT =
                "com.android.contacts.action.SHOW_OR_CREATE_CONTACT";

        /**
         * Activity Action: Initiate a message to someone by voice. The message could be text,
         * audio, video or image(photo). This action supports messaging with a specific contact
         * regardless of the underlying messaging protocol used.
         * <p>
         * The action could be originated from the Voice Assistant as a voice interaction. In such
         * case, a receiving activity that supports {@link android.content.Intent#CATEGORY_VOICE}
         * could check return value of {@link android.app.Activity#isVoiceInteractionRoot} before
         * proceeding. By doing this check the activity verifies that the action indeed was
         * initiated by Voice Assistant and could send a message right away, without any further
         * input from the user. This allows for a smooth user experience when sending a message by
         * voice. Note: this activity must also support the {@link
         * android.content.Intent#CATEGORY_DEFAULT} so it can be found by {@link
         * android.service.voice.VoiceInteractionSession#startVoiceActivity}.
         * <p>
         * When the action was not initiated by Voice Assistant or when the receiving activity does
         * not support {@link android.content.Intent#CATEGORY_VOICE}, the activity must confirm
         * with the user before sending the message (because in this case it is unknown which app
         * sent the intent, it could be malicious).
         * <p>
         * To allow the Voice Assistant to help users with contacts disambiguation, the messaging
         * app may choose to integrate with the Contacts Provider. You will need to specify a new
         * MIME type in order to store your app’s unique contact IDs and optional human readable
         * labels in the Data table. The Voice Assistant needs to know this MIME type and {@link
         * RawContacts#ACCOUNT_TYPE} that you are using in order to provide the smooth contact
         * disambiguation user experience. The following convention should be met when performing
         * such integration:
         * <ul>
         * <li>This activity should have a string meta-data field associated with it, {@link
         * #METADATA_ACCOUNT_TYPE}, which defines {@link RawContacts#ACCOUNT_TYPE} for your Contacts
         * Provider implementation. The account type should be globally unique, for example you can
         * use your app package name as the account type.</li>
         * <li>This activity should have a string meta-data field associated with it, {@link
         * #METADATA_MIMETYPE}, which defines {@link DataColumns#MIMETYPE} for your Contacts
         * Provider implementation. For example, you can use
         * "vnd.android.cursor.item/vnd.{$app_package_name}.profile" as MIME type.</li>
         * <li>When filling Data table row for METADATA_MIMETYPE, column {@link DataColumns#DATA1}
         * should store the unique contact ID as understood by the app. This value will be used in
         * the {@link #EXTRA_RECIPIENT_CONTACT_CHAT_ID}.</li>
         * <li>Optionally, when filling Data table row for METADATA_MIMETYPE, column {@link
         * DataColumns#DATA3} could store a human readable label for the ID. For example it could be
         * phone number or human readable username/user_id like "a_super_cool_user_name". This label
         * may be shown below the Contact Name by the Voice Assistant as the user completes the
         * voice action. If DATA3 is empty, the ID in DATA1 may be shown instead.</li>
         * <li><em>Note: Do not use DATA3 to store the Contact Name. The Voice Assistant will
         * already get the Contact Name from the RawContact’s display_name.</em></li>
         * <li><em>Note: Some apps may choose to use phone number as the unique contact ID in DATA1.
         * If this applies to you and you’d like phone number to be shown below the Contact Name by
         * the Voice Assistant, then you may choose to leave DATA3 empty.</em></li>
         * <li><em>Note: If your app also uses DATA3 to display contact details in the Contacts App,
         * make sure it does not include prefix text such as "Message +<phone>" or "Free Message
         * +<phone>", etc. If you must show the prefix text in the Contacts App, please use a
         * different DATA# column, and update your contacts.xml to point to this new column. </em>
         * </li>
         * </ul>
         * If the app chooses not to integrate with the Contacts Provider (in particular, when
         * either METADATA_ACCOUNT_TYPE or METADATA_MIMETYPE field is missing), Voice Assistant
         * will use existing phone number entries as contact ID's for such app.
         * <p>
         * Input: {@link android.content.Intent#getType} is the MIME type of the data being sent.
         * The intent sender will always put the concrete mime type in the intent type, like
         * "text/plain" or "audio/wav" for example. If the MIME type is "text/plain", message to
         * sent will be provided via {@link android.content.Intent#EXTRA_TEXT} as a styled
         * CharSequence. Otherwise, the message content will be supplied through {@link
         * android.content.Intent#setClipData(ClipData)} as a content provider URI(s). In the latter
         * case, EXTRA_TEXT could still be supplied optionally; for example, for audio messages
         * ClipData will contain URI of a recording and EXTRA_TEXT could contain the text
         * transcription of this recording.
         * <p>
         * The message can have n recipients. The n-th recipient of the message will be provided as
         * n-th elements of {@link #EXTRA_RECIPIENT_CONTACT_URI}, {@link
         * #EXTRA_RECIPIENT_CONTACT_CHAT_ID} and {@link #EXTRA_RECIPIENT_CONTACT_NAME} (as a
         * consequence, EXTRA_RECIPIENT_CONTACT_URI, EXTRA_RECIPIENT_CONTACT_CHAT_ID and
         * EXTRA_RECIPIENT_CONTACT_NAME should all be of length n). If neither of these 3 elements
         * is provided (e.g. all 3 are null) for the recipient or if the information provided is
         * ambiguous then the activity should prompt the user for the recipient to send the message
         * to.
         * <p>
         * Output: nothing
         *
         * @see #EXTRA_RECIPIENT_CONTACT_URI
         * @see #EXTRA_RECIPIENT_CONTACT_CHAT_ID
         * @see #EXTRA_RECIPIENT_CONTACT_NAME
         * @see #METADATA_ACCOUNT_TYPE
         * @see #METADATA_MIMETYPE
         */
        @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_VOICE_SEND_MESSAGE_TO_CONTACTS =
                "android.provider.action.VOICE_SEND_MESSAGE_TO_CONTACTS";

        /**
         * This extra specifies a content provider uri(s) for the contact(s) (if the contacts were
         * located in the Contacts Provider), used with {@link
         * #ACTION_VOICE_SEND_MESSAGE_TO_CONTACTS} to supply the recipient(s). The value of this
         * extra is a {@code String[]}. The number of elements in the array should be equal to
         * number of recipients (and consistent with {@link #EXTRA_RECIPIENT_CONTACT_CHAT_ID} and
         * {@link #EXTRA_RECIPIENT_CONTACT_NAME}). When the value of the element for the particular
         * recipient is absent, it will be set to null.
         * <p>
         * <em>Note: one contact may have multiple accounts (e.g. Chat IDs) on a specific messaging
         * platform, so this may be ambiguous. E.g., one contact “John Smith” could have two
         * accounts on the same messaging app.</em>
         * <p>
         * <em>Example value: {"content://com.android.contacts/contacts/16"}</em>
         */
        public static final String EXTRA_RECIPIENT_CONTACT_URI =
                "android.provider.extra.RECIPIENT_CONTACT_URI";

        /**
         * This extra specifies a messaging app’s unique ID(s) for the contact(s), used with {@link
         * #ACTION_VOICE_SEND_MESSAGE_TO_CONTACTS} to supply the recipient(s). The value of this
         * extra is a {@code String[]}. The number of elements in the array should be equal to
         * number of recipients (and consistent with {@link #EXTRA_RECIPIENT_CONTACT_URI} and {@link
         * #EXTRA_RECIPIENT_CONTACT_NAME}). When the value of the element for the particular
         * recipient is absent, it will be set to null.
         * <p>
         * The value of the elements comes from the {@link DataColumns#DATA1} column in Contacts
         * Provider with {@link DataColumns#MIMETYPE} from {@link #METADATA_MIMETYPE} (if both
         * {@link #METADATA_ACCOUNT_TYPE} and {@link #METADATA_MIMETYPE} are specified by the app;
         * otherwise, the value will be a phone number), and should be the unambiguous contact
         * endpoint. This value is app-specific, it could be some proprietary ID or a phone number.
         */
        public static final String EXTRA_RECIPIENT_CONTACT_CHAT_ID =
                "android.provider.extra.RECIPIENT_CONTACT_CHAT_ID";

        /**
         * This extra specifies the contact name (full name from the Contacts Provider), used with
         * {@link #ACTION_VOICE_SEND_MESSAGE_TO_CONTACTS} to supply the recipient. The value of this
         * extra is a {@code String[]}. The number of elements in the array should be equal to
         * number of recipients (and consistent with {@link #EXTRA_RECIPIENT_CONTACT_URI} and {@link
         * #EXTRA_RECIPIENT_CONTACT_CHAT_ID}). When the value of the element for the particular
         * recipient is absent, it will be set to null.
         * <p>
         * The value of the elements comes from RawContact's display_name column.
         * <p>
         * <em>Example value: {"Jane Doe"}</em>
         */
        public static final String EXTRA_RECIPIENT_CONTACT_NAME =
                "android.provider.extra.RECIPIENT_CONTACT_NAME";

        /**
         * A string associated with an {@link #ACTION_VOICE_SEND_MESSAGE_TO_CONTACTS} activity
         * describing {@link RawContacts#ACCOUNT_TYPE} for the corresponding Contacts Provider
         * implementation.
         */
        public static final String METADATA_ACCOUNT_TYPE = "android.provider.account_type";

        /**
         * A string associated with an {@link #ACTION_VOICE_SEND_MESSAGE_TO_CONTACTS} activity
         * describing {@link DataColumns#MIMETYPE} for the corresponding Contacts Provider
         * implementation.
         */
        public static final String METADATA_MIMETYPE = "android.provider.mimetype";

        /**
         * Starts an Activity that lets the user select the multiple phones from a
         * list of phone numbers which come from the contacts or
         * {@link #EXTRA_PHONE_URIS}.
         * <p>
         * The phone numbers being passed in through {@link #EXTRA_PHONE_URIS}
         * could belong to the contacts or not, and will be selected by default.
         * <p>
         * The user's selection will be returned from
         * {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}
         * if the resultCode is
         * {@link android.app.Activity#RESULT_OK}, the array of picked phone
         * numbers are in the Intent's
         * {@link #EXTRA_PHONE_URIS}; otherwise, the
         * {@link android.app.Activity#RESULT_CANCELED} is returned if the user
         * left the Activity without changing the selection.
         *
         * @hide
         */
        public static final String ACTION_GET_MULTIPLE_PHONES =
                "com.android.contacts.action.GET_MULTIPLE_PHONES";

        /**
         * A broadcast action which is sent when any change has been made to the profile, such
         * as the profile name or the picture.  A receiver must have
         * the android.permission.READ_PROFILE permission.
         *
         * @hide
         */
        public static final String ACTION_PROFILE_CHANGED =
                "android.provider.Contacts.PROFILE_CHANGED";

        /**
         * Used with {@link #SHOW_OR_CREATE_CONTACT} to force creating a new
         * contact if no matching contact found. Otherwise, default behavior is
         * to prompt user with dialog before creating.
         * <p>
         * Type: BOOLEAN
         */
        public static final String EXTRA_FORCE_CREATE =
                "com.android.contacts.action.FORCE_CREATE";

        /**
         * Used with {@link #SHOW_OR_CREATE_CONTACT} to specify an exact
         * description to be shown when prompting user about creating a new
         * contact.
         * <p>
         * Type: STRING
         */
        public static final String EXTRA_CREATE_DESCRIPTION =
            "com.android.contacts.action.CREATE_DESCRIPTION";

        /**
         * Used with {@link #ACTION_GET_MULTIPLE_PHONES} as the input or output value.
         * <p>
         * The phone numbers want to be picked by default should be passed in as
         * input value. These phone numbers could belong to the contacts or not.
         * <p>
         * The phone numbers which were picked by the user are returned as output
         * value.
         * <p>
         * Type: array of URIs, the tel URI is used for the phone numbers which don't
         * belong to any contact, the content URI is used for phone id in contacts.
         *
         * @hide
         */
        public static final String EXTRA_PHONE_URIS =
            "com.android.contacts.extra.PHONE_URIS";

        /**
         * Optional extra used with {@link #SHOW_OR_CREATE_CONTACT} to specify a
         * dialog location using screen coordinates. When not specified, the
         * dialog will be centered.
         *
         * @hide
         */
        @Deprecated
        public static final String EXTRA_TARGET_RECT = "target_rect";

        /**
         * Optional extra used with {@link #SHOW_OR_CREATE_CONTACT} to specify a
         * desired dialog style, usually a variation on size. One of
         * {@link #MODE_SMALL}, {@link #MODE_MEDIUM}, or {@link #MODE_LARGE}.
         *
         * @hide
         */
        @Deprecated
        public static final String EXTRA_MODE = "mode";

        /**
         * Value for {@link #EXTRA_MODE} to show a small-sized dialog.
         *
         * @hide
         */
        @Deprecated
        public static final int MODE_SMALL = 1;

        /**
         * Value for {@link #EXTRA_MODE} to show a medium-sized dialog.
         *
         * @hide
         */
        @Deprecated
        public static final int MODE_MEDIUM = 2;

        /**
         * Value for {@link #EXTRA_MODE} to show a large-sized dialog.
         *
         * @hide
         */
        @Deprecated
        public static final int MODE_LARGE = 3;

        /**
         * Optional extra used with {@link #SHOW_OR_CREATE_CONTACT} to indicate
         * a list of specific MIME-types to exclude and not display. Stored as a
         * {@link String} array.
         *
         * @hide
         */
        @Deprecated
        public static final String EXTRA_EXCLUDE_MIMES = "exclude_mimes";

        /**
         * Convenience class that contains string constants used
         * to create contact {@link android.content.Intent Intents}.
         */
        public static final class Insert {
            /** The action code to use when adding a contact */
            public static final String ACTION = Intent.ACTION_INSERT;

            /**
             * If present, forces a bypass of quick insert mode.
             */
            public static final String FULL_MODE = "full_mode";

            /**
             * The extra field for the contact name.
             * <P>Type: String</P>
             */
            public static final String NAME = "name";

            // TODO add structured name values here.

            /**
             * The extra field for the contact phonetic name.
             * <P>Type: String</P>
             */
            public static final String PHONETIC_NAME = "phonetic_name";

            /**
             * The extra field for the contact company.
             * <P>Type: String</P>
             */
            public static final String COMPANY = "company";

            /**
             * The extra field for the contact job title.
             * <P>Type: String</P>
             */
            public static final String JOB_TITLE = "job_title";

            /**
             * The extra field for the contact notes.
             * <P>Type: String</P>
             */
            public static final String NOTES = "notes";

            /**
             * The extra field for the contact phone number.
             * <P>Type: String</P>
             */
            public static final String PHONE = "phone";

            /**
             * The extra field for the contact phone number type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Phone},
             *  or a string specifying a custom label.</P>
             */
            public static final String PHONE_TYPE = "phone_type";

            /**
             * The extra field for the phone isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String PHONE_ISPRIMARY = "phone_isprimary";

            /**
             * The extra field for an optional second contact phone number.
             * <P>Type: String</P>
             */
            public static final String SECONDARY_PHONE = "secondary_phone";

            /**
             * The extra field for an optional second contact phone number type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Phone},
             *  or a string specifying a custom label.</P>
             */
            public static final String SECONDARY_PHONE_TYPE = "secondary_phone_type";

            /**
             * The extra field for an optional third contact phone number.
             * <P>Type: String</P>
             */
            public static final String TERTIARY_PHONE = "tertiary_phone";

            /**
             * The extra field for an optional third contact phone number type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Phone},
             *  or a string specifying a custom label.</P>
             */
            public static final String TERTIARY_PHONE_TYPE = "tertiary_phone_type";

            /**
             * The extra field for the contact email address.
             * <P>Type: String</P>
             */
            public static final String EMAIL = "email";

            /**
             * The extra field for the contact email type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Email}
             *  or a string specifying a custom label.</P>
             */
            public static final String EMAIL_TYPE = "email_type";

            /**
             * The extra field for the email isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String EMAIL_ISPRIMARY = "email_isprimary";

            /**
             * The extra field for an optional second contact email address.
             * <P>Type: String</P>
             */
            public static final String SECONDARY_EMAIL = "secondary_email";

            /**
             * The extra field for an optional second contact email type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Email}
             *  or a string specifying a custom label.</P>
             */
            public static final String SECONDARY_EMAIL_TYPE = "secondary_email_type";

            /**
             * The extra field for an optional third contact email address.
             * <P>Type: String</P>
             */
            public static final String TERTIARY_EMAIL = "tertiary_email";

            /**
             * The extra field for an optional third contact email type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Email}
             *  or a string specifying a custom label.</P>
             */
            public static final String TERTIARY_EMAIL_TYPE = "tertiary_email_type";

            /**
             * The extra field for the contact postal address.
             * <P>Type: String</P>
             */
            public static final String POSTAL = "postal";

            /**
             * The extra field for the contact postal address type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.StructuredPostal}
             *  or a string specifying a custom label.</P>
             */
            public static final String POSTAL_TYPE = "postal_type";

            /**
             * The extra field for the postal isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String POSTAL_ISPRIMARY = "postal_isprimary";

            /**
             * The extra field for an IM handle.
             * <P>Type: String</P>
             */
            public static final String IM_HANDLE = "im_handle";

            /**
             * The extra field for the IM protocol
             */
            public static final String IM_PROTOCOL = "im_protocol";

            /**
             * The extra field for the IM isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String IM_ISPRIMARY = "im_isprimary";

            /**
             * The extra field that allows the client to supply multiple rows of
             * arbitrary data for a single contact created using the {@link Intent#ACTION_INSERT}
             * or edited using {@link Intent#ACTION_EDIT}. It is an ArrayList of
             * {@link ContentValues}, one per data row. Supplying this extra is
             * similar to inserting multiple rows into the {@link Data} table,
             * except the user gets a chance to see and edit them before saving.
             * Each ContentValues object must have a value for {@link Data#MIMETYPE}.
             * If supplied values are not visible in the editor UI, they will be
             * dropped.  Duplicate data will dropped.  Some fields
             * like {@link CommonDataKinds.Email#TYPE Email.TYPE} may be automatically
             * adjusted to comply with the constraints of the specific account type.
             * For example, an Exchange contact can only have one phone numbers of type Home,
             * so the contact editor may choose a different type for this phone number to
             * avoid dropping the valueable part of the row, which is the phone number.
             * <p>
             * Example:
             * <pre>
             *  ArrayList&lt;ContentValues&gt; data = new ArrayList&lt;ContentValues&gt;();
             *
             *  ContentValues row1 = new ContentValues();
             *  row1.put(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE);
             *  row1.put(Organization.COMPANY, "Android");
             *  data.add(row1);
             *
             *  ContentValues row2 = new ContentValues();
             *  row2.put(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE);
             *  row2.put(Email.TYPE, Email.TYPE_CUSTOM);
             *  row2.put(Email.LABEL, "Green Bot");
             *  row2.put(Email.ADDRESS, "android@android.com");
             *  data.add(row2);
             *
             *  Intent intent = new Intent(Intent.ACTION_INSERT, Contacts.CONTENT_URI);
             *  intent.putParcelableArrayListExtra(Insert.DATA, data);
             *
             *  startActivity(intent);
             * </pre>
             */
            public static final String DATA = "data";

            /**
             * Used to specify the account in which to create the new contact.
             * <p>
             * If this value is not provided, the user is presented with a disambiguation
             * dialog to chose an account
             * <p>
             * Type: {@link Account}
             */
            public static final String EXTRA_ACCOUNT = "android.provider.extra.ACCOUNT";

            /**
             * Used to specify the data set within the account in which to create the
             * new contact.
             * <p>
             * This value is optional - if it is not specified, the contact will be
             * created in the base account, with no data set.
             * <p>
             * Type: String
             */
            public static final String EXTRA_DATA_SET = "android.provider.extra.DATA_SET";
        }
    }

    /**
     * @hide
     * @deprecated These columns were never public since added. They will not be supported
     * as of Android version {@link android.os.Build.VERSION_CODES#R}.
     */
    @Deprecated
    @SystemApi
    protected interface MetadataSyncColumns {

        /**
         * The raw contact backup id.
         * A reference to the {@link ContactsContract.RawContacts#BACKUP_ID} that save the
         * persistent unique id for each raw contact within its source system.
         */
        public static final String RAW_CONTACT_BACKUP_ID = "raw_contact_backup_id";

        /**
         * The account type to which the raw_contact of this item is associated. See
         * {@link RawContacts#ACCOUNT_TYPE}
         */
        public static final String ACCOUNT_TYPE = "account_type";

        /**
         * The account name to which the raw_contact of this item is associated. See
         * {@link RawContacts#ACCOUNT_NAME}
         */
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * The data set within the account that the raw_contact of this row belongs to. This allows
         * multiple sync adapters for the same account type to distinguish between
         * each others' data.
         * {@link RawContacts#DATA_SET}
         */
        public static final String DATA_SET = "data_set";

        /**
         * A text column contains the Json string got from People API. The Json string contains
         * all the metadata related to the raw contact, i.e., all the data fields and
         * aggregation exceptions.
         *
         * Here is an example of the Json string got from the actual schema.
         * <pre>
         *     {
         *       "unique_contact_id": {
         *         "account_type": "CUSTOM_ACCOUNT",
         *         "custom_account_type": "facebook",
         *         "account_name": "android-test",
         *         "contact_id": "1111111",
         *         "data_set": "FOCUS"
         *       },
         *       "contact_prefs": {
         *         "send_to_voicemail": true,
         *         "starred": false,
         *         "pinned": 2
         *       },
         *       "aggregation_data": [
         *         {
         *           "type": "TOGETHER",
         *           "contact_ids": [
         *             {
         *               "account_type": "GOOGLE_ACCOUNT",
         *               "account_name": "android-test2",
         *               "contact_id": "2222222",
         *               "data_set": "GOOGLE_PLUS"
         *             },
         *             {
         *               "account_type": "GOOGLE_ACCOUNT",
         *               "account_name": "android-test3",
         *               "contact_id": "3333333",
         *               "data_set": "CUSTOM",
         *               "custom_data_set": "custom type"
         *             }
         *           ]
         *         }
         *       ],
         *       "field_data": [
         *         {
         *           "field_data_id": "1001",
         *           "field_data_prefs": {
         *             "is_primary": true,
         *             "is_super_primary": true
         *           },
         *           "usage_stats": [
         *             {
         *               "usage_type": "CALL",
         *               "last_time_used": 10000001,
         *               "usage_count": 10
         *             }
         *           ]
         *         }
         *       ]
         *     }
         * </pre>
         */
        public static final String DATA = "data";

        /**
         * The "deleted" flag: "0" by default, "1" if the row has been marked
         * for deletion. When {@link android.content.ContentResolver#delete} is
         * called on a raw contact, updating MetadataSync table to set the flag of the raw contact
         * as "1", then metadata sync adapter deletes the raw contact metadata on the server.
         * <P>Type: INTEGER</P>
         */
        public static final String DELETED = "deleted";
    }

    /**
     * Constants for the metadata sync table. This table is used to cache the metadata_sync data
     * from server before it is merged into other CP2 tables.
     *
     * @hide
     * @deprecated These columns were never public since added. They will not be supported
     * as of Android version {@link android.os.Build.VERSION_CODES#R}.
     */
    @Deprecated
    @SystemApi
    public static final class MetadataSync implements BaseColumns, MetadataSyncColumns {

        /** The authority for the contacts metadata */
        public static final String METADATA_AUTHORITY = "com.android.contacts.metadata";

        /** A content:// style uri to the authority for the contacts metadata */
        public static final Uri METADATA_AUTHORITY_URI = Uri.parse(
                "content://" + METADATA_AUTHORITY);

        /**
         * This utility class cannot be instantiated
         */
        private MetadataSync() {
        }

        /**
         * The content:// style URI for this table.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(METADATA_AUTHORITY_URI,
                "metadata_sync");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of contact metadata
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/contact_metadata";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single contact metadata.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contact_metadata";
    }

    /**
     * @hide
     * @deprecated These columns are no longer supported as of Android version
     * {@link android.os.Build.VERSION_CODES#R}.
     */
    @Deprecated
    @SystemApi
    protected interface MetadataSyncStateColumns {

        /**
         * A reference to the name of the account to which this state belongs
         * <P>Type: STRING</P>
         */
        public static final String ACCOUNT_TYPE = "account_type";

        /**
         * A reference to the type of the account to which this state belongs
         * <P>Type: STRING</P>
         */
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * A reference to the data set within the account to which this state belongs
         * <P>Type: STRING</P>
         */
        public static final String DATA_SET = "data_set";

        /**
         * The sync state associated with this account.
         * <P>Type: Blob</P>
         */
        public static final String STATE = "state";
    }

    /**
     * Constants for the metadata_sync_state table. This table is used to store the metadata
     * sync state for a set of accounts.
     *
     * @hide
     * @deprecated These columns are no longer supported as of Android version
     * {@link android.os.Build.VERSION_CODES#R}.
     */
    @Deprecated
    @SystemApi
    public static final class MetadataSyncState implements BaseColumns, MetadataSyncStateColumns {

        /**
         * This utility class cannot be instantiated
         */
        private MetadataSyncState() {
        }

        /**
         * The content:// style URI for this table.
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(MetadataSync.METADATA_AUTHORITY_URI, "metadata_sync_state");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of contact metadata sync
         * states.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/contact_metadata_sync_state";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single contact metadata sync
         * state.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/contact_metadata_sync_state";
    }

    private static Bundle nullSafeCall(@NonNull ContentResolver resolver, @NonNull Uri uri,
            @NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        try (ContentProviderClient client = resolver.acquireContentProviderClient(uri)) {
            return client.call(method, arg, extras);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }
}
