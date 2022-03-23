/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single operation to be performed as part of a batch of operations.
 *
 * @see ContentProvider#applyBatch(ArrayList)
 */
public class ContentProviderOperation implements Parcelable {
    /** @hide exposed for unit tests */
    @UnsupportedAppUsage
    public final static int TYPE_INSERT = 1;
    /** @hide exposed for unit tests */
    @UnsupportedAppUsage
    public final static int TYPE_UPDATE = 2;
    /** @hide exposed for unit tests */
    @UnsupportedAppUsage
    public final static int TYPE_DELETE = 3;
    /** @hide exposed for unit tests */
    public final static int TYPE_ASSERT = 4;
    /** @hide exposed for unit tests */
    public final static int TYPE_CALL = 5;

    @UnsupportedAppUsage
    private final int mType;
    @UnsupportedAppUsage
    private final Uri mUri;
    private final String mMethod;
    private final String mArg;
    private final ArrayMap<String, Object> mValues;
    private final ArrayMap<String, Object> mExtras;
    @UnsupportedAppUsage
    private final String mSelection;
    private final SparseArray<Object> mSelectionArgs;
    private final Integer mExpectedCount;
    private final boolean mYieldAllowed;
    private final boolean mExceptionAllowed;

    private final static String TAG = "ContentProviderOperation";

    /**
     * Creates a {@link ContentProviderOperation} by copying the contents of a
     * {@link Builder}.
     */
    private ContentProviderOperation(Builder builder) {
        mType = builder.mType;
        mUri = builder.mUri;
        mMethod = builder.mMethod;
        mArg = builder.mArg;
        mValues = builder.mValues;
        mExtras = builder.mExtras;
        mSelection = builder.mSelection;
        mSelectionArgs = builder.mSelectionArgs;
        mExpectedCount = builder.mExpectedCount;
        mYieldAllowed = builder.mYieldAllowed;
        mExceptionAllowed = builder.mExceptionAllowed;
    }

    private ContentProviderOperation(Parcel source) {
        mType = source.readInt();
        mUri = Uri.CREATOR.createFromParcel(source);
        mMethod = source.readInt() != 0 ? source.readString8() : null;
        mArg = source.readInt() != 0 ? source.readString8() : null;
        final int valuesSize = source.readInt();
        if (valuesSize != -1) {
            mValues = new ArrayMap<>(valuesSize);
            source.readArrayMap(mValues, null);
        } else {
            mValues = null;
        }
        final int extrasSize = source.readInt();
        if (extrasSize != -1) {
            mExtras = new ArrayMap<>(extrasSize);
            source.readArrayMap(mExtras, null);
        } else {
            mExtras = null;
        }
        mSelection = source.readInt() != 0 ? source.readString8() : null;
        mSelectionArgs = source.readSparseArray(null);
        mExpectedCount = source.readInt() != 0 ? source.readInt() : null;
        mYieldAllowed = source.readInt() != 0;
        mExceptionAllowed = source.readInt() != 0;
    }

