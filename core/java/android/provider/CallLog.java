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

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.location.Country;
import android.location.CountryDetector;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract.CommonDataKinds.Callable;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.DataUsageFeedback;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.PhoneConstants;

import java.util.List;

/**
 * The CallLog provider contains information about placed and received calls.
 */
public class CallLog {
    private static final String LOG_TAG = "CallLog";

    public static final String AUTHORITY = "call_log";

    /**
     * The content:// style URL for this provider
     */
    public static final Uri CONTENT_URI =
        Uri.parse("content://" + AUTHORITY);

    /**
     * Contains the recent calls.
     */
    public static class Calls implements BaseColumns {
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://call_log/calls");

        /**
         * The content:// style URL for filtering this table on phone numbers
         */
        public static final Uri CONTENT_FILTER_URI =
                Uri.parse("content://call_log/calls/filter");

        /**
         * Query parameter used to limit the number of call logs returned.
         * <p>
         * TYPE: integer
         */
        public static final String LIMIT_PARAM_KEY = "limit";

        /**
         * Query parameter used to specify the starting record to return.
         * <p>
         * TYPE: integer
         */
        public static final String OFFSET_PARAM_KEY = "offset";

        /**
         * An optional URI parameter which instructs the provider to allow the operation to be
         * applied to voicemail records as well.
         * <p>
         * TYPE: Boolean
         * <p>
         * Using this parameter with a value of {@code true} will result in a security error if the
         * calling package does not have appropriate permissions to access voicemails.
         *
         * @hide
         */
        public static final String ALLOW_VOICEMAILS_PARAM_KEY = "allow_voicemails";

        /**
         * An optional extra used with {@link #CONTENT_TYPE Calls.CONTENT_TYPE} and
         * {@link Intent#ACTION_VIEW} to specify that the presented list of calls should be
         * filtered for a particular call type.
         *
         * Applications implementing a call log UI should check for this extra, and display a
         * filtered list of calls based on the specified call type. If not applicable within the
         * application's UI, it should be silently ignored.
         *
         * <p>
         * The following example brings up the call log, showing only missed calls.
         * <pre>
         * Intent intent = new Intent(Intent.ACTION_VIEW);
         * intent.setType(CallLog.Calls.CONTENT_TYPE);
         * intent.putExtra(CallLog.Calls.EXTRA_CALL_TYPE_FILTER, CallLog.Calls.MISSED_TYPE);
         * startActivity(intent);
         * </pre>
         * </p>
         */
        public static final String EXTRA_CALL_TYPE_FILTER =
                "android.provider.extra.CALL_TYPE_FILTER";

        /**
         * Content uri used to access call log entries, including voicemail records. You must have
         * the READ_CALL_LOG and WRITE_CALL_LOG permissions to read and write to the call log, as
         * well as READ_VOICEMAIL and WRITE_VOICEMAIL permissions to read and write voicemails.
         */
        public static final Uri CONTENT_URI_WITH_VOICEMAIL = CONTENT_URI.buildUpon()
                .appendQueryParameter(ALLOW_VOICEMAILS_PARAM_KEY, "true")
                .build();

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "date DESC";

        /**
         * The MIME type of {@link #CONTENT_URI} and {@link #CONTENT_FILTER_URI}
         * providing a directory of calls.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/calls";

        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single
         * call.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/calls";

        /**
         * The type of the call (incoming, outgoing or missed).
         * <P>Type: INTEGER (int)</P>
         */
        public static final String TYPE = "type";

        /** Call log type for incoming calls. */
        public static final int INCOMING_TYPE = 1;
        /** Call log type for outgoing calls. */
        public static final int OUTGOING_TYPE = 2;
        /** Call log type for missed calls. */
        public static final int MISSED_TYPE = 3;
        /** Call log type for voicemails. */
        public static final int VOICEMAIL_TYPE = 4;

        /**
         * Bit-mask describing features of the call (e.g. video).
         *
         * <P>Type: INTEGER (int)</P>
         */
        public static final String FEATURES = "features";

        /** Call had video. */
        public static final int FEATURES_VIDEO = 0x1;

        /**
         * The phone number as the user entered it.
         * <P>Type: TEXT</P>
         */
        public static final String NUMBER = "number";

        /**
         * The number presenting rules set by the network.
         *
         * <p>
         * Allowed values:
         * <ul>
         * <li>{@link #PRESENTATION_ALLOWED}</li>
         * <li>{@link #PRESENTATION_RESTRICTED}</li>
         * <li>{@link #PRESENTATION_UNKNOWN}</li>
         * <li>{@link #PRESENTATION_PAYPHONE}</li>
         * </ul>
         * </p>
         *
         * <P>Type: INTEGER</P>
         */
        public static final String NUMBER_PRESENTATION = "presentation";

