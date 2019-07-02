/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * An implementation of SparseArray specialized for {@link android.graphics.RectF}.
 * <p>
 * As this is a sparse array, it represents an array of {@link RectF} most of which are null. This
 * class could be in some other packages like android.graphics or android.util but currently
 * belong to android.view.inputmethod because this class is hidden and used only in input method
 * framework.
 * </p>
 * @hide
 */
public final class SparseRectFArray implements Parcelable {
    /**
     * The keys, in ascending order, of those {@link RectF} that are not null. For example,
     * {@code [null, null, null, Rect1, null, Rect2]} would be represented by {@code [3,5]}.
     * @see #mCoordinates
     */
    private final int[] mKeys;

    /**
     * Stores coordinates of the rectangles, in the order of
     * {@code rects[mKeys[0]].left}, {@code rects[mKeys[0]].top},
     * {@code rects[mKeys[0]].right}, {@code rects[mKeys[0]].bottom},
     * {@code rects[mKeys[1]].left}, {@code rects[mKeys[1]].top},
     * {@code rects[mKeys[1]].right}, {@code rects[mKeys[1]].bottom},
     * {@code rects[mKeys[2]].left}, {@code rects[mKeys[2]].top}, ....
     */
    private final float[] mCoordinates;

    /**
     * Stores visibility information.
     */
    private final int[] mFlagsArray;

    public SparseRectFArray(final Parcel source) {
        mKeys = source.createIntArray();
        mCoordinates = source.createFloatArray();
        mFlagsArray = source.createIntArray();
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeIntArray(mKeys);
        dest.writeFloatArray(mCoordinates);
        dest.writeIntArray(mFlagsArray);
    }

    @Override
    public int hashCode() {
        // TODO: Improve the hash function.
        if (mKeys == null || mKeys.length == 0) {
            return 0;
        }
        int hash = mKeys.length;
        // For performance reasons, only the first rectangle is used for the hash code now.
        for (int i = 0; i < 4; i++) {
            hash *= 31;
            hash += mCoordinates[i];
        }
        hash *= 31;
        hash += mFlagsArray[0];
        return hash;
    }

