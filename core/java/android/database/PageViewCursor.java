/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.database;

import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.os.Bundle;
import android.util.Log;
import android.util.MathUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

/**
 * Cursor wrapper that provides visibility into a subset of a wrapped cursor.
 *
 * The window is specified by offset and limit.
 *
 * @hide
 */
public final class PageViewCursor extends CrossProcessCursorWrapper {

    /**
     * An extra added to results that are auto-paged using the wrapper.
     */
    public static final String EXTRA_AUTO_PAGED = "android.content.extra.AUTO_PAGED";

    private static final String TAG = "PageViewCursor";
    private static final boolean DEBUG = false;
    private static final boolean VERBOSE = false;

    private final int mOffset;  // aka first index
    private final int mCount;
    private final Bundle mExtras;

    private int mPos = -1;

    /**
     * @see PageViewCursor#wrap(Cursor, Bundle)
     */
    @VisibleForTesting
    public PageViewCursor(Cursor cursor, int offset, int limit) {
        super(cursor);

        checkArgument(offset > -1);
        checkArgument(limit > -1);

        mOffset = offset;

        mExtras = new Bundle();
        Bundle extras = cursor.getExtras();
        if (extras != null) {
            mExtras.putAll(extras);
        }
        mExtras.putBoolean(EXTRA_AUTO_PAGED, true);

        // We need a mutable bundle so we can add QUERY_RESULT_SIZE.
        // Direct equality check is correct here. Bundle.EMPTY is a specific instance
        // of Bundle that is immutable by way of implementation.
        // mExtras = (extras == Bundle.EMPTY) ? new Bundle() : extras;

        // When we're wrapping another cursor, it should not already be "paged".
        checkArgument(!mExtras.containsKey(ContentResolver.EXTRA_TOTAL_SIZE));

        int count = mCursor.getCount();
        mExtras.putInt(ContentResolver.EXTRA_TOTAL_SIZE, count);

        mCount = MathUtils.constrain(count - offset, 0, limit);

        if (DEBUG) Log.d(TAG, "Wrapped cursor"
            + " offset: " + mOffset
            + ", limit: " + limit
            + ", delegate_size: " + count
            + ", paged_count: " + mCount);
    }

    @Override
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int getPosition() {
        return mPos;
    }

    @Override
    public boolean isBeforeFirst() {
        if (mCount == 0) {
            return true;
        }
        return mPos == -1;
    }

    @Override
    public boolean isAfterLast() {
        if (mCount == 0) {
            return true;
        }
        return mPos == mCount;
    }

    @Override
    public boolean isFirst() {
        return mPos == 0;
    }

    @Override
    public boolean isLast() {
        return mPos == mCount - 1;
    }

    @Override
    public boolean moveToFirst() {
        return moveToPosition(0);
    }

    @Override
    public boolean moveToLast() {
        return moveToPosition(mCount - 1);
    }

    @Override
    public boolean moveToNext() {
        return move(1);
    }

    @Override
    public boolean moveToPrevious() {
        return move(-1);
    }

    @Override
    public boolean move(int offset) {
        return moveToPosition(mPos + offset);
    }

    @Override
    public boolean moveToPosition(int position) {
        if (position >= mCount) {
            if (VERBOSE) Log.v(TAG, "Invalid Positon: " + position + " >= count: " + mCount
                    + ". Moving to last record.");
            mPos = mCount;
            super.moveToPosition(mOffset + mPos);  // move into "after last" state.
            return false;
        }

        // Make sure position isn't before the beginning of the cursor
        if (position < 0) {
            if (VERBOSE) Log.v(TAG, "Ignoring invalid move to position: " + position);
            mPos = -1;
            super.moveToPosition(mPos);
            return false;
        }

        if (position == mPos) {
            if (VERBOSE) Log.v(TAG, "Ignoring no-op move to position: " + position);
            return true;
        }

        int delegatePosition = position + mOffset;
        if (VERBOSE) Log.v(TAG, "Moving delegate cursor to position: " + delegatePosition);
        if (super.moveToPosition(delegatePosition)) {
            mPos = position;
            return true;
        } else {
            mPos = -1;
            super.moveToPosition(-1);
            return false;
        }
    }

    @Override
    public boolean onMove(int oldPosition, int newPosition) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public int getCount() {
        return mCount;
    }

    /**
     * Wraps the cursor such that it will honor paging args (if present), AND if the cursor
     * does not report paging size.
     *
     * <p>No-op if cursor already contains paging or is less than specified page size.
     */
    public static Cursor wrap(Cursor cursor, @Nullable Bundle queryArgs) {

        boolean hasPagingArgs =
                queryArgs != null
                && (queryArgs.containsKey(ContentResolver.QUERY_ARG_OFFSET)
                || queryArgs.containsKey(ContentResolver.QUERY_ARG_LIMIT));

        if (!hasPagingArgs) {
            if (VERBOSE) Log.d(TAG, "No-wrap: No paging args in request.");
            return cursor;
        }

        if (hasPagedResponseDetails(cursor.getExtras())) {
            if (VERBOSE) Log.d(TAG, "No-wrap. Cursor has paging details.");
            return cursor;
        }

        return new PageViewCursor(
                cursor,
                queryArgs.getInt(ContentResolver.QUERY_ARG_OFFSET, 0),
                queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT, Integer.MAX_VALUE));
    }

    /**
     * @return true if the extras contains information indicating the associated
     * cursor is paged.
     */
    private static boolean hasPagedResponseDetails(@Nullable Bundle extras) {
        if (extras != null && extras.containsKey(ContentResolver.EXTRA_TOTAL_SIZE)) {
            return true;
        }

        String[] honoredArgs = extras.getStringArray(ContentResolver.EXTRA_HONORED_ARGS);
        if (honoredArgs != null && (
                ArrayUtils.contains(honoredArgs, ContentResolver.QUERY_ARG_OFFSET)
                || ArrayUtils.contains(honoredArgs, ContentResolver.QUERY_ARG_LIMIT))) {
            return true;
        }

        return false;
    }
}