        /** Number is allowed to display for caller id. */
        public static final int PRESENTATION_ALLOWED = 1;
        /** Number is blocked by user. */
        public static final int PRESENTATION_RESTRICTED = 2;
        /** Number is not specified or unknown by network. */
        public static final int PRESENTATION_UNKNOWN = 3;
        /** Number is a pay phone. */
        public static final int PRESENTATION_PAYPHONE = 4;

        /**
         * The ISO 3166-1 two letters country code of the country where the
         * user received or made the call.
         * <P>
         * Type: TEXT
         * </P>
         */
        public static final String COUNTRY_ISO = "countryiso";

        /**
         * The date the call occured, in milliseconds since the epoch
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE = "date";

        /**
         * The duration of the call in seconds
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DURATION = "duration";

        /**
         * The data usage of the call in bytes.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATA_USAGE = "data_usage";

        /**
         * Whether or not the call has been acknowledged
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String NEW = "new";

        /**
         * The cached name associated with the phone number, if it exists.
         * This value is not guaranteed to be current, if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_NAME = "name";

        /**
         * The cached number type (Home, Work, etc) associated with the
         * phone number, if it exists.
         * This value is not guaranteed to be current, if the contact information
         * associated with this number has changed.
         * <P>Type: INTEGER</P>
         */
        public static final String CACHED_NUMBER_TYPE = "numbertype";

        /**
         * The cached number label, for a custom number type, associated with the
         * phone number, if it exists.
         * This value is not guaranteed to be current, if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_NUMBER_LABEL = "numberlabel";

        /**
         * URI of the voicemail entry. Populated only for {@link #VOICEMAIL_TYPE}.
         * <P>Type: TEXT</P>
         */
        public static final String VOICEMAIL_URI = "voicemail_uri";

        /**
         * Transcription of the call or voicemail entry. This will only be populated for call log
         * entries of type {@link #VOICEMAIL_TYPE} that have valid transcriptions.
         */
        public static final String TRANSCRIPTION = "transcription";

        /**
         * Whether this item has been read or otherwise consumed by the user.
         * <p>
         * Unlike the {@link #NEW} field, which requires the user to have acknowledged the
         * existence of the entry, this implies the user has interacted with the entry.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String IS_READ = "is_read";

        /**
         * A geocoded location for the number associated with this call.
         * <p>
         * The string represents a city, state, or country associated with the number.
         * <P>Type: TEXT</P>
         */
        public static final String GEOCODED_LOCATION = "geocoded_location";

        /**
         * The cached URI to look up the contact associated with the phone number, if it exists.
         * This value may not be current if the contact information associated with this number
         * has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_LOOKUP_URI = "lookup_uri";

        /**
         * The cached phone number of the contact which matches this entry, if it exists.
         * This value may not be current if the contact information associated with this number
         * has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_MATCHED_NUMBER = "matched_number";

        /**
         * The cached normalized(E164) version of the phone number, if it exists.
         * This value may not be current if the contact information associated with this number
         * has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_NORMALIZED_NUMBER = "normalized_number";

        /**
         * The cached photo id of the picture associated with the phone number, if it exists.
         * This value may not be current if the contact information associated with this number
         * has changed.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String CACHED_PHOTO_ID = "photo_id";

        /**
         * The cached photo URI of the picture associated with the phone number, if it exists.
         * This value may not be current if the contact information associated with this number
         * has changed.
         * <P>Type: TEXT (URI)</P>
         */
        public static final String CACHED_PHOTO_URI = "photo_uri";

        /**
         * The cached phone number, formatted with formatting rules based on the country the
         * user was in when the call was made or received.
         * This value is not guaranteed to be present, and may not be current if the contact
         * information associated with this number
         * has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_FORMATTED_NUMBER = "formatted_number";

        // Note: PHONE_ACCOUNT_* constant values are "subscription_*" due to a historic naming
        // that was encoded into call log databases.

        /**
         * The component name of the account used to place or receive the call; in string form.
         * <P>Type: TEXT</P>
         */
        public static final String PHONE_ACCOUNT_COMPONENT_NAME = "subscription_component_name";

        /**
         * The identifier for the account used to place or receive the call.
         * <P>Type: TEXT</P>
         */
        public static final String PHONE_ACCOUNT_ID = "subscription_id";

