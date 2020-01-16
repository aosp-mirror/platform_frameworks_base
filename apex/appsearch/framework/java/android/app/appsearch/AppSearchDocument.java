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

package android.app.appsearch;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.PropertyProto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a document unit.
 *
 * <p>Documents are constructed via {@link AppSearchDocument.Builder}.
 *
 * @hide
 */
// TODO(b/143789408) set TTL for document in mProtoBuilder
// TODO(b/144518768) add visibility field if the stakeholders are comfortable with a no-op
//  opt-in for this release.
public class AppSearchDocument {
    private static final String TAG = "AppSearchDocument";

    /**
     * The maximum number of elements in a repeatable field. Will reject the request if exceed
     * this limit.
     */
    private static final int MAX_REPEATED_PROPERTY_LENGTH = 100;

    /**
     * The maximum {@link String#length} of a {@link String} field. Will reject the request if
     * {@link String}s longer than this.
     */
    private static final int MAX_STRING_LENGTH = 20_000;

    /**
     * Contains {@link AppSearchDocument} basic information (uri, schemaType etc) and properties
     * ordered by keys.
     */
    @NonNull
    private final DocumentProto mProto;

    /** Contains all properties in {@link #mProto} to support get properties via keys. */
    @NonNull
    private final Bundle mPropertyBundle;

    /**
     * Create a new {@link AppSearchDocument}.
     * @param proto Contains {@link AppSearchDocument} basic information (uri, schemaType etc) and
     *               properties ordered by keys.
     * @param propertyBundle Contains all properties in {@link #mProto} to support get
     *                        properties via keys.
     */
    private AppSearchDocument(@NonNull DocumentProto proto, @NonNull Bundle propertyBundle) {
        this.mProto = proto;
        this.mPropertyBundle = propertyBundle;
    }

    /**
     * Create a new {@link AppSearchDocument} from an existing instance.
     *
     * <p>This method should be only used by constructor of a subclass.
     */
    protected AppSearchDocument(@NonNull AppSearchDocument document) {
        this(document.mProto, document.mPropertyBundle);
    }

