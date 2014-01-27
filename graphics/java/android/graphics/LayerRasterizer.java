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

public class LayerRasterizer extends Rasterizer {
    public LayerRasterizer() {
        native_instance = nativeConstructor();
    }
    
	/**	Add a new layer (above any previous layers) to the rasterizer.
		The layer will extract those fields that affect the mask from
		the specified paint, but will not retain a reference to the paint
		object itself, so it may be reused without danger of side-effects.
	*/
    public void addLayer(Paint paint, float dx, float dy) {
        nativeAddLayer(native_instance, paint.mNativePaint, dx, dy);
    }

    public void addLayer(Paint paint) {
        nativeAddLayer(native_instance, paint.mNativePaint, 0, 0);
    }

    private static native int nativeConstructor();
    private static native void nativeAddLayer(int native_layer, int native_paint, float dx, float dy);
}

