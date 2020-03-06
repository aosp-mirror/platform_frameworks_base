/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;

import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.TermMatchType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

/**
 * The AppSearch Schema for a particular type of document.
 *
 * <p>For example, an e-mail message or a music recording could be a schema type.
 *
 * <p>The schema consists of type information, properties, and config (like tokenization type).
 *
 * @hide
 */
public final class AppSearchSchema {
    private final SchemaTypeConfigProto mProto;

    private AppSearchSchema(SchemaTypeConfigProto proto) {
        mProto = proto;
    }

    /**
     * Returns the {@link SchemaTypeConfigProto} populated by this builder.
     * @hide
     */
    @NonNull
    @VisibleForTesting
    public SchemaTypeConfigProto getProto() {
        return mProto;
    }

    @Override
    public String toString() {
        return mProto.toString();
    }

    /** Builder for {@link AppSearchSchema objects}. */
    public static final class Builder {
        private final SchemaTypeConfigProto.Builder mProtoBuilder =
                SchemaTypeConfigProto.newBuilder();

        /** Creates a new {@link AppSearchSchema.Builder}. */
        public Builder(@NonNull String typeName) {
            mProtoBuilder.setSchemaType(typeName);
        }

        /** Adds a property to the given type. */
        @NonNull
        public AppSearchSchema.Builder addProperty(@NonNull PropertyConfig propertyConfig) {
            mProtoBuilder.addProperties(propertyConfig.mProto);
            return this;
        }

        /**
         * Constructs a new {@link AppSearchSchema} from the contents of this builder.
         *
         * <p>After calling this method, the builder must no longer be used.
         */
        @NonNull
        public AppSearchSchema build() {
            Set<String> propertyNames = new ArraySet<>();
            for (PropertyConfigProto propertyConfigProto : mProtoBuilder.getPropertiesList()) {
                if (!propertyNames.add(propertyConfigProto.getPropertyName())) {
                    throw new IllegalSchemaException(
                            "Property defined more than once: "
                                    + propertyConfigProto.getPropertyName());
                }
            }
            return new AppSearchSchema(mProtoBuilder.build());
        }
    }

