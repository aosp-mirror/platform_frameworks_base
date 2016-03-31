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

package com.android.internal.util;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.util.LongSparseLongArray;

/**
 * Delegate used to provide new implementation the native methods of {@link VirtualRefBasePtr}
 *
 * Through the layoutlib_create tool, the original native  methods of VirtualRefBasePtr have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 */
@SuppressWarnings("unused")
public class VirtualRefBasePtr_Delegate {
    private static final DelegateManager<Object> sManager = new DelegateManager<>(Object.class);
    private static final LongSparseLongArray sRefCount = new LongSparseLongArray();

    @LayoutlibDelegate
    /*package*/ static synchronized void nIncStrong(long ptr) {
        long counter = sRefCount.get(ptr);
        sRefCount.put(ptr, ++counter);
    }

    @LayoutlibDelegate
    /*package*/ static synchronized void nDecStrong(long ptr) {
        long counter = sRefCount.get(ptr);

        if (counter > 1) {
            sRefCount.put(ptr, --counter);
        } else {
            sRefCount.delete(ptr);
            sManager.removeJavaReferenceFor(ptr);
        }
    }
}
