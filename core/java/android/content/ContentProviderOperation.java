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

import android.annotation.UnsupportedAppUsage;
import android.content.ContentProvider;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

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

    @UnsupportedAppUsage
    private final int mType;
    @UnsupportedAppUsage
    private final Uri mUri;
    @UnsupportedAppUsage
    private final String mSelection;
    private final String[] mSelectionArgs;
    private final ContentValues mValues;
    private final Integer mExpectedCount;
    private final ContentValues mValuesBackReferences;
    private final Map<Integer, Integer> mSelectionArgsBackReferences;
    private final boolean mYieldAllowed;

    private final static String TAG = "ContentProviderOperation";

    /**
     * Creates a {@link ContentProviderOperation} by copying the contents of a
     * {@link Builder}.
     */
    private ContentProviderOperation(Builder builder) {
        mType = builder.mType;
        mUri = builder.mUri;
        mValues = builder.mValues;
        mSelection = builder.mSelection;
        mSelectionArgs = builder.mSelectionArgs;
        mExpectedCount = builder.mExpectedCount;
        mSelectionArgsBackReferences = builder.mSelectionArgsBackReferences;
        mValuesBackReferences = builder.mValuesBackReferences;
        mYieldAllowed = builder.mYieldAllowed;
    }

    private ContentProviderOperation(Parcel source) {
        mType = source.readInt();
        mUri = Uri.CREATOR.createFromParcel(source);
        mValues = source.readInt() != 0 ? ContentValues.CREATOR.createFromParcel(source) : null;
        mSelection = source.readInt() != 0 ? source.readString() : null;
        mSelectionArgs = source.readInt() != 0 ? source.readStringArray() : null;
        mExpectedCount = source.readInt() != 0 ? source.readInt() : null;
        mValuesBackReferences = source.readInt() != 0
                ? ContentValues.CREATOR.createFromParcel(source)
                : null;
        mSelectionArgsBackReferences = source.readInt() != 0
                ? new HashMap<Integer, Integer>()
                : null;
        if (mSelectionArgsBackReferences != null) {
            final int count = source.readInt();
            for (int i = 0; i < count; i++) {
                mSelectionArgsBackReferences.put(source.readInt(), source.readInt());
            }
        }
        mYieldAllowed = source.readInt() != 0;
    }

    /** @hide */
    public ContentProviderOperation(ContentProviderOperation cpo, Uri withUri) {
        mType = cpo.mType;
        mUri = withUri;
        mValues = cpo.mValues;
        mSelection = cpo.mSelection;
        mSelectionArgs = cpo.mSelectionArgs;
        mExpectedCount = cpo.mExpectedCount;
        mSelectionArgsBackReferences = cpo.mSelectionArgsBackReferences;
        mValuesBackReferences = cpo.mValuesBackReferences;
        mYieldAllowed = cpo.mYieldAllowed;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        Uri.writeToParcel(dest, mUri);
        if (mValues != null) {
            dest.writeInt(1);
            mValues.writeToParcel(dest, 0);
        } else {
            dest.writeInt(0);
        }
        if (mSelection != null) {
            dest.writeInt(1);
            dest.writeString(mSelection);
        } else {
            dest.writeInt(0);
        }
        if (mSelectionArgs != null) {
            dest.writeInt(1);
            dest.writeStringArray(mSelectionArgs);
        } else {
            dest.writeInt(0);
        }
        if (mExpectedCount != null) {
            dest.writeInt(1);
            dest.writeInt(mExpectedCount);
        } else {
            dest.writeInt(0);
        }
        if (mValuesBackReferences != null) {
            dest.writeInt(1);
            mValuesBackReferences.writeToParcel(dest, 0);
        } else {
            dest.writeInt(0);
        }
        if (mSelectionArgsBackReferences != null) {
            dest.writeInt(1);
            dest.writeInt(mSelectionArgsBackReferences.size());
            for (Map.Entry<Integer, Integer> entry : mSelectionArgsBackReferences.entrySet()) {
                dest.writeInt(entry.getKey());
                dest.writeInt(entry.getValue());
            }
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(mYieldAllowed ? 1 : 0);
    }

    /**
     * Create a {@link Builder} suitable for building an insert {@link ContentProviderOperation}.
     * @param uri The {@link Uri} that is the target of the insert.
     * @return a {@link Builder}
     */
    public static Builder newInsert(Uri uri) {
        return new Builder(TYPE_INSERT, uri);
    }

    /**
     * Create a {@link Builder} suitable for building an update {@link ContentProviderOperation}.
     * @param uri The {@link Uri} that is the target of the update.
     * @return a {@link Builder}
     */
    public static Builder newUpdate(Uri uri) {
        return new Builder(TYPE_UPDATE, uri);
    }

    /**
     * Create a {@link Builder} suitable for building a delete {@link ContentProviderOperation}.
     * @param uri The {@link Uri} that is the target of the delete.
     * @return a {@link Builder}
     */
    public static Builder newDelete(Uri uri) {
        return new Builder(TYPE_DELETE, uri);
    }

    /**
     * Create a {@link Builder} suitable for building a
     * {@link ContentProviderOperation} to assert a set of values as provided
     * through {@link Builder#withValues(ContentValues)}.
     */
    public static Builder newAssertQuery(Uri uri) {
        return new Builder(TYPE_ASSERT, uri);
    }

    /**
     * Gets the Uri for the target of the operation.
     */
    public Uri getUri() {
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

    /** @hide exposed for unit tests */
    @UnsupportedAppUsage
    public int getType() {
        return mType;
    }

    /**
     * Returns true if the operation represents an insertion.
     *
     * @see #newInsert
     */
    public boolean isInsert() {
        return mType == TYPE_INSERT;
    }

    /**
     * Returns true if the operation represents a deletion.
     *
     * @see #newDelete
     */
    public boolean isDelete() {
        return mType == TYPE_DELETE;
    }

    /**
     * Returns true if the operation represents an update.
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
    public ContentProviderResult apply(ContentProvider provider, ContentProviderResult[] backRefs,
            int numBackRefs) throws OperationApplicationException {
        ContentValues values = resolveValueBackReferences(backRefs, numBackRefs);
        String[] selectionArgs =
                resolveSelectionArgsBackReferences(backRefs, numBackRefs);

        if (mType == TYPE_INSERT) {
            Uri newUri = provider.insert(mUri, values);
            if (newUri == null) {
                throw new OperationApplicationException("insert failed");
            }
            return new ContentProviderResult(newUri);
        }

        int numRows;
        if (mType == TYPE_DELETE) {
            numRows = provider.delete(mUri, mSelection, selectionArgs);
        } else if (mType == TYPE_UPDATE) {
            numRows = provider.update(mUri, values, mSelection, selectionArgs);
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
            final Cursor cursor = provider.query(mUri, projection, mSelection, selectionArgs, null);
            try {
                numRows = cursor.getCount();
                if (projection != null) {
                    while (cursor.moveToNext()) {
                        for (int i = 0; i < projection.length; i++) {
                            final String cursorValue = cursor.getString(i);
                            final String expectedValue = values.getAsString(projection[i]);
                            if (!TextUtils.equals(cursorValue, expectedValue)) {
                                // Throw exception when expected values don't match
                                Log.e(TAG, this.toString());
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
            Log.e(TAG, this.toString());
            throw new IllegalStateException("bad type, " + mType);
        }

        if (mExpectedCount != null && mExpectedCount != numRows) {
            Log.e(TAG, this.toString());
            throw new OperationApplicationException("wrong number of rows: " + numRows);
        }

        return new ContentProviderResult(numRows);
    }

    /**
     * The ContentValues back references are represented as a ContentValues object where the
     * key refers to a column and the value is an index of the back reference whose
     * valued should be associated with the column.
     * <p>
     * This is intended to be a private method but it is exposed for
     * unit testing purposes
     * @param backRefs an array of previous results
     * @param numBackRefs the number of valid previous results in backRefs
     * @return the ContentValues that should be used in this operation application after
     * expansion of back references. This can be called if either mValues or mValuesBackReferences
     * is null
     */
    public ContentValues resolveValueBackReferences(
            ContentProviderResult[] backRefs, int numBackRefs) {
        if (mValuesBackReferences == null) {
            return mValues;
        }
        final ContentValues values;
        if (mValues == null) {
            values = new ContentValues();
        } else {
            values = new ContentValues(mValues);
        }
        for (Map.Entry<String, Object> entry : mValuesBackReferences.valueSet()) {
            String key = entry.getKey();
            Integer backRefIndex = mValuesBackReferences.getAsInteger(key);
            if (backRefIndex == null) {
                Log.e(TAG, this.toString());
                throw new IllegalArgumentException("values backref " + key + " is not an integer");
            }
            values.put(key, backRefToValue(backRefs, numBackRefs, backRefIndex));
        }
        return values;
    }

    /**
     * The Selection Arguments back references are represented as a Map of Integer->Integer where
     * the key is an index into the selection argument array (see {@link Builder#withSelection})
     * and the value is the index of the previous result that should be used for that selection
     * argument array slot.
     * <p>
     * This is intended to be a private method but it is exposed for
     * unit testing purposes
     * @param backRefs an array of previous results
     * @param numBackRefs the number of valid previous results in backRefs
     * @return the ContentValues that should be used in this operation application after
     * expansion of back references. This can be called if either mValues or mValuesBackReferences
     * is null
     */
    public String[] resolveSelectionArgsBackReferences(
            ContentProviderResult[] backRefs, int numBackRefs) {
        if (mSelectionArgsBackReferences == null) {
            return mSelectionArgs;
        }
        String[] newArgs = new String[mSelectionArgs.length];
        System.arraycopy(mSelectionArgs, 0, newArgs, 0, mSelectionArgs.length);
        for (Map.Entry<Integer, Integer> selectionArgBackRef
                : mSelectionArgsBackReferences.entrySet()) {
            final Integer selectionArgIndex = selectionArgBackRef.getKey();
            final int backRefIndex = selectionArgBackRef.getValue();
            newArgs[selectionArgIndex] =
                    String.valueOf(backRefToValue(backRefs, numBackRefs, backRefIndex));
        }
        return newArgs;
    }

    @Override
    public String toString() {
        return "mType: " + mType + ", mUri: " + mUri +
                ", mSelection: " + mSelection +
                ", mExpectedCount: " + mExpectedCount +
                ", mYieldAllowed: " + mYieldAllowed +
                ", mValues: " + mValues +
                ", mValuesBackReferences: " + mValuesBackReferences +
                ", mSelectionArgsBackReferences: " + mSelectionArgsBackReferences;
    }

    /**
     * Return the string representation of the requested back reference.
     * @param backRefs an array of results
     * @param numBackRefs the number of items in the backRefs array that are valid
     * @param backRefIndex which backRef to be used
     * @throws ArrayIndexOutOfBoundsException thrown if the backRefIndex is larger than
     * the numBackRefs
     * @return the string representation of the requested back reference.
     */
    private long backRefToValue(ContentProviderResult[] backRefs, int numBackRefs,
            Integer backRefIndex) {
        if (backRefIndex >= numBackRefs) {
            Log.e(TAG, this.toString());
            throw new ArrayIndexOutOfBoundsException("asked for back ref " + backRefIndex
                    + " but there are only " + numBackRefs + " back refs");
        }
        ContentProviderResult backRef = backRefs[backRefIndex];
        long backRefValue;
        if (backRef.uri != null) {
            backRefValue = ContentUris.parseId(backRef.uri);
        } else {
            backRefValue = backRef.count;
        }
        return backRefValue;
    }

    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Creator<ContentProviderOperation> CREATOR =
            new Creator<ContentProviderOperation>() {
        public ContentProviderOperation createFromParcel(Parcel source) {
            return new ContentProviderOperation(source);
        }

        public ContentProviderOperation[] newArray(int size) {
            return new ContentProviderOperation[size];
        }
    };

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
        private String mSelection;
        private String[] mSelectionArgs;
        private ContentValues mValues;
        private Integer mExpectedCount;
        private ContentValues mValuesBackReferences;
        private Map<Integer, Integer> mSelectionArgsBackReferences;
        private boolean mYieldAllowed;

        /** Create a {@link Builder} of a given type. The uri must not be null. */
        private Builder(int type, Uri uri) {
            if (uri == null) {
                throw new IllegalArgumentException("uri must not be null");
            }
            mType = type;
            mUri = uri;
        }

        /** Create a ContentProviderOperation from this {@link Builder}. */
        public ContentProviderOperation build() {
            if (mType == TYPE_UPDATE) {
                if ((mValues == null || mValues.isEmpty())
                      && (mValuesBackReferences == null || mValuesBackReferences.isEmpty())) {
                    throw new IllegalArgumentException("Empty values");
                }
            }
            if (mType == TYPE_ASSERT) {
                if ((mValues == null || mValues.isEmpty())
                      && (mValuesBackReferences == null || mValuesBackReferences.isEmpty())
                        && (mExpectedCount == null)) {
                    throw new IllegalArgumentException("Empty values");
                }
            }
            return new ContentProviderOperation(this);
        }

        /**
         * Add a {@link ContentValues} of back references. The key is the name of the column
         * and the value is an integer that is the index of the previous result whose
         * value should be used for the column. The value is added as a {@link String}.
         * A column value from the back references takes precedence over a value specified in
         * {@link #withValues}.
         * This can only be used with builders of type insert, update, or assert.
         * @return this builder, to allow for chaining.
         */
        public Builder withValueBackReferences(ContentValues backReferences) {
            if (mType != TYPE_INSERT && mType != TYPE_UPDATE && mType != TYPE_ASSERT) {
                throw new IllegalArgumentException(
                        "only inserts, updates, and asserts can have value back-references");
            }
            mValuesBackReferences = backReferences;
            return this;
        }

        /**
         * Add a ContentValues back reference.
         * A column value from the back references takes precedence over a value specified in
         * {@link #withValues}.
         * This can only be used with builders of type insert, update, or assert.
         * @return this builder, to allow for chaining.
         */
        public Builder withValueBackReference(String key, int previousResult) {
            if (mType != TYPE_INSERT && mType != TYPE_UPDATE && mType != TYPE_ASSERT) {
                throw new IllegalArgumentException(
                        "only inserts, updates, and asserts can have value back-references");
            }
            if (mValuesBackReferences == null) {
                mValuesBackReferences = new ContentValues();
            }
            mValuesBackReferences.put(key, previousResult);
            return this;
        }

        /**
         * Add a back references as a selection arg. Any value at that index of the selection arg
         * that was specified by {@link #withSelection} will be overwritten.
         * This can only be used with builders of type update, delete, or assert.
         * @return this builder, to allow for chaining.
         */
        public Builder withSelectionBackReference(int selectionArgIndex, int previousResult) {
            if (mType != TYPE_UPDATE && mType != TYPE_DELETE && mType != TYPE_ASSERT) {
                throw new IllegalArgumentException("only updates, deletes, and asserts "
                        + "can have selection back-references");
            }
            if (mSelectionArgsBackReferences == null) {
                mSelectionArgsBackReferences = new HashMap<Integer, Integer>();
            }
            mSelectionArgsBackReferences.put(selectionArgIndex, previousResult);
            return this;
        }

        /**
         * The ContentValues to use. This may be null. These values may be overwritten by
         * the corresponding value specified by {@link #withValueBackReference} or by
         * future calls to {@link #withValues} or {@link #withValue}.
         * This can only be used with builders of type insert, update, or assert.
         * @return this builder, to allow for chaining.
         */
        public Builder withValues(ContentValues values) {
            if (mType != TYPE_INSERT && mType != TYPE_UPDATE && mType != TYPE_ASSERT) {
                throw new IllegalArgumentException(
                        "only inserts, updates, and asserts can have values");
            }
            if (mValues == null) {
                mValues = new ContentValues();
            }
            mValues.putAll(values);
            return this;
        }

        /**
         * A value to insert or update. This value may be overwritten by
         * the corresponding value specified by {@link #withValueBackReference}.
         * This can only be used with builders of type insert, update, or assert.
         * @param key the name of this value
         * @param value the value itself. the type must be acceptable for insertion by
         * {@link ContentValues#put}
         * @return this builder, to allow for chaining.
         */
        public Builder withValue(String key, Object value) {
            if (mType != TYPE_INSERT && mType != TYPE_UPDATE && mType != TYPE_ASSERT) {
                throw new IllegalArgumentException("only inserts and updates can have values");
            }
            if (mValues == null) {
                mValues = new ContentValues();
            }
            if (value == null) {
                mValues.putNull(key);
            } else if (value instanceof String) {
                mValues.put(key, (String) value);
            } else if (value instanceof Byte) {
                mValues.put(key, (Byte) value);
            } else if (value instanceof Short) {
                mValues.put(key, (Short) value);
            } else if (value instanceof Integer) {
                mValues.put(key, (Integer) value);
            } else if (value instanceof Long) {
                mValues.put(key, (Long) value);
            } else if (value instanceof Float) {
                mValues.put(key, (Float) value);
            } else if (value instanceof Double) {
                mValues.put(key, (Double) value);
            } else if (value instanceof Boolean) {
                mValues.put(key, (Boolean) value);
            } else if (value instanceof byte[]) {
                mValues.put(key, (byte[]) value);
            } else {
                throw new IllegalArgumentException("bad value type: " + value.getClass().getName());
            }
            return this;
        }

        /**
         * The selection and arguments to use. An occurrence of '?' in the selection will be
         * replaced with the corresponding occurrence of the selection argument. Any of the
         * selection arguments may be overwritten by a selection argument back reference as
         * specified by {@link #withSelectionBackReference}.
         * This can only be used with builders of type update, delete, or assert.
         * @return this builder, to allow for chaining.
         */
        public Builder withSelection(String selection, String[] selectionArgs) {
            if (mType != TYPE_UPDATE && mType != TYPE_DELETE && mType != TYPE_ASSERT) {
                throw new IllegalArgumentException(
                        "only updates, deletes, and asserts can have selections");
            }
            mSelection = selection;
            if (selectionArgs == null) {
                mSelectionArgs = null;
            } else {
                mSelectionArgs = new String[selectionArgs.length];
                System.arraycopy(selectionArgs, 0, mSelectionArgs, 0, selectionArgs.length);
            }
            return this;
        }

        /**
         * If set then if the number of rows affected by this operation does not match
         * this count {@link OperationApplicationException} will be throw.
         * This can only be used with builders of type update, delete, or assert.
         * @return this builder, to allow for chaining.
         */
        public Builder withExpectedCount(int count) {
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
        public Builder withYieldAllowed(boolean yieldAllowed) {
            mYieldAllowed = yieldAllowed;
            return this;
        }
    }
}
