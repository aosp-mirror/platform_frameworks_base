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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.appsearch.util.BundleUtil;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * Represents a document unit.
 *
 * <p>Documents contain structured data conforming to their {@link AppSearchSchema} type. Each
 * document is uniquely identified by a URI and namespace.
 *
 * @see AppSearchSession#put
 * @see AppSearchSession#getByUri
 * @see AppSearchSession#search
 */
public class GenericDocument {
    private static final String TAG = "AppSearchGenericDocumen";

    /** The maximum number of elements in a repeatable field. */
    private static final int MAX_REPEATED_PROPERTY_LENGTH = 100;

    /** The maximum {@link String#length} of a {@link String} field. */
    private static final int MAX_STRING_LENGTH = 20_000;

    /** The maximum number of indexed properties a document can have. */
    private static final int MAX_INDEXED_PROPERTIES = 16;

    /** The default score of document. */
    private static final int DEFAULT_SCORE = 0;

    /** The default time-to-live in millisecond of a document, which is infinity. */
    private static final long DEFAULT_TTL_MILLIS = 0L;

    private static final String PROPERTIES_FIELD = "properties";
    private static final String BYTE_ARRAY_FIELD = "byteArray";
    private static final String SCHEMA_TYPE_FIELD = "schemaType";
    private static final String URI_FIELD = "uri";
    private static final String SCORE_FIELD = "score";
    private static final String TTL_MILLIS_FIELD = "ttlMillis";
    private static final String CREATION_TIMESTAMP_MILLIS_FIELD = "creationTimestampMillis";
    private static final String NAMESPACE_FIELD = "namespace";

    /**
     * The maximum number of indexed properties a document can have.
     *
     * <p>Indexed properties are properties which are strings where the {@link
     * AppSearchSchema.StringPropertyConfig#getIndexingType} value is anything other than {@link
     * AppSearchSchema.StringPropertyConfig.IndexingType#INDEXING_TYPE_NONE}.
     */
    public static int getMaxIndexedProperties() {
        return MAX_INDEXED_PROPERTIES;
    }

    /** Contains {@link GenericDocument} basic information (uri, schemaType etc). */
    @NonNull final Bundle mBundle;

    /** Contains all properties in {@link GenericDocument} to support getting properties via keys */
    @NonNull private final Bundle mProperties;

    @NonNull private final String mUri;
    @NonNull private final String mSchemaType;
    private final long mCreationTimestampMillis;
    @Nullable private Integer mHashCode;

    /**
     * Rebuilds a {@link GenericDocument} by the a bundle.
     *
     * @param bundle Contains {@link GenericDocument} basic information (uri, schemaType etc) and a
     *     properties bundle contains all properties in {@link GenericDocument} to support getting
     *     properties via keys.
     * @hide
     */
    public GenericDocument(@NonNull Bundle bundle) {
        Preconditions.checkNotNull(bundle);
        mBundle = bundle;
        mProperties = Preconditions.checkNotNull(bundle.getParcelable(PROPERTIES_FIELD));
        mUri = Preconditions.checkNotNull(mBundle.getString(URI_FIELD));
        mSchemaType = Preconditions.checkNotNull(mBundle.getString(SCHEMA_TYPE_FIELD));
        mCreationTimestampMillis =
                mBundle.getLong(CREATION_TIMESTAMP_MILLIS_FIELD, System.currentTimeMillis());
    }