    /** @hide */
    public ContentProviderOperation(ContentProviderOperation cpo, Uri withUri) {
        mType = cpo.mType;
        mUri = withUri;
        mMethod = cpo.mMethod;
        mArg = cpo.mArg;
        mValues = cpo.mValues;
        mExtras = cpo.mExtras;
        mSelection = cpo.mSelection;
        mSelectionArgs = cpo.mSelectionArgs;
        mExpectedCount = cpo.mExpectedCount;
        mYieldAllowed = cpo.mYieldAllowed;
        mExceptionAllowed = cpo.mExceptionAllowed;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        Uri.writeToParcel(dest, mUri);
        if (mMethod != null) {
            dest.writeInt(1);
            dest.writeString8(mMethod);
        } else {
            dest.writeInt(0);
        }
        if (mArg != null) {
            dest.writeInt(1);
            dest.writeString8(mArg);
        } else {
            dest.writeInt(0);
        }
        if (mValues != null) {
            dest.writeInt(mValues.size());
            dest.writeArrayMap(mValues);
        } else {
            dest.writeInt(-1);
        }
        if (mExtras != null) {
            dest.writeInt(mExtras.size());
            dest.writeArrayMap(mExtras);
        } else {
            dest.writeInt(-1);
        }
        if (mSelection != null) {
            dest.writeInt(1);
            dest.writeString8(mSelection);
        } else {
            dest.writeInt(0);
        }
        dest.writeSparseArray(mSelectionArgs);
        if (mExpectedCount != null) {
            dest.writeInt(1);
            dest.writeInt(mExpectedCount);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(mYieldAllowed ? 1 : 0);
        dest.writeInt(mExceptionAllowed ? 1 : 0);
    }

    /**
     * Create a {@link Builder} suitable for building an operation that will
     * invoke {@link ContentProvider#insert}.
     *
     * @param uri The {@link Uri} that is the target of the operation.
     */
    public static @NonNull Builder newInsert(@NonNull Uri uri) {
        return new Builder(TYPE_INSERT, uri);
    }

    /**
     * Create a {@link Builder} suitable for building an operation that will
     * invoke {@link ContentProvider#update}.
     *
     * @param uri The {@link Uri} that is the target of the operation.
     */
    public static @NonNull Builder newUpdate(@NonNull Uri uri) {
        return new Builder(TYPE_UPDATE, uri);
    }

    /**
     * Create a {@link Builder} suitable for building an operation that will
     * invoke {@link ContentProvider#delete}.
     *
     * @param uri The {@link Uri} that is the target of the operation.
     */
    public static @NonNull Builder newDelete(@NonNull Uri uri) {
        return new Builder(TYPE_DELETE, uri);
    }

    /**
     * Create a {@link Builder} suitable for building a
     * {@link ContentProviderOperation} to assert a set of values as provided
     * through {@link Builder#withValues(ContentValues)}.
     */
    public static @NonNull Builder newAssertQuery(@NonNull Uri uri) {
        return new Builder(TYPE_ASSERT, uri);
    }

    /**
     * Create a {@link Builder} suitable for building an operation that will
     * invoke {@link ContentProvider#call}.
     *
     * @param uri The {@link Uri} that is the target of the operation.
     */
    public static @NonNull Builder newCall(@NonNull Uri uri, @Nullable String method,
            @Nullable String arg) {
        return new Builder(TYPE_CALL, uri, method, arg);
    }

    /**
     * Gets the Uri for the target of the operation.
     */
    public @NonNull Uri getUri() {
        return mUri;
    }

    /**
     * Returns true if the operation allows yielding the database to other transactions
     * if the database is contended.
     *
     * @see android.database.sqlite.SQLiteDatabase#yieldIfContendedSafely()
     */
    public boolean isYieldAllowed() {
        return mYieldAllowed;
    }

    /**
     * Returns true if this operation allows subsequent operations to continue
     * even if this operation throws an exception. When true, any encountered
     * exception is returned via {@link ContentProviderResult#exception}.
     */
    public boolean isExceptionAllowed() {
        return mExceptionAllowed;
    }

    /** @hide exposed for unit tests */
    @UnsupportedAppUsage
    public int getType() {
        return mType;
    }

    /**
     * Returns true if the operation represents a {@link ContentProvider#insert}
     * operation.
     *
     * @see #newInsert
     */
    public boolean isInsert() {
        return mType == TYPE_INSERT;
    }

    /**
     * Returns true if the operation represents a {@link ContentProvider#delete}
     * operation.
     *
     * @see #newDelete
     */
    public boolean isDelete() {
        return mType == TYPE_DELETE;
    }

    /**
     * Returns true if the operation represents a {@link ContentProvider#update}
     * operation.
     *
     * @see #newUpdate
     */
    public boolean isUpdate() {
        return mType == TYPE_UPDATE;
    }

    /**
     * Returns true if the operation represents an assert query.
     *
     * @see #newAssertQuery
     */
    public boolean isAssertQuery() {
        return mType == TYPE_ASSERT;
    }

    /**
     * Returns true if the operation represents a {@link ContentProvider#call}
     * operation.
     *
     * @see #newCall
     */
    public boolean isCall() {
        return mType == TYPE_CALL;
    }

    /**
     * Returns true if the operation represents an insertion, deletion, or update.
     *
     * @see #isInsert
     * @see #isDelete
     * @see #isUpdate
     */
    public boolean isWriteOperation() {
        return mType == TYPE_DELETE || mType == TYPE_INSERT || mType == TYPE_UPDATE;
    }

    /**
     * Returns true if the operation represents an assert query.
     *
     * @see #isAssertQuery
     */
    public boolean isReadOperation() {
        return mType == TYPE_ASSERT;
    }

    /**
     * Applies this operation using the given provider. The backRefs array is used to resolve any
     * back references that were requested using
     * {@link Builder#withValueBackReferences(ContentValues)} and
     * {@link Builder#withSelectionBackReference}.
     * @param provider the {@link ContentProvider} on which this batch is applied
     * @param backRefs a {@link ContentProviderResult} array that will be consulted
     * to resolve any requested back references.
     * @param numBackRefs the number of valid results on the backRefs array.
     * @return a {@link ContentProviderResult} that contains either the {@link Uri} of the inserted
     * row if this was an insert otherwise the number of rows affected.
     * @throws OperationApplicationException thrown if either the insert fails or
     * if the number of rows affected didn't match the expected count
     */
    public @NonNull ContentProviderResult apply(@NonNull ContentProvider provider,
            @NonNull ContentProviderResult[] backRefs, int numBackRefs)
            throws OperationApplicationException {
        if (mExceptionAllowed) {
            try {
                return applyInternal(provider, backRefs, numBackRefs);
            } catch (Exception e) {
                return new ContentProviderResult(e);
            }
        } else {
            return applyInternal(provider, backRefs, numBackRefs);
        }
    }

    private ContentProviderResult applyInternal(ContentProvider provider,
            ContentProviderResult[] backRefs, int numBackRefs)
            throws OperationApplicationException {
        final ContentValues values = resolveValueBackReferences(backRefs, numBackRefs);

        // If the creator requested explicit selection or selectionArgs, it
        // should take precedence over similar values they defined in extras
        Bundle extras = resolveExtrasBackReferences(backRefs, numBackRefs);
        if (mSelection != null) {
            extras = (extras != null) ? extras : new Bundle();
            extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, mSelection);
        }
        if (mSelectionArgs != null) {
            extras = (extras != null) ? extras : new Bundle();
            extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    resolveSelectionArgsBackReferences(backRefs, numBackRefs));
        }

