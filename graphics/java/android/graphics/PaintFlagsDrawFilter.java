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

package android.graphics;

public class PaintFlagsDrawFilter extends DrawFilter {
    /** @hide **/
    public final int clearBits;
    /** @hide **/
    public final int setBits;

    /**
     * Subclass of DrawFilter that affects every paint by first clearing
     * the specified clearBits in the paint's flags, and then setting the
     * specified setBits in the paint's flags.
     *
     * @param clearBits These bits will be cleared in the paint's flags
     * @param setBits These bits will be set in the paint's flags
     */
    public PaintFlagsDrawFilter(int clearBits, int setBits) {
        this.clearBits = clearBits;
        this.setBits = setBits;
        // our native constructor can return 0, if the specified bits
        // are effectively a no-op
        mNativeInt = nativeConstructor(clearBits, setBits);
    }
    
    private static native long nativeConstructor(int clearBits, int setBits);
}