    /**
     * Creates a new {@link GenericDocument} from an existing instance.
     *
     * <p>This method should be only used by constructor of a subclass.
     */
    protected GenericDocument(@NonNull GenericDocument document) {
        this(document.mBundle);
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

    /** Returns the URI of the {@link GenericDocument}. */
    @NonNull
    public String getUri() {
        return mUri;
    }

    /** Returns the namespace of the {@link GenericDocument}. */
    @NonNull
    public String getNamespace() {
        return mBundle.getString(NAMESPACE_FIELD, /*defaultValue=*/ "");
    }

    /** Returns the {@link AppSearchSchema} type of the {@link GenericDocument}. */
    @NonNull
    public String getSchemaType() {
        return mSchemaType;
    }

    /**
     * Returns the creation timestamp of the {@link GenericDocument}, in milliseconds.
     *
     * <p>The value is in the {@link System#currentTimeMillis} time base.
     */
    public long getCreationTimestampMillis() {
        return mCreationTimestampMillis;
    }

    /**
     * Returns the TTL (time-to-live) of the {@link GenericDocument}, in milliseconds.
     *
     * <p>The TTL is measured against {@link #getCreationTimestampMillis}. At the timestamp of
     * {@code creationTimestampMillis + ttlMillis}, measured in the {@link System#currentTimeMillis}
     * time base, the document will be auto-deleted.
     *
     * <p>The default value is 0, which means the document is permanent and won't be auto-deleted
     * until the app is uninstalled or {@link AppSearchSession#remove} is called.
     */
    public long getTtlMillis() {
        return mBundle.getLong(TTL_MILLIS_FIELD, DEFAULT_TTL_MILLIS);
    }

    /**
     * Returns the score of the {@link GenericDocument}.
     *
     * <p>The score is a query-independent measure of the document's quality, relative to other
     * {@link GenericDocument} objects of the same {@link AppSearchSchema} type.
     *
     * <p>Results may be sorted by score using {@link SearchSpec.Builder#setRankingStrategy}.
     * Documents with higher scores are considered better than documents with lower scores.
     *
     * <p>Any non-negative integer can be used a score.
     */
    public int getScore() {
        return mBundle.getInt(SCORE_FIELD, DEFAULT_SCORE);
    }

    /** Returns the names of all properties defined in this document. */
    @NonNull
    public Set<String> getPropertyNames() {
        return Collections.unmodifiableSet(mProperties.keySet());
    }

    /**
     * Retrieves the property value with the given key as {@link Object}.
     *
     * @param key The key to look for.
     * @return The entry with the given key as an object or {@code null} if there is no such key.
     */
    @Nullable
    public Object getProperty(@NonNull String key) {
        Preconditions.checkNotNull(key);
        Object property = mProperties.get(key);
        if (property instanceof ArrayList) {
            return getPropertyBytesArray(key);
        } else if (property instanceof Parcelable[]) {
            return getPropertyDocumentArray(key);
        }
        return property;
    }

    /**
     * Retrieves a {@link String} value by key.
     *
     * @param key The key to look for.
     * @return The first {@link String} associated with the given key or {@code null} if there is no
     *     such key or the value is of a different type.
     */
    @Nullable
    public String getPropertyString(@NonNull String key) {
        Preconditions.checkNotNull(key);
        String[] propertyArray = getPropertyStringArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("String", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code long} value by key.
     *
     * @param key The key to look for.
     * @return The first {@code long} associated with the given key or default value {@code 0} if
     *     there is no such key or the value is of a different type.
     */
    public long getPropertyLong(@NonNull String key) {
        Preconditions.checkNotNull(key);
        long[] propertyArray = getPropertyLongArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return 0;
        }
        warnIfSinglePropertyTooLong("Long", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code double} value by key.
     *
     * @param key The key to look for.
     * @return The first {@code double} associated with the given key or default value {@code 0.0}
     *     if there is no such key or the value is of a different type.
     */
    public double getPropertyDouble(@NonNull String key) {
        Preconditions.checkNotNull(key);
        double[] propertyArray = getPropertyDoubleArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return 0.0;
        }
        warnIfSinglePropertyTooLong("Double", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code boolean} value by key.
     *
     * @param key The key to look for.
     * @return The first {@code boolean} associated with the given key or default value {@code
     *     false} if there is no such key or the value is of a different type.
     */
    public boolean getPropertyBoolean(@NonNull String key) {
        Preconditions.checkNotNull(key);
        boolean[] propertyArray = getPropertyBooleanArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return false;
        }
        warnIfSinglePropertyTooLong("Boolean", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code byte[]} value by key.
     *
     * @param key The key to look for.
     * @return The first {@code byte[]} associated with the given key or {@code null} if there is no
     *     such key or the value is of a different type.
     */
    @Nullable
    public byte[] getPropertyBytes(@NonNull String key) {
        Preconditions.checkNotNull(key);
        byte[][] propertyArray = getPropertyBytesArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("ByteArray", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@link GenericDocument} value by key.
     *
     * @param key The key to look for.
     * @return The first {@link GenericDocument} associated with the given key or {@code null} if
     *     there is no such key or the value is of a different type.
     */
    @Nullable
    public GenericDocument getPropertyDocument(@NonNull String key) {
        Preconditions.checkNotNull(key);
        GenericDocument[] propertyArray = getPropertyDocumentArray(key);
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("Document", key, propertyArray.length);
        return propertyArray[0];
    }

    /** Prints a warning to logcat if the given propertyLength is greater than 1. */
    private static void warnIfSinglePropertyTooLong(
            @NonNull String propertyType, @NonNull String key, int propertyLength) {
        if (propertyLength > 1) {
            Log.w(
                    TAG,
                    "The value for \""
                            + key
                            + "\" contains "
                            + propertyLength
                            + " elements. Only the first one will be returned from "
                            + "getProperty"
                            + propertyType
                            + "(). Try getProperty"
                            + propertyType
                            + "Array().");
        }
    }

    /**
     * Retrieves a repeated {@code String} property by key.
     *
     * @param key The key to look for.
     * @return The {@code String[]} associated with the given key, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @Nullable
    public String[] getPropertyStringArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        return getAndCastPropertyArray(key, String[].class);
    }

    /**
     * Retrieves a repeated {@code long[]} property by key.
     *
     * @param key The key to look for.
     * @return The {@code long[]} associated with the given key, or {@code null} if no value is set
     *     or the value is of a different type.
     */
    @Nullable
    public long[] getPropertyLongArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        return getAndCastPropertyArray(key, long[].class);
    }

    /**
     * Retrieves a repeated {@code double} property by key.
     *
     * @param key The key to look for.
     * @return The {@code double[]} associated with the given key, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @Nullable
    public double[] getPropertyDoubleArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        return getAndCastPropertyArray(key, double[].class);
    }

    /**
     * Retrieves a repeated {@code boolean} property by key.
     *
     * @param key The key to look for.
     * @return The {@code boolean[]} associated with the given key, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @Nullable
    public boolean[] getPropertyBooleanArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        return getAndCastPropertyArray(key, boolean[].class);
    }

    /**
     * Retrieves a {@code byte[][]} property by key.
     *
     * @param key The key to look for.
     * @return The {@code byte[][]} associated with the given key, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @SuppressLint("ArrayReturn")
    @Nullable
    @SuppressWarnings("unchecked")
    public byte[][] getPropertyBytesArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        ArrayList<Bundle> bundles = getAndCastPropertyArray(key, ArrayList.class);
        if (bundles == null || bundles.size() == 0) {
            return null;
        }
        byte[][] bytes = new byte[bundles.size()][];
        for (int i = 0; i < bundles.size(); i++) {
            Bundle bundle = bundles.get(i);
            if (bundle == null) {
                Log.e(TAG, "The inner bundle is null at " + i + ", for key: " + key);
                continue;
            }
            byte[] innerBytes = bundle.getByteArray(BYTE_ARRAY_FIELD);
            if (innerBytes == null) {
                Log.e(TAG, "The bundle at " + i + " contains a null byte[].");
                continue;
            }
            bytes[i] = innerBytes;
        }
        return bytes;
    }

    /**
     * Retrieves a repeated {@link GenericDocument} property by key.
     *
     * @param key The key to look for.
     * @return The {@link GenericDocument}[] associated with the given key, or {@code null} if no
     *     value is set or the value is of a different type.
     */
    @SuppressLint("ArrayReturn")
    @Nullable
    public GenericDocument[] getPropertyDocumentArray(@NonNull String key) {
        Preconditions.checkNotNull(key);
        Parcelable[] bundles = getAndCastPropertyArray(key, Parcelable[].class);
        if (bundles == null || bundles.length == 0) {
            return null;
        }
        GenericDocument[] documents = new GenericDocument[bundles.length];
        for (int i = 0; i < bundles.length; i++) {
            if (bundles[i] == null) {
                Log.e(TAG, "The inner bundle is null at " + i + ", for key: " + key);
                continue;
            }
            if (!(bundles[i] instanceof Bundle)) {
                Log.e(
                        TAG,
                        "The inner element at "
                                + i
                                + " is a "
                                + bundles[i].getClass()
                                + ", not a Bundle for key: "
                                + key);
                continue;
            }
            documents[i] = new GenericDocument((Bundle) bundles[i]);
        }
        return documents;
    }

    /**
     * Gets a repeated property of the given key, and casts it to the given class type, which must
     * be an array class type.
     */
    @Nullable
    private <T> T getAndCastPropertyArray(@NonNull String key, @NonNull Class<T> tClass) {
        Object value = mProperties.get(key);
        if (value == null) {
            return null;
        }
        try {
            return tClass.cast(value);
        } catch (ClassCastException e) {
            Log.w(TAG, "Error casting to requested type for key \"" + key + "\"", e);
            return null;
        }
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GenericDocument)) {
            return false;
        }
        GenericDocument otherDocument = (GenericDocument) other;
        return BundleUtil.deepEquals(this.mBundle, otherDocument.mBundle);
    }

