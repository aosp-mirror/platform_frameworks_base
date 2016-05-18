/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.WorkerThread;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * <p>
 * The contract between the blockednumber provider and applications. Contains definitions for
 * the supported URIs and columns.
 * </p>
 *
 * <h3> Overview </h3>
 * <p>
 * The content provider exposes a table containing blocked numbers. The columns and URIs for
 * accessing this table are defined by the {@link BlockedNumbers} class. Messages, and calls from
 * blocked numbers are discarded by the platform. Notifications upon provider changes can be
 * received using a {@link android.database.ContentObserver}.
 * </p>
 * <p>
 * The platform will not block messages, and calls from emergency numbers as defined by
 * {@link android.telephony.PhoneNumberUtils#isEmergencyNumber(String)}. If the user contacts
 * emergency services, number blocking is disabled by the platform for a duration defined by
 * {@link android.telephony.CarrierConfigManager#KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT}.
 * </p>
 *
 * <h3> Permissions </h3>
 * <p>
 * Only the system, the default SMS application, and the default phone app
 * (See {@link android.telecom.TelecomManager#getDefaultDialerPackage()}), and carrier apps
 * (See {@link android.service.carrier.CarrierService}) can read, and write to the blockednumber
 * provider. However, {@link #canCurrentUserBlockNumbers(Context)} can be accessed by any
 * application.
 * </p>
 *
 * <h3> Data </h3>
 * <p>
 * Other than regular phone numbers, the blocked number provider can also store addresses (such
 * as email) from which a user can receive messages, and calls. The blocked numbers are stored
 * in the {@link BlockedNumbers#COLUMN_ORIGINAL_NUMBER} column. A normalized version of phone
 * numbers (if normalization is possible) is stored in {@link BlockedNumbers#COLUMN_E164_NUMBER}
 * column. The platform blocks calls, and messages from an address if it is present in in the
 * {@link BlockedNumbers#COLUMN_ORIGINAL_NUMBER} column or if the E164 version of the address
 * matches the {@link BlockedNumbers#COLUMN_E164_NUMBER} column.
 * </p>
 *
 * <h3> Operations </h3>
 * <dl>
 * <dt><b>Insert</b></dt>
 * <dd>
 * <p>
 * {@link BlockedNumbers#COLUMN_ORIGINAL_NUMBER} is a required column that needs to be populated.
 * Apps can optionally provide the {@link BlockedNumbers#COLUMN_E164_NUMBER} which is the phone
 * number's E164 representation. The provider automatically populates this column if the app does
 * not provide it. Note that this column is not populated if normalization fails or if the address
 * is not a phone number (eg: email).
 * <p>
 * Attempting to insert an existing blocked number (same
 * {@link BlockedNumbers#COLUMN_ORIGINAL_NUMBER} column) will result in replacing the existing
 * blocked number.
 * <p>
 * Examples:
 * <pre>
 * ContentValues values = new ContentValues();
 * values.put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, "1234567890");
 * Uri uri = getContentResolver().insert(BlockedNumbers.CONTENT_URI, values);
 * </pre>
 * <pre>
 * ContentValues values = new ContentValues();
 * values.put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, "1234567890");
 * values.put(BlockedNumbers.COLUMN_E164_NUMBER, "+11234567890");
 * Uri uri = getContentResolver().insert(BlockedNumbers.CONTENT_URI, values);
 * </pre>
 * <pre>
 * ContentValues values = new ContentValues();
 * values.put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, "12345@abdcde.com");
 * Uri uri = getContentResolver().insert(BlockedNumbers.CONTENT_URI, values);
 * </pre>
 * </p>
 * </dd>
 * <dt><b>Update</b></dt>
 * <dd>
 * <p>
 * Updates are not supported. Use Delete, and Insert instead.
 * </p>
 * </dd>
 * <dt><b>Delete</b></dt>
 * <dd>
 * <p>
 * Deletions can be performed as follows:
 * <pre>
 * ContentValues values = new ContentValues();
 * values.put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, "1234567890");
 * Uri uri = getContentResolver().insert(BlockedNumbers.CONTENT_URI, values);
 * getContentResolver().delete(uri, null, null);
 * </pre>
 * To check if a particular number is blocked, use the method
 * {@link #isBlocked(Context, String)}.
 * </p>
 * </dd>
 * <dt><b>Query</b></dt>
 * <dd>
 * <p>
 * All blocked numbers can be enumerated as follows:
 * <pre>
 * Cursor c = getContentResolver().query(BlockedNumbers.CONTENT_URI,
 *          new String[]{BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
 *          BlockedNumbers.COLUMN_E164_NUMBER}, null, null, null);
 * </pre>
 * </p>
 * </dd>
 * <dt><b>Unblock</b></dt>
 * <dd>
 * <p>
 * Use the method {@link #unblock(Context, String)} to unblock numbers.
 * </p>
 * </dd>
 *
 * <h3> Multi-user </h3>
 * <p>
 * Apps must use the method {@link #canCurrentUserBlockNumbers(Context)} before performing any
 * operation on the blocked number provider. If {@link #canCurrentUserBlockNumbers(Context)} returns
 * {@code false}, all operations on the provider will fail with a {@link SecurityException}. The
 * platform will block calls, and messages from numbers in the provider independent of the current
 * user.
 * </p>
 */
public class BlockedNumberContract {
    private BlockedNumberContract() {
    }

    /** The authority for the blocked number provider */
    public static final String AUTHORITY = "com.android.blockednumber";

    /** A content:// style uri to the authority for the blocked number provider */
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * Constants to interact with the blocked numbers list.
     */
    public static class BlockedNumbers {
        private BlockedNumbers() {
        }

        /**
         * Content URI for the blocked numbers.
         * <h3> Supported operations </h3>
         * <p> blocked
         * <ul>
         * <li> query
         * <li> delete
         * <li> insert
         * </ul>
         * <p> blocked/ID
         * <ul>
         * <li> query (selection is not supported)
         * <li> delete (selection is not supported)
         * </ul>
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "blocked");

        /**
         * The MIME type of {@link #CONTENT_URI} itself providing a directory of blocked phone
         * numbers.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/blocked_number";

        /**
         * The MIME type of a blocked phone number under {@link #CONTENT_URI}.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/blocked_number";

        /**
         * Auto-generated ID field which monotonically increases.
         * <p>TYPE: long</p>
         */
        public static final String COLUMN_ID = "_id";

        /**
         * Phone number to block.
         * <p>Must be specified in {@code insert}.
         * <p>TYPE: String</p>
         */
        public static final String COLUMN_ORIGINAL_NUMBER = "original_number";

        /**
         * Phone number to block.  The system generates it from {@link #COLUMN_ORIGINAL_NUMBER}
         * by removing all formatting characters.
         * <p>Optional in {@code insert}.  When not specified, the system tries to generate it
         * assuming the current country. (Which will still be null if the number is not valid.)
         * <p>TYPE: String</p>
         */
        public static final String COLUMN_E164_NUMBER = "e164_number";
    }

    /** @hide */
    public static final String METHOD_IS_BLOCKED = "is_blocked";

    /** @hide */
    public static final String METHOD_UNBLOCK= "unblock";

    /** @hide */
    public static final String RES_NUMBER_IS_BLOCKED = "blocked";

    /** @hide */
    public static final String RES_NUM_ROWS_DELETED = "num_deleted";

    /** @hide */
    public static final String METHOD_CAN_CURRENT_USER_BLOCK_NUMBERS =
            "can_current_user_block_numbers";

    /** @hide */
    public static final String RES_CAN_BLOCK_NUMBERS = "can_block";

    /**
     * Returns whether a given number is in the blocked list.
     *
     * <p> This matches the {@code phoneNumber} against the
     * {@link BlockedNumbers#COLUMN_ORIGINAL_NUMBER} column, and the E164 representation of the
     * {@code phoneNumber} with the {@link BlockedNumbers#COLUMN_E164_NUMBER} column.
     *
     * <p> Note that if the {@link #canCurrentUserBlockNumbers} is {@code false} for the user
     * context {@code context}, this method will throw a {@link SecurityException}.
     *
     * @return {@code true} if the {@code phoneNumber} is blocked.
     */
    @WorkerThread
    public static boolean isBlocked(Context context, String phoneNumber) {
        final Bundle res = context.getContentResolver().call(
                AUTHORITY_URI, METHOD_IS_BLOCKED, phoneNumber, null);
        return res != null && res.getBoolean(RES_NUMBER_IS_BLOCKED, false);
    }

    /**
     * Unblocks the {@code phoneNumber} if it is blocked.
     *
     * <p> This deletes all rows where the {@code phoneNumber} matches the
     * {@link BlockedNumbers#COLUMN_ORIGINAL_NUMBER} column or the E164 representation of the
     * {@code phoneNumber} matches the {@link BlockedNumbers#COLUMN_E164_NUMBER} column.
     *
     * <p>To delete rows based on exact match with specific columns such as
     * {@link BlockedNumbers#COLUMN_ID} use
     * {@link android.content.ContentProvider#delete(Uri, String, String[])} with
     * {@link BlockedNumbers#CONTENT_URI} URI.
     *
     * <p> Note that if the {@link #canCurrentUserBlockNumbers} is {@code false} for the user
     * context {@code context}, this method will throw a {@link SecurityException}.
     *
     * @return the number of rows deleted in the blocked number provider as a result of unblock.
     */
    @WorkerThread
    public static int unblock(Context context, String phoneNumber) {
        final Bundle res = context.getContentResolver().call(
                AUTHORITY_URI, METHOD_UNBLOCK, phoneNumber, null);
        return res.getInt(RES_NUM_ROWS_DELETED, 0);
    }

    /**
     * Checks if blocking numbers is supported for the current user.
     * <p> Typically, blocking numbers is only supported for one user at a time.
     *
     * @return {@code true} if the current user can block numbers.
     */
    public static boolean canCurrentUserBlockNumbers(Context context) {
        final Bundle res = context.getContentResolver().call(
                AUTHORITY_URI, METHOD_CAN_CURRENT_USER_BLOCK_NUMBERS, null, null);
        return res != null && res.getBoolean(RES_CAN_BLOCK_NUMBERS, false);
    }

    /**
     * <p>
     * The contract between the blockednumber provider and the system.
     * </p>
     * <p>This is a wrapper over {@link BlockedNumberContract} that also manages the blocking
     * behavior when the user contacts emergency services. See
     * {@link #notifyEmergencyContact(Context)} for details. All methods are protected by
     * {@link android.Manifest.permission#READ_BLOCKED_NUMBERS} and
     * {@link android.Manifest.permission#WRITE_BLOCKED_NUMBERS} appropriately which ensure that
     * only system can access the methods defined here.
     * </p>
     * @hide
     */
    public static class SystemContract {
        /**
         * A protected broadcast intent action for letting components with
         * {@link android.Manifest.permission#READ_BLOCKED_NUMBERS} know that the block suppression
         * status as returned by {@link #getBlockSuppressionStatus(Context)} has been updated.
         */
        public static final String ACTION_BLOCK_SUPPRESSION_STATE_CHANGED =
                "android.provider.action.BLOCK_SUPPRESSION_STATE_CHANGED";

        public static final String METHOD_NOTIFY_EMERGENCY_CONTACT = "notify_emergency_contact";

        public static final String METHOD_END_BLOCK_SUPPRESSION = "end_block_suppression";

        public static final String METHOD_SHOULD_SYSTEM_BLOCK_NUMBER = "should_system_block_number";

        public static final String METHOD_GET_BLOCK_SUPPRESSION_STATUS =
                "get_block_suppression_status";

        public static final String RES_IS_BLOCKING_SUPPRESSED = "blocking_suppressed";

        public static final String RES_BLOCKING_SUPPRESSED_UNTIL_TIMESTAMP =
                "blocking_suppressed_until_timestamp";

        /**
         * Notifies the provider that emergency services were contacted by the user.
         * <p> This results in {@link #shouldSystemBlockNumber} returning {@code false} independent
         * of the contents of the provider for a duration defined by
         * {@link android.telephony.CarrierConfigManager#KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT}
         * the provider unless {@link #endBlockSuppression(Context)} is called.
         */
        public static void notifyEmergencyContact(Context context) {
            context.getContentResolver().call(
                    AUTHORITY_URI, METHOD_NOTIFY_EMERGENCY_CONTACT, null, null);
        }

        /**
         * Notifies the provider to disable suppressing blocking. If emergency services were not
         * contacted recently at all, calling this method is a no-op.
         */
        public static void endBlockSuppression(Context context) {
            context.getContentResolver().call(
                    AUTHORITY_URI, METHOD_END_BLOCK_SUPPRESSION, null, null);
        }

        /**
         * Returns {@code true} if {@code phoneNumber} is blocked taking
         * {@link #notifyEmergencyContact(Context)} into consideration. If emergency services have
         * not been contacted recently, this method is equivalent to
         * {@link #isBlocked(Context, String)}.
         */
        public static boolean shouldSystemBlockNumber(Context context, String phoneNumber) {
            final Bundle res = context.getContentResolver().call(
                    AUTHORITY_URI, METHOD_SHOULD_SYSTEM_BLOCK_NUMBER, phoneNumber, null);
            return res != null && res.getBoolean(RES_NUMBER_IS_BLOCKED, false);
        }

        /**
         * Returns the current status of block suppression.
         */
        public static BlockSuppressionStatus getBlockSuppressionStatus(Context context) {
            final Bundle res = context.getContentResolver().call(
                    AUTHORITY_URI, METHOD_GET_BLOCK_SUPPRESSION_STATUS, null, null);
            return new BlockSuppressionStatus(res.getBoolean(RES_IS_BLOCKING_SUPPRESSED, false),
                    res.getLong(RES_BLOCKING_SUPPRESSED_UNTIL_TIMESTAMP, 0));
        }

        /**
         * Represents the current status of {@link #shouldSystemBlockNumber(Context, String)}. If
         * emergency services have been contacted recently, {@link #isSuppressed} is {@code true},
         * and blocking is disabled until the timestamp {@link #untilTimestampMillis}.
         */
        public static class BlockSuppressionStatus {
            public final boolean isSuppressed;
            /**
             * Timestamp in milliseconds from epoch.
             */
            public final long untilTimestampMillis;

            public BlockSuppressionStatus(boolean isSuppressed, long untilTimestampMillis) {
                this.isSuppressed = isSuppressed;
                this.untilTimestampMillis = untilTimestampMillis;
            }
        }
    }
}
