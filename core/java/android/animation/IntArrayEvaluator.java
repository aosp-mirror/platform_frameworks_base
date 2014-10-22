/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.animation;

/**
 * This evaluator can be used to perform type interpolation between <code>int[]</code> values.
 * Each index into the array is treated as a separate value to interpolate. For example,
 * evaluating <code>{100, 200}</code> and <code>{300, 400}</code> will interpolate the value at
 * the first index between 100 and 300 and the value at the second index value between 200 and 400.
 */
public class IntArrayEvaluator implements TypeEvaluator<int[]> {

    private int[] mArray;

    /**
     * Create an IntArrayEvaluator that does not reuse the animated value. Care must be taken
     * when using this option because on every evaluation a new <code>int[]</code> will be
     * allocated.
     *
     * @see #IntArrayEvaluator(int[])
     */
    public IntArrayEvaluator() {
    }

    /**
     * Create an IntArrayEvaluator that reuses <code>reuseArray</code> for every evaluate() call.
     * Caution must be taken to ensure that the value returned from
     * {@link android.animation.ValueAnimator#getAnimatedValue()} is not cached, modified, or
     * used across threads. The value will be modified on each <code>evaluate()</code> call.
     *
     * @param reuseArray The array to modify and return from <code>evaluate</code>.
     */
    public IntArrayEvaluator(int[] reuseArray) {
        mArray = reuseArray;
    }

    /**
     * Interpolates the value at each index by the fraction. If {@link #IntArrayEvaluator(int[])}
     * was used to construct this object, <code>reuseArray</code> will be returned, otherwise
     * a new <code>int[]</code> will be returned.
     *
     * @param fraction   The fraction from the starting to the ending values
     * @param startValue The start value.
     * @param endValue   The end value.
     * @return An <code>int[]</code> where each element is an interpolation between
     *         the same index in startValue and endValue.
     */
    @Override
    public int[] evaluate(float fraction, int[] startValue, int[] endValue) {
        int[] array = mArray;
        if (array == null) {
            array = new int[startValue.length];
        }
        for (int i = 0; i < array.length; i++) {
            int start = startValue[i];
            int end = endValue[i];
            array[i] = (int) (start + (fraction * (end - start)));
        }
        return array;
    }
}
