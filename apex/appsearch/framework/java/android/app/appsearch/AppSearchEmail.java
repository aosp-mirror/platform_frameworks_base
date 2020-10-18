/*
 * Copyright 2020 The Android Open Source Project
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

package android.app.appsearch;


import android.annotation.NonNull;
import android.annotation.Nullable;

import android.app.appsearch.AppSearchSchema.PropertyConfig;

/**
 * Encapsulates a {@link GenericDocument} that represent an email.
 *
 * <p>This class is a higher level implement of {@link GenericDocument}.
 *
 * <p>This class will eventually migrate to Jetpack, where it will become public API.
 *
 * @hide
 */

public class AppSearchEmail extends GenericDocument {
    /** The name of the schema type for {@link AppSearchEmail} documents.*/
    public static final String SCHEMA_TYPE = "builtin:Email";

    private static final String KEY_FROM = "from";
    private static final String KEY_TO = "to";
    private static final String KEY_CC = "cc";
    private static final String KEY_BCC = "bcc";
    private static final String KEY_SUBJECT = "subject";
    private static final String KEY_BODY = "body";

    public static final AppSearchSchema SCHEMA = new AppSearchSchema.Builder(SCHEMA_TYPE)
            .addProperty(new PropertyConfig.Builder(KEY_FROM)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_TO)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_CC)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_BCC)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_SUBJECT)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new PropertyConfig.Builder(KEY_BODY)
                    .setDataType(PropertyConfig.DATA_TYPE_STRING)
                    .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).build();

    /**
     * Creates a new {@link AppSearchEmail} from the contents of an existing
     * {@link GenericDocument}.
     *
     * @param document The {@link GenericDocument} containing the email content.
     */
    public AppSearchEmail(@NonNull GenericDocument document) {
        super(document);
    }

    /**
     * Get the from address of {@link AppSearchEmail}.
     *
     * @return Returns the subject of {@link AppSearchEmail} or {@code null} if it's not been set
     *         yet.
     */
    @Nullable
    public String getFrom() {
        return getPropertyString(KEY_FROM);
    }

    /**
     * Get the destination addresses of {@link AppSearchEmail}.
     *
     * @return Returns the destination addresses of {@link AppSearchEmail} or {@code null} if it's
     *         not been set yet.
     */
    @Nullable
    public String[] getTo() {
        return getPropertyStringArray(KEY_TO);
    }

    /**
     * Get the CC list of {@link AppSearchEmail}.
     *
     * @return Returns the CC list of {@link AppSearchEmail} or {@code null} if it's not been set
     *         yet.
     */
    @Nullable
    public String[] getCc() {
        return getPropertyStringArray(KEY_CC);
    }

    /**
     * Get the BCC list of {@link AppSearchEmail}.
     *
     * @return Returns the BCC list of {@link AppSearchEmail} or {@code null} if it's not been set
     *         yet.
     */
    @Nullable
    public String[] getBcc() {
        return getPropertyStringArray(KEY_BCC);
    }

    /**
     * Get the subject of {@link AppSearchEmail}.
     *
     * @return Returns the value subject of {@link AppSearchEmail} or {@code null} if it's not been
     *         set yet.
     */
    @Nullable
    public String getSubject() {
        return getPropertyString(KEY_SUBJECT);
    }

    /**
     * Get the body of {@link AppSearchEmail}.
     *
     * @return Returns the body of {@link AppSearchEmail} or {@code null} if it's not been set yet.
     */
    @Nullable
    public String getBody() {
        return getPropertyString(KEY_BODY);
    }

    /**
     * The builder class for {@link AppSearchEmail}.
     */
    public static class Builder extends GenericDocument.Builder<AppSearchEmail.Builder> {

        /**
         * Create a new {@link AppSearchEmail.Builder}
         * @param uri The Uri of the Email.
         */
        public Builder(@NonNull String uri) {
            super(uri, SCHEMA_TYPE);
        }

        /**
         * Set the from address of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setFrom(@NonNull String from) {
            setProperty(KEY_FROM, from);
            return this;
        }

        /**
         * Set the destination address of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setTo(@NonNull String... to) {
            setProperty(KEY_TO, to);
            return this;
        }

        /**
         * Set the CC list of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setCc(@NonNull String... cc) {
            setProperty(KEY_CC, cc);
            return this;
        }

        /**
         * Set the BCC list of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setBcc(@NonNull String... bcc) {
            setProperty(KEY_BCC, bcc);
            return this;
        }

        /**
         * Set the subject of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setSubject(@NonNull String subject) {
            setProperty(KEY_SUBJECT, subject);
            return this;
        }

        /**
         * Set the body of {@link AppSearchEmail}
         */
        @NonNull
        public AppSearchEmail.Builder setBody(@NonNull String body) {
            setProperty(KEY_BODY, body);
            return this;
        }

        /** Builds the {@link AppSearchEmail} object. */
        @NonNull
        @Override
        public AppSearchEmail build() {
            return new AppSearchEmail(super.build());
        }
    }
}
