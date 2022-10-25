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

package com.android.internal.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.view.IInputMethodManager;

import java.util.function.Consumer;

/**
 * A global wrapper to directly invoke {@link IInputMethodManager} IPCs.
 *
 * <p>All public static methods are guaranteed to be thread-safe.</p>
 *
 * <p>All public methods are guaranteed to do nothing when {@link IInputMethodManager} is
 * unavailable.</p>
 */
public final class IInputMethodManagerGlobal {
    @Nullable
    private static volatile IInputMethodManager sServiceCache = null;

    /**
     * @return {@code true} if {@link IInputMethodManager} is available.
     */
    @AnyThread
    public static boolean isAvailable() {
        return getService() != null;
    }

    @AnyThread
    @Nullable
    private static IInputMethodManager getService() {
        IInputMethodManager service = sServiceCache;
        if (service == null) {
            service = IInputMethodManager.Stub.asInterface(
                    ServiceManager.getService(Context.INPUT_METHOD_SERVICE));
            if (service == null) {
                return null;
            }
            sServiceCache = service;
        }
        return service;
    }

    @AnyThread
    private static void handleRemoteExceptionOrRethrow(@NonNull RemoteException e,
            @Nullable Consumer<RemoteException> exceptionHandler) {
        if (exceptionHandler != null) {
            exceptionHandler.accept(e);
        } else {
            throw e.rethrowFromSystemServer();
        }
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
    @RequiresNoPermission
    @AnyThread
    public static void startProtoDump(byte[] protoDump, int source, String where,
            @Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.startProtoDump(protoDump, source, where);
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    /**
     * Invokes {@link IInputMethodManager#startImeTrace()}.
     *
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_UI_TRACING)
    @AnyThread
    public static void startImeTrace(@Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.startImeTrace();
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    /**
     * Invokes {@link IInputMethodManager#stopImeTrace()}.
     *
     * @param exceptionHandler an optional {@link RemoteException} handler.
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_UI_TRACING)
    @AnyThread
    public static void stopImeTrace(@Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.stopImeTrace();
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }

    /**
     * Invokes {@link IInputMethodManager#isImeTraceEnabled()}.
     *
     * @return The return value of {@link IInputMethodManager#isImeTraceEnabled()}.
     */
    @RequiresNoPermission
    @AnyThread
    public static boolean isImeTraceEnabled() {
        final IInputMethodManager service = getService();
        if (service == null) {
            return false;
        }
        try {
            return service.isImeTraceEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Invokes {@link IInputMethodManager#removeImeSurface()}
     */
    @RequiresPermission(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    @AnyThread
    public static void removeImeSurface(@Nullable Consumer<RemoteException> exceptionHandler) {
        final IInputMethodManager service = getService();
        if (service == null) {
            return;
        }
        try {
            service.removeImeSurface();
        } catch (RemoteException e) {
            handleRemoteExceptionOrRethrow(e, exceptionHandler);
        }
    }
}
