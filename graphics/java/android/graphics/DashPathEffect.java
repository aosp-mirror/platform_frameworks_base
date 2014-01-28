/*
 * Copyright (C) 2006 The Android Open Source Project
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

public class DashPathEffect extends PathEffect {

    /**
     * The intervals array must contain an even number of entries (>=2), with
     * the even indices specifying the "on" intervals, and the odd indices
     * specifying the "off" intervals. phase is an offset into the intervals
     * array (mod the sum of all of the intervals). The intervals array
     * controls the length of the dashes. The paint's strokeWidth controls the
     * thickness of the dashes.
     * Note: this patheffect only affects drawing with the paint's style is set
     * to STROKE or FILL_AND_STROKE. It is ignored if the drawing is done with
     * style == FILL.
     * @param intervals array of ON and OFF distances
     * @param phase offset into the intervals array
     */
    public DashPathEffect(float intervals[], float phase) {
        if (intervals.length < 2) {
            throw new ArrayIndexOutOfBoundsException();
        }
        native_instance = nativeCreate(intervals, phase);
    }
    
    private static native long nativeCreate(float intervals[], float phase);
}