        if (mType == TYPE_INSERT) {
            final Uri newUri = provider.insert(mUri, values, extras);
            if (newUri != null) {
                return new ContentProviderResult(newUri);
            } else {
                throw new OperationApplicationException(
                        "Insert into " + mUri + " returned no result");
            }
        } else if (mType == TYPE_CALL) {
            final Bundle res = provider.call(mUri.getAuthority(), mMethod, mArg, extras);
            return new ContentProviderResult(res);
        }

        final int numRows;
        if (mType == TYPE_DELETE) {
            numRows = provider.delete(mUri, extras);
        } else if (mType == TYPE_UPDATE) {
            numRows = provider.update(mUri, values, extras);
        } else if (mType == TYPE_ASSERT) {
            // Assert that all rows match expected values
            String[] projection =  null;
            if (values != null) {
                // Build projection map from expected values
                final ArrayList<String> projectionList = new ArrayList<String>();
                for (Map.Entry<String, Object> entry : values.valueSet()) {
                    projectionList.add(entry.getKey());
                }
                projection = projectionList.toArray(new String[projectionList.size()]);
            }
            final Cursor cursor = provider.query(mUri, projection, extras, null);
            try {
                numRows = cursor.getCount();
                if (projection != null) {
                    while (cursor.moveToNext()) {
                        for (int i = 0; i < projection.length; i++) {
                            final String cursorValue = cursor.getString(i);
                            final String expectedValue = values.getAsString(projection[i]);
                            if (!TextUtils.equals(cursorValue, expectedValue)) {
                                // Throw exception when expected values don't match
                                throw new OperationApplicationException("Found value " + cursorValue
                                        + " when expected " + expectedValue + " for column "
                                        + projection[i]);
                            }
                        }
                    }
                }
            } finally {
                cursor.close();
            }
        } else {
            throw new IllegalStateException("bad type, " + mType);
        }

