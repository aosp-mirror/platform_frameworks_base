/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.graphics;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.system.VMRuntime;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.util.ConcurrentModificationException;
import java.util.Iterator;

/**
 * <code>PathIterator</code> can be used to query a given {@link Path} object, to discover its
 * operations and point values.
 */
public class PathIterator implements Iterator<PathIterator.Segment> {

    private final float[] mPointsArray;
    private final long mPointsAddress;
    private int mCachedVerb = -1;
    private boolean mDone = false;
    private final long mNativeIterator;
    private final Path mPath;
    private final int mPathGenerationId;
    private static final int POINT_ARRAY_SIZE = 8;

    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createMalloced(
                    PathIterator.class.getClassLoader(), nGetFinalizer());

    /**
     * The <code>Verb</code> indicates the operation for a given segment of a path. These
     * operations correspond exactly to the primitive operations on {@link Path}, such as
     * {@link Path#moveTo(float, float)} and {@link Path#lineTo(float, float)}, except for
     * {@link #VERB_DONE}, which means that there are no more operations in this path.
     */
    @Retention(SOURCE)
    @IntDef({VERB_MOVE, VERB_LINE, VERB_QUAD, VERB_CONIC, VERB_CUBIC, VERB_CLOSE, VERB_DONE})
    @interface Verb {}
    // these must match the values in SkPath.h
    public static final int VERB_MOVE = 0;
    public static final int VERB_LINE = 1;
    public static final int VERB_QUAD = 2;
    public static final int VERB_CONIC = 3;
    public static final int VERB_CUBIC = 4;
    public static final int VERB_CLOSE = 5;
    public static final int VERB_DONE = 6;

    /**
     * Returns a {@link PathIterator} object for this path, which can be used to query the
     * data (operations and points) in the path. Iterators can only be used on Path objects
     * that have not been modified since the iterator was created. Calling
     * {@link #next(float[], int)}, {@link #next()}, or {@link #hasNext()} on an
     * iterator for a modified path will result in a {@link ConcurrentModificationException}.
     *
     * @param path The {@link Path} for which this iterator can be queried.
     */
    PathIterator(@NonNull Path path) {
        mPath = path;
        mNativeIterator = nCreate(mPath.mNativePath);
        mPathGenerationId = mPath.getGenerationId();
        final VMRuntime runtime = VMRuntime.getRuntime();
        mPointsArray = (float[]) runtime.newNonMovableArray(float.class, POINT_ARRAY_SIZE);
        mPointsAddress = runtime.addressOf(mPointsArray);
        sRegistry.registerNativeAllocation(this, mNativeIterator);
    }

    /**
     * Returns the next verb in this iterator's {@link Path}, and fills entries in the
     * <code>points</code> array with the point data (if any) for that operation.
     * Each two floats represent the data for a single point of that operation.
     * The number of pairs of floats supplied in the resulting array depends on the verb:
     * <ul>
     * <li>{@link #VERB_MOVE}: 1 pair (indices 0 to 1)</li>
     * <li>{@link #VERB_LINE}: 2 pairs (indices 0 to 3)</li>
     * <li>{@link #VERB_QUAD}: 3 pairs (indices 0 to 5)</li>
     * <li>{@link #VERB_CONIC}: 3.5 pairs (indices 0 to 6), the seventh entry has the conic
     * weight</li>
     * <li>{@link #VERB_CUBIC}: 4 pairs (indices 0 to 7)</li>
     * <li>{@link #VERB_CLOSE}: 0 pairs</li>
     * <li>{@link #VERB_DONE}: 0 pairs</li>
     * </ul>
     * @param points The point data for this operation, must have at least
     *               8 items available to hold up to 4 pairs of point values
     * @param offset An offset into the <code>points</code> array where entries should be placed.
     * @return the operation for the next element in the iteration
     * @throws ArrayIndexOutOfBoundsException if the points array is too small
     * @throws ConcurrentModificationException if the underlying path was modified
     * since this iterator was created.
     */
    @NonNull
    public @Verb int next(@NonNull float[] points, int offset) {
        if (points.length < offset + POINT_ARRAY_SIZE) {
            throw new ArrayIndexOutOfBoundsException("points array must be able to "
                    + "hold at least 8 entries");
        }
        @Verb int returnVerb = getReturnVerb(mCachedVerb);
        mCachedVerb = -1;
        System.arraycopy(mPointsArray, 0, points, offset, POINT_ARRAY_SIZE);
        return returnVerb;
    }

    /**
     * Returns true if the there are more elements in this iterator to be returned.
     * A return value of <code>false</code> means there are no more elements, and an
     * ensuing call to {@link #next()} or {@link #next(float[], int)} )} will return
     * {@link #VERB_DONE}.
     *
     * @return true if there are more elements to be iterated through, false otherwise
     * @throws ConcurrentModificationException if the underlying path was modified
     * since this iterator was created.
     */
    @Override
    public boolean hasNext() {
        if (mCachedVerb == -1) {
            mCachedVerb = nextInternal();
        }
        return mCachedVerb != VERB_DONE;
    }