    @Override
    public int hashCode() {
        if (mHashCode == null) {
            mHashCode = BundleUtil.deepHashCode(mBundle);
        }
        return mHashCode;
    }

    @Override
    @NonNull
    public String toString() {
        return bundleToString(mBundle).toString();
    }

    @SuppressWarnings("unchecked")
    private static StringBuilder bundleToString(Bundle bundle) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            final Set<String> keySet = bundle.keySet();
            String[] keys = keySet.toArray(new String[0]);
            // Sort keys to make output deterministic. We need a custom comparator to handle
            // nulls (arbitrarily putting them first, similar to Comparator.nullsFirst, which is
            // only available since N).
            Arrays.sort(
                    keys,
                    (@Nullable String s1, @Nullable String s2) -> {
                        if (s1 == null) {
                            return s2 == null ? 0 : -1;
                        } else if (s2 == null) {
                            return 1;
                        } else {
                            return s1.compareTo(s2);
                        }
                    });
            for (String key : keys) {
                stringBuilder.append("{ key: '").append(key).append("' value: ");
                Object valueObject = bundle.get(key);
                if (valueObject == null) {
                    stringBuilder.append("<null>");
                } else if (valueObject instanceof Bundle) {
                    stringBuilder.append(bundleToString((Bundle) valueObject));
                } else if (valueObject.getClass().isArray()) {
                    stringBuilder.append("[ ");
                    for (int i = 0; i < Array.getLength(valueObject); i++) {
                        Object element = Array.get(valueObject, i);
                        stringBuilder.append("'");
                        if (element instanceof Bundle) {
                            stringBuilder.append(bundleToString((Bundle) element));
                        } else {
                            stringBuilder.append(Array.get(valueObject, i));
                        }
                        stringBuilder.append("' ");
                    }
                    stringBuilder.append("]");
                } else if (valueObject instanceof ArrayList) {
                    for (Bundle innerBundle : (ArrayList<Bundle>) valueObject) {
                        stringBuilder.append(bundleToString(innerBundle));
                    }
                } else {
                    stringBuilder.append(valueObject.toString());
                }
                stringBuilder.append(" } ");
            }
        } catch (RuntimeException e) {
            // Catch any exceptions here since corrupt Bundles can throw different types of
            // exceptions (e.g. b/38445840 & b/68937025).
            stringBuilder.append("<error>");
        }
        return stringBuilder;
    }

    /**
     * The builder class for {@link GenericDocument}.
     *
     * @param <BuilderType> Type of subclass who extends this.
     */
    // This builder is specifically designed to be extended by classes deriving from
    // GenericDocument.
    @SuppressLint("StaticFinalBuilder")
    public static class Builder<BuilderType extends Builder> {

        private final Bundle mProperties = new Bundle();
        private final Bundle mBundle = new Bundle();
        private final BuilderType mBuilderTypeInstance;
        private boolean mBuilt = false;

        /**
         * Creates a new {@link GenericDocument.Builder}.
         *
         * <p>Once {@link #build} is called, the instance can no longer be used.
         *
         * <p>URIs are unique within a namespace.
         *
         * <p>The number of namespaces per app should be kept small for efficiency reasons.
         *
         * @param namespace the namespace to set for the {@link GenericDocument}.
         * @param uri the URI to set for the {@link GenericDocument}.
         * @param schemaType the {@link AppSearchSchema} type of the {@link GenericDocument}. The
         *     provided {@code schemaType} must be defined using {@link AppSearchSession#setSchema}
         *     prior to inserting a document of this {@code schemaType} into the AppSearch index
         *     using {@link AppSearchSession#put}. Otherwise, the document will be rejected by
         *     {@link AppSearchSession#put} with result code {@link
         *     AppSearchResult#RESULT_NOT_FOUND}.
         */
        @SuppressWarnings("unchecked")
        public Builder(@NonNull String namespace, @NonNull String uri, @NonNull String schemaType) {
            Preconditions.checkNotNull(namespace);
            Preconditions.checkNotNull(uri);
            Preconditions.checkNotNull(schemaType);
            mBuilderTypeInstance = (BuilderType) this;
            mBundle.putString(GenericDocument.NAMESPACE_FIELD, namespace);
            mBundle.putString(GenericDocument.URI_FIELD, uri);
            mBundle.putString(GenericDocument.SCHEMA_TYPE_FIELD, schemaType);
            // Set current timestamp for creation timestamp by default.
            mBundle.putLong(
                    GenericDocument.CREATION_TIMESTAMP_MILLIS_FIELD, System.currentTimeMillis());
            mBundle.putLong(GenericDocument.TTL_MILLIS_FIELD, DEFAULT_TTL_MILLIS);
            mBundle.putInt(GenericDocument.SCORE_FIELD, DEFAULT_SCORE);
            mBundle.putBundle(PROPERTIES_FIELD, mProperties);
        }

        /**
         * Sets the score of the {@link GenericDocument}.
         *
         * <p>The score is a query-independent measure of the document's quality, relative to other
         * {@link GenericDocument} objects of the same {@link AppSearchSchema} type.
         *
         * <p>Results may be sorted by score using {@link SearchSpec.Builder#setRankingStrategy}.
         * Documents with higher scores are considered better than documents with lower scores.
         *
         * <p>Any non-negative integer can be used a score. By default, scores are set to 0.
         *
         * @param score any non-negative {@code int} representing the document's score.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public BuilderType setScore(@IntRange(from = 0, to = Integer.MAX_VALUE) int score) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            if (score < 0) {
                throw new IllegalArgumentException("Document score cannot be negative.");
            }
            mBundle.putInt(GenericDocument.SCORE_FIELD, score);
            return mBuilderTypeInstance;
        }

        /**
         * Sets the creation timestamp of the {@link GenericDocument}, in milliseconds.
         *
         * <p>This should be set using a value obtained from the {@link System#currentTimeMillis}
         * time base.
         *
         * @param creationTimestampMillis a creation timestamp in milliseconds.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public BuilderType setCreationTimestampMillis(long creationTimestampMillis) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBundle.putLong(
                    GenericDocument.CREATION_TIMESTAMP_MILLIS_FIELD, creationTimestampMillis);
            return mBuilderTypeInstance;
        }

        /**
         * Sets the TTL (time-to-live) of the {@link GenericDocument}, in milliseconds.
         *
         * <p>The TTL is measured against {@link #getCreationTimestampMillis}. At the timestamp of
         * {@code creationTimestampMillis + ttlMillis}, measured in the {@link
         * System#currentTimeMillis} time base, the document will be auto-deleted.
         *
         * <p>The default value is 0, which means the document is permanent and won't be
         * auto-deleted until the app is uninstalled or {@link AppSearchSession#remove} is called.
         *
         * @param ttlMillis a non-negative duration in milliseconds.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public BuilderType setTtlMillis(long ttlMillis) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            if (ttlMillis < 0) {
                throw new IllegalArgumentException("Document ttlMillis cannot be negative.");
            }
            mBundle.putLong(GenericDocument.TTL_MILLIS_FIELD, ttlMillis);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code String} values for a property, replacing its previous values.
         *
         * @param key the key associated with the {@code values}.
         * @param values the {@code String} values of the property.
         * @throws IllegalArgumentException if no values are provided, if provided values exceed
         *     maximum repeated property length, or if a passed in {@code String} is {@code null}.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public BuilderType setPropertyString(@NonNull String key, @NonNull String... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code boolean} values for a property, replacing its previous
         * values.
         *
         * @param key the key associated with the {@code values}.
         * @param values the {@code boolean} values of the property.
         * @throws IllegalArgumentException if no values are provided or if values exceed maximum
         *     repeated property length.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public BuilderType setPropertyBoolean(@NonNull String key, @NonNull boolean... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code long} values for a property, replacing its previous values.
         *
         * @param key the key associated with the {@code values}.
         * @param values the {@code long} values of the property.
         * @throws IllegalArgumentException if no values are provided or if values exceed maximum
         *     repeated property length.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public BuilderType setPropertyLong(@NonNull String key, @NonNull long... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code double} values for a property, replacing its previous values.
         *
         * @param key the key associated with the {@code values}.
         * @param values the {@code double} values of the property.
         * @throws IllegalArgumentException if no values are provided or if values exceed maximum
         *     repeated property length.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public BuilderType setPropertyDouble(@NonNull String key, @NonNull double... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code byte[]} for a property, replacing its previous values.
         *
         * @param key the key associated with the {@code values}.
         * @param values the {@code byte[]} of the property.
         * @throws IllegalArgumentException if no values are provided, if provided values exceed
         *     maximum repeated property length, or if a passed in {@code byte[]} is {@code null}.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public BuilderType setPropertyBytes(@NonNull String key, @NonNull byte[]... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@link GenericDocument} values for a property, replacing its
         * previous values.
         *
         * @param key the key associated with the {@code values}.
         * @param values the {@link GenericDocument} values of the property.
         * @throws IllegalArgumentException if no values are provided, if provided values exceed if
         *     provided values exceed maximum repeated property length, or if a passed in {@link
         *     GenericDocument} is {@code null}.
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public BuilderType setPropertyDocument(
                @NonNull String key, @NonNull GenericDocument... values) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(values);
            putInPropertyBundle(key, values);
            return mBuilderTypeInstance;
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull String[] values)
                throws IllegalArgumentException {
            validateRepeatedPropertyLength(key, values.length);
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The String at " + i + " is null.");
                } else if (values[i].length() > MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException(
                            "The String at "
                                    + i
                                    + " length is: "
                                    + values[i].length()
                                    + ", which exceeds length limit: "
                                    + MAX_STRING_LENGTH
                                    + ".");
                }
            }
            mProperties.putStringArray(key, values);
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull boolean[] values) {
            validateRepeatedPropertyLength(key, values.length);
            mProperties.putBooleanArray(key, values);
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull double[] values) {
            validateRepeatedPropertyLength(key, values.length);
            mProperties.putDoubleArray(key, values);
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull long[] values) {
            validateRepeatedPropertyLength(key, values.length);
            mProperties.putLongArray(key, values);
        }

        /**
         * Converts and saves a byte[][] into {@link #mProperties}.
         *
         * <p>Bundle doesn't support for two dimension array byte[][], we are converting byte[][]
         * into ArrayList<Bundle>, and each elements will contain a one dimension byte[].
         */
        private void putInPropertyBundle(@NonNull String key, @NonNull byte[][] values) {
            validateRepeatedPropertyLength(key, values.length);
            ArrayList<Bundle> bundles = new ArrayList<>(values.length);
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The byte[] at " + i + " is null.");
                }
                Bundle bundle = new Bundle();
                bundle.putByteArray(BYTE_ARRAY_FIELD, values[i]);
                bundles.add(bundle);
            }
            mProperties.putParcelableArrayList(key, bundles);
        }

        private void putInPropertyBundle(@NonNull String key, @NonNull GenericDocument[] values) {
            validateRepeatedPropertyLength(key, values.length);
            Parcelable[] documentBundles = new Parcelable[values.length];
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The document at " + i + " is null.");
                }
                documentBundles[i] = values[i].mBundle;
            }
            mProperties.putParcelableArray(key, documentBundles);
        }

        private static void validateRepeatedPropertyLength(@NonNull String key, int length) {
            if (length == 0) {
                throw new IllegalArgumentException("The input array is empty.");
            } else if (length > MAX_REPEATED_PROPERTY_LENGTH) {
                throw new IllegalArgumentException(
                        "Repeated property \""
                                + key
                                + "\" has length "
                                + length
                                + ", which exceeds the limit of "
                                + MAX_REPEATED_PROPERTY_LENGTH);
            }
        }

        /**
         * Builds the {@link GenericDocument} object.
         *
         * @throws IllegalStateException if the builder has already been used.
         */
        @NonNull
        public GenericDocument build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new GenericDocument(mBundle);
        }
    }
}
