/*
 * Copyright (C) 2022 The Android Open Source Project
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
package randomparcel;
import android.os.IBinder;
import android.os.Parcel;

public class FuzzBinder {
    static {
        System.loadLibrary("random_parcel_jni");
    }

    // DO NOT REUSE: This API should be called from fuzzer to setup JNI dependencies from
    // libandroid_runtime. THIS IS WORKAROUND. Please file a bug if you need to use this.
    public static void init() {
        System.loadLibrary("android_runtime");
        registerNatives();
    }

    // This API automatically fuzzes provided service
    public static void fuzzService(IBinder binder, byte[] data) {
        fuzzServiceInternal(binder, data);
    }

    // This API fills parcel object
    public static void fillRandomParcel(Parcel parcel, byte[] data) {
        fillParcelInternal(parcel, data);
    }

    private static native void fuzzServiceInternal(IBinder binder, byte[] data);
    private static native void fillParcelInternal(Parcel parcel, byte[] data);
    private static native int registerNatives();
}
