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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.exceptions.IllegalSchemaException;
import android.app.appsearch.util.BundleUtil;
import android.os.Bundle;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The AppSearch Schema for a particular type of document.
 *
 * <p>For example, an e-mail message or a music recording could be a schema type.
 *
 * <p>The schema consists of type information, properties, and config (like tokenization type).
 *
 * @see AppSearchSession#setSchema
 */
public final class AppSearchSchema {
    private static final String SCHEMA_TYPE_FIELD = "schemaType";
    private static final String PROPERTIES_FIELD = "properties";

    private final Bundle mBundle;

    /** @hide */
    public AppSearchSchema(@NonNull Bundle bundle) {
        Preconditions.checkNotNull(bundle);
        mBundle = bundle;
    }

    /**
     * Returns the {@link Bundle} populated by this builder.
     *
     * @hide
     */
    @NonNull
    public Bundle getBundle() {
        return mBundle;
    }

    @Override
    public String toString() {
        return mBundle.toString();
    }

    /** Returns the name of this schema type, e.g. Email. */
    @NonNull
    public String getSchemaType() {
        return mBundle.getString(SCHEMA_TYPE_FIELD, "");
    }

    /** @deprecated Use {@link GetSchemaResponse#getVersion()} instead. */
    @Deprecated
    public @IntRange(from = 0) int getVersion() {
        return 0;
    }

