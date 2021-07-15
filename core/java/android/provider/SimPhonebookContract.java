/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.provider.SimPhonebookContract.ElementaryFiles.EF_ADN;
import static android.provider.SimPhonebookContract.ElementaryFiles.EF_FDN;
import static android.provider.SimPhonebookContract.ElementaryFiles.EF_SDN;
import static android.provider.SimPhonebookContract.ElementaryFiles.PATH_SEGMENT_EF_ADN;
import static android.provider.SimPhonebookContract.ElementaryFiles.PATH_SEGMENT_EF_FDN;
import static android.provider.SimPhonebookContract.ElementaryFiles.PATH_SEGMENT_EF_SDN;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.WorkerThread;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * The contract between the provider of contact records on the device's SIM cards and applications.
 * Contains definitions of the supported URIs and columns.
 *
 * <h3>Permissions</h3>
 * <p>
 * Querying this provider requires {@link android.Manifest.permission#READ_CONTACTS} and writing
 * to this provider requires {@link android.Manifest.permission#WRITE_CONTACTS}
 * </p>
 */
public final class SimPhonebookContract {

    /** The authority for the SIM phonebook provider. */
    public static final String AUTHORITY = "com.android.simphonebook";
    /** The content:// style uri to the authority for the SIM phonebook provider. */
    @NonNull
    public static final Uri AUTHORITY_URI = Uri.parse("content://com.android.simphonebook");
    /**
     * The Uri path element used to indicate that the following path segment is a subscription ID
     * for the SIM card that will be operated on.
     *
     * @hide
     */
    public static final String SUBSCRIPTION_ID_PATH_SEGMENT = "subid";

    private SimPhonebookContract() {
    }

    /**
     * Returns the Uri path segment used to reference the specified elementary file type for Uris
     * returned by this API.
     *
     * @hide
     */
    @NonNull
    public static String getEfUriPath(@ElementaryFiles.EfType int efType) {
        switch (efType) {
            case EF_ADN:
                return PATH_SEGMENT_EF_ADN;
            case EF_FDN:
                return PATH_SEGMENT_EF_FDN;
            case EF_SDN:
                return PATH_SEGMENT_EF_SDN;
            default:
                throw new IllegalArgumentException("Unsupported EfType " + efType);
        }
    }

    /**
     * Constants for the contact records on a SIM card.
     *
     * <h3 id="simrecords-data">Data</h3>
     * <p>
     * Data is stored in a specific elementary file on a specific SIM card and these are isolated
     * from each other. SIM cards are identified by their subscription ID. SIM cards may not support
     * all or even any of the elementary file types. A SIM will have constraints on
     * the values of the data that can be stored in each elementary file. The available SIMs,
     * their supported elementary file types and the constraints on the data can be discovered by
     * querying {@link ElementaryFiles#CONTENT_URI}. Each elementary file has a fixed capacity
     * for the number of records that may be stored. This can be determined from the value
     * of the {@link ElementaryFiles#MAX_RECORDS} column.
     * </p>
     * <p>
     * The {@link SimRecords#PHONE_NUMBER} column can only contain dialable characters and this
     * applies regardless of the SIM that is being used. See
     * {@link android.telephony.PhoneNumberUtils#isDialable(char)} for more details. Additionally
     * the phone number can contain at most {@link ElementaryFiles#PHONE_NUMBER_MAX_LENGTH}
     * characters. The {@link SimRecords#NAME} column can contain at most
     * {@link ElementaryFiles#NAME_MAX_LENGTH} bytes when it is encoded for storage on the SIM.
     * Encoding is done internally and so the name should be provided to these provider APIs as a
     * Java String but the number of bytes required to encode it for storage will vary depending on
     * the characters it contains. This length can be determined by calling
     * {@link SimRecords#getEncodedNameLength(ContentResolver, String)}.
     * </p>
     * <h3>Operations </h3>
     * <dl>
     * <dd><b>Insert</b></dd>
     * <p>
     * Only {@link ElementaryFiles#EF_ADN} supports inserts. {@link SimRecords#PHONE_NUMBER}
     * is a required column. If the value provided for this column is missing, null, empty
     * or violates the requirements discussed in the <a href="#simrecords-data">Data</a>
     * section above an {@link IllegalArgumentException} will be thrown. The
     * {@link SimRecords#NAME} column may be omitted but if provided and it violates any of
     * the requirements discussed in the <a href="#simrecords-data">Data</a> section above
     * an {@link IllegalArgumentException} will be thrown.
     * </p>
     * <p>
     * If an insert is not possible because the elementary file is full then an
     * {@link IllegalStateException} will be thrown.
     * </p>
     * <dd><b>Update</b></dd>
     * <p>
     * Updates can only be performed for individual records on {@link ElementaryFiles#EF_ADN}.
     * A specific record is referenced via the Uri returned by
     * {@link SimRecords#getItemUri(int, int, int)}. Updates have the same constraints and
     * behavior for the {@link SimRecords#PHONE_NUMBER} and {@link SimRecords#NAME} as insert.
     * However, in the case of update the {@link SimRecords#PHONE_NUMBER} may be omitted as
     * the existing record will already have a valid value.
     * </p>
     * <dd><b>Delete</b></dd>
     * <p>
     * Delete may only be performed for individual records on {@link ElementaryFiles#EF_ADN}.
     * Deleting records will free up space for use by future inserts.
     * </p>
     * <dd><b>Query</b></dd>
     * <p>
     * All the records stored on a specific elementary file can be read via a Uri returned by
     * {@link SimRecords#getContentUri(int, int)}. This query always returns all records; there
     * is no support for filtering via a selection. An individual record can be queried via a Uri
     * returned by {@link SimRecords#getItemUri(int, int, int)}. Queries will throw an
     * {@link IllegalArgumentException} when the SIM with the subscription ID or the elementary file
     * type are invalid or unavailable.
     * </p>
     * </dl>
     */
    public static final class SimRecords {

        /**
         * The subscription ID of the SIM the record is from.
         *
         * @see SubscriptionInfo#getSubscriptionId()
         */
        public static final String SUBSCRIPTION_ID = "subscription_id";
        /**
         * The type of the elementary file the record is from.
         *
         * @see ElementaryFiles#EF_ADN
         * @see ElementaryFiles#EF_FDN
         * @see ElementaryFiles#EF_SDN
         */
        public static final String ELEMENTARY_FILE_TYPE = "elementary_file_type";
        /**
         * The 1-based offset of the record in the elementary file that contains it.
         *
         * <p>This can be used to access individual SIM records by appending it to the
         * elementary file URIs but it is not like a normal database ID because it is not
         * auto-incrementing and it is not unique across SIM cards or elementary files. Hence, care
         * should be taken when using it to ensure that it is applied to the correct SIM and EF.
         *
         * @see #getItemUri(int, int, int)
         */
        public static final String RECORD_NUMBER = "record_number";
        /**
         * The name for this record.
         *
         * <p>An {@link IllegalArgumentException} will be thrown by insert and update if this
         * exceeds the maximum supported length. Use
         * {@link #getEncodedNameLength(ContentResolver, String)} to check how long the name
         * will be after encoding.
         *
         * @see ElementaryFiles#NAME_MAX_LENGTH
         * @see #getEncodedNameLength(ContentResolver, String)
         */
        public static final String NAME = "name";
        /**
         * The phone number for this record.
         *
         * <p>Only dialable characters are supported.
         *
         * <p>An {@link IllegalArgumentException} will be thrown by insert and update if this
         * exceeds the maximum supported length or contains unsupported characters.
         *
         * @see ElementaryFiles#PHONE_NUMBER_MAX_LENGTH
         * @see android.telephony.PhoneNumberUtils#isDialable(char)
         */
        public static final String PHONE_NUMBER = "phone_number";

        /** The MIME type of a CONTENT_URI subdirectory of a single SIM record. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/sim-contact_v2";
        /** The MIME type of CONTENT_URI providing a directory of SIM records. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/sim-contact_v2";

        /**
         * Value returned from {@link #getEncodedNameLength(ContentResolver, String)} when the name
         * length could not be determined because the name could not be encoded.
         */
        public static final int ERROR_NAME_UNSUPPORTED = -1;

        /**
         * The method name used to get the encoded length of a value for {@link SimRecords#NAME}
         * column.
         *
         * @hide
         * @see #getEncodedNameLength(ContentResolver, String)
         * @see ContentResolver#call(String, String, String, Bundle)
         */
        public static final String GET_ENCODED_NAME_LENGTH_METHOD_NAME = "get_encoded_name_length";

        /**
         * Extra key used for an integer value that contains the length in bytes of an encoded
         * name.
         *
         * @hide
         * @see #getEncodedNameLength(ContentResolver, String)
         * @see #GET_ENCODED_NAME_LENGTH_METHOD_NAME
         */
        public static final String EXTRA_ENCODED_NAME_LENGTH =
                "android.provider.extra.ENCODED_NAME_LENGTH";


        /**
         * Key for the PIN2 needed to modify FDN record that should be passed in the Bundle
         * passed to {@link ContentResolver#insert(Uri, ContentValues, Bundle)},
         * {@link ContentResolver#update(Uri, ContentValues, Bundle)}
         * and {@link ContentResolver#delete(Uri, Bundle)}.
         *
         * <p>Modifying FDN records also requires either
         * {@link android.Manifest.permission#MODIFY_PHONE_STATE} or
         * {@link TelephonyManager#hasCarrierPrivileges()}
         *
         * @hide
         */
        @SystemApi
        public static final String QUERY_ARG_PIN2 = "android:query-arg-pin2";

        private SimRecords() {
        }

        /**
         * Returns the content Uri for the specified elementary file on the specified SIM.
         *
         * <p>When queried this Uri will return all of the contact records in the specified
         * elementary file on the specified SIM. The available subscriptionIds and efTypes can
         * be discovered by querying {@link ElementaryFiles#CONTENT_URI}.
         *
         * <p>If a SIM with the provided subscription ID does not exist or the SIM with the provided
         * subscription ID doesn't support the specified entity file then all operations will
         * throw an {@link IllegalArgumentException}.
         *
         * @param subscriptionId the subscriptionId of the SIM card that this Uri will reference
         * @param efType         the elementary file on the SIM that this Uri will reference
         * @see ElementaryFiles#EF_ADN
         * @see ElementaryFiles#EF_FDN
         * @see ElementaryFiles#EF_SDN
         */
        @NonNull
        public static Uri getContentUri(int subscriptionId, @ElementaryFiles.EfType int efType) {
            return buildContentUri(subscriptionId, efType).build();
        }

        /**
         * Content Uri for the specific SIM record with the provided {@link #RECORD_NUMBER}.
         *
         * <p>When queried this will return the record identified by the provided arguments.
         *
         * <p>For a non-existent record:
         * <ul>
         *     <li>query will return an empty cursor</li>
         *     <li>update will return 0</li>
         *     <li>delete will return 0</li>
         * </ul>
         *
         * @param subscriptionId the subscription ID of the SIM containing the record. If no SIM
         *                       with this subscription ID exists then it will be treated as a
         *                       non-existent record
         * @param efType         the elementary file type containing the record. If the specified
         *                       SIM doesn't support this elementary file then it will be treated
         *                       as a non-existent record.
         * @param recordNumber   the record number of the record this Uri should reference. This
         *                       must be greater than 0. If there is no record with this record
         *                       number in the specified entity file then it will be treated as a
         *                       non-existent record.
         * @see ElementaryFiles#SUBSCRIPTION_ID
         * @see ElementaryFiles#EF_TYPE
         * @see #RECORD_NUMBER
         */
        @NonNull
        public static Uri getItemUri(
                int subscriptionId, @ElementaryFiles.EfType int efType,
                @IntRange(from = 1) int recordNumber) {
            // Elementary file record indices are 1-based.
            Preconditions.checkArgument(recordNumber > 0, "Invalid recordNumber");

            return buildContentUri(subscriptionId, efType)
                    .appendPath(String.valueOf(recordNumber))
                    .build();
        }

        /**
         * Returns the number of bytes required to encode the specified name when it is stored
         * on the SIM.
         *
         * <p>{@link ElementaryFiles#NAME_MAX_LENGTH} is specified in bytes but the encoded name
         * may require more than 1 byte per character depending on the characters it contains. So
         * this method can be used to check whether a name exceeds the max length.
         *
         * @return the number of bytes required by the encoded name or
         * {@link #ERROR_NAME_UNSUPPORTED} if the name could not be encoded.
         * @throws IllegalStateException if the provider fails to return the length.
         * @see SimRecords#NAME
         * @see ElementaryFiles#NAME_MAX_LENGTH
         */
        @WorkerThread
        @IntRange(from = 0)
        public static int getEncodedNameLength(
                @NonNull ContentResolver resolver, @NonNull String name) {
            Objects.requireNonNull(name);
            Bundle result = resolver.call(AUTHORITY, GET_ENCODED_NAME_LENGTH_METHOD_NAME, name,
                    null);
            if (result == null || !result.containsKey(EXTRA_ENCODED_NAME_LENGTH)) {
                throw new IllegalStateException("Provider malfunction: no length was returned.");
            }
            int length = result.getInt(EXTRA_ENCODED_NAME_LENGTH, ERROR_NAME_UNSUPPORTED);
            if (length < 0 && length != ERROR_NAME_UNSUPPORTED) {
                throw new IllegalStateException(
                        "Provider malfunction: invalid length was returned.");
            }
            return length;
        }

        private static Uri.Builder buildContentUri(
                int subscriptionId, @ElementaryFiles.EfType int efType) {
            return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(AUTHORITY)
                    .appendPath(SUBSCRIPTION_ID_PATH_SEGMENT)
                    .appendPath(String.valueOf(subscriptionId))
                    .appendPath(getEfUriPath(efType));
        }

    }

    /**
     * Constants for metadata about the elementary files of the SIM cards in the phone.
     *
     * <h3>Operations </h3>
     * <dl>
     * <dd><b>Insert</b></dd>
     * <p>Insert is not supported for the Uris defined in this class.</p>
     * <dd><b>Update</b></dd>
     * <p>Update is not supported for the Uris defined in this class.</p>
     * <dd><b>Delete</b></dd>
     * <p>Delete is not supported for the Uris defined in this class.</p>
     * <dd><b>Query</b></dd>
     * <p>
     * The elementary files for all the inserted SIMs can be read via
     * {@link ElementaryFiles#CONTENT_URI}. Unsupported elementary files are omitted from the
     * results. This Uri always returns all supported elementary files for all available SIMs; it
     * does not support filtering via a selection. A specific elementary file can be queried
     * via a Uri returned by {@link ElementaryFiles#getItemUri(int, int)}. If the elementary file
     * referenced by this Uri is unsupported by the SIM then the query will return an empty cursor.
     * </p>
     * </dl>
     */
    public static final class ElementaryFiles {

        /** {@link SubscriptionInfo#getSimSlotIndex()} of the SIM for this row. */
        public static final String SLOT_INDEX = "slot_index";
        /** {@link SubscriptionInfo#getSubscriptionId()} of the SIM for this row. */
        public static final String SUBSCRIPTION_ID = "subscription_id";
        /**
         * The elementary file type for this row.
         *
         * @see ElementaryFiles#EF_ADN
         * @see ElementaryFiles#EF_FDN
         * @see ElementaryFiles#EF_SDN
         */
        public static final String EF_TYPE = "ef_type";
        /** The maximum number of records supported by the elementary file. */
        public static final String MAX_RECORDS = "max_records";
        /** Count of the number of records that are currently stored in the elementary file. */
        public static final String RECORD_COUNT = "record_count";
        /** The maximum length supported for the name of a record in the elementary file. */
        public static final String NAME_MAX_LENGTH = "name_max_length";
        /**
         * The maximum length supported for the phone number of a record in the elementary file.
         */
        public static final String PHONE_NUMBER_MAX_LENGTH = "phone_number_max_length";

        /**
         * A value for an elementary file that is not recognized.
         *
         * <p>Generally this should be ignored. If new values are added then this will be used
         * for apps that target SDKs where they aren't defined.
         */
        public static final int EF_UNKNOWN = 0;
        /**
         * Type for accessing records in the "abbreviated dialing number" (ADN) elementary file on
         * the SIM.
         *
         * <p>ADN records are typically user created.
         */
        public static final int EF_ADN = 1;
        /**
         * Type for accessing records in the "fixed dialing number" (FDN) elementary file on the
         * SIM.
         *
         * <p>FDN numbers are the numbers that are allowed to dialed for outbound calls when FDN is
         * enabled.
         *
         * <p>FDN records cannot be modified by applications. Hence, insert, update and
         * delete methods operating on this Uri will throw UnsupportedOperationException
         */
        public static final int EF_FDN = 2;
        /**
         * Type for accessing records in the "service dialing number" (SDN) elementary file on the
         * SIM.
         *
         * <p>Typically SDNs are preset numbers provided by the carrier for common operations (e.g.
         * voicemail, check balance, etc).
         *
         * <p>SDN records cannot be modified by applications. Hence, insert, update and delete
         * methods operating on this Uri will throw UnsupportedOperationException
         */
        public static final int EF_SDN = 3;
        /**
         * The Uri path segment used to target the ADN elementary file for SimPhonebookProvider
         * content operations.
         *
         * @hide
         */
        public static final String PATH_SEGMENT_EF_ADN = "adn";
        /**
         * The Uri path segment used to target the FDN elementary file for SimPhonebookProvider
         * content operations.
         *
         * @hide
         */
        public static final String PATH_SEGMENT_EF_FDN = "fdn";
        /**
         * The Uri path segment used to target the SDN elementary file for SimPhonebookProvider
         * content operations.
         *
         * @hide
         */
        public static final String PATH_SEGMENT_EF_SDN = "sdn";
        /** The MIME type of CONTENT_URI providing a directory of ADN-like elementary files. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/sim-elementary-file";
        /** The MIME type of a CONTENT_URI subdirectory of a single ADN-like elementary file. */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/sim-elementary-file";
        /**
         * The Uri path segment used to construct Uris for the metadata defined in this class.
         *
         * @hide
         */
        public static final String ELEMENTARY_FILES_PATH_SEGMENT = "elementary_files";

        /** Content URI for the ADN-like elementary files available on the device. */
        @NonNull
        public static final Uri CONTENT_URI = AUTHORITY_URI
                .buildUpon()
                .appendPath(ELEMENTARY_FILES_PATH_SEGMENT).build();

        private ElementaryFiles() {
        }

        /**
         * Returns a content uri for a specific elementary file.
         *
         * <p>If a SIM with the specified subscriptionId is not present an exception will be thrown.
         * If the SIM doesn't support the specified elementary file it will return an empty cursor.
         */
        @NonNull
        public static Uri getItemUri(int subscriptionId, @EfType int efType) {
            return CONTENT_URI.buildUpon().appendPath(SUBSCRIPTION_ID_PATH_SEGMENT)
                    .appendPath(String.valueOf(subscriptionId))
                    .appendPath(getEfUriPath(efType))
                    .build();
        }

        /**
         * Annotation for the valid elementary file types.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"EF"},
                value = {EF_UNKNOWN, EF_ADN, EF_FDN, EF_SDN})
        public @interface EfType {
        }
    }
}
