/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm.utils;

import android.view.SurfaceControl;

/**
 * JNI wrapper for ASurfaceTransaction_setOnComplete NDK method which is not
 * exposed as a Java API on older Android versions
 */
public class SurfaceControlUtils {

    /**
     * Adds a listener for transaction completion (when transaction is presented on the screen)
     * @param transaction transaction to which add the listener
     * @param runnable callback which will be called when transaction is presented
     */
    public static void addTransactionCompletedListener(SurfaceControl.Transaction transaction,
            Runnable onComplete) {
        nativeAddTransactionCompletedListener(transaction, onComplete);
    }

    private static native void nativeAddTransactionCompletedListener(
        SurfaceControl.Transaction transaction, Runnable callback);
}
