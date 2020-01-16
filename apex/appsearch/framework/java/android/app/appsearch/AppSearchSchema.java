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

import com.android.internal.annotations.VisibleForTesting;

import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Representation of the AppSearch Schema.
 *
 * <p>The schema is the set of document types, properties, and config (like tokenization type)
 * understood by AppSearch for this app.
 *
 * @hide
 */
public final class AppSearchSchema {
    private final SchemaProto mProto;

    private AppSearchSchema(SchemaProto proto) {
        mProto = proto;
    }

    /** Creates a new {@link AppSearchSchema.Builder}. */
    @NonNull
    public static AppSearchSchema.Builder newBuilder() {
        return new AppSearchSchema.Builder();
    }

    /** Creates a new {@link SchemaType.Builder}. */
    @NonNull
    public static SchemaType.Builder newSchemaTypeBuilder(@NonNull String typeName) {
        return new SchemaType.Builder(typeName);
    }

    /** Creates a new {@link PropertyConfig.Builder}. */
    @NonNull
    public static PropertyConfig.Builder newPropertyBuilder(@NonNull String propertyName) {
        return new PropertyConfig.Builder(propertyName);
    }

    /** Creates a new {@link IndexingConfig.Builder}. */
    @NonNull
    public static IndexingConfig.Builder newIndexingConfigBuilder() {
        return new IndexingConfig.Builder();
    }

    /**
     * Returns the schema proto populated by the {@link AppSearchSchema} builders.
     * @hide
     */
    @NonNull
    @VisibleForTesting
    public SchemaProto getProto() {
        return mProto;
    }

    /** Builder for {@link AppSearchSchema objects}. */
    public static final class Builder {
        private final SchemaProto.Builder mProtoBuilder = SchemaProto.newBuilder();

        private Builder() {}

        /** Adds a supported type to this app's AppSearch schema. */
        @NonNull
        public AppSearchSchema.Builder addType(@NonNull SchemaType schemaType) {
            mProtoBuilder.addTypes(schemaType.mProto);
            return this;
        }

        /**
         * Constructs a new {@link AppSearchSchema} from the contents of this builder.
         *
         * <p>After calling this method, the builder must no longer be used.
         */
        @NonNull
        public AppSearchSchema build() {
            return new AppSearchSchema(mProtoBuilder.build());
        }
    }

    /**
     * Represents a type of a document.
     *
     * <p>For example, an e-mail message or a music recording could be a schema type.
     */
    public static final class SchemaType {
        private final SchemaTypeConfigProto mProto;

        private SchemaType(SchemaTypeConfigProto proto) {
            mProto = proto;
        }

        /** Builder for {@link SchemaType} objects. */
        public static final class Builder {
            private final SchemaTypeConfigProto.Builder mProtoBuilder =
                    SchemaTypeConfigProto.newBuilder();

            private Builder(@NonNull String typeName) {
                mProtoBuilder.setSchemaType(typeName);
            }

            /** Adds a property to the given type. */
            @NonNull
            public SchemaType.Builder addProperty(@NonNull PropertyConfig propertyConfig) {
                mProtoBuilder.addProperties(propertyConfig.mProto);
                return this;
            }

            /**
             * Constructs a new {@link SchemaType} from the contents of this builder.
             *
             * <p>After calling this method, the builder must no longer be used.
             */
            @NonNull
            public SchemaType build() {
                return new SchemaType(mProtoBuilder.build());
            }
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

        private final PropertyConfigProto mProto;

        private PropertyConfig(PropertyConfigProto proto) {
            mProto = proto;
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
            private final PropertyConfigProto.Builder mProtoBuilder =
                    PropertyConfigProto.newBuilder();

            private Builder(String propertyName) {
                mProtoBuilder.setPropertyName(propertyName);
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
                mProtoBuilder.setDataType(dataTypeProto);
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
                mProtoBuilder.setSchemaType(schemaType);
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
                mProtoBuilder.setCardinality(cardinalityProto);
                return this;
            }

            /**
             * Configures how this property should be indexed.
             *
             * <p>If this is not supplied, the property will not be indexed at all.
             */
            @NonNull
            public PropertyConfig.Builder setIndexingConfig(
                    @NonNull IndexingConfig indexingConfig) {
                mProtoBuilder.setIndexingConfig(indexingConfig.mProto);
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
                if (mProtoBuilder.getDataType() == PropertyConfigProto.DataType.Code.UNKNOWN) {
                    throw new IllegalSchemaException("Missing field: dataType");
                }
                if (mProtoBuilder.getSchemaType().isEmpty()
                        && mProtoBuilder.getDataType()
                                == PropertyConfigProto.DataType.Code.DOCUMENT) {
                    throw new IllegalSchemaException(
                            "Missing field: schemaType (required for configs with "
                                    + "dataType = DOCUMENT)");
                }
                if (mProtoBuilder.getCardinality()
                        == PropertyConfigProto.Cardinality.Code.UNKNOWN) {
                    throw new IllegalSchemaException("Missing field: cardinality");
                }
                return new PropertyConfig(mProtoBuilder.build());
            }
        }
    }

