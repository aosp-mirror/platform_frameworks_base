/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.InputChannel;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.view.IInputMethodSession;

/**
 * A utility class to take care of boilerplate code around IPCs.
 *
 * <p>Note: All public methods are designed to be thread-safe.</p>
 */
public class MultiClientInputMethodPrivilegedOperations {
    private static final String TAG = "MultiClientInputMethodPrivilegedOperations";

    private static final class OpsHolder {
        @Nullable
        @GuardedBy("this")
        private IMultiClientInputMethodPrivilegedOperations mPrivOps;

        /**
         * Sets {@link IMultiClientInputMethodPrivilegedOperations}.
         *
         * <p>This method can be called only once.</p>
         *
         * @param privOps Binder interface to be set.
         */
        @AnyThread
        public synchronized void set(IMultiClientInputMethodPrivilegedOperations privOps) {
            if (mPrivOps != null) {
                throw new IllegalStateException(
                        "IMultiClientInputMethodPrivilegedOperations must be set at most once."
                                + " privOps=" + privOps);
            }
            mPrivOps = privOps;
        }

        /**
         * A simplified version of {@link android.os.Debug#getCaller()}.
         *
         * @return method name of the caller.
         */
        @AnyThread
        private static String getCallerMethodName() {
            final StackTraceElement[] callStack = Thread.currentThread().getStackTrace();
            if (callStack.length <= 4) {
                return "<bottom of call stack>";
            }
            return callStack[4].getMethodName();
        }

        @AnyThread
        public synchronized void dispose() {
            mPrivOps = null;
        }

        @AnyThread
        @Nullable
        public synchronized IMultiClientInputMethodPrivilegedOperations getAndWarnIfNull() {
            if (mPrivOps == null) {
                Log.e(TAG, getCallerMethodName() + " is ignored."
                        + " Call it within attachToken() and InputMethodService.onDestroy()");
            }
            return mPrivOps;
        }
    }
    private final OpsHolder mOps = new OpsHolder();

    /**
     * Sets {@link IMultiClientInputMethodPrivilegedOperations}.
     *
     * <p>This method can be called only once.</p>
     *
     * @param privOps Binder interface to be set.
     */
    @AnyThread
    public void set(IMultiClientInputMethodPrivilegedOperations privOps) {
        mOps.set(privOps);
    }

    /**
     * Disposes internal Binder proxy so that the real Binder object can be garbage collected.
     */
    @AnyThread
    public void dispose() {
        mOps.dispose();
    }

    /**

     * Calls {@link IMultiClientInputMethodPrivilegedOperations#createInputMethodWindowToken(int)}.
     *
     * @param displayId display ID on which the IME window will be shown.
     * @return Window token to be specified to the IME window.
     */
    @AnyThread
    public IBinder createInputMethodWindowToken(int displayId) {
        IMultiClientInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return null;
        }
        try {
            return ops.createInputMethodWindowToken(displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link
     * IMultiClientInputMethodPrivilegedOperations#deleteInputMethodWindowToken(IBinder)}.
     *
     * @param token Window token that is to be deleted.
     */
    @AnyThread
    public void deleteInputMethodWindowToken(IBinder token) {
        IMultiClientInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.deleteInputMethodWindowToken(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IMultiClientInputMethodPrivilegedOperations#acceptClient(int,
     * IInputMethodSession, IMultiClientInputMethodSession, InputChannel)}.
     *
     * @param clientId client ID to be accepted.
     * @param session {@link IInputMethodSession} that is also used for traditional IME protocol.
     * @param multiClientSession {@link IMultiClientInputMethodSession} that defines new callbacks
     *                           for multi-client scenarios.
     * @param writeChannel {@link InputChannel} that is also used for traditional IME protocol.
     */
    @AnyThread
    public void acceptClient(int clientId, IInputMethodSession session,
            IMultiClientInputMethodSession multiClientSession, InputChannel writeChannel) {
        final IMultiClientInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.acceptClient(clientId, session, multiClientSession, writeChannel);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IMultiClientInputMethodPrivilegedOperations#reportImeWindowTarget(int, int,
     * IBinder)}.
     *
     * @param clientId client ID about which new IME target window is reported.
     * @param targetWindowHandle integer handle of the target window.
     * @param imeWindowToken {@link IBinder} window token of the IME window.
     */
    @AnyThread
    public void reportImeWindowTarget(int clientId, int targetWindowHandle,
            IBinder imeWindowToken) {
        final IMultiClientInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.reportImeWindowTarget(clientId, targetWindowHandle, imeWindowToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IMultiClientInputMethodPrivilegedOperations#isUidAllowedOnDisplay(int, int)}.
     *
     * @param displayId display ID to be verified.
     * @param uid UID to be verified.
     * @return {@code true} when {@code uid} is allowed to access to {@code displayId}.
     */
    @AnyThread
    public boolean isUidAllowedOnDisplay(int displayId, int uid) {
        final IMultiClientInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return false;
        }
        try {
            return ops.isUidAllowedOnDisplay(displayId, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls {@link IMultiClientInputMethodPrivilegedOperations#setActive(int, boolean)}.
     * @param clientId client ID to be set active/inactive
     * @param active {@code true} set set active.
     */
    @AnyThread
    public void setActive(int clientId, boolean active) {
        final IMultiClientInputMethodPrivilegedOperations ops = mOps.getAndWarnIfNull();
        if (ops == null) {
            return;
        }
        try {
            ops.setActive(clientId, active);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