        if (mExpectedCount != null && mExpectedCount != numRows) {
            throw new OperationApplicationException(
                    "Expected " + mExpectedCount + " rows but actual " + numRows);
        }

        return new ContentProviderResult(numRows);
    }

    /**
     * Return the values for this operation after resolving any requested
     * back-references using the given results.
     *
     * @param backRefs the results to use when resolving any back-references
     * @param numBackRefs the number of results which are valid
     */
    public @Nullable ContentValues resolveValueBackReferences(
            @NonNull ContentProviderResult[] backRefs, int numBackRefs) {
        if (mValues != null) {
            final ContentValues values = new ContentValues();
            for (int i = 0; i < mValues.size(); i++) {
                final Object value = mValues.valueAt(i);
                final Object resolved;
                if (value instanceof BackReference) {
                    resolved = ((BackReference) value).resolve(backRefs, numBackRefs);
                } else {
                    resolved = value;
                }
                values.putObject(mValues.keyAt(i), resolved);
            }
            return values;
        } else {
            return null;
        }
    }

    /**
     * Return the extras for this operation after resolving any requested
     * back-references using the given results.
     *
     * @param backRefs the results to use when resolving any back-references
     * @param numBackRefs the number of results which are valid
     */
    public @Nullable Bundle resolveExtrasBackReferences(
            @NonNull ContentProviderResult[] backRefs, int numBackRefs) {
        if (mExtras != null) {
            final Bundle extras = new Bundle();
            for (int i = 0; i < mExtras.size(); i++) {
                final Object value = mExtras.valueAt(i);
                final Object resolved;
                if (value instanceof BackReference) {
                    resolved = ((BackReference) value).resolve(backRefs, numBackRefs);
                } else {
                    resolved = value;
                }
                extras.putObject(mExtras.keyAt(i), resolved);
            }
            return extras;
        } else {
            return null;
        }
    }

    /**
     * Return the selection arguments for this operation after resolving any
     * requested back-references using the given results.
     *
     * @param backRefs the results to use when resolving any back-references
     * @param numBackRefs the number of results which are valid
     */
    public @Nullable String[] resolveSelectionArgsBackReferences(
            @NonNull ContentProviderResult[] backRefs, int numBackRefs) {
        if (mSelectionArgs != null) {
            int max = -1;
            for (int i = 0; i < mSelectionArgs.size(); i++) {
                max = Math.max(max, mSelectionArgs.keyAt(i));
            }

            final String[] selectionArgs = new String[max + 1];
            for (int i = 0; i < mSelectionArgs.size(); i++) {
                final Object value = mSelectionArgs.valueAt(i);
                final Object resolved;
                if (value instanceof BackReference) {
                    resolved = ((BackReference) value).resolve(backRefs, numBackRefs);
                } else {
                    resolved = value;
                }
                selectionArgs[mSelectionArgs.keyAt(i)] = String.valueOf(resolved);
            }
            return selectionArgs;
        } else {
            return null;
        }
    }

    /** {@hide} */
    public static String typeToString(int type) {
        switch (type) {
            case TYPE_INSERT: return "insert";
            case TYPE_UPDATE: return "update";
            case TYPE_DELETE: return "delete";
            case TYPE_ASSERT: return "assert";
            case TYPE_CALL: return "call";
            default: return Integer.toString(type);
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ContentProviderOperation(");
        sb.append("type=").append(typeToString(mType)).append(' ');
        if (mUri != null) {
            sb.append("uri=").append(mUri).append(' ');
        }
        if (mValues != null) {
            sb.append("values=").append(mValues).append(' ');
        }
        if (mSelection != null) {
            sb.append("selection=").append(mSelection).append(' ');
        }
        if (mSelectionArgs != null) {
            sb.append("selectionArgs=").append(mSelectionArgs).append(' ');
        }
        if (mExpectedCount != null) {
            sb.append("expectedCount=").append(mExpectedCount).append(' ');
        }
        if (mYieldAllowed) {
            sb.append("yieldAllowed ");
        }
        if (mExceptionAllowed) {
            sb.append("exceptionAllowed ");
        }
        sb.deleteCharAt(sb.length() - 1);
        sb.append(")");
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Creator<ContentProviderOperation> CREATOR =
            new Creator<ContentProviderOperation>() {
        @Override
        public ContentProviderOperation createFromParcel(Parcel source) {
            return new ContentProviderOperation(source);
        }

        @Override
        public ContentProviderOperation[] newArray(int size) {
            return new ContentProviderOperation[size];
        }
    };

    /** {@hide} */
    public static class BackReference implements Parcelable {
        private final int fromIndex;
        private final String fromKey;

        private BackReference(int fromIndex, String fromKey) {
            this.fromIndex = fromIndex;
            this.fromKey = fromKey;
        }

        public BackReference(Parcel src) {
            this.fromIndex = src.readInt();
            if (src.readInt() != 0) {
                this.fromKey = src.readString8();
            } else {
                this.fromKey = null;
            }
        }

        public Object resolve(ContentProviderResult[] backRefs, int numBackRefs) {
            if (fromIndex >= numBackRefs) {
                Log.e(TAG, this.toString());
                throw new ArrayIndexOutOfBoundsException("asked for back ref " + fromIndex
                        + " but there are only " + numBackRefs + " back refs");
            }
            ContentProviderResult backRef = backRefs[fromIndex];
            Object backRefValue;
            if (backRef.extras != null) {
                backRefValue = backRef.extras.get(fromKey);
            } else if (backRef.uri != null) {
                backRefValue = ContentUris.parseId(backRef.uri);
            } else {
                backRefValue = (long) backRef.count;
            }
            return backRefValue;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(fromIndex);
            if (fromKey != null) {
                dest.writeInt(1);
                dest.writeString8(fromKey);
            } else {
                dest.writeInt(0);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Creator<BackReference> CREATOR =
                new Creator<BackReference>() {
            @Override
            public BackReference createFromParcel(Parcel source) {
                return new BackReference(source);
            }

            @Override
            public BackReference[] newArray(int size) {
                return new BackReference[size];
            }
        };
    }

    /**
     * Used to add parameters to a {@link ContentProviderOperation}. The {@link Builder} is
     * first created by calling {@link ContentProviderOperation#newInsert(android.net.Uri)},
     * {@link ContentProviderOperation#newUpdate(android.net.Uri)},
     * {@link ContentProviderOperation#newDelete(android.net.Uri)} or
     * {@link ContentProviderOperation#newAssertQuery(Uri)}. The withXXX methods
     * can then be used to add parameters to the builder. See the specific methods to find for
     * which {@link Builder} type each is allowed. Call {@link #build} to create the
     * {@link ContentProviderOperation} once all the parameters have been supplied.
     */
    public static class Builder {
        private final int mType;
        private final Uri mUri;
        private final String mMethod;
        private final String mArg;
        private ArrayMap<String, Object> mValues;
        private ArrayMap<String, Object> mExtras;
        private String mSelection;
        private SparseArray<Object> mSelectionArgs;
        private Integer mExpectedCount;
        private boolean mYieldAllowed;
        private boolean mExceptionAllowed;

        private Builder(int type, Uri uri) {
            this(type, uri, null, null);
        }

        private Builder(int type, Uri uri, String method, String arg) {
            mType = type;
            mUri = Objects.requireNonNull(uri);
            mMethod = method;
            mArg = arg;
        }

        /** Create a ContentProviderOperation from this {@link Builder}. */
        public @NonNull ContentProviderOperation build() {
            if (mType == TYPE_UPDATE) {
                if ((mValues == null || mValues.isEmpty())) {
                    throw new IllegalArgumentException("Empty values");
                }
            }
            if (mType == TYPE_ASSERT) {
                if ((mValues == null || mValues.isEmpty())
                        && (mExpectedCount == null)) {
                    throw new IllegalArgumentException("Empty values");
                }
            }
            return new ContentProviderOperation(this);
        }

        private void ensureValues() {
            if (mValues == null) {
                mValues = new ArrayMap<>();
            }
        }

        private void ensureExtras() {
            if (mExtras == null) {
                mExtras = new ArrayMap<>();
            }
        }

        private void ensureSelectionArgs() {
            if (mSelectionArgs == null) {
                mSelectionArgs = new SparseArray<>();
            }
        }

        private void setValue(@NonNull String key, @NonNull Object value) {
            ensureValues();
            final boolean oldReference = mValues.get(key) instanceof BackReference;
            final boolean newReference = value instanceof BackReference;
            if (!oldReference || newReference) {
                mValues.put(key, value);
            }
        }

        private void setExtra(@NonNull String key, @NonNull Object value) {
            ensureExtras();
            final boolean oldReference = mExtras.get(key) instanceof BackReference;
            final boolean newReference = value instanceof BackReference;
            if (!oldReference || newReference) {
                mExtras.put(key, value);
            }
        }

        private void setSelectionArg(int index, @NonNull Object value) {
            ensureSelectionArgs();
            final boolean oldReference = mSelectionArgs.get(index) instanceof BackReference;
            final boolean newReference = value instanceof BackReference;
            if (!oldReference || newReference) {
                mSelectionArgs.put(index, value);
            }
        }

        /**
         * Configure the values to use for this operation. This method will
         * replace any previously defined values for the contained keys, but it
         * will not replace any back-reference requests.
         * <p>
         * Any value may be dynamically overwritten using the result of a
         * previous operation by using methods such as
         * {@link #withValueBackReference(String, int)}.
         */
        public @NonNull Builder withValues(@NonNull ContentValues values) {
            assertValuesAllowed();
            ensureValues();
            final ArrayMap<String, Object> rawValues = values.getValues();
            for (int i = 0; i < rawValues.size(); i++) {
                setValue(rawValues.keyAt(i), rawValues.valueAt(i));
            }
            return this;
        }

        /**
         * Configure the given value to use for this operation. This method will
         * replace any previously defined value for this key.
         *
         * @param key the key indicating which value to configure
         */
        public @NonNull Builder withValue(@NonNull String key, @Nullable Object value) {
            assertValuesAllowed();
            if (!ContentValues.isSupportedValue(value)) {
                throw new IllegalArgumentException("bad value type: " + value.getClass().getName());
            }
            setValue(key, value);
            return this;
        }

        /**
         * Configure the given values to be dynamically overwritten using the
         * result of a previous operation. This method will replace any
         * previously defined values for these keys.
         *
         * @param backReferences set of values where the key indicates which
         *            value to configure and the value the index indicating
         *            which historical {@link ContentProviderResult} should
         *            overwrite the value
         */
        public @NonNull Builder withValueBackReferences(@NonNull ContentValues backReferences) {
            assertValuesAllowed();
            final ArrayMap<String, Object> rawValues = backReferences.getValues();
            for (int i = 0; i < rawValues.size(); i++) {
                setValue(rawValues.keyAt(i),
                        new BackReference((int) rawValues.valueAt(i), null));
            }
            return this;
        }

        /**
         * Configure the given value to be dynamically overwritten using the
         * result of a previous operation. This method will replace any
         * previously defined value for this key.
         *
         * @param key the key indicating which value to configure
         * @param fromIndex the index indicating which historical
         *            {@link ContentProviderResult} should overwrite the value
         */
        public @NonNull Builder withValueBackReference(@NonNull String key, int fromIndex) {
            assertValuesAllowed();
            setValue(key, new BackReference(fromIndex, null));
            return this;
        }

        /**
         * Configure the given value to be dynamically overwritten using the
         * result of a previous operation. This method will replace any
         * previously defined value for this key.
         *
         * @param key the key indicating which value to configure
         * @param fromIndex the index indicating which historical
         *            {@link ContentProviderResult} should overwrite the value
         * @param fromKey the key of indicating which
         *            {@link ContentProviderResult#extras} value should
         *            overwrite the value
         */
        public @NonNull Builder withValueBackReference(@NonNull String key, int fromIndex,
                @NonNull String fromKey) {
            assertValuesAllowed();
            setValue(key, new BackReference(fromIndex, fromKey));
            return this;
        }

        /**
         * Configure the extras to use for this operation. This method will
         * replace any previously defined values for the contained keys, but it
         * will not replace any back-reference requests.
         * <p>
         * Any value may be dynamically overwritten using the result of a
         * previous operation by using methods such as
         * {@link #withExtraBackReference(String, int)}.
         */
        public @NonNull Builder withExtras(@NonNull Bundle extras) {
            assertExtrasAllowed();
            ensureExtras();
            for (String key : extras.keySet()) {
                setExtra(key, extras.get(key));
            }
            return this;
        }

        /**
         * Configure the given extra to use for this operation. This method will
         * replace any previously defined extras for this key.
         *
         * @param key the key indicating which extra to configure
         */
        public @NonNull Builder withExtra(@NonNull String key, @Nullable Object value) {
            assertExtrasAllowed();
            setExtra(key, value);
            return this;
        }

        /**
         * Configure the given extra to be dynamically overwritten using the
         * result of a previous operation. This method will replace any
         * previously defined extras for this key.
         *
         * @param key the key indicating which extra to configure
         * @param fromIndex the index indicating which historical
         *            {@link ContentProviderResult} should overwrite the extra
         */
        public @NonNull Builder withExtraBackReference(@NonNull String key, int fromIndex) {
            assertExtrasAllowed();
            setExtra(key, new BackReference(fromIndex, null));
            return this;
        }

        /**
         * Configure the given extra to be dynamically overwritten using the
         * result of a previous operation. This method will replace any
         * previously defined extras for this key.
         *
         * @param key the key indicating which extra to configure
         * @param fromIndex the index indicating which historical
         *            {@link ContentProviderResult} should overwrite the extra
         * @param fromKey the key of indicating which
         *            {@link ContentProviderResult#extras} value should
         *            overwrite the extra
         */
        public @NonNull Builder withExtraBackReference(@NonNull String key, int fromIndex,
                @NonNull String fromKey) {
            assertExtrasAllowed();
            setExtra(key, new BackReference(fromIndex, fromKey));
            return this;
        }

        /**
         * Configure the selection and selection arguments to use for this
         * operation. This method will replace any previously defined selection
         * and selection arguments, but it will not replace any back-reference
         * requests.
         * <p>
         * An occurrence of {@code ?} in the selection will be replaced with the
         * corresponding selection argument when the operation is executed.
         * <p>
         * Any selection argument may be dynamically overwritten using the
         * result of a previous operation by using methods such as
         * {@link #withSelectionBackReference(int, int)}.
         */
        public @NonNull Builder withSelection(@Nullable String selection,
                @Nullable String[] selectionArgs) {
            assertSelectionAllowed();
            mSelection = selection;
            if (selectionArgs != null) {
                ensureSelectionArgs();
                for (int i = 0; i < selectionArgs.length; i++) {
                    setSelectionArg(i, selectionArgs[i]);
                }
            }
            return this;
        }

        /**
         * Configure the given selection argument to be dynamically overwritten
         * using the result of a previous operation. This method will replace
         * any previously defined selection argument at this index.
         *
         * @param index the index indicating which selection argument to
         *            configure
         * @param fromIndex the index indicating which historical
         *            {@link ContentProviderResult} should overwrite the
         *            selection argument
         */
        public @NonNull Builder withSelectionBackReference(int index, int fromIndex) {
            assertSelectionAllowed();
            setSelectionArg(index, new BackReference(fromIndex, null));
            return this;
        }

        /**
         * Configure the given selection argument to be dynamically overwritten
         * using the result of a previous operation. This method will replace
         * any previously defined selection argument at this index.
         *
         * @param index the index indicating which selection argument to
         *            configure
         * @param fromIndex the index indicating which historical
         *            {@link ContentProviderResult} should overwrite the
         *            selection argument
         * @param fromKey the key of indicating which
         *            {@link ContentProviderResult#extras} value should
         *            overwrite the selection argument
         */
        public @NonNull Builder withSelectionBackReference(int index, int fromIndex,
                @NonNull String fromKey) {
            assertSelectionAllowed();
            setSelectionArg(index, new BackReference(fromIndex, fromKey));
            return this;
        }

        /**
         * If set then if the number of rows affected by this operation does not match
         * this count {@link OperationApplicationException} will be throw.
         * This can only be used with builders of type update, delete, or assert.
         * @return this builder, to allow for chaining.
         */
        public @NonNull Builder withExpectedCount(int count) {
            if (mType != TYPE_UPDATE && mType != TYPE_DELETE && mType != TYPE_ASSERT) {
                throw new IllegalArgumentException(
                        "only updates, deletes, and asserts can have expected counts");
            }
            mExpectedCount = count;
            return this;
        }

        /**
         * If set to true then the operation allows yielding the database to other transactions
         * if the database is contended.
         * @return this builder, to allow for chaining.
         * @see android.database.sqlite.SQLiteDatabase#yieldIfContendedSafely()
         */
        public @NonNull Builder withYieldAllowed(boolean yieldAllowed) {
            mYieldAllowed = yieldAllowed;
            return this;
        }

        /**
         * If set to true, this operation allows subsequent operations to
         * continue even if this operation throws an exception. When true, any
         * encountered exception is returned via
         * {@link ContentProviderResult#exception}.
         */
        public @NonNull Builder withExceptionAllowed(boolean exceptionAllowed) {
            mExceptionAllowed = exceptionAllowed;
            return this;
        }

        /** {@hide} */
        public @NonNull Builder withFailureAllowed(boolean failureAllowed) {
            return withExceptionAllowed(failureAllowed);
        }

        private void assertValuesAllowed() {
            switch (mType) {
                case TYPE_INSERT:
                case TYPE_UPDATE:
                case TYPE_ASSERT:
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Values not supported for " + typeToString(mType));
            }
        }

        private void assertSelectionAllowed() {
            switch (mType) {
                case TYPE_UPDATE:
                case TYPE_DELETE:
                case TYPE_ASSERT:
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Selection not supported for " + typeToString(mType));
            }
        }

        private void assertExtrasAllowed() {
            switch (mType) {
                case TYPE_INSERT:
                case TYPE_UPDATE:
                case TYPE_DELETE:
                case TYPE_ASSERT:
                case TYPE_CALL:
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Extras not supported for " + typeToString(mType));
            }
        }
    }
}
