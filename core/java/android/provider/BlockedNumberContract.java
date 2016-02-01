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

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * Constants and methods to access blocked phone numbers for incoming calls and texts.
 *
 * TODO javadoc
 * - Proper javadoc tagging.
 * - Code sample?
 * - Describe who can access it.
 */
public class BlockedNumberContract {
    private BlockedNumberContract() {
    }

    /** The authority for the contacts provider */
    public static final String AUTHORITY = "com.android.blockednumber";

    /** A content:// style uri to the authority for the contacts provider */
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * TODO javadoc
     *
     * Constants to interact with the blocked phone number list.
     */
    public static class BlockedNumbers {
        private BlockedNumbers() {
        }

        /**
         * TODO javadoc
         *
         * Content URI for the blocked numbers.
         *
         * Supported operations
         * blocked
         * - query
         * - delete
         * - insert
         *
         * blocked/ID
         * - query (selection is not supported)
         * - delete (selection is not supported)
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "blocked");

        /**
         * The MIME type of {@link #CONTENT_URI} itself providing a directory of blocked phone
         * numbers.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/blocked_numbers";

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
    public static final String RES_NUMBER_IS_BLOCKED = "blocked";

    /** @hide */
    public static final String METHOD_CAN_CURRENT_USER_BLOCK_NUMBERS =
            "can_current_user_block_numbers";

    /** @hide */
    public static final String RES_CAN_BLOCK_NUMBERS = "can_block";

    /**
     * Returns whether a given number is in the blocked list.
     * <p> Note that if the {@link #canCurrentUserBlockNumbers} is {@code false} for the user
     * context {@code context}, this method will throw an {@link UnsupportedOperationException}.
     */
    public static boolean isBlocked(Context context, String phoneNumber) {
        final Bundle res = context.getContentResolver().call(AUTHORITY_URI,
                METHOD_IS_BLOCKED, phoneNumber, null);
        return res != null && res.getBoolean(RES_NUMBER_IS_BLOCKED, false);
    }

    /**
     * Returns {@code true} if blocking numbers is supported for the current user.
     * <p> Typically, blocking numbers is only supported for the primary user.
     */
    public static boolean canCurrentUserBlockNumbers(Context context) {
        final Bundle res = context.getContentResolver().call(
                AUTHORITY_URI, METHOD_CAN_CURRENT_USER_BLOCK_NUMBERS, null, null);
        return res != null && res.getBoolean(RES_CAN_BLOCK_NUMBERS, false);
    }

}
