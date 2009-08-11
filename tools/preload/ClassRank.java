/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.util.Comparator;

/**
 * Ranks classes for preloading based on how long their operations took
 * and how early the operations happened. Higher ranked classes come first.
 */
class ClassRank implements Comparator<Operation> {

    /**
     * Increase this number to add more weight to classes which were loaded
     * earlier.
     */
    static final int SEQUENCE_WEIGHT = 500; // 0.5ms

    static final int BUCKET_SIZE = 5;

    public int compare(Operation a, Operation b) {
        // Higher ranked operations should come first.
        int result = rankOf(b) - rankOf(a);
        if (result != 0) {
            return result;
        }

        // Make sure we don't drop one of two classes w/ the same rank.
        // If a load and an initialization have the same rank, it's OK
        // to treat the operations equally.
        return a.loadedClass.name.compareTo(b.loadedClass.name);
    }

    /** Ranks the given operation. */
    private static int rankOf(Operation o) {
        return o.medianExclusiveTimeMicros()
                + SEQUENCE_WEIGHT / (o.index / BUCKET_SIZE + 1);
    }
}