    /**
     * Returns the list of {@link PropertyConfig}s that are part of this schema.
     *
     * <p>This method creates a new list when called.
     */
    @NonNull
    @SuppressWarnings("MixedMutabilityReturnType")
    public List<PropertyConfig> getProperties() {
        ArrayList<Bundle> propertyBundles =
                mBundle.getParcelableArrayList(AppSearchSchema.PROPERTIES_FIELD);
        if (propertyBundles.isEmpty()) {
            return Collections.emptyList();
        }
        List<PropertyConfig> ret = new ArrayList<>(propertyBundles.size());
        for (int i = 0; i < propertyBundles.size(); i++) {
            ret.add(PropertyConfig.fromBundle(propertyBundles.get(i)));
        }
        return ret;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AppSearchSchema)) {
            return false;
        }
        AppSearchSchema otherSchema = (AppSearchSchema) other;
        if (!getSchemaType().equals(otherSchema.getSchemaType())) {
            return false;
        }
        return getProperties().equals(otherSchema.getProperties());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSchemaType(), getProperties());
    }

    /** Builder for {@link AppSearchSchema objects}. */
    public static final class Builder {
        private final String mSchemaType;
        private final ArrayList<Bundle> mPropertyBundles = new ArrayList<>();
        private final Set<String> mPropertyNames = new ArraySet<>();
        private boolean mBuilt = false;

        /** Creates a new {@link AppSearchSchema.Builder}. */
        public Builder(@NonNull String schemaType) {
            Preconditions.checkNotNull(schemaType);
            mSchemaType = schemaType;
        }

        /** Adds a property to the given type. */
        @NonNull
        public AppSearchSchema.Builder addProperty(@NonNull PropertyConfig propertyConfig) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(propertyConfig);
            String name = propertyConfig.getName();
            if (!mPropertyNames.add(name)) {
                throw new IllegalSchemaException("Property defined more than once: " + name);
            }
            mPropertyBundles.add(propertyConfig.mBundle);
            return this;
        }

        /**
         * @deprecated TODO(b/181887768): This method is a no-op and only exists for dogfooder
         *     transition.
         */
        @Deprecated
        @NonNull
        public AppSearchSchema.Builder setVersion(@IntRange(from = 0) int version) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            return this;
        }

        /**
         * Constructs a new {@link AppSearchSchema} from the contents of this builder.
         *
         * <p>After calling this method, the builder must no longer be used.
         */
        @NonNull
        public AppSearchSchema build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Bundle bundle = new Bundle();
            bundle.putString(AppSearchSchema.SCHEMA_TYPE_FIELD, mSchemaType);
            bundle.putParcelableArrayList(AppSearchSchema.PROPERTIES_FIELD, mPropertyBundles);
            mBuilt = true;
            return new AppSearchSchema(bundle);
        }
    }

    /**
     * Common configuration for a single property (field) in a Document.
     *
     * <p>For example, an {@code EmailMessage} would be a type and the {@code subject} would be a
     * property.
     */
    public abstract static class PropertyConfig {
        static final String NAME_FIELD = "name";
        static final String DATA_TYPE_FIELD = "dataType";
        static final String CARDINALITY_FIELD = "cardinality";

        /**
         * Physical data-types of the contents of the property.
         *
         * @hide
         */
        // NOTE: The integer values of these constants must match the proto enum constants in
        // com.google.android.icing.proto.PropertyConfigProto.DataType.Code.
        @IntDef(
                value = {
                    DATA_TYPE_STRING,
                    DATA_TYPE_INT64,
                    DATA_TYPE_DOUBLE,
                    DATA_TYPE_BOOLEAN,
                    DATA_TYPE_BYTES,
                    DATA_TYPE_DOCUMENT,
                })
        @Retention(RetentionPolicy.SOURCE)
        public @interface DataType {}

        /** @hide */
        public static final int DATA_TYPE_STRING = 1;

        /** @hide */
        public static final int DATA_TYPE_INT64 = 2;

        /** @hide */
        public static final int DATA_TYPE_DOUBLE = 3;

        /** @hide */
        public static final int DATA_TYPE_BOOLEAN = 4;

        /**
         * Unstructured BLOB.
         *
         * @hide
         */
        public static final int DATA_TYPE_BYTES = 5;

        /**
         * Indicates that the property is itself a {@link GenericDocument}, making it part of a
         * hierarchical schema. Any property using this DataType MUST have a valid {@link
         * PropertyConfig#getSchemaType}.
         *
         * @hide
         */
        public static final int DATA_TYPE_DOCUMENT = 6;

        /**
         * The cardinality of the property (whether it is required, optional or repeated).
         *
         * @hide
         */
        // NOTE: The integer values of these constants must match the proto enum constants in
        // com.google.android.icing.proto.PropertyConfigProto.Cardinality.Code.
        @IntDef(
                value = {
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

        final Bundle mBundle;

        @Nullable private Integer mHashCode;

        PropertyConfig(@NonNull Bundle bundle) {
            mBundle = Preconditions.checkNotNull(bundle);
        }

        @Override
        public String toString() {
            return mBundle.toString();
        }

        /** Returns the name of this property. */
        @NonNull
        public String getName() {
            return mBundle.getString(NAME_FIELD, "");
        }

        /**
         * Returns the type of data the property contains (e.g. string, int, bytes, etc).
         *
         * @hide
         */
        public @DataType int getDataType() {
            return mBundle.getInt(DATA_TYPE_FIELD, -1);
        }

        /**
         * Returns the cardinality of the property (whether it is optional, required or repeated).
         */
        public @Cardinality int getCardinality() {
            return mBundle.getInt(CARDINALITY_FIELD, -1);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PropertyConfig)) {
                return false;
            }
            PropertyConfig otherProperty = (PropertyConfig) other;
            return BundleUtil.deepEquals(this.mBundle, otherProperty.mBundle);
        }

        @Override
        public int hashCode() {
            if (mHashCode == null) {
                mHashCode = BundleUtil.deepHashCode(mBundle);
            }
            return mHashCode;
        }

        /**
         * Converts a {@link Bundle} into a {@link PropertyConfig} depending on its internal data
         * type.
         *
         * <p>The bundle is not cloned.
         *
         * @throws IllegalArgumentException if the bundle does no contain a recognized value in its
         *     {@code DATA_TYPE_FIELD}.
         * @hide
         */
        @NonNull
        public static PropertyConfig fromBundle(@NonNull Bundle propertyBundle) {
            switch (propertyBundle.getInt(PropertyConfig.DATA_TYPE_FIELD)) {
                case PropertyConfig.DATA_TYPE_STRING:
                    return new StringPropertyConfig(propertyBundle);
                case PropertyConfig.DATA_TYPE_INT64:
                    return new Int64PropertyConfig(propertyBundle);
                case PropertyConfig.DATA_TYPE_DOUBLE:
                    return new DoublePropertyConfig(propertyBundle);
                case PropertyConfig.DATA_TYPE_BOOLEAN:
                    return new BooleanPropertyConfig(propertyBundle);
                case PropertyConfig.DATA_TYPE_BYTES:
                    return new BytesPropertyConfig(propertyBundle);
                case PropertyConfig.DATA_TYPE_DOCUMENT:
                    return new DocumentPropertyConfig(propertyBundle);
                default:
                    throw new IllegalArgumentException(
                            "Unsupported property bundle of type "
                                    + propertyBundle.getInt(PropertyConfig.DATA_TYPE_FIELD)
                                    + "; contents: "
                                    + propertyBundle);
            }
        }
    }

    /** Configuration for a property of type String in a Document. */
    public static final class StringPropertyConfig extends PropertyConfig {
        private static final String INDEXING_TYPE_FIELD = "indexingType";
        private static final String TOKENIZER_TYPE_FIELD = "tokenizerType";

        /**
         * Encapsulates the configurations on how AppSearch should query/index these terms.
         *
         * @hide
         */
        @IntDef(
                value = {
                    INDEXING_TYPE_NONE,
                    INDEXING_TYPE_EXACT_TERMS,
                    INDEXING_TYPE_PREFIXES,
                })
        @Retention(RetentionPolicy.SOURCE)
        public @interface IndexingType {}

        /** Content in this property will not be tokenized or indexed. */
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

        /**
         * Configures how tokens should be extracted from this property.
         *
         * @hide
         */
        // NOTE: The integer values of these constants must match the proto enum constants in
        // com.google.android.icing.proto.IndexingConfig.TokenizerType.Code.
        @IntDef(
                value = {
                    TOKENIZER_TYPE_NONE,
                    TOKENIZER_TYPE_PLAIN,
                })
        @Retention(RetentionPolicy.SOURCE)
        public @interface TokenizerType {}

        /**
         * It is only valid for tokenizer_type to be 'NONE' if {@link #getIndexingType} is {@link
         * #INDEXING_TYPE_NONE}.
         */
        public static final int TOKENIZER_TYPE_NONE = 0;

        /** Tokenization for plain text. */
        public static final int TOKENIZER_TYPE_PLAIN = 1;

        StringPropertyConfig(@NonNull Bundle bundle) {
            super(bundle);
        }

        /** Returns how the property is indexed. */
        public @IndexingType int getIndexingType() {
            return mBundle.getInt(INDEXING_TYPE_FIELD);
        }

        /** Returns how this property is tokenized (split into words). */
        public @TokenizerType int getTokenizerType() {
            return mBundle.getInt(TOKENIZER_TYPE_FIELD);
        }

        /**
         * Builder for {@link StringPropertyConfig}.
         *
         * <p>{@link #setCardinality} must be called or {@link #build} will fail.
         */
        public static final class Builder {
            private final Bundle mBundle = new Bundle();
            private boolean mBuilt = false;

            /** Creates a new {@link StringPropertyConfig.Builder}. */
            public Builder(@NonNull String propertyName) {
                mBundle.putString(NAME_FIELD, propertyName);
                mBundle.putInt(DATA_TYPE_FIELD, DATA_TYPE_STRING);
            }

            /**
             * The cardinality of the property (whether it is optional, required or repeated).
             *
             * <p>This property must be set.
             */
            @SuppressWarnings("MissingGetterMatchingBuilder") // getter defined in superclass
            @NonNull
            public StringPropertyConfig.Builder setCardinality(@Cardinality int cardinality) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkArgumentInRange(
                        cardinality, CARDINALITY_REPEATED, CARDINALITY_REQUIRED, "cardinality");
                mBundle.putInt(CARDINALITY_FIELD, cardinality);
                return this;
            }

            /**
             * Configures how a property should be indexed so that it can be retrieved by queries.
             */
            @NonNull
            public StringPropertyConfig.Builder setIndexingType(@IndexingType int indexingType) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkArgumentInRange(
                        indexingType, INDEXING_TYPE_NONE, INDEXING_TYPE_PREFIXES, "indexingType");
                mBundle.putInt(INDEXING_TYPE_FIELD, indexingType);
                return this;
            }

            /** Configures how this property should be tokenized (split into words). */
            @NonNull
            public StringPropertyConfig.Builder setTokenizerType(@TokenizerType int tokenizerType) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkArgumentInRange(
                        tokenizerType, TOKENIZER_TYPE_NONE, TOKENIZER_TYPE_PLAIN, "tokenizerType");
                mBundle.putInt(TOKENIZER_TYPE_FIELD, tokenizerType);
                return this;
            }

            /**
             * Constructs a new {@link StringPropertyConfig} from the contents of this builder.
             *
             * <p>After calling this method, the builder must no longer be used.
             *
             * @throws IllegalSchemaException if the property is not correctly populated
             */
            @NonNull
            public StringPropertyConfig build() {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                // TODO(b/147692920): Send the schema to Icing Lib for official validation, instead
                //     of partially reimplementing some of the validation Icing does here.
                if (!mBundle.containsKey(CARDINALITY_FIELD)) {
                    throw new IllegalSchemaException("Missing field: cardinality");
                }
                mBuilt = true;
                return new StringPropertyConfig(mBundle);
            }
        }
    }

    /** Configuration for a property containing a 64-bit integer. */
    public static final class Int64PropertyConfig extends PropertyConfig {
        Int64PropertyConfig(@NonNull Bundle bundle) {
            super(bundle);
        }

        /**
         * Builder for {@link Int64PropertyConfig}.
         *
         * <p>{@link #setCardinality} must be called or {@link #build} will fail.
         */
        public static final class Builder {
            private final Bundle mBundle = new Bundle();
            private boolean mBuilt = false;

            /** Creates a new {@link Int64PropertyConfig.Builder}. */
            public Builder(@NonNull String propertyName) {
                mBundle.putString(NAME_FIELD, propertyName);
                mBundle.putInt(DATA_TYPE_FIELD, DATA_TYPE_INT64);
            }

            /**
             * The cardinality of the property (whether it is optional, required or repeated).
             *
             * <p>This property must be set.
             */
            @SuppressWarnings("MissingGetterMatchingBuilder") // getter defined in superclass
            @NonNull
            public Int64PropertyConfig.Builder setCardinality(@Cardinality int cardinality) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkArgumentInRange(
                        cardinality, CARDINALITY_REPEATED, CARDINALITY_REQUIRED, "cardinality");
                mBundle.putInt(CARDINALITY_FIELD, cardinality);
                return this;
            }

            /**
             * Constructs a new {@link Int64PropertyConfig} from the contents of this builder.
             *
             * <p>After calling this method, the builder must no longer be used.
             *
             * @throws IllegalSchemaException if the property is not correctly populated
             */
            @NonNull
            public Int64PropertyConfig build() {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                if (!mBundle.containsKey(CARDINALITY_FIELD)) {
                    throw new IllegalSchemaException("Missing field: cardinality");
                }
                mBuilt = true;
                return new Int64PropertyConfig(mBundle);
            }
        }
    }

    /** Configuration for a property containing a double-precision decimal number. */
    public static final class DoublePropertyConfig extends PropertyConfig {
        DoublePropertyConfig(@NonNull Bundle bundle) {
            super(bundle);
        }

        /**
         * Builder for {@link DoublePropertyConfig}.
         *
         * <p>{@link #setCardinality} must be called or {@link #build} will fail.
         */
        public static final class Builder {
            private final Bundle mBundle = new Bundle();
            private boolean mBuilt = false;

            /** Creates a new {@link DoublePropertyConfig.Builder}. */
            public Builder(@NonNull String propertyName) {
                mBundle.putString(NAME_FIELD, propertyName);
                mBundle.putInt(DATA_TYPE_FIELD, DATA_TYPE_DOUBLE);
            }

            /**
             * The cardinality of the property (whether it is optional, required or repeated).
             *
             * <p>This property must be set.
             */
            @SuppressWarnings("MissingGetterMatchingBuilder") // getter defined in superclass
            @NonNull
            public DoublePropertyConfig.Builder setCardinality(@Cardinality int cardinality) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkArgumentInRange(
                        cardinality, CARDINALITY_REPEATED, CARDINALITY_REQUIRED, "cardinality");
                mBundle.putInt(CARDINALITY_FIELD, cardinality);
                return this;
            }

            /**
             * Constructs a new {@link DoublePropertyConfig} from the contents of this builder.
             *
             * <p>After calling this method, the builder must no longer be used.
             *
             * @throws IllegalSchemaException if the property is not correctly populated
             */
            @NonNull
            public DoublePropertyConfig build() {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                if (!mBundle.containsKey(CARDINALITY_FIELD)) {
                    throw new IllegalSchemaException("Missing field: cardinality");
                }
                mBuilt = true;
                return new DoublePropertyConfig(mBundle);
            }
        }
    }

    /** Configuration for a property containing a boolean. */
    public static final class BooleanPropertyConfig extends PropertyConfig {
        BooleanPropertyConfig(@NonNull Bundle bundle) {
            super(bundle);
        }

        /**
         * Builder for {@link BooleanPropertyConfig}.
         *
         * <p>{@link #setCardinality} must be called or {@link #build} will fail.
         */
        public static final class Builder {
            private final Bundle mBundle = new Bundle();
            private boolean mBuilt = false;

            /** Creates a new {@link BooleanPropertyConfig.Builder}. */
            public Builder(@NonNull String propertyName) {
                mBundle.putString(NAME_FIELD, propertyName);
                mBundle.putInt(DATA_TYPE_FIELD, DATA_TYPE_BOOLEAN);
            }

            /**
             * The cardinality of the property (whether it is optional, required or repeated).
             *
             * <p>This property must be set.
             */
            @SuppressWarnings("MissingGetterMatchingBuilder") // getter defined in superclass
            @NonNull
            public BooleanPropertyConfig.Builder setCardinality(@Cardinality int cardinality) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkArgumentInRange(
                        cardinality, CARDINALITY_REPEATED, CARDINALITY_REQUIRED, "cardinality");
                mBundle.putInt(CARDINALITY_FIELD, cardinality);
                return this;
            }

            /**
             * Constructs a new {@link BooleanPropertyConfig} from the contents of this builder.
             *
             * <p>After calling this method, the builder must no longer be used.
             *
             * @throws IllegalSchemaException if the property is not correctly populated
             */
            @NonNull
            public BooleanPropertyConfig build() {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                if (!mBundle.containsKey(CARDINALITY_FIELD)) {
                    throw new IllegalSchemaException("Missing field: cardinality");
                }
                mBuilt = true;
                return new BooleanPropertyConfig(mBundle);
            }
        }
    }

    /** Configuration for a property containing a byte array. */
    public static final class BytesPropertyConfig extends PropertyConfig {
        BytesPropertyConfig(@NonNull Bundle bundle) {
            super(bundle);
        }

        /**
         * Builder for {@link BytesPropertyConfig}.
         *
         * <p>{@link #setCardinality} must be called or {@link #build} will fail.
         */
        public static final class Builder {
            private final Bundle mBundle = new Bundle();
            private boolean mBuilt = false;

            /** Creates a new {@link BytesPropertyConfig.Builder}. */
            public Builder(@NonNull String propertyName) {
                mBundle.putString(NAME_FIELD, propertyName);
                mBundle.putInt(DATA_TYPE_FIELD, DATA_TYPE_BYTES);
            }

            /**
             * The cardinality of the property (whether it is optional, required or repeated).
             *
             * <p>This property must be set.
             */
            @SuppressWarnings("MissingGetterMatchingBuilder") // getter defined in superclass
            @NonNull
            public BytesPropertyConfig.Builder setCardinality(@Cardinality int cardinality) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkArgumentInRange(
                        cardinality, CARDINALITY_REPEATED, CARDINALITY_REQUIRED, "cardinality");
                mBundle.putInt(CARDINALITY_FIELD, cardinality);
                return this;
            }

            /**
             * Constructs a new {@link BytesPropertyConfig} from the contents of this builder.
             *
             * <p>After calling this method, the builder must no longer be used.
             *
             * @throws IllegalSchemaException if the property is not correctly populated
             */
            @NonNull
            public BytesPropertyConfig build() {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                if (!mBundle.containsKey(CARDINALITY_FIELD)) {
                    throw new IllegalSchemaException("Missing field: cardinality");
                }
                mBuilt = true;
                return new BytesPropertyConfig(mBundle);
            }
        }
    }

    /** Configuration for a property containing another Document. */
    public static final class DocumentPropertyConfig extends PropertyConfig {
        private static final String SCHEMA_TYPE_FIELD = "schemaType";
        private static final String INDEX_NESTED_PROPERTIES_FIELD = "indexNestedProperties";

        DocumentPropertyConfig(@NonNull Bundle bundle) {
            super(bundle);
        }

        /** Returns the logical schema-type of the contents of this document property. */
        @NonNull
        public String getSchemaType() {
            return Preconditions.checkNotNull(mBundle.getString(SCHEMA_TYPE_FIELD));
        }

        /**
         * Returns whether fields in the nested document should be indexed according to that
         * document's schema.
         *
         * <p>If false, the nested document's properties are not indexed regardless of its own
         * schema.
         */
        public boolean isIndexNestedProperties() {
            return mBundle.getBoolean(INDEX_NESTED_PROPERTIES_FIELD);
        }

        /**
         * Builder for {@link DocumentPropertyConfig}.
         *
         * <p>The following properties must be set, or {@link DocumentPropertyConfig} construction
         * will fail:
         *
         * <ul>
         *   <li>cardinality
         *   <li>schemaType
         * </ul>
         */
        public static final class Builder {
            private final Bundle mBundle = new Bundle();
            private boolean mBuilt = false;

            /** Creates a new {@link DocumentPropertyConfig.Builder}. */
            public Builder(@NonNull String propertyName) {
                mBundle.putString(NAME_FIELD, propertyName);
                mBundle.putInt(DATA_TYPE_FIELD, DATA_TYPE_DOCUMENT);
            }

            /**
             * The logical schema-type of the contents of this property.
             *
             * <p>This property must be set.
             */
            @NonNull
            public DocumentPropertyConfig.Builder setSchemaType(@NonNull String schemaType) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkNotNull(schemaType);
                mBundle.putString(SCHEMA_TYPE_FIELD, schemaType);
                return this;
            }

            /**
             * The cardinality of the property (whether it is optional, required or repeated).
             *
             * <p>This property must be set.
             */
            @SuppressWarnings("MissingGetterMatchingBuilder") // getter defined in superclass
            @NonNull
            public DocumentPropertyConfig.Builder setCardinality(@Cardinality int cardinality) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkArgumentInRange(
                        cardinality, CARDINALITY_REPEATED, CARDINALITY_REQUIRED, "cardinality");
                mBundle.putInt(CARDINALITY_FIELD, cardinality);
                return this;
            }

            /**
             * Configures whether fields in the nested document should be indexed according to that
             * document's schema.
             *
             * <p>If false, the nested document's properties are not indexed regardless of its own
             * schema.
             */
            @NonNull
            public DocumentPropertyConfig.Builder setIndexNestedProperties(
                    boolean indexNestedProperties) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                mBundle.putBoolean(INDEX_NESTED_PROPERTIES_FIELD, indexNestedProperties);
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
            public DocumentPropertyConfig build() {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                if (mBundle.getString(SCHEMA_TYPE_FIELD, "").isEmpty()) {
                    throw new IllegalSchemaException("Missing field: schemaType");
                }
                if (!mBundle.containsKey(CARDINALITY_FIELD)) {
                    throw new IllegalSchemaException("Missing field: cardinality");
                }
                mBuilt = true;
                return new DocumentPropertyConfig(mBundle);
            }
        }
    }
}
