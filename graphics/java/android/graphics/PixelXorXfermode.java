/*
 * Copyright (C) 2007 The Android Open Source Project
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

/**
 * PixelXorXfermode implements a simple pixel xor (op ^ src ^ dst).
 * This transformation does not follow premultiplied conventions, therefore
 * this mode *always* returns an opaque color (alpha == 255). Thus it is
 * not really usefull for operating on blended colors.
 */
@Deprecated
public class PixelXorXfermode extends Xfermode {

    public PixelXorXfermode(int opColor) {
        native_instance = nativeCreate(opColor);
    }

    private static native long nativeCreate(int opColor);
}