        /**
         * The address associated with the account used to place or receive the call; in string
         * form. For SIM-based calls, this is the user's own phone number.
         * <P>Type: TEXT</P>
         *
         * @hide
         */
        public static final String PHONE_ACCOUNT_ADDRESS = "phone_account_address";

        /**
         * Indicates that the entry will be hidden from all queries until the associated
         * {@link android.telecom.PhoneAccount} is registered with the system.
         * <P>Type: INTEGER</P>
         *
         * @hide
         */
        public static final String PHONE_ACCOUNT_HIDDEN = "phone_account_hidden";

        /**
         * The subscription ID used to place this call.  This is no longer used and has been
         * replaced with PHONE_ACCOUNT_COMPONENT_NAME/PHONE_ACCOUNT_ID.
         * For ContactsProvider internal use only.
         * <P>Type: INTEGER</P>
         *
         * @Deprecated
         * @hide
         */
        public static final String SUB_ID = "sub_id";

        /**
         * If a successful call is made that is longer than this duration, update the phone number
         * in the ContactsProvider with the normalized version of the number, based on the user's
         * current country code.
         */
        private static final int MIN_DURATION_FOR_NORMALIZED_NUMBER_UPDATE_MS = 1000 * 10;

        /**
         * Adds a call to the call log.
         *
         * @param ci the CallerInfo object to get the target contact from.  Can be null
         * if the contact is unknown.
         * @param context the context used to get the ContentResolver
         * @param number the phone number to be added to the calls db
         * @param presentation enum value from PhoneConstants.PRESENTATION_xxx, which
         *        is set by the network and denotes the number presenting rules for
         *        "allowed", "payphone", "restricted" or "unknown"
         * @param callType enumerated values for "incoming", "outgoing", or "missed"
         * @param features features of the call (e.g. Video).
         * @param accountHandle The accountHandle object identifying the provider of the call
         * @param start time stamp for the call in milliseconds
         * @param duration call duration in seconds
         * @param dataUsage data usage for the call in bytes, null if data usage was not tracked for
         *                  the call.
         * @result The URI of the call log entry belonging to the user that made or received this
         *        call.
         * {@hide}
         */
        public static Uri addCall(CallerInfo ci, Context context, String number,
                int presentation, int callType, int features, PhoneAccountHandle accountHandle,
                long start, int duration, Long dataUsage) {
            return addCall(ci, context, number, presentation, callType, features, accountHandle,
                    start, duration, dataUsage, false, false);
        }


        /**
         * Adds a call to the call log.
         *
         * @param ci the CallerInfo object to get the target contact from.  Can be null
         * if the contact is unknown.
         * @param context the context used to get the ContentResolver
         * @param number the phone number to be added to the calls db
         * @param presentation enum value from PhoneConstants.PRESENTATION_xxx, which
         *        is set by the network and denotes the number presenting rules for
         *        "allowed", "payphone", "restricted" or "unknown"
         * @param callType enumerated values for "incoming", "outgoing", or "missed"
         * @param features features of the call (e.g. Video).
         * @param accountHandle The accountHandle object identifying the provider of the call
         * @param start time stamp for the call in milliseconds
         * @param duration call duration in seconds
         * @param dataUsage data usage for the call in bytes, null if data usage was not tracked for
         *                  the call.
         * @param addForAllUsers If true, the call is added to the call log of all currently
         *        running users. The caller must have the MANAGE_USERS permission if this is true.
         *
         * @result The URI of the call log entry belonging to the user that made or received this
         *        call.
         * {@hide}
         */
        public static Uri addCall(CallerInfo ci, Context context, String number,
                                  int presentation, int callType, int features, PhoneAccountHandle accountHandle,
                                  long start, int duration, Long dataUsage, boolean addForAllUsers) {
            return addCall(ci, context, number, presentation, callType, features, accountHandle,
                    start, duration, dataUsage, addForAllUsers, false);
        }

