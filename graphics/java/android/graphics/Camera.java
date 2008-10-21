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


public class Camera {

    public Camera() {
        nativeConstructor();
    }

    public native void save();
    public native void restore();

    public native void translate(float x, float y, float z);
    public native void rotateX(float deg);
    public native void rotateY(float deg);
    public native void rotateZ(float deg);

    public void getMatrix(Matrix matrix) {
        nativeGetMatrix(matrix.native_instance);
    }
    public void applyToCanvas(Canvas canvas) {
        nativeApplyToCanvas(canvas.mNativeCanvas);
    }

    public native float dotWithNormal(float dx, float dy, float dz);
    
    protected void finalize() throws Throwable {
        nativeDestructor();
    }

    private native void nativeConstructor();
    private native void nativeDestructor();
    private native void nativeGetMatrix(int native_matrix);
    private native void nativeApplyToCanvas(int native_canvas);
    
    int native_instance;
}

