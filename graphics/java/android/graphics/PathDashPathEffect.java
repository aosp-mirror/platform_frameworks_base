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

public class PathDashPathEffect extends PathEffect {

    public enum Style {
        TRANSLATE(0),   //!< translate the shape to each position
        ROTATE(1),      //!< rotate the shape about its center
        MORPH(2);       //!< transform each point, and turn lines into curves
        
        Style(int value) {
            native_style = value;
        }
        int native_style;
    }

    /**
     * Dash the drawn path by stamping it with the specified shape. This only
     * applies to drawings when the paint's style is STROKE or STROKE_AND_FILL.
     * If the paint's style is FILL, then this effect is ignored. The paint's
     * strokeWidth does not affect the results.
     * @param shape The path to stamp along
     * @param advance spacing between each stamp of shape
     * @param phase amount to offset before the first shape is stamped
     * @param style how to transform the shape at each position as it is stamped
     */
    public PathDashPathEffect(Path shape, float advance, float phase,
                              Style style) {
        native_instance = nativeCreate(shape.ni(), advance, phase,
                                       style.native_style);
    }
    
    private static native int nativeCreate(int native_path, float advance,
                                           float phase, int native_style);
}

