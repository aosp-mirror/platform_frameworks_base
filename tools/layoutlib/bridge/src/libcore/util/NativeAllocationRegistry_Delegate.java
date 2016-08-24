/*
 * Copyright (C) 2016 The Android Open Source Project
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

package libcore.util;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

/**
 * Delegate implementing the native methods of {@link NativeAllocationRegistry}
 *
 * Through the layoutlib_create tool, the original native methods of NativeAllocationRegistry have
 * been replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original NativeAllocationRegistry class.
 *
 * @see DelegateManager
 */
public class NativeAllocationRegistry_Delegate {

    // ---- delegate manager ----
    private static final DelegateManager<NativeAllocationRegistry_Delegate> sManager =
            new DelegateManager<>(NativeAllocationRegistry_Delegate.class);

    private final FreeFunction mFinalizer;

    private NativeAllocationRegistry_Delegate(FreeFunction finalizer) {
        mFinalizer = finalizer;
    }

    /**
     * The result of this method should be cached by the class and reused.
     */
    public static long createFinalizer(FreeFunction finalizer) {
        return sManager.addNewDelegate(new NativeAllocationRegistry_Delegate(finalizer));
    }

    @LayoutlibDelegate
    /*package*/ static void applyFreeFunction(long freeFunction, long nativePtr) {
        NativeAllocationRegistry_Delegate delegate = sManager.getDelegate(freeFunction);
        if (delegate != null) {
            delegate.mFinalizer.free(nativePtr);
        }
    }

    public interface FreeFunction {
        void free(long nativePtr);
    }
}
