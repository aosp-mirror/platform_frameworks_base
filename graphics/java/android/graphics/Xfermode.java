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

// This file was generated from the C++ include file: SkXfermode.h
// Any changes made to this file will be discarded by the build.
// To change this file, either edit the include, or device/tools/gluemaker/main.cpp, 
// or one of the auxilary file specifications in device/tools/gluemaker.

package android.graphics;

/**
 * Xfermode is the base class for objects that are called to implement custom
 * "transfer-modes" in the drawing pipeline. The static function Create(Modes)
 * can be called to return an instance of any of the predefined subclasses as
 * specified in the Modes enum. When an Xfermode is assigned to an Paint, then
 * objects drawn with that paint have the xfermode applied.
 */
public class Xfermode {

    protected void finalize() throws Throwable {
        try {
            finalizer(native_instance);
        } finally {
            super.finalize();
        }
    }

    private static native void finalizer(long native_instance);

    long native_instance;
}
