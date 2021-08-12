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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.appsearch.util.BundleUtil;
import android.app.appsearch.util.IndentingStringBuilder;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a document unit.
 *
 * <p>Documents contain structured data conforming to their {@link AppSearchSchema} type. Each
 * document is uniquely identified by a namespace and a String ID within that namespace.
 *
 * <p>Documents are constructed by using the {@link GenericDocument.Builder}.
 *
 * @see AppSearchSession#put
 * @see AppSearchSession#getByDocumentId
 * @see AppSearchSession#search
 */
public class GenericDocument {
    private static final String TAG = "AppSearchGenericDocumen";

    /** The maximum number of indexed properties a document can have. */
    private static final int MAX_INDEXED_PROPERTIES = 16;

    /** The default score of document. */
    private static final int DEFAULT_SCORE = 0;

    /** The default time-to-live in millisecond of a document, which is infinity. */
    private static final long DEFAULT_TTL_MILLIS = 0L;

    private static final String PROPERTIES_FIELD = "properties";
    private static final String BYTE_ARRAY_FIELD = "byteArray";
    private static final String SCHEMA_TYPE_FIELD = "schemaType";
    private static final String ID_FIELD = "id";
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

    /**
     * Contains all {@link GenericDocument} information in a packaged format.
     *
     * <p>Keys are the {@code *_FIELD} constants in this class.
     */
    @NonNull final Bundle mBundle;

    /** Contains all properties in {@link GenericDocument} to support getting properties via name */
    @NonNull private final Bundle mProperties;

    @NonNull private final String mId;
    @NonNull private final String mSchemaType;
    private final long mCreationTimestampMillis;
    @Nullable private Integer mHashCode;