    /**
     * Configuration for a single property (field) of a document type.
     *
     * <p>For example, an {@code EmailMessage} would be a type and the {@code subject} would be
     * a property.
     */
    public static final class PropertyConfig {
        /** Physical data-types of the contents of the property. */
        // NOTE: The integer values of these constants must match the proto enum constants in
        // com.google.android.icing.proto.PropertyConfigProto.DataType.Code.
        @IntDef(prefix = {"DATA_TYPE_"}, value = {
                DATA_TYPE_STRING,
                DATA_TYPE_INT64,
                DATA_TYPE_DOUBLE,
                DATA_TYPE_BOOLEAN,
                DATA_TYPE_BYTES,
                DATA_TYPE_DOCUMENT,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface DataType {}

        public static final int DATA_TYPE_STRING = 1;
        public static final int DATA_TYPE_INT64 = 2;
        public static final int DATA_TYPE_DOUBLE = 3;
        public static final int DATA_TYPE_BOOLEAN = 4;

        /** Unstructured BLOB. */
        public static final int DATA_TYPE_BYTES = 5;

        /**
         * Indicates that the property itself is an Document, making it part a hierarchical
         * Document schema. Any property using this DataType MUST have a valid
         * {@code schemaType}.
         */
        public static final int DATA_TYPE_DOCUMENT = 6;

        /** The cardinality of the property (whether it is required, optional or repeated). */
        // NOTE: The integer values of these constants must match the proto enum constants in
        // com.google.android.icing.proto.PropertyConfigProto.Cardinality.Code.
        @IntDef(prefix = {"CARDINALITY_"}, value = {
                CARDINALITY_REPEATED,
                CARDINALITY_OPTIONAL,
                CARDINALITY_REQUIRED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Cardinality {}

        /** Any number of items (including zero) [0...*]. */
        public static final int CARDINALITY_REPEATED = 1;

        /** Zero or one value [0,1]. */
        public static final int CARDINALITY_OPTIONAL = 2;

        /** Exactly one value [1]. */
        public static final int CARDINALITY_REQUIRED = 3;

        /** Encapsulates the configurations on how AppSearch should query/index these terms. */
        @IntDef(prefix = {"INDEXING_TYPE_"}, value = {
                INDEXING_TYPE_NONE,
                INDEXING_TYPE_EXACT_TERMS,
                INDEXING_TYPE_PREFIXES,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface IndexingType {}

        /**
         * Content in this property will not be tokenized or indexed.
         *
         * <p>Useful if the data type is not made up of terms (e.g.
         * {@link PropertyConfig#DATA_TYPE_DOCUMENT} or {@link PropertyConfig#DATA_TYPE_BYTES}
         * type). All the properties inside the nested property won't be indexed regardless of the
         * value of {@code indexingType} for the nested properties.
         */
        public static final int INDEXING_TYPE_NONE = 0;

        /**
         * Content in this property should only be returned for queries matching the exact tokens
         * appearing in this property.
         *
         * <p>Ex. A property with "fool" should NOT match a query for "foo".
         */
        public static final int INDEXING_TYPE_EXACT_TERMS = 1;

        /**
         * Content in this property should be returned for queries that are either exact matches or
         * query matches of the tokens appearing in this property.
         *
         * <p>Ex. A property with "fool" <b>should</b> match a query for "foo".
         */
        public static final int INDEXING_TYPE_PREFIXES = 2;

        /** Configures how tokens should be extracted from this property. */
        // NOTE: The integer values of these constants must match the proto enum constants in
        // com.google.android.icing.proto.IndexingConfig.TokenizerType.Code.
        @IntDef(prefix = {"TOKENIZER_TYPE_"}, value = {
                TOKENIZER_TYPE_NONE,
                TOKENIZER_TYPE_PLAIN,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface TokenizerType {}

        /**
         * It is only valid for tokenizer_type to be 'NONE' if the data type is
         * {@link PropertyConfig#DATA_TYPE_DOCUMENT}.
         */
        public static final int TOKENIZER_TYPE_NONE = 0;

        /** Tokenization for plain text. */
        public static final int TOKENIZER_TYPE_PLAIN = 1;

        private final PropertyConfigProto mProto;

        private PropertyConfig(PropertyConfigProto proto) {
            mProto = proto;
        }

        @Override
        public String toString() {
            return mProto.toString();
        }

        /**
         * Builder for {@link PropertyConfig}.
         *
         * <p>The following properties must be set, or {@link PropertyConfig} construction will
         * fail:
         * <ul>
         *     <li>dataType
         *     <li>cardinality
         * </ul>
         *
         * <p>In addition, if {@code schemaType} is {@link #DATA_TYPE_DOCUMENT}, {@code schemaType}
         * is also required.
         */
        public static final class Builder {
            private final PropertyConfigProto.Builder mPropertyConfigProto =
                    PropertyConfigProto.newBuilder();
            private final com.google.android.icing.proto.IndexingConfig.Builder
                    mIndexingConfigProto =
                        com.google.android.icing.proto.IndexingConfig.newBuilder();

            /** Creates a new {@link PropertyConfig.Builder}. */
            public Builder(@NonNull String propertyName) {
                mPropertyConfigProto.setPropertyName(propertyName);
            }

            /**
             * Type of data the property contains (e.g. string, int, bytes, etc).
             *
             * <p>This property must be set.
             */
            @NonNull
            public PropertyConfig.Builder setDataType(@DataType int dataType) {
                PropertyConfigProto.DataType.Code dataTypeProto =
                        PropertyConfigProto.DataType.Code.forNumber(dataType);
                if (dataTypeProto == null) {
                    throw new IllegalArgumentException("Invalid dataType: " + dataType);
                }
                mPropertyConfigProto.setDataType(dataTypeProto);
                return this;
            }

            /**
             * The logical schema-type of the contents of this property.
             *
             * <p>Only required when {@link #setDataType(int)} is set to
             * {@link #DATA_TYPE_DOCUMENT}. Otherwise, it is ignored.
             */
            @NonNull
            public PropertyConfig.Builder setSchemaType(@NonNull String schemaType) {
                mPropertyConfigProto.setSchemaType(schemaType);
                return this;
            }

            /**
             * The cardinality of the property (whether it is optional, required or repeated).
             *
             * <p>This property must be set.
             */
            @NonNull
            public PropertyConfig.Builder setCardinality(@Cardinality int cardinality) {
                PropertyConfigProto.Cardinality.Code cardinalityProto =
                        PropertyConfigProto.Cardinality.Code.forNumber(cardinality);
                if (cardinalityProto == null) {
                    throw new IllegalArgumentException("Invalid cardinality: " + cardinality);
                }
                mPropertyConfigProto.setCardinality(cardinalityProto);
                return this;
            }

            /**
             * Configures how a property should be indexed so that it can be retrieved by queries.
             */
            @NonNull
            public PropertyConfig.Builder setIndexingType(@IndexingType int indexingType) {
                TermMatchType.Code termMatchTypeProto;
                switch (indexingType) {
                    case INDEXING_TYPE_NONE:
                        termMatchTypeProto = TermMatchType.Code.UNKNOWN;
                        break;
                    case INDEXING_TYPE_EXACT_TERMS:
                        termMatchTypeProto = TermMatchType.Code.EXACT_ONLY;
                        break;
                    case INDEXING_TYPE_PREFIXES:
                        termMatchTypeProto = TermMatchType.Code.PREFIX;
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid indexingType: " + indexingType);
                }
                mIndexingConfigProto.setTermMatchType(termMatchTypeProto);
                return this;
            }

            /** Configures how this property should be tokenized (split into words). */
            @NonNull
            public PropertyConfig.Builder setTokenizerType(@TokenizerType int tokenizerType) {
                com.google.android.icing.proto.IndexingConfig.TokenizerType.Code
                        tokenizerTypeProto =
                            com.google.android.icing.proto.IndexingConfig
                                .TokenizerType.Code.forNumber(tokenizerType);
                if (tokenizerTypeProto == null) {
                    throw new IllegalArgumentException("Invalid tokenizerType: " + tokenizerType);
                }
                mIndexingConfigProto.setTokenizerType(tokenizerTypeProto);
                return this;
            }

            /**
             * Constructs a new {@link PropertyConfig} from the contents of this builder.
             *
             * <p>After calling this method, the builder must no longer be used.
             *
             * @throws IllegalSchemaException If the property is not correctly populated (e.g.
             *     missing {@code dataType}).
             */
            @NonNull
            public PropertyConfig build() {
                mPropertyConfigProto.setIndexingConfig(mIndexingConfigProto);
                // TODO(b/147692920): Send the schema to Icing Lib for official validation, instead
                //     of partially reimplementing some of the validation Icing does here.
                if (mPropertyConfigProto.getDataType()
                        == PropertyConfigProto.DataType.Code.UNKNOWN) {
                    throw new IllegalSchemaException("Missing field: dataType");
                }
                if (mPropertyConfigProto.getSchemaType().isEmpty()
                        && mPropertyConfigProto.getDataType()
                            == PropertyConfigProto.DataType.Code.DOCUMENT) {
                    throw new IllegalSchemaException(
                            "Missing field: schemaType (required for configs with "
                                    + "dataType = DOCUMENT)");
                }
                if (mPropertyConfigProto.getCardinality()
                        == PropertyConfigProto.Cardinality.Code.UNKNOWN) {
                    throw new IllegalSchemaException("Missing field: cardinality");
                }
                return new PropertyConfig(mPropertyConfigProto.build());
            }
        }
    }
}