    /** @hide */
    AppSearchDocument(@NonNull DocumentProto documentProto) {
        this(documentProto, new Bundle());
        for (int i = 0; i < documentProto.getPropertiesCount(); i++) {
            PropertyProto property = documentProto.getProperties(i);
            String name = property.getName();
            if (property.getStringValuesCount() > 0) {
                String[] values = new String[property.getStringValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getStringValues(j);
                }
                mPropertyBundle.putStringArray(name, values);
            } else if (property.getInt64ValuesCount() > 0) {
                long[] values = new long[property.getInt64ValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getInt64Values(j);
                }
                mPropertyBundle.putLongArray(property.getName(), values);
            } else if (property.getDoubleValuesCount() > 0) {
                double[] values = new double[property.getDoubleValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getDoubleValues(j);
                }
                mPropertyBundle.putDoubleArray(property.getName(), values);
            } else if (property.getBooleanValuesCount() > 0) {
                boolean[] values = new boolean[property.getBooleanValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getBooleanValues(j);
                }
                mPropertyBundle.putBooleanArray(property.getName(), values);
            } else if (property.getBytesValuesCount() > 0) {
                byte[][] values = new byte[property.getBytesValuesCount()][];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getBytesValues(j).toByteArray();
                }
                mPropertyBundle.putObject(name, values);
            } else if (property.getDocumentValuesCount() > 0) {
                AppSearchDocument[] values =
                        new AppSearchDocument[property.getDocumentValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = new AppSearchDocument(property.getDocumentValues(j));
                }
                mPropertyBundle.putObject(name, values);
            } else {
                throw new IllegalStateException("Unknown type of value: " + name);
            }
        }
    }

    /**
     * Get the {@link DocumentProto} of the {@link AppSearchDocument}.
     *
     * <p>The {@link DocumentProto} contains {@link AppSearchDocument}'s basic information and all
     *    properties ordered by keys.
     * @hide
     */
    @NonNull
    @VisibleForTesting
    public DocumentProto getProto() {
        return mProto;
    }

    /**
     * Get the uri of the {@link AppSearchDocument}.
     *
     * @hide
     */
    @NonNull
    public String getUri() {
        return mProto.getUri();
    }

    /**
     * Get the schema type of the {@link AppSearchDocument}.
     * @hide
     */
    @NonNull
    public String getSchemaType() {
        return mProto.getSchema();
    }

    /**
     * Get the creation timestamp in milliseconds of the {@link AppSearchDocument}. Value will be in
     * the {@link System#currentTimeMillis()} time base.
     *
     * @hide
     */
    @CurrentTimeMillisLong
    public long getCreationTimestampMillis() {
        return mProto.getCreationTimestampMs();
    }

    /**
     * Returns the score of the {@link AppSearchDocument}.
     *
     * <p>The score is a query-independent measure of the document's quality, relative to other
     * {@link AppSearchDocument}s of the same type.
     *
     * <p>The default value is 0.
     *
     * @hide
     */
    public int getScore() {
        return mProto.getScore();
    }

    /**
     * Retrieve a {@link String} value by key.
     *
     * @param key The key to look for.
     * @return The first {@link String} associated with the given key or {@code null} if there
     *     is no such key or the value is of a different type.
     * @hide
     */
    @Nullable
    public String getPropertyString(@NonNull String key) {
        String[] propertyArray = getPropertyStringArray(key);
        if (ArrayUtils.isEmpty(propertyArray)) {
            return null;
        }
        warnIfSinglePropertyTooLong("String", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieve a {@link Long} value by key.
     *
     * @param key The key to look for.
     * @return The first {@link Long} associated with the given key or {@code null} if there
     *     is no such key or the value is of a different type.
     * @hide
     */
    @Nullable
    public Long getPropertyLong(@NonNull String key) {
        long[] propertyArray = getPropertyLongArray(key);
        if (ArrayUtils.isEmpty(propertyArray)) {
            return null;
        }
        warnIfSinglePropertyTooLong("Long", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieve a {@link Double} value by key.
     *
     * @param key The key to look for.
     * @return The first {@link Double} associated with the given key or {@code null} if there
     *     is no such key or the value is of a different type.
     * @hide
     */
    @Nullable
    public Double getPropertyDouble(@NonNull String key) {
        double[] propertyArray = getPropertyDoubleArray(key);
        // TODO(tytytyww): Add support double array to ArraysUtils.isEmpty().
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("Double", key, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieve a {@link Boolean} value by key.
     *
     * @param key The key to look for.
     * @return The first {@link Boolean} associated with the given key or {@code null} if there
     *     is no such key or the value is of a different type.
     * @hide
     */
    @Nullable
    public Boolean getPropertyBoolean(@NonNull String key) {
        boolean[] propertyArray = getPropertyBooleanArray(key);
        if (ArrayUtils.isEmpty(propertyArray)) {
            return null;
        }
        warnIfSinglePropertyTooLong("Boolean", key, propertyArray.length);
        return propertyArray[0];
    }

    /** Prints a warning to logcat if the given propertyLength is greater than 1. */
    private static void warnIfSinglePropertyTooLong(
            @NonNull String propertyType, @NonNull String key, int propertyLength) {
        if (propertyLength > 1) {
            Log.w(TAG, "The value for \"" + key + "\" contains " + propertyLength
                    + " elements. Only the first one will be returned from "
                    + "getProperty" + propertyType + "(). Try getProperty" + propertyType
                    + "Array().");
        }
    }

    /**
     * Retrieve a repeated {@code String} property by key.
     *
     * @param key The key to look for.
     * @return The {@code String[]} associated with the given key, or {@code null} if no value
     *     is set or the value is of a different type.
     * @hide
     */
    @Nullable
    public String[] getPropertyStringArray(@NonNull String key) {
        return getAndCastPropertyArray(key, String[].class);
    }

    /**
     * Retrieve a repeated {@code long} property by key.
     *
     * @param key The key to look for.
     * @return The {@code long[]} associated with the given key, or {@code null} if no value is
     *     set or the value is of a different type.
     * @hide
     */
    @Nullable
    public long[] getPropertyLongArray(@NonNull String key) {
        return getAndCastPropertyArray(key, long[].class);
    }

    /**
     * Retrieve a repeated {@code double} property by key.
     *
     * @param key The key to look for.
     * @return The {@code double[]} associated with the given key, or {@code null} if no value
     *     is set or the value is of a different type.
     * @hide
     */
    @Nullable
    public double[] getPropertyDoubleArray(@NonNull String key) {
        return getAndCastPropertyArray(key, double[].class);
    }

    /**
     * Retrieve a repeated {@code boolean} property by key.
     *
     * @param key The key to look for.
     * @return The {@code boolean[]} associated with the given key, or {@code null} if no value
     *     is set or the value is of a different type.
     * @hide
     */
    @Nullable
    public boolean[] getPropertyBooleanArray(@NonNull String key) {
        return getAndCastPropertyArray(key, boolean[].class);
    }

    /**
     * Gets a repeated property of the given key, and casts it to the given class type, which
     * must be an array class type.
     */
    @Nullable
    private <T> T getAndCastPropertyArray(@NonNull String key, @NonNull Class<T> tClass) {
        Object value = mPropertyBundle.get(key);
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
        // Check only proto's equality is sufficient here since all properties in
        // mPropertyBundle are ordered by keys and stored in proto.
        if (this == other) {
            return true;
        }
        if (!(other instanceof AppSearchDocument)) {
            return false;
        }
        AppSearchDocument otherDocument = (AppSearchDocument) other;
        return this.mProto.equals(otherDocument.mProto);
    }

    @Override
    public int hashCode() {
        // Hash only proto is sufficient here since all properties in mPropertyBundle are
        // ordered by keys and stored in proto.
        return mProto.hashCode();
    }

    @Override
    public String toString() {
        return mProto.toString();
    }

    /**
     * The builder class for {@link AppSearchDocument}.
     *
     * @param <BuilderType> Type of subclass who extend this.
     * @hide
     */
    public static class Builder<BuilderType extends Builder> {

        private final Bundle mPropertyBundle = new Bundle();
        private final DocumentProto.Builder mProtoBuilder = DocumentProto.newBuilder();
        private final BuilderType mBuilderTypeInstance;

        /**
         * Create a new {@link AppSearchDocument.Builder}.
         *
         * @param uri The uri of {@link AppSearchDocument}.
         * @param schemaType The schema type of the {@link AppSearchDocument}. The passed-in
         *       {@code schemaType} must be defined using {@link AppSearchManager#setSchema} prior
         *       to inserting a document of this {@code schemaType} into the AppSearch index using
         *       {@link AppSearchManager#putDocuments(List)}. Otherwise, the document will be
         *       rejected by {@link AppSearchManager#putDocuments(List)}.
         * @hide
         */
        public Builder(@NonNull String uri, @NonNull String schemaType) {
            mBuilderTypeInstance = (BuilderType) this;
            mProtoBuilder.setUri(uri).setSchema(schemaType);
            // Set current timestamp for creation timestamp by default.
            setCreationTimestampMillis(System.currentTimeMillis());
        }

        /**
         * Set the score of the {@link AppSearchDocument}.
         *
         * <p>The score is a query-independent measure of the document's quality, relative to
         * other {@link AppSearchDocument}s of the same type.
         *
         * @throws IllegalArgumentException If the provided value is negative.
         * @hide
         */
        @NonNull
        public BuilderType setScore(@IntRange(from = 0, to = Integer.MAX_VALUE) int score) {
            if (score < 0) {
                throw new IllegalArgumentException("Document score cannot be negative");
            }
            mProtoBuilder.setScore(score);
            return mBuilderTypeInstance;
        }

        /**
         * Set the creation timestamp in milliseconds of the {@link AppSearchDocument}. Should be
         * set using a value obtained from the {@link System#currentTimeMillis()} time base.
         *
         * @hide
         */
        @NonNull
        public BuilderType setCreationTimestampMillis(
                @CurrentTimeMillisLong long creationTimestampMillis) {
            mProtoBuilder.setCreationTimestampMs(creationTimestampMillis);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code String} values for a property, replacing its previous
         * values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@code String} values of the property.
         * @hide
         */
        @NonNull
        public BuilderType setProperty(@NonNull String key, @NonNull String... values) {
            putInBundle(mPropertyBundle, key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code boolean} values for a property, replacing its previous
         * values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@code boolean} values of the schema.org property.
         * @hide
         */
        @NonNull
        public BuilderType setProperty(@NonNull String key, @NonNull boolean... values) {
            putInBundle(mPropertyBundle, key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code long} values for a property, replacing its previous
         * values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@code long} values of the schema.org property.
         * @hide
         */
        @NonNull
        public BuilderType setProperty(@NonNull String key, @NonNull long... values) {
            putInBundle(mPropertyBundle, key, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code double} values for a property, replacing its previous
         * values.
         *
         * @param key The key associated with the {@code values}.
         * @param values The {@code double} values of the schema.org property.
         * @hide
         */
        @NonNull
        public BuilderType setProperty(@NonNull String key, @NonNull double... values) {
            putInBundle(mPropertyBundle, key, values);
            return mBuilderTypeInstance;
        }

        private static void putInBundle(
                @NonNull Bundle bundle, @NonNull String key, @NonNull String... values)
                throws IllegalArgumentException {
            Objects.requireNonNull(key);
            Objects.requireNonNull(values);
            validateRepeatedPropertyLength(key, values.length);
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The String at " + i + " is null.");
                } else if (values[i].length() > MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException("The String at " + i + " length is: "
                            + values[i].length()  + ", which exceeds length limit: "
                            + MAX_STRING_LENGTH + ".");
                }
            }
            bundle.putStringArray(key, values);
        }

        private static void putInBundle(
                @NonNull Bundle bundle, @NonNull String key, @NonNull boolean... values) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(values);
            validateRepeatedPropertyLength(key, values.length);
            bundle.putBooleanArray(key, values);
        }

        private static void putInBundle(
                @NonNull Bundle bundle, @NonNull String key, @NonNull double... values) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(values);
            validateRepeatedPropertyLength(key, values.length);
            bundle.putDoubleArray(key, values);
        }

        private static void putInBundle(
                @NonNull Bundle bundle, @NonNull String key, @NonNull long... values) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(values);
            validateRepeatedPropertyLength(key, values.length);
            bundle.putLongArray(key, values);
        }

        private static void validateRepeatedPropertyLength(@NonNull String key, int length) {
            if (length == 0) {
                throw new IllegalArgumentException("The input array is empty.");
            } else if (length > MAX_REPEATED_PROPERTY_LENGTH) {
                throw new IllegalArgumentException(
                        "Repeated property \"" + key + "\" has length " + length
                                + ", which exceeds the limit of "
                                + MAX_REPEATED_PROPERTY_LENGTH);
            }
        }

        /**
         * Builds the {@link AppSearchDocument} object.
         * @hide
         */
        public AppSearchDocument build() {
            // Build proto by sorting the keys in propertyBundle to exclude the influence of
            // order. Therefore documents will generate same proto as long as the contents are
            // same. Note that the order of repeated fields is still preserved.
            ArrayList<String> keys = new ArrayList<>(mPropertyBundle.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                Object values = mPropertyBundle.get(key);
                PropertyProto.Builder propertyProto = PropertyProto.newBuilder().setName(key);
                if (values instanceof boolean[]) {
                    for (boolean value : (boolean[]) values) {
                        propertyProto.addBooleanValues(value);
                    }
                } else if (values instanceof long[]) {
                    for (long value : (long[]) values) {
                        propertyProto.addInt64Values(value);
                    }
                } else if (values instanceof double[]) {
                    for (double value : (double[]) values) {
                        propertyProto.addDoubleValues(value);
                    }
                } else if (values instanceof String[]) {
                    for (String value : (String[]) values) {
                        propertyProto.addStringValues(value);
                    }
                } else {
                    throw new IllegalStateException(
                            "Property \"" + key + "\" has unsupported value type \""
                                    + values.getClass().getSimpleName() + "\"");
                }
                mProtoBuilder.addProperties(propertyProto);
            }
            return new AppSearchDocument(mProtoBuilder.build(), mPropertyBundle);
        }
    }
}