        /**
         * Adds a call to the call log.
         *
         * @param ci the CallerInfo object to get the target contact from.  Can be null
         * if the contact is unknown.
         * @param context the context used to get the ContentResolver
         * @param number the phone number to be added to the calls db
         * @param presentation enum value from PhoneConstants.PRESENTATION_xxx, which
         *        is set by the network and denotes the number presenting rules for
         *        "allowed", "payphone", "restricted" or "unknown"
         * @param callType enumerated values for "incoming", "outgoing", or "missed"
         * @param features features of the call (e.g. Video).
         * @param accountHandle The accountHandle object identifying the provider of the call
         * @param start time stamp for the call in milliseconds
         * @param duration call duration in seconds
         * @param dataUsage data usage for the call in bytes, null if data usage was not tracked for
         *                  the call.
         * @param addForAllUsers If true, the call is added to the call log of all currently
         *        running users. The caller must have the MANAGE_USERS permission if this is true.
         * @param is_read Flag to show if the missed call log has been read by the user or not.
         *                Used for call log restore of missed calls.
         *
         * @result The URI of the call log entry belonging to the user that made or received this
         *        call.
         * {@hide}
         */
        public static Uri addCall(CallerInfo ci, Context context, String number,
                int presentation, int callType, int features, PhoneAccountHandle accountHandle,
                long start, int duration, Long dataUsage, boolean addForAllUsers, boolean is_read) {
            final ContentResolver resolver = context.getContentResolver();
            int numberPresentation = PRESENTATION_ALLOWED;

            TelecomManager tm = null;
            try {
                tm = TelecomManager.from(context);
            } catch (UnsupportedOperationException e) {}

            String accountAddress = null;
            if (tm != null && accountHandle != null) {
                PhoneAccount account = tm.getPhoneAccount(accountHandle);
                if (account != null) {
                    Uri address = account.getSubscriptionAddress();
                    if (address != null) {
                        accountAddress = address.getSchemeSpecificPart();
                    }
                }
            }

            // Remap network specified number presentation types
            // PhoneConstants.PRESENTATION_xxx to calllog number presentation types
            // Calls.PRESENTATION_xxx, in order to insulate the persistent calllog
            // from any future radio changes.
            // If the number field is empty set the presentation type to Unknown.
            if (presentation == PhoneConstants.PRESENTATION_RESTRICTED) {
                numberPresentation = PRESENTATION_RESTRICTED;
            } else if (presentation == PhoneConstants.PRESENTATION_PAYPHONE) {
                numberPresentation = PRESENTATION_PAYPHONE;
            } else if (TextUtils.isEmpty(number)
                    || presentation == PhoneConstants.PRESENTATION_UNKNOWN) {
                numberPresentation = PRESENTATION_UNKNOWN;
            }
            if (numberPresentation != PRESENTATION_ALLOWED) {
                number = "";
                if (ci != null) {
                    ci.name = "";
                }
            }

            // accountHandle information
            String accountComponentString = null;
            String accountId = null;
            if (accountHandle != null) {
                accountComponentString = accountHandle.getComponentName().flattenToString();
                accountId = accountHandle.getId();
            }

            ContentValues values = new ContentValues(6);

            values.put(NUMBER, number);
            values.put(NUMBER_PRESENTATION, Integer.valueOf(numberPresentation));
            values.put(TYPE, Integer.valueOf(callType));
            values.put(FEATURES, features);
            values.put(DATE, Long.valueOf(start));
            values.put(DURATION, Long.valueOf(duration));
            if (dataUsage != null) {
                values.put(DATA_USAGE, dataUsage);
            }
            values.put(PHONE_ACCOUNT_COMPONENT_NAME, accountComponentString);
            values.put(PHONE_ACCOUNT_ID, accountId);
            values.put(PHONE_ACCOUNT_ADDRESS, accountAddress);
            values.put(NEW, Integer.valueOf(1));

            if (callType == MISSED_TYPE) {
                values.put(IS_READ, Integer.valueOf(is_read ? 1 : 0));
            }

            if ((ci != null) && (ci.contactIdOrZero > 0)) {
                // Update usage information for the number associated with the contact ID.
                // We need to use both the number and the ID for obtaining a data ID since other
                // contacts may have the same number.

                final Cursor cursor;

                // We should prefer normalized one (probably coming from
                // Phone.NORMALIZED_NUMBER column) first. If it isn't available try others.
                if (ci.normalizedNumber != null) {
                    final String normalizedPhoneNumber = ci.normalizedNumber;
                    cursor = resolver.query(Phone.CONTENT_URI,
                            new String[] { Phone._ID },
                            Phone.CONTACT_ID + " =? AND " + Phone.NORMALIZED_NUMBER + " =?",
                            new String[] { String.valueOf(ci.contactIdOrZero),
                                    normalizedPhoneNumber},
                            null);
                } else {
                    final String phoneNumber = ci.phoneNumber != null ? ci.phoneNumber : number;
                    cursor = resolver.query(
                            Uri.withAppendedPath(Callable.CONTENT_FILTER_URI,
                                    Uri.encode(phoneNumber)),
                            new String[] { Phone._ID },
                            Phone.CONTACT_ID + " =?",
                            new String[] { String.valueOf(ci.contactIdOrZero) },
                            null);
                }

                if (cursor != null) {
                    try {
                        if (cursor.getCount() > 0 && cursor.moveToFirst()) {
                            final String dataId = cursor.getString(0);
                            updateDataUsageStatForData(resolver, dataId);
                            if (duration >= MIN_DURATION_FOR_NORMALIZED_NUMBER_UPDATE_MS
                                    && callType == Calls.OUTGOING_TYPE
                                    && TextUtils.isEmpty(ci.normalizedNumber)) {
                                updateNormalizedNumber(context, resolver, dataId, number);
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
            }

            Uri result = null;

            if (addForAllUsers) {
                // Insert the entry for all currently running users, in order to trigger any
                // ContentObservers currently set on the call log.
                final UserManager userManager = (UserManager) context.getSystemService(
                        Context.USER_SERVICE);
                List<UserInfo> users = userManager.getUsers(true);
                final int currentUserId = userManager.getUserHandle();
                final int count = users.size();
                for (int i = 0; i < count; i++) {
                    final UserInfo user = users.get(i);
                    final UserHandle userHandle = user.getUserHandle();
                    if (userManager.isUserRunning(userHandle)
                            && !userManager.hasUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS,
                                    userHandle)
                            && !user.isManagedProfile()) {
                        Uri uri = addEntryAndRemoveExpiredEntries(context,
                                ContentProvider.maybeAddUserId(CONTENT_URI, user.id), values);
                        if (user.id == currentUserId) {
                            result = uri;
                        }
                    }
                }
            } else {
                result = addEntryAndRemoveExpiredEntries(context, CONTENT_URI, values);
            }

            return result;
        }

        /**
         * Query the call log database for the last dialed number.
         * @param context Used to get the content resolver.
         * @return The last phone number dialed (outgoing) or an empty
         * string if none exist yet.
         */
        public static String getLastOutgoingCall(Context context) {
            final ContentResolver resolver = context.getContentResolver();
            Cursor c = null;
            try {
                c = resolver.query(
                    CONTENT_URI,
                    new String[] {NUMBER},
                    TYPE + " = " + OUTGOING_TYPE,
                    null,
                    DEFAULT_SORT_ORDER + " LIMIT 1");
                if (c == null || !c.moveToFirst()) {
                    return "";
                }
                return c.getString(0);
            } finally {
                if (c != null) c.close();
            }
        }

        private static Uri addEntryAndRemoveExpiredEntries(Context context, Uri uri,
                ContentValues values) {
            final ContentResolver resolver = context.getContentResolver();
            Uri result = resolver.insert(uri, values);
            resolver.delete(uri, "_id IN " +
                    "(SELECT _id FROM calls ORDER BY " + DEFAULT_SORT_ORDER
                    + " LIMIT -1 OFFSET 500)", null);
            return result;
        }

        private static void updateDataUsageStatForData(ContentResolver resolver, String dataId) {
            final Uri feedbackUri = DataUsageFeedback.FEEDBACK_URI.buildUpon()
                    .appendPath(dataId)
                    .appendQueryParameter(DataUsageFeedback.USAGE_TYPE,
                                DataUsageFeedback.USAGE_TYPE_CALL)
                    .build();
            resolver.update(feedbackUri, new ContentValues(), null, null);
        }

        /*
         * Update the normalized phone number for the given dataId in the ContactsProvider, based
         * on the user's current country.
         */
        private static void updateNormalizedNumber(Context context, ContentResolver resolver,
                String dataId, String number) {
            if (TextUtils.isEmpty(number) || TextUtils.isEmpty(dataId)) {
                return;
            }
            final String countryIso = getCurrentCountryIso(context);
            if (TextUtils.isEmpty(countryIso)) {
                return;
            }
            final String normalizedNumber = PhoneNumberUtils.formatNumberToE164(number,
                    getCurrentCountryIso(context));
            if (TextUtils.isEmpty(normalizedNumber)) {
                return;
            }
            final ContentValues values = new ContentValues();
            values.put(Phone.NORMALIZED_NUMBER, normalizedNumber);
            resolver.update(Data.CONTENT_URI, values, Data._ID + "=?", new String[] {dataId});
        }

        private static String getCurrentCountryIso(Context context) {
            String countryIso = null;
            final CountryDetector detector = (CountryDetector) context.getSystemService(
                    Context.COUNTRY_DETECTOR);
            if (detector != null) {
                final Country country = detector.detectCountry();
                if (country != null) {
                    countryIso = country.getCountryIso();
                }
            }
            return countryIso;
        }
    }
}
