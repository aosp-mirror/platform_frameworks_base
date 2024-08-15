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

package android.view.inputmethod;

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.os.RemoteException;

import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.view.IInputMethodManager;

import java.util.function.Consumer;

/**
 * Defines a set of static methods that can be used globally by framework classes.
 *
 * @hide
 */
public class InputMethodManagerGlobal {
    /**
     * @return {@code true} if IME tracing is currently is available.
     */
    @AnyThread
    public static boolean isImeTraceAvailable() {
        return IInputMethodManagerGlobalInvoker.isAvailable();
    }

    /**
     * Invokes {@link IInputMethodManager#startProtoDump(byte[], int, String)}.
     *
     * @param protoDump client or service side information to be stored by the server
     * @param source where the information is coming from, refer to
     *               {@link ImeTracing#IME_TRACING_FROM_CLIENT} and
     *               {@link ImeTracing#IME_TRACING_FROM_IMS}
     * @param where where the information is coming from.
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @AnyThread
    @RequiresNoPermission
    public static void startProtoDump(byte[] protoDump, int source, String where,
            @Nullable Consumer<RemoteException> exceptionHandler) {
        IInputMethodManagerGlobalInvoker.startProtoDump(protoDump, source, where, exceptionHandler);
    }

    /**
     * Invokes {@link IInputMethodManager#startImeTrace()}.
     *
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @AnyThread
    @RequiresPermission(Manifest.permission.CONTROL_UI_TRACING)
    public static void startImeTrace(@Nullable Consumer<RemoteException> exceptionHandler) {
        IInputMethodManagerGlobalInvoker.startImeTrace(exceptionHandler);
    }

    /**
     * Invokes {@link IInputMethodManager#stopImeTrace()}.
     *
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @AnyThread
    @RequiresPermission(Manifest.permission.CONTROL_UI_TRACING)
    public static void stopImeTrace(@Nullable Consumer<RemoteException> exceptionHandler) {
        IInputMethodManagerGlobalInvoker.stopImeTrace(exceptionHandler);
    }

    /**
     * Invokes {@link IInputMethodManager#isImeTraceEnabled()}.
     *
     * @return The return value of {@link IInputMethodManager#isImeTraceEnabled()}.
     */
    @AnyThread
    @RequiresNoPermission
    public static boolean isImeTraceEnabled() {
        return IInputMethodManagerGlobalInvoker.isImeTraceEnabled();
    }

    /**
     * Invokes {@link IInputMethodManager#removeImeSurface()}
     *
     * @param displayId display ID from which this request originates.
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @AnyThread
    @RequiresPermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    public static void removeImeSurface(int displayId,
            @Nullable Consumer<RemoteException> exceptionHandler) {
        IInputMethodManagerGlobalInvoker.removeImeSurface(displayId, exceptionHandler);
    }
}
