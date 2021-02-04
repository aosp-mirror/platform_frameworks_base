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
import static android.provider.SimPhonebookContract.ElementaryFiles.EF_ADN_PATH_SEGMENT;
import static android.provider.SimPhonebookContract.ElementaryFiles.EF_FDN;
import static android.provider.SimPhonebookContract.ElementaryFiles.EF_FDN_PATH_SEGMENT;
import static android.provider.SimPhonebookContract.ElementaryFiles.EF_SDN;
import static android.provider.SimPhonebookContract.ElementaryFiles.EF_SDN_PATH_SEGMENT;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.WorkerThread;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
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
 * <p>This content provider does not support any of the QUERY_ARG_SQL* bundle arguments. An
 * IllegalArgumentException will be thrown if these are included.
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
    @SystemApi
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
    @SystemApi
    public static String getEfUriPath(@ElementaryFiles.EfType int efType) {
        switch (efType) {
            case EF_ADN:
                return EF_ADN_PATH_SEGMENT;
            case EF_FDN:
                return EF_FDN_PATH_SEGMENT;
            case EF_SDN:
                return EF_SDN_PATH_SEGMENT;
            default:
                throw new IllegalArgumentException("Unsupported EfType " + efType);
        }
    }

    /** Constants for the contact records on a SIM card. */
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
         * exceeds the maximum supported length or contains unsupported characters.
         * {@link #validateName(ContentResolver, int, int, String)} )} can be used to
         * check whether the name is supported.
         *
         * @see ElementaryFiles#NAME_MAX_LENGTH
         * @see #validateName(ContentResolver, int, int, String) )
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
         * The path segment that is appended to {@link #getContentUri(int, int)} which indicates
         * that the following path segment contains a name to be validated.
         *
         * @hide
         * @see #validateName(ContentResolver, int, int, String)
         */
        @SystemApi
        public static final String VALIDATE_NAME_PATH_SEGMENT = "validate_name";

        /**
         * The key for a cursor extra that contains the result of a validate name query.
         *
         * @hide
         * @see #validateName(ContentResolver, int, int, String)
         */
        @SystemApi
        public static final String EXTRA_NAME_VALIDATION_RESULT =
                "android.provider.extra.NAME_VALIDATION_RESULT";


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
         * subscription ID doesn't support the specified entity file then queries will return
         * and empty cursor and inserts will throw an {@link IllegalArgumentException}
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
         */
        @NonNull
        public static Uri getItemUri(
                int subscriptionId, @ElementaryFiles.EfType int efType, int recordNumber) {
            // Elementary file record indices are 1-based.
            Preconditions.checkArgument(recordNumber > 0, "Invalid recordNumber");

            return buildContentUri(subscriptionId, efType)
                    .appendPath(String.valueOf(recordNumber))
                    .build();
        }

        /**
         * Validates a value that is being provided for the {@link #NAME} column.
         *
         * <p>The return value can be used to check if the name is valid. If it is not valid then
         * inserts and updates to the specified elementary file that use the provided name value
         * will throw an {@link IllegalArgumentException}.
         *
         * <p>If the specified SIM or elementary file don't exist then
         * {@link NameValidationResult#getMaxEncodedLength()} will be zero and
         * {@link NameValidationResult#isValid()} will return false.
         */
        @NonNull
        @WorkerThread
        public static NameValidationResult validateName(
                @NonNull ContentResolver resolver, int subscriptionId,
                @ElementaryFiles.EfType int efType,
                @NonNull String name) {
            Bundle queryArgs = new Bundle();
            queryArgs.putString(SimRecords.NAME, name);
            try (Cursor cursor =
                         resolver.query(buildContentUri(subscriptionId, efType)
                                 .appendPath(VALIDATE_NAME_PATH_SEGMENT)
                                 .build(), null, queryArgs, null)) {
                NameValidationResult result = cursor.getExtras()
                        .getParcelable(EXTRA_NAME_VALIDATION_RESULT);
                return result != null ? result : new NameValidationResult(name, "", 0, 0);
            }
        }

        private static Uri.Builder buildContentUri(
                int subscriptionId, @ElementaryFiles.EfType int efType) {
            return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(AUTHORITY)
                    .appendPath(SUBSCRIPTION_ID_PATH_SEGMENT)
                    .appendPath(String.valueOf(subscriptionId))
                    .appendPath(getEfUriPath(efType));
        }

        /** Contains details about the validity of a value provided for the {@link #NAME} column. */
        public static final class NameValidationResult implements Parcelable {

            @NonNull
            public static final Creator<NameValidationResult> CREATOR =
                    new Creator<NameValidationResult>() {

                        @Override
                        public NameValidationResult createFromParcel(@NonNull Parcel in) {
                            return new NameValidationResult(in);
                        }

                        @NonNull
                        @Override
                        public NameValidationResult[] newArray(int size) {
                            return new NameValidationResult[size];
                        }
                    };

            private final String mName;
            private final String mSanitizedName;
            private final int mEncodedLength;
            private final int mMaxEncodedLength;

            /** Creates a new instance from the provided values. */
            public NameValidationResult(@NonNull String name, @NonNull String sanitizedName,
                    int encodedLength, int maxEncodedLength) {
                this.mName = Objects.requireNonNull(name);
                this.mSanitizedName = Objects.requireNonNull(sanitizedName);
                this.mEncodedLength = encodedLength;
                this.mMaxEncodedLength = maxEncodedLength;
            }

            private NameValidationResult(Parcel in) {
                this(in.readString(), in.readString(), in.readInt(), in.readInt());
            }

            /** Returns the original name that is being validated. */
            @NonNull
            public String getName() {
                return mName;
            }

            /**
             * Returns a sanitized copy of the original name with all unsupported characters
             * replaced with spaces.
             */
            @NonNull
            public String getSanitizedName() {
                return mSanitizedName;
            }

            /**
             * Returns whether the original name isValid.
             *
             * <p>If this returns false then inserts and updates using the name will throw an
             * {@link IllegalArgumentException}
             */
            public boolean isValid() {
                return mMaxEncodedLength > 0 && mEncodedLength <= mMaxEncodedLength
                        && Objects.equals(
                        mName, mSanitizedName);
            }

            /** Returns whether the character at the specified position is supported by the SIM. */
            public boolean isSupportedCharacter(int position) {
                return mName.charAt(position) == mSanitizedName.charAt(position);
            }

            /**
             * Returns the number of bytes required to save the name.
             *
             * <p>This may be more than the number of characters in the name.
             */
            public int getEncodedLength() {
                return mEncodedLength;
            }

            /**
             * Returns the maximum number of bytes that are supported for the name.
             *
             * @see ElementaryFiles#NAME_MAX_LENGTH
             */
            public int getMaxEncodedLength() {
                return mMaxEncodedLength;
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public void writeToParcel(@NonNull Parcel dest, int flags) {
                dest.writeString(mName);
                dest.writeString(mSanitizedName);
                dest.writeInt(mEncodedLength);
                dest.writeInt(mMaxEncodedLength);
            }
        }
    }

    /** Constants for metadata about the elementary files of the SIM cards in the phone. */
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
        /** @hide */
        @SystemApi
        public static final String EF_ADN_PATH_SEGMENT = "adn";
        /** @hide */
        @SystemApi
        public static final String EF_FDN_PATH_SEGMENT = "fdn";
        /** @hide */
        @SystemApi
        public static final String EF_SDN_PATH_SEGMENT = "sdn";
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
        @SystemApi
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
         * If the SIM doesn't support the specified elementary file it will have a zero value for
         * {@link #MAX_RECORDS}.
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