    @Override
    public boolean equals(Object obj){
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SparseRectFArray)) {
            return false;
        }
        final SparseRectFArray that = (SparseRectFArray) obj;

        return Arrays.equals(mKeys, that.mKeys) && Arrays.equals(mCoordinates, that.mCoordinates)
                && Arrays.equals(mFlagsArray, that.mFlagsArray);
    }

    @Override
    public String toString() {
        if (mKeys == null || mCoordinates == null || mFlagsArray == null) {
            return "SparseRectFArray{}";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("SparseRectFArray{");
        for (int i = 0; i < mKeys.length; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            final int baseIndex = i * 4;
            sb.append(mKeys[i]);
            sb.append(":[");
            sb.append(mCoordinates[baseIndex + 0]);
            sb.append(",");
            sb.append(mCoordinates[baseIndex + 1]);
            sb.append("],[");
            sb.append(mCoordinates[baseIndex + 2]);
            sb.append(",");
            sb.append(mCoordinates[baseIndex + 3]);
            sb.append("]:flagsArray=");
            sb.append(mFlagsArray[i]);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Builder for {@link SparseRectFArray}. This class is not designed to be thread-safe.
     * @hide
     */
    public static final class SparseRectFArrayBuilder {
        /**
         * Throws {@link IllegalArgumentException} to make sure that this class is correctly used.
         * @param key key to be checked.
         */
        private void checkIndex(final int key) {
            if (mCount == 0) {
                return;
            }
            if (mKeys[mCount - 1] >= key) {
                throw new IllegalArgumentException("key must be greater than all existing keys.");
            }
        }

        /**
         * Extends the internal array if necessary.
         */
        private void ensureBufferSize() {
            if (mKeys == null) {
                mKeys = new int[INITIAL_SIZE];
            }
            if (mCoordinates == null) {
                mCoordinates = new float[INITIAL_SIZE * 4];
            }
            if (mFlagsArray == null) {
                mFlagsArray = new int[INITIAL_SIZE];
            }
            final int requiredIndexArraySize = mCount + 1;
            if (mKeys.length <= requiredIndexArraySize) {
                final int[] newArray = new int[requiredIndexArraySize * 2];
                System.arraycopy(mKeys, 0, newArray, 0, mCount);
                mKeys = newArray;
            }
            final int requiredCoordinatesArraySize = (mCount + 1) * 4;
            if (mCoordinates.length <= requiredCoordinatesArraySize) {
                final float[] newArray = new float[requiredCoordinatesArraySize * 2];
                System.arraycopy(mCoordinates, 0, newArray, 0, mCount * 4);
                mCoordinates = newArray;
            }
            final int requiredFlagsArraySize = requiredIndexArraySize;
            if (mFlagsArray.length <= requiredFlagsArraySize) {
                final int[] newArray = new int[requiredFlagsArraySize * 2];
                System.arraycopy(mFlagsArray, 0, newArray, 0, mCount);
                mFlagsArray = newArray;
            }
        }

        /**
         * Puts the rectangle with an integer key.
         * @param key the key to be associated with the rectangle. It must be greater than all
         * existing keys that have been previously specified.
         * @param left left of the rectangle.
         * @param top top of the rectangle.
         * @param right right of the rectangle.
         * @param bottom bottom of the rectangle.
         * @param flags an arbitrary integer value to be associated with this rectangle.
         * @return the receiver object itself for chaining method calls.
         * @throws IllegalArgumentException If the index is not greater than all of existing keys.
         */
        public SparseRectFArrayBuilder append(final int key,
                final float left, final float top, final float right, final float bottom,
                final int flags) {
            checkIndex(key);
            ensureBufferSize();
            final int baseCoordinatesIndex = mCount * 4;
            mCoordinates[baseCoordinatesIndex + 0] = left;
            mCoordinates[baseCoordinatesIndex + 1] = top;
            mCoordinates[baseCoordinatesIndex + 2] = right;
            mCoordinates[baseCoordinatesIndex + 3] = bottom;
            final int flagsIndex = mCount;
            mFlagsArray[flagsIndex] = flags;
            mKeys[mCount] = key;
            ++mCount;
            return this;
        }
        private int mCount = 0;
        private int[] mKeys = null;
        private float[] mCoordinates = null;
        private int[] mFlagsArray = null;
        private static int INITIAL_SIZE = 16;

        public boolean isEmpty() {
            return mCount <= 0;
        }

        /**
         * @return {@link SparseRectFArray} using parameters in this {@link SparseRectFArray}.
         */
        public SparseRectFArray build() {
            return new SparseRectFArray(this);
        }

        public void reset() {
            if (mCount == 0) {
                mKeys = null;
                mCoordinates = null;
                mFlagsArray = null;
            }
            mCount = 0;
        }
    }

    private SparseRectFArray(final SparseRectFArrayBuilder builder) {
        if (builder.mCount == 0) {
            mKeys = null;
            mCoordinates = null;
            mFlagsArray = null;
        } else {
            mKeys = new int[builder.mCount];
            mCoordinates = new float[builder.mCount * 4];
            mFlagsArray = new int[builder.mCount];
            System.arraycopy(builder.mKeys, 0, mKeys, 0, builder.mCount);
            System.arraycopy(builder.mCoordinates, 0, mCoordinates, 0, builder.mCount * 4);
            System.arraycopy(builder.mFlagsArray, 0, mFlagsArray, 0, builder.mCount);
        }
    }

    public RectF get(final int index) {
        if (mKeys == null) {
            return null;
        }
        if (index < 0) {
            return null;
        }
        final int arrayIndex = Arrays.binarySearch(mKeys, index);
        if (arrayIndex < 0) {
            return null;
        }
        final int baseCoordIndex = arrayIndex * 4;
        return new RectF(mCoordinates[baseCoordIndex],
                mCoordinates[baseCoordIndex + 1],
                mCoordinates[baseCoordIndex + 2],
                mCoordinates[baseCoordIndex + 3]);
    }

    public int getFlags(final int index, final int valueIfKeyNotFound) {
        if (mKeys == null) {
            return valueIfKeyNotFound;
        }
        if (index < 0) {
            return valueIfKeyNotFound;
        }
        final int arrayIndex = Arrays.binarySearch(mKeys, index);
        if (arrayIndex < 0) {
            return valueIfKeyNotFound;
        }
        return mFlagsArray[arrayIndex];
    }

    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<SparseRectFArray> CREATOR =
            new Parcelable.Creator<SparseRectFArray>() {
                @Override
                public SparseRectFArray createFromParcel(Parcel source) {
                    return new SparseRectFArray(source);
                }
                @Override
                public SparseRectFArray[] newArray(int size) {
                    return new SparseRectFArray[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }
}