    /**
     * Returns the next verb in the iteration, or {@link #VERB_DONE} if there are no more
     * elements.
     *
     * @return the next verb in the iteration, or {@link #VERB_DONE} if there are no more
     * elements
     * @throws ConcurrentModificationException if the underlying path was modified
     * since this iterator was created.
     */
    @NonNull
    public @Verb int peek() {
        if (mPathGenerationId != mPath.getGenerationId()) {
            throw new ConcurrentModificationException(
                    "Iterator cannot be used on modified Path");
        }
        if (mDone) {
            return VERB_DONE;
        }
        return nPeek(mNativeIterator);
    }

    /**
     * This is where the work is done for {@link #next()}. Using this internal method
     * is helfpul for managing the cached segment used by {@link #hasNext()}.
     *
     * @return the segment to be returned by {@link #next()}
     * @throws ConcurrentModificationException if the underlying path was modified
     * since this iterator was created.
     */
    @NonNull
    private @Verb int nextInternal() {
        if (mDone) {
            return VERB_DONE;
        }
        if (mPathGenerationId != mPath.getGenerationId()) {
            throw new ConcurrentModificationException(
                    "Iterator cannot be used on modified Path");
        }
        @Verb int verb = nNext(mNativeIterator, mPointsAddress);
        if (verb == VERB_DONE) {
            mDone = true;
        }
        return verb;
    }

    /**
     * Returns the next {@link Segment} element in this iterator.
     *
     * There are two versions of <code>next()</code>. This version is slightly more
     * expensive at runtime, since it allocates a new {@link Segment} object with
     * every call. The other version, {@link #next(float[], int)} requires no such allocation, but
     * requires a little more manual effort to use.
     *
     * @return the next segment in this iterator
     * @throws ConcurrentModificationException if the underlying path was modified
     * since this iterator was created.
     */
    @NonNull
    @Override
    public Segment next() {
        @Verb int returnVerb = getReturnVerb(mCachedVerb);
        mCachedVerb = -1;
        float conicWeight = 0f;
        if (returnVerb == VERB_CONIC) {
            conicWeight = mPointsArray[6];
        }
        float[] returnPoints = new float[8];
        System.arraycopy(mPointsArray, 0, returnPoints, 0, POINT_ARRAY_SIZE);
        return new Segment(returnVerb, returnPoints, conicWeight);
    }

    private @Verb int getReturnVerb(int cachedVerb) {
        switch (cachedVerb) {
            case VERB_MOVE: return VERB_MOVE;
            case VERB_LINE: return VERB_LINE;
            case VERB_QUAD: return VERB_QUAD;
            case VERB_CONIC: return VERB_CONIC;
            case VERB_CUBIC: return VERB_CUBIC;
            case VERB_CLOSE: return VERB_CLOSE;
            case VERB_DONE: return VERB_DONE;
        }
        return nextInternal();
    }

    /**
     * This class holds the data for a given segment in a path, as returned by
     * {@link #next()}.
     */
    public static class Segment {
        private final @Verb int mVerb;
        private final float[] mPoints;
        private final float mConicWeight;

        /**
         * The operation for this segment.
         *
         * @return the verb which indicates the operation happening in this segment
         */
        @NonNull
        public @Verb int getVerb() {
            return mVerb;
        }

        /**
         * The point data for this segment.
         *
         * Each two floats represent the data for a single point of that operation.
         * The number of pairs of floats supplied in the resulting array depends on the verb:
         * <ul>
         * <li>{@link #VERB_MOVE}: 1 pair (indices 0 to 1)</li>
         * <li>{@link #VERB_LINE}: 2 pairs (indices 0 to 3)</li>
         * <li>{@link #VERB_QUAD}: 3 pairs (indices 0 to 5)</li>
         * <li>{@link #VERB_CONIC}: 4 pairs (indices 0 to 7), the last pair contains the
         * conic weight twice</li>
         * <li>{@link #VERB_CUBIC}: 4 pairs (indices 0 to 7)</li>
         * <li>{@link #VERB_CLOSE}: 0 pairs</li>
         * <li>{@link #VERB_DONE}: 0 pairs</li>
         * </ul>
         * @return the point data for this segment
         */
        @NonNull
        public float[] getPoints() {
            return mPoints;
        }

        /**
         * The weight for the conic operation in this segment. If the verb in this segment
         * is not equal to {@link #VERB_CONIC}, the weight value is undefined.
         *
         * @see Path#conicTo(float, float, float, float, float)
         * @return the weight for the conic operation in this segment, if any
         */
        public float getConicWeight() {
            return mConicWeight;
        }

        Segment(@NonNull @Verb int verb, @NonNull float[] points, float conicWeight) {
            mVerb = verb;
            mPoints = points;
            mConicWeight = conicWeight;
        }
    }

    // ------------------ Regular JNI ------------------------

    private static native long nCreate(long nativePath);
    private static native long nGetFinalizer();

    // ------------------ Critical JNI ------------------------

    @CriticalNative
    private static native int nNext(long nativeIterator, long pointsAddress);

    @CriticalNative
    private static native int nPeek(long nativeIterator);
}
