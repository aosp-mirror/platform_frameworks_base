/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.databinding.writer;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Used for code generation. A BitSet can be converted into a flag set,
 * which is basically a list of longs that can be divided into pieces.
 */
public class FlagSet {
    public static final int sBucketSize = 64;// long
    public final String type;
    public final long[] buckets;
    private String mLocalName;
    private boolean mIsDynamic = false;

    public FlagSet(BitSet bitSet, int bucketCount) {
        buckets = new long[bucketCount];
        Arrays.fill(buckets, 0L);
        for (int i = bitSet.nextSetBit(0);
                i != -1; i = bitSet.nextSetBit(i + 1)) {
            buckets[i / sBucketSize] |= 1 << (i % sBucketSize);
        }
        type = "long";
    }

    public FlagSet(long[] buckets) {
        this.buckets = new long[buckets.length];
        for (int i = 0; i < buckets.length; i ++) {
            this.buckets[i] = buckets[i];
        }
        type = "long";
    }

    public FlagSet(int... bits) {
        int max = 0;
        for (int i = 0 ; i < bits.length; i ++) {
            max = Math.max(i, bits[i]);
        }
        buckets = new long[1 + (max / sBucketSize)];
        for (int x = 0 ; x < bits.length; x ++) {
            final int i = bits[x];
            buckets[i / sBucketSize] |= 1 << (i % sBucketSize);
        }
        type = "long";
    }

    public boolean intersect(FlagSet other, int bucketIndex) {
        return (buckets[bucketIndex] & other.buckets[bucketIndex]) != 0;
    }

    public String getLocalName() {
        return mLocalName;
    }

    public void setLocalName(String localName) {
        mLocalName = localName;
    }

    public boolean hasLocalName() {
        return mLocalName != null;
    }

    public boolean isDynamic() {
        return mIsDynamic;
    }

    public void setDynamic(boolean isDynamic) {
        mIsDynamic = isDynamic;
    }
}