    /**
     * Rebuilds a {@link GenericDocument} from a bundle.
     *
     * @param bundle Packaged {@link GenericDocument} data, such as the result of {@link
     *     #getBundle}.
     * @hide
     */
    public GenericDocument(@NonNull Bundle bundle) {
        Objects.requireNonNull(bundle);
        mBundle = bundle;
        mProperties = Objects.requireNonNull(bundle.getParcelable(PROPERTIES_FIELD));
        mId = Objects.requireNonNull(mBundle.getString(ID_FIELD));
        mSchemaType = Objects.requireNonNull(mBundle.getString(SCHEMA_TYPE_FIELD));
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

    /** Returns the unique identifier of the {@link GenericDocument}. */
    @NonNull
    public String getId() {
        return mId;
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
    @CurrentTimeMillisLong
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
     * Retrieves the property value with the given path as {@link Object}.
     *
     * <p>A path can be a simple property name, such as those returned by {@link #getPropertyNames}.
     * It may also be a dot-delimited path through the nested document hierarchy, with nested {@link
     * GenericDocument} properties accessed via {@code '.'} and repeated properties optionally
     * indexed into via {@code [n]}.
     *
     * <p>For example, given the following {@link GenericDocument}:
     *
     * <pre>
     *     (Message) {
     *         from: "sender@example.com"
     *         to: [{
     *             name: "Albert Einstein"
     *             email: "einstein@example.com"
     *           }, {
     *             name: "Marie Curie"
     *             email: "curie@example.com"
     *           }]
     *         tags: ["important", "inbox"]
     *         subject: "Hello"
     *     }
     * </pre>
     *
     * <p>Here are some example paths and their results:
     *
     * <ul>
     *   <li>{@code "from"} returns {@code "sender@example.com"} as a {@link String} array with one
     *       element
     *   <li>{@code "to"} returns the two nested documents containing contact information as a
     *       {@link GenericDocument} array with two elements
     *   <li>{@code "to[1]"} returns the second nested document containing Marie Curie's contact
     *       information as a {@link GenericDocument} array with one element
     *   <li>{@code "to[1].email"} returns {@code "curie@example.com"}
     *   <li>{@code "to[100].email"} returns {@code null} as this particular document does not have
     *       that many elements in its {@code "to"} array.
     *   <li>{@code "to.email"} aggregates emails across all nested documents that have them,
     *       returning {@code ["einstein@example.com", "curie@example.com"]} as a {@link String}
     *       array with two elements.
     * </ul>
     *
     * <p>If you know the expected type of the property you are retrieving, it is recommended to use
     * one of the typed versions of this method instead, such as {@link #getPropertyString} or
     * {@link #getPropertyStringArray}.
     *
     * @param path The path to look for.
     * @return The entry with the given path as an object or {@code null} if there is no such path.
     *     The returned object will be one of the following types: {@code String[]}, {@code long[]},
     *     {@code double[]}, {@code boolean[]}, {@code byte[][]}, {@code GenericDocument[]}.
     */
    @Nullable
    public Object getProperty(@NonNull String path) {
        Objects.requireNonNull(path);
        Object rawValue = getRawPropertyFromRawDocument(path, mBundle);

        // Unpack the raw value into the types the user expects, if required.
        if (rawValue instanceof Bundle) {
            // getRawPropertyFromRawDocument may return a document as a bare Bundle as a performance
            // optimization for lookups.
            GenericDocument document = new GenericDocument((Bundle) rawValue);
            return new GenericDocument[] {document};
        }

        if (rawValue instanceof List) {
            // byte[][] fields are packed into List<Bundle> where each Bundle contains just a single
            // entry: BYTE_ARRAY_FIELD -> byte[].
            @SuppressWarnings("unchecked")
            List<Bundle> bundles = (List<Bundle>) rawValue;
            if (bundles.size() == 0) {
                return null;
            }
            byte[][] bytes = new byte[bundles.size()][];
            for (int i = 0; i < bundles.size(); i++) {
                Bundle bundle = bundles.get(i);
                if (bundle == null) {
                    Log.e(TAG, "The inner bundle is null at " + i + ", for path: " + path);
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

        if (rawValue instanceof Parcelable[]) {
            // The underlying Bundle of nested GenericDocuments is packed into a Parcelable array.
            // We must unpack it into GenericDocument instances.
            Parcelable[] bundles = (Parcelable[]) rawValue;
            if (bundles.length == 0) {
                return null;
            }
            GenericDocument[] documents = new GenericDocument[bundles.length];
            for (int i = 0; i < bundles.length; i++) {
                if (bundles[i] == null) {
                    Log.e(TAG, "The inner bundle is null at " + i + ", for path: " + path);
                    continue;
                }
                if (!(bundles[i] instanceof Bundle)) {
                    Log.e(
                            TAG,
                            "The inner element at "
                                    + i
                                    + " is a "
                                    + bundles[i].getClass()
                                    + ", not a Bundle for path: "
                                    + path);
                    continue;
                }
                documents[i] = new GenericDocument((Bundle) bundles[i]);
            }
            return documents;
        }

        // Otherwise the raw property is the same as the final property and needs no transformation.
        return rawValue;
    }

    /**
     * Looks up a property path within the given document bundle.
     *
     * <p>The return value may be any of GenericDocument's internal repeated storage types
     * (String[], long[], double[], boolean[], ArrayList&lt;Bundle&gt;, Parcelable[]).
     */
    @Nullable
    private static Object getRawPropertyFromRawDocument(
            @NonNull String path, @NonNull Bundle documentBundle) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(documentBundle);
        Bundle properties = Objects.requireNonNull(documentBundle.getBundle(PROPERTIES_FIELD));

        // Determine whether the path is just a raw property name with no control characters
        int controlIdx = -1;
        boolean controlIsIndex = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '[' || c == '.') {
                controlIdx = i;
                controlIsIndex = c == '[';
                break;
            }
        }

        // Look up the value of the first path element
        Object firstElementValue;
        if (controlIdx == -1) {
            firstElementValue = properties.get(path);
        } else {
            String name = path.substring(0, controlIdx);
            firstElementValue = properties.get(name);
        }

        // If the path has no further elements, we're done.
        if (firstElementValue == null || controlIdx == -1) {
            return firstElementValue;
        }

        // At this point, for a path like "recipients[0]", firstElementValue contains the value of
        // "recipients". If the first element of the path is an indexed value, we now update
        // firstElementValue to contain "recipients[0]" instead.
        String remainingPath;
        if (!controlIsIndex) {
            // Remaining path is everything after the .
            remainingPath = path.substring(controlIdx + 1);
        } else {
            int endBracketIdx = path.indexOf(']', controlIdx);
            if (endBracketIdx == -1) {
                throw new IllegalArgumentException("Malformed path (no ending ']'): " + path);
            }
            if (endBracketIdx + 1 < path.length() && path.charAt(endBracketIdx + 1) != '.') {
                throw new IllegalArgumentException(
                        "Malformed path (']' not followed by '.'): " + path);
            }
            String indexStr = path.substring(controlIdx + 1, endBracketIdx);
            int index = Integer.parseInt(indexStr);
            if (index < 0) {
                throw new IllegalArgumentException("Path index less than 0: " + path);
            }

            // Remaining path is everything after the [n]
            if (endBracketIdx + 1 < path.length()) {
                // More path remains, and we've already checked that charAt(endBracketIdx+1) == .
                remainingPath = path.substring(endBracketIdx + 2);
            } else {
                // No more path remains.
                remainingPath = null;
            }

            // Extract the right array element
            Object extractedValue = null;
            if (firstElementValue instanceof String[]) {
                String[] stringValues = (String[]) firstElementValue;
                if (index < stringValues.length) {
                    extractedValue = Arrays.copyOfRange(stringValues, index, index + 1);
                }
            } else if (firstElementValue instanceof long[]) {
                long[] longValues = (long[]) firstElementValue;
                if (index < longValues.length) {
                    extractedValue = Arrays.copyOfRange(longValues, index, index + 1);
                }
            } else if (firstElementValue instanceof double[]) {
                double[] doubleValues = (double[]) firstElementValue;
                if (index < doubleValues.length) {
                    extractedValue = Arrays.copyOfRange(doubleValues, index, index + 1);
                }
            } else if (firstElementValue instanceof boolean[]) {
                boolean[] booleanValues = (boolean[]) firstElementValue;
                if (index < booleanValues.length) {
                    extractedValue = Arrays.copyOfRange(booleanValues, index, index + 1);
                }
            } else if (firstElementValue instanceof List) {
                @SuppressWarnings("unchecked")
                List<Bundle> bundles = (List<Bundle>) firstElementValue;
                if (index < bundles.size()) {
                    extractedValue = bundles.subList(index, index + 1);
                }
            } else if (firstElementValue instanceof Parcelable[]) {
                // Special optimization: to avoid creating new singleton arrays for traversing paths
                // we return the bare document Bundle in this particular case.
                Parcelable[] bundles = (Parcelable[]) firstElementValue;
                if (index < bundles.length) {
                    extractedValue = (Bundle) bundles[index];
                }
            } else {
                throw new IllegalStateException("Unsupported value type: " + firstElementValue);
            }
            firstElementValue = extractedValue;
        }

        // If we are at the end of the path or there are no deeper elements in this document, we
        // have nothing to recurse into.
        if (firstElementValue == null || remainingPath == null) {
            return firstElementValue;
        }

        // More of the path remains; recursively evaluate it
        if (firstElementValue instanceof Bundle) {
            return getRawPropertyFromRawDocument(remainingPath, (Bundle) firstElementValue);
        } else if (firstElementValue instanceof Parcelable[]) {
            Parcelable[] parcelables = (Parcelable[]) firstElementValue;
            if (parcelables.length == 1) {
                return getRawPropertyFromRawDocument(remainingPath, (Bundle) parcelables[0]);
            }

            // Slowest path: we're collecting values across repeated nested docs. (Example: given a
            // path like recipient.name, where recipient is a repeated field, we return a string
            // array where each recipient's name is an array element).
            //
            // Performance note: Suppose that we have a property path "a.b.c" where the "a"
            // property has N document values and each containing a "b" property with M document
            // values and each of those containing a "c" property with an int array.
            //
            // We'll allocate a new ArrayList for each of the "b" properties, add the M int arrays
            // from the "c" properties to it and then we'll allocate an int array in
            // flattenAccumulator before returning that (1 + M allocation per "b" property).
            //
            // When we're on the "a" properties, we'll allocate an ArrayList and add the N
            // flattened int arrays returned from the "b" properties to the list. Then we'll
            // allocate an int array in flattenAccumulator (1 + N ("b" allocs) allocations per "a").
            // So this implementation could incur 1 + N + NM allocs.
            //
            // However, we expect the vast majority of getProperty calls to be either for direct
            // property names (not paths) or else property paths returned from snippetting, which
            // always refer to exactly one property value and don't aggregate across repeated
            // values. The implementation is optimized for these two cases, requiring no additional
            // allocations. So we've decided that the above performance characteristics are OK for
            // the less used path.
            List<Object> accumulator = new ArrayList<>(parcelables.length);
            for (int i = 0; i < parcelables.length; i++) {
                Object value =
                        getRawPropertyFromRawDocument(remainingPath, (Bundle) parcelables[i]);
                if (value != null) {
                    accumulator.add(value);
                }
            }
            return flattenAccumulator(accumulator);
        } else {
            Log.e(TAG, "Failed to apply path to document; no nested value found: " + path);
            return null;
        }
    }

    /**
     * Combines accumulated repeated properties from multiple documents into a single array.
     *
     * @param accumulator List containing objects of the following types: {@code String[]}, {@code
     *     long[]}, {@code double[]}, {@code boolean[]}, {@code List<Bundle>}, or {@code
     *     Parcelable[]}.
     * @return The result of concatenating each individual list element into a larger array/list of
     *     the same type.
     */
    @Nullable
    private static Object flattenAccumulator(@NonNull List<Object> accumulator) {
        if (accumulator.isEmpty()) {
            return null;
        }
        Object first = accumulator.get(0);
        if (first instanceof String[]) {
            int length = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                length += ((String[]) accumulator.get(i)).length;
            }
            String[] result = new String[length];
            int total = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                String[] castValue = (String[]) accumulator.get(i);
                System.arraycopy(castValue, 0, result, total, castValue.length);
                total += castValue.length;
            }
            return result;
        }
        if (first instanceof long[]) {
            int length = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                length += ((long[]) accumulator.get(i)).length;
            }
            long[] result = new long[length];
            int total = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                long[] castValue = (long[]) accumulator.get(i);
                System.arraycopy(castValue, 0, result, total, castValue.length);
                total += castValue.length;
            }
            return result;
        }
        if (first instanceof double[]) {
            int length = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                length += ((double[]) accumulator.get(i)).length;
            }
            double[] result = new double[length];
            int total = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                double[] castValue = (double[]) accumulator.get(i);
                System.arraycopy(castValue, 0, result, total, castValue.length);
                total += castValue.length;
            }
            return result;
        }
        if (first instanceof boolean[]) {
            int length = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                length += ((boolean[]) accumulator.get(i)).length;
            }
            boolean[] result = new boolean[length];
            int total = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                boolean[] castValue = (boolean[]) accumulator.get(i);
                System.arraycopy(castValue, 0, result, total, castValue.length);
                total += castValue.length;
            }
            return result;
        }
        if (first instanceof List) {
            int length = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                length += ((List<?>) accumulator.get(i)).size();
            }
            List<Bundle> result = new ArrayList<>(length);
            for (int i = 0; i < accumulator.size(); i++) {
                @SuppressWarnings("unchecked")
                List<Bundle> castValue = (List<Bundle>) accumulator.get(i);
                result.addAll(castValue);
            }
            return result;
        }
        if (first instanceof Parcelable[]) {
            int length = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                length += ((Parcelable[]) accumulator.get(i)).length;
            }
            Parcelable[] result = new Parcelable[length];
            int total = 0;
            for (int i = 0; i < accumulator.size(); i++) {
                Parcelable[] castValue = (Parcelable[]) accumulator.get(i);
                System.arraycopy(castValue, 0, result, total, castValue.length);
                total += castValue.length;
            }
            return result;
        }
        throw new IllegalStateException("Unexpected property type: " + first);
    }

    /**
     * Retrieves a {@link String} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The first {@link String} associated with the given path or {@code null} if there is
     *     no such value or the value is of a different type.
     */
    @Nullable
    public String getPropertyString(@NonNull String path) {
        Objects.requireNonNull(path);
        String[] propertyArray = getPropertyStringArray(path);
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("String", path, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code long} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The first {@code long} associated with the given path or default value {@code 0} if
     *     there is no such value or the value is of a different type.
     */
    public long getPropertyLong(@NonNull String path) {
        Objects.requireNonNull(path);
        long[] propertyArray = getPropertyLongArray(path);
        if (propertyArray == null || propertyArray.length == 0) {
            return 0;
        }
        warnIfSinglePropertyTooLong("Long", path, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code double} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The first {@code double} associated with the given path or default value {@code 0.0}
     *     if there is no such value or the value is of a different type.
     */
    public double getPropertyDouble(@NonNull String path) {
        Objects.requireNonNull(path);
        double[] propertyArray = getPropertyDoubleArray(path);
        if (propertyArray == null || propertyArray.length == 0) {
            return 0.0;
        }
        warnIfSinglePropertyTooLong("Double", path, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code boolean} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The first {@code boolean} associated with the given path or default value {@code
     *     false} if there is no such value or the value is of a different type.
     */
    public boolean getPropertyBoolean(@NonNull String path) {
        Objects.requireNonNull(path);
        boolean[] propertyArray = getPropertyBooleanArray(path);
        if (propertyArray == null || propertyArray.length == 0) {
            return false;
        }
        warnIfSinglePropertyTooLong("Boolean", path, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@code byte[]} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The first {@code byte[]} associated with the given path or {@code null} if there is
     *     no such value or the value is of a different type.
     */
    @Nullable
    public byte[] getPropertyBytes(@NonNull String path) {
        Objects.requireNonNull(path);
        byte[][] propertyArray = getPropertyBytesArray(path);
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("ByteArray", path, propertyArray.length);
        return propertyArray[0];
    }

    /**
     * Retrieves a {@link GenericDocument} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The first {@link GenericDocument} associated with the given path or {@code null} if
     *     there is no such value or the value is of a different type.
     */
    @Nullable
    public GenericDocument getPropertyDocument(@NonNull String path) {
        Objects.requireNonNull(path);
        GenericDocument[] propertyArray = getPropertyDocumentArray(path);
        if (propertyArray == null || propertyArray.length == 0) {
            return null;
        }
        warnIfSinglePropertyTooLong("Document", path, propertyArray.length);
        return propertyArray[0];
    }

    /** Prints a warning to logcat if the given propertyLength is greater than 1. */
    private static void warnIfSinglePropertyTooLong(
            @NonNull String propertyType, @NonNull String path, int propertyLength) {
        if (propertyLength > 1) {
            Log.w(
                    TAG,
                    "The value for \""
                            + path
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
     * Retrieves a repeated {@code String} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The {@code String[]} associated with the given path, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @Nullable
    public String[] getPropertyStringArray(@NonNull String path) {
        Objects.requireNonNull(path);
        Object value = getProperty(path);
        return safeCastProperty(path, value, String[].class);
    }

    /**
     * Retrieves a repeated {@code long[]} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The {@code long[]} associated with the given path, or {@code null} if no value is set
     *     or the value is of a different type.
     */
    @Nullable
    public long[] getPropertyLongArray(@NonNull String path) {
        Objects.requireNonNull(path);
        Object value = getProperty(path);
        return safeCastProperty(path, value, long[].class);
    }

    /**
     * Retrieves a repeated {@code double} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The {@code double[]} associated with the given path, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @Nullable
    public double[] getPropertyDoubleArray(@NonNull String path) {
        Objects.requireNonNull(path);
        Object value = getProperty(path);
        return safeCastProperty(path, value, double[].class);
    }

    /**
     * Retrieves a repeated {@code boolean} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The {@code boolean[]} associated with the given path, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @Nullable
    public boolean[] getPropertyBooleanArray(@NonNull String path) {
        Objects.requireNonNull(path);
        Object value = getProperty(path);
        return safeCastProperty(path, value, boolean[].class);
    }

    /**
     * Retrieves a {@code byte[][]} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The {@code byte[][]} associated with the given path, or {@code null} if no value is
     *     set or the value is of a different type.
     */
    @SuppressLint("ArrayReturn")
    @Nullable
    public byte[][] getPropertyBytesArray(@NonNull String path) {
        Objects.requireNonNull(path);
        Object value = getProperty(path);
        return safeCastProperty(path, value, byte[][].class);
    }

    /**
     * Retrieves a repeated {@link GenericDocument} property by path.
     *
     * <p>See {@link #getProperty} for a detailed description of the path syntax.
     *
     * @param path The path to look for.
     * @return The {@link GenericDocument}[] associated with the given path, or {@code null} if no
     *     value is set or the value is of a different type.
     */
    @SuppressLint("ArrayReturn")
    @Nullable
    public GenericDocument[] getPropertyDocumentArray(@NonNull String path) {
        Objects.requireNonNull(path);
        Object value = getProperty(path);
        return safeCastProperty(path, value, GenericDocument[].class);
    }

    /**
     * Casts a repeated property to the provided type, logging an error and returning {@code null}
     * if the cast fails.
     *
     * @param path Path to the property within the document. Used for logging.
     * @param value Value of the property
     * @param tClass Class to cast the value into
     */
    @Nullable
    private static <T> T safeCastProperty(
            @NonNull String path, @Nullable Object value, @NonNull Class<T> tClass) {
        if (value == null) {
            return null;
        }
        try {
            return tClass.cast(value);
        } catch (ClassCastException e) {
            Log.w(TAG, "Error casting to requested type for path \"" + path + "\"", e);
            return null;
        }
    }

    /**
     * Copies the contents of this {@link GenericDocument} into a new {@link
     * GenericDocument.Builder}.
     *
     * <p>The returned builder is a deep copy whose data is separate from this document.
     *
     * @hide
     */
    // TODO(b/171882200): Expose this API in Android T
    @NonNull
    public GenericDocument.Builder<GenericDocument.Builder<?>> toBuilder() {
        Bundle clonedBundle = BundleUtil.deepCopy(mBundle);
        return new GenericDocument.Builder<>(clonedBundle);
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
        IndentingStringBuilder stringBuilder = new IndentingStringBuilder();
        appendGenericDocumentString(stringBuilder);
        return stringBuilder.toString();
    }

    /**
     * Appends a debug string for the {@link GenericDocument} instance to the given string builder.
     *
     * @param builder the builder to append to.
     */
    void appendGenericDocumentString(@NonNull IndentingStringBuilder builder) {
        Objects.requireNonNull(builder);

        builder.append("{\n");
        builder.increaseIndentLevel();

        builder.append("namespace: \"").append(getNamespace()).append("\",\n");
        builder.append("id: \"").append(getId()).append("\",\n");
        builder.append("score: ").append(getScore()).append(",\n");
        builder.append("schemaType: \"").append(getSchemaType()).append("\",\n");
        builder.append("creationTimestampMillis: ")
                .append(getCreationTimestampMillis())
                .append(",\n");
        builder.append("timeToLiveMillis: ").append(getTtlMillis()).append(",\n");

        builder.append("properties: {\n");

        String[] sortedProperties = getPropertyNames().toArray(new String[0]);
        Arrays.sort(sortedProperties);

        for (int i = 0; i < sortedProperties.length; i++) {
            Object property = getProperty(sortedProperties[i]);
            builder.increaseIndentLevel();
            appendPropertyString(sortedProperties[i], property, builder);
            if (i != sortedProperties.length - 1) {
                builder.append(",\n");
            }
            builder.decreaseIndentLevel();
        }

        builder.append("\n");
        builder.append("}");

        builder.decreaseIndentLevel();
        builder.append("\n");
        builder.append("}");
    }

    /**
     * Appends a debug string for the given document property to the given string builder.
     *
     * @param propertyName name of property to create string for.
     * @param property property object to create string for.
     * @param builder the builder to append to.
     */
    private void appendPropertyString(
            @NonNull String propertyName,
            @NonNull Object property,
            @NonNull IndentingStringBuilder builder) {
        Objects.requireNonNull(propertyName);
        Objects.requireNonNull(property);
        Objects.requireNonNull(builder);

        builder.append("\"").append(propertyName).append("\": [");
        if (property instanceof GenericDocument[]) {
            GenericDocument[] documentValues = (GenericDocument[]) property;
            for (int i = 0; i < documentValues.length; ++i) {
                builder.append("\n");
                builder.increaseIndentLevel();
                documentValues[i].appendGenericDocumentString(builder);
                if (i != documentValues.length - 1) {
                    builder.append(",");
                }
                builder.append("\n");
                builder.decreaseIndentLevel();
            }
            builder.append("]");
        } else {
            int propertyArrLength = Array.getLength(property);
            for (int i = 0; i < propertyArrLength; i++) {
                Object propertyElement = Array.get(property, i);
                if (propertyElement instanceof String) {
                    builder.append("\"").append((String) propertyElement).append("\"");
                } else if (propertyElement instanceof byte[]) {
                    builder.append(Arrays.toString((byte[]) propertyElement));
                } else {
                    builder.append(propertyElement.toString());
                }
                if (i != propertyArrLength - 1) {
                    builder.append(", ");
                } else {
                    builder.append("]");
                }
            }
        }
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
        private Bundle mBundle;
        private Bundle mProperties;
        private final BuilderType mBuilderTypeInstance;
        private boolean mBuilt = false;

        /**
         * Creates a new {@link GenericDocument.Builder}.
         *
         * <p>Document IDs are unique within a namespace.
         *
         * <p>The number of namespaces per app should be kept small for efficiency reasons.
         *
         * @param namespace the namespace to set for the {@link GenericDocument}.
         * @param id the unique identifier for the {@link GenericDocument} in its namespace.
         * @param schemaType the {@link AppSearchSchema} type of the {@link GenericDocument}. The
         *     provided {@code schemaType} must be defined using {@link AppSearchSession#setSchema}
         *     prior to inserting a document of this {@code schemaType} into the AppSearch index
         *     using {@link AppSearchSession#put}. Otherwise, the document will be rejected by
         *     {@link AppSearchSession#put} with result code {@link
         *     AppSearchResult#RESULT_NOT_FOUND}.
         */
        @SuppressWarnings("unchecked")
        public Builder(@NonNull String namespace, @NonNull String id, @NonNull String schemaType) {
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(id);
            Objects.requireNonNull(schemaType);

            mBundle = new Bundle();
            mBuilderTypeInstance = (BuilderType) this;
            mBundle.putString(GenericDocument.NAMESPACE_FIELD, namespace);
            mBundle.putString(GenericDocument.ID_FIELD, id);
            mBundle.putString(GenericDocument.SCHEMA_TYPE_FIELD, schemaType);
            mBundle.putLong(GenericDocument.TTL_MILLIS_FIELD, DEFAULT_TTL_MILLIS);
            mBundle.putInt(GenericDocument.SCORE_FIELD, DEFAULT_SCORE);

            mProperties = new Bundle();
            mBundle.putBundle(PROPERTIES_FIELD, mProperties);
        }

        /**
         * Creates a new {@link GenericDocument.Builder} from the given Bundle.
         *
         * <p>The bundle is NOT copied.
         */
        @SuppressWarnings("unchecked")
        Builder(@NonNull Bundle bundle) {
            mBundle = Objects.requireNonNull(bundle);
            mProperties = mBundle.getBundle(PROPERTIES_FIELD);
            mBuilderTypeInstance = (BuilderType) this;
        }

        /**
         * Sets the app-defined namespace this document resides in, changing the value provided in
         * the constructor. No special values are reserved or understood by the infrastructure.
         *
         * <p>Document IDs are unique within a namespace.
         *
         * <p>The number of namespaces per app should be kept small for efficiency reasons.
         *
         * @hide
         */
        @NonNull
        public BuilderType setNamespace(@NonNull String namespace) {
            Objects.requireNonNull(namespace);
            resetIfBuilt();
            mBundle.putString(GenericDocument.NAMESPACE_FIELD, namespace);
            return mBuilderTypeInstance;
        }

        /**
         * Sets the ID of this document, changing the value provided in the constructor. No special
         * values are reserved or understood by the infrastructure.
         *
         * <p>Document IDs are unique within a namespace.
         *
         * @hide
         */
        @NonNull
        public BuilderType setId(@NonNull String id) {
            Objects.requireNonNull(id);
            resetIfBuilt();
            mBundle.putString(GenericDocument.ID_FIELD, id);
            return mBuilderTypeInstance;
        }

        /**
         * Sets the schema type of this document, changing the value provided in the constructor.
         *
         * <p>To successfully index a document, the schema type must match the name of an {@link
         * AppSearchSchema} object previously provided to {@link AppSearchSession#setSchema}.
         *
         * @hide
         */
        @NonNull
        public BuilderType setSchemaType(@NonNull String schemaType) {
            Objects.requireNonNull(schemaType);
            resetIfBuilt();
            mBundle.putString(GenericDocument.SCHEMA_TYPE_FIELD, schemaType);
            return mBuilderTypeInstance;
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
         */
        @NonNull
        public BuilderType setScore(@IntRange(from = 0, to = Integer.MAX_VALUE) int score) {
            if (score < 0) {
                throw new IllegalArgumentException("Document score cannot be negative.");
            }
            resetIfBuilt();
            mBundle.putInt(GenericDocument.SCORE_FIELD, score);
            return mBuilderTypeInstance;
        }

        /**
         * Sets the creation timestamp of the {@link GenericDocument}, in milliseconds.
         *
         * <p>This should be set using a value obtained from the {@link System#currentTimeMillis}
         * time base.
         *
         * <p>If this method is not called, this will be set to the time the object is built.
         *
         * @param creationTimestampMillis a creation timestamp in milliseconds.
         */
        @NonNull
        public BuilderType setCreationTimestampMillis(
                @CurrentTimeMillisLong long creationTimestampMillis) {
            resetIfBuilt();
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
         */
        @NonNull
        public BuilderType setTtlMillis(long ttlMillis) {
            if (ttlMillis < 0) {
                throw new IllegalArgumentException("Document ttlMillis cannot be negative.");
            }
            resetIfBuilt();
            mBundle.putLong(GenericDocument.TTL_MILLIS_FIELD, ttlMillis);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code String} values for a property, replacing its previous values.
         *
         * @param name the name associated with the {@code values}. Must match the name for this
         *     property as given in {@link AppSearchSchema.PropertyConfig#getName}.
         * @param values the {@code String} values of the property.
         * @throws IllegalArgumentException if no values are provided, or if a passed in {@code
         *     String} is {@code null}.
         */
        @NonNull
        public BuilderType setPropertyString(@NonNull String name, @NonNull String... values) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(values);
            resetIfBuilt();
            putInPropertyBundle(name, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code boolean} values for a property, replacing its previous
         * values.
         *
         * @param name the name associated with the {@code values}. Must match the name for this
         *     property as given in {@link AppSearchSchema.PropertyConfig#getName}.
         * @param values the {@code boolean} values of the property.
         */
        @NonNull
        public BuilderType setPropertyBoolean(@NonNull String name, @NonNull boolean... values) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(values);
            resetIfBuilt();
            putInPropertyBundle(name, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code long} values for a property, replacing its previous values.
         *
         * @param name the name associated with the {@code values}. Must match the name for this
         *     property as given in {@link AppSearchSchema.PropertyConfig#getName}.
         * @param values the {@code long} values of the property.
         */
        @NonNull
        public BuilderType setPropertyLong(@NonNull String name, @NonNull long... values) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(values);
            resetIfBuilt();
            putInPropertyBundle(name, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code double} values for a property, replacing its previous values.
         *
         * @param name the name associated with the {@code values}. Must match the name for this
         *     property as given in {@link AppSearchSchema.PropertyConfig#getName}.
         * @param values the {@code double} values of the property.
         */
        @NonNull
        public BuilderType setPropertyDouble(@NonNull String name, @NonNull double... values) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(values);
            resetIfBuilt();
            putInPropertyBundle(name, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@code byte[]} for a property, replacing its previous values.
         *
         * @param name the name associated with the {@code values}. Must match the name for this
         *     property as given in {@link AppSearchSchema.PropertyConfig#getName}.
         * @param values the {@code byte[]} of the property.
         * @throws IllegalArgumentException if no values are provided, or if a passed in {@code
         *     byte[]} is {@code null}.
         */
        @NonNull
        public BuilderType setPropertyBytes(@NonNull String name, @NonNull byte[]... values) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(values);
            resetIfBuilt();
            putInPropertyBundle(name, values);
            return mBuilderTypeInstance;
        }

        /**
         * Sets one or multiple {@link GenericDocument} values for a property, replacing its
         * previous values.
         *
         * @param name the name associated with the {@code values}. Must match the name for this
         *     property as given in {@link AppSearchSchema.PropertyConfig#getName}.
         * @param values the {@link GenericDocument} values of the property.
         * @throws IllegalArgumentException if no values are provided, or if a passed in {@link
         *     GenericDocument} is {@code null}.
         */
        @NonNull
        public BuilderType setPropertyDocument(
                @NonNull String name, @NonNull GenericDocument... values) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(values);
            resetIfBuilt();
            putInPropertyBundle(name, values);
            return mBuilderTypeInstance;
        }

        /**
         * Clears the value for the property with the given name.
         *
         * <p>Note that this method does not support property paths.
         *
         * @param name The name of the property to clear.
         * @hide
         */
        @NonNull
        public BuilderType clearProperty(@NonNull String name) {
            Objects.requireNonNull(name);
            resetIfBuilt();
            mProperties.remove(name);
            return mBuilderTypeInstance;
        }

        private void putInPropertyBundle(@NonNull String name, @NonNull String[] values)
                throws IllegalArgumentException {
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The String at " + i + " is null.");
                }
            }
            mProperties.putStringArray(name, values);
        }

        private void putInPropertyBundle(@NonNull String name, @NonNull boolean[] values) {
            mProperties.putBooleanArray(name, values);
        }

        private void putInPropertyBundle(@NonNull String name, @NonNull double[] values) {
            mProperties.putDoubleArray(name, values);
        }

        private void putInPropertyBundle(@NonNull String name, @NonNull long[] values) {
            mProperties.putLongArray(name, values);
        }

        /**
         * Converts and saves a byte[][] into {@link #mProperties}.
         *
         * <p>Bundle doesn't support for two dimension array byte[][], we are converting byte[][]
         * into ArrayList<Bundle>, and each elements will contain a one dimension byte[].
         */
        private void putInPropertyBundle(@NonNull String name, @NonNull byte[][] values) {
            ArrayList<Bundle> bundles = new ArrayList<>(values.length);
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The byte[] at " + i + " is null.");
                }
                Bundle bundle = new Bundle();
                bundle.putByteArray(BYTE_ARRAY_FIELD, values[i]);
                bundles.add(bundle);
            }
            mProperties.putParcelableArrayList(name, bundles);
        }

        private void putInPropertyBundle(@NonNull String name, @NonNull GenericDocument[] values) {
            Parcelable[] documentBundles = new Parcelable[values.length];
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    throw new IllegalArgumentException("The document at " + i + " is null.");
                }
                documentBundles[i] = values[i].mBundle;
            }
            mProperties.putParcelableArray(name, documentBundles);
        }

        /** Builds the {@link GenericDocument} object. */
        @NonNull
        public GenericDocument build() {
            mBuilt = true;
            // Set current timestamp for creation timestamp by default.
            if (mBundle.getLong(GenericDocument.CREATION_TIMESTAMP_MILLIS_FIELD, -1) == -1) {
                mBundle.putLong(
                        GenericDocument.CREATION_TIMESTAMP_MILLIS_FIELD,
                        System.currentTimeMillis());
            }
            return new GenericDocument(mBundle);
        }

        private void resetIfBuilt() {
            if (mBuilt) {
                mBundle = BundleUtil.deepCopy(mBundle);
                mProperties = mBundle.getBundle(PROPERTIES_FIELD);
                mBuilt = false;
            }
        }
    }
}