    /** Configures how a property should be indexed so that it can be retrieved by queries. */
    public static final class IndexingConfig {
        /** Encapsulates the configurations on how AppSearch should query/index these terms. */
        // NOTE: The integer values of these constants must match the proto enum constants in
        // com.google.android.icing.proto.TermMatchType.Code.
        @IntDef(prefix = {"TERM_MATCH_TYPE_"}, value = {
                TERM_MATCH_TYPE_UNKNOWN,
                TERM_MATCH_TYPE_EXACT_ONLY,
                TERM_MATCH_TYPE_PREFIX,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface TermMatchType {}

        /**
         * Content in this property will not be tokenized or indexed.
         *
         * <p>Useful if the data type is not made up of terms (e.g.
         * {@link PropertyConfig#DATA_TYPE_DOCUMENT} or {@link PropertyConfig#DATA_TYPE_BYTES}
         * type). All the properties inside the nested property won't be indexed regardless of the
         * value of {@code termMatchType} for the nested properties.
         */
        public static final int TERM_MATCH_TYPE_UNKNOWN = 0;

        /**
         * Content in this property should only be returned for queries matching the exact tokens
         * appearing in this property.
         *
         * <p>Ex. A property with "fool" should NOT match a query for "foo".
         */
        public static final int TERM_MATCH_TYPE_EXACT_ONLY = 1;

        /**
         * Content in this property should be returned for queries that are either exact matches or
         * query matches of the tokens appearing in this property.
         *
         * <p>Ex. A property with "fool" <b>should</b> match a query for "foo".
         */
        public static final int TERM_MATCH_TYPE_PREFIX = 2;

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

        private final com.google.android.icing.proto.IndexingConfig mProto;

        private IndexingConfig(com.google.android.icing.proto.IndexingConfig proto) {
            mProto = proto;
        }

        /**
         * Builder for {@link IndexingConfig} objects.
         *
         * <p>You may skip adding an {@link IndexingConfig} for a property, which is equivalent to
         * an {@link IndexingConfig} having {@code termMatchType} equal to
         * {@link #TERM_MATCH_TYPE_UNKNOWN}. In this case the property will not be indexed.
         */
        public static final class Builder {
            private final com.google.android.icing.proto.IndexingConfig.Builder mProtoBuilder =
                    com.google.android.icing.proto.IndexingConfig.newBuilder();

            private Builder() {}

            /** Configures how the content of this property should be matched in the index. */
            @NonNull
            public IndexingConfig.Builder setTermMatchType(@TermMatchType int termMatchType) {
                com.google.android.icing.proto.TermMatchType.Code termMatchTypeProto =
                        com.google.android.icing.proto.TermMatchType.Code.forNumber(termMatchType);
                if (termMatchTypeProto == null) {
                    throw new IllegalArgumentException("Invalid termMatchType: " + termMatchType);
                }
                mProtoBuilder.setTermMatchType(termMatchTypeProto);
                return this;
            }

            /** Configures how this property should be tokenized (split into words). */
            @NonNull
            public IndexingConfig.Builder setTokenizerType(@TokenizerType int tokenizerType) {
                com.google.android.icing.proto.IndexingConfig.TokenizerType.Code
                        tokenizerTypeProto =
                            com.google.android.icing.proto.IndexingConfig
                                    .TokenizerType.Code.forNumber(tokenizerType);
                if (tokenizerTypeProto == null) {
                    throw new IllegalArgumentException("Invalid tokenizerType: " + tokenizerType);
                }
                mProtoBuilder.setTokenizerType(tokenizerTypeProto);
                return this;
            }

            /**
             * Constructs a new {@link IndexingConfig} from the contents of this builder.
             *
             * <p>After calling this method, the builder must no longer be used.
             */
            @NonNull
            public IndexingConfig build() {
                return new IndexingConfig(mProtoBuilder.build());
            }
        }
    }
}
