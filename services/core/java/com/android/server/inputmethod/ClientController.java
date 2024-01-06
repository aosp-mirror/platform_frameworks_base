/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.NonNull;
import android.content.pm.PackageManagerInternal;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.view.inputmethod.InputBinding;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteInputConnection;

/**
 * Store and manage {@link InputMethodManagerService} clients. This class was designed to be a
 * singleton in {@link InputMethodManagerService} since it stores information about all clients,
 * still the current client will be defined per display.
 *
 * <p>
 * As part of the re-architecture plan (described in go/imms-rearchitecture-plan), the following
 * fields and methods will be moved out from IMMS and placed here:
 * <ul>
 * <li>mCurClient (ClientState)</li>
 * <li>mClients (ArrayMap of ClientState indexed by IBinder)</li>
 * <li>mLastSwitchUserId</li>
 * </ul>
 * <p>
 * Nested Classes (to move from IMMS):
 * <ul>
 * <li>ClientDeathRecipient</li>
 * <li>ClientState<</li>
 * </ul>
 * <p>
 * Methods to rewrite and/or extract from IMMS and move here:
 * <ul>
 * <li>addClient</li>
 * <li>removeClient</li>
 * <li>verifyClientAndPackageMatch</li>
 * <li>setImeTraceEnabledForAllClients (make it reactive)</li>
 * <li>unbindCurrentClient</li>
 * </ul>
 */
// TODO(b/314150112): Update the Javadoc above, by removing the re-architecture steps, once this
//  class is finalized
final class ClientController {

    // TODO(b/314150112): Make this field private when breaking the cycle with IMMS.
    @GuardedBy("ImfLock.class")
    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<>();

    private final PackageManagerInternal mPackageManagerInternal;

    ClientController(PackageManagerInternal packageManagerInternal) {
        mPackageManagerInternal = packageManagerInternal;
    }

    @GuardedBy("ImfLock.class")
    void addClient(IInputMethodClientInvoker clientInvoker,
            IRemoteInputConnection inputConnection,
            int selfReportedDisplayId, IBinder.DeathRecipient deathRecipient, int callerUid,
            int callerPid) {
        // TODO: Optimize this linear search.
        final int numClients = mClients.size();
        for (int i = 0; i < numClients; ++i) {
            final ClientState state = mClients.valueAt(i);
            if (state.mUid == callerUid && state.mPid == callerPid
                    && state.mSelfReportedDisplayId == selfReportedDisplayId) {
                throw new SecurityException("uid=" + callerUid + "/pid=" + callerPid
                        + "/displayId=" + selfReportedDisplayId + " is already registered");
            }
        }
        try {
            clientInvoker.asBinder().linkToDeath(deathRecipient, 0 /* flags */);
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
        // We cannot fully avoid race conditions where the client UID already lost the access to
        // the given self-reported display ID, even if the client is not maliciously reporting
        // a fake display ID. Unconditionally returning SecurityException just because the
        // client doesn't pass display ID verification can cause many test failures hence not an
        // option right now.  At the same time
        //    context.getSystemService(InputMethodManager.class)
        // is expected to return a valid non-null instance at any time if we do not choose to
        // have the client crash.  Thus we do not verify the display ID at all here.  Instead we
        // later check the display ID every time the client needs to interact with the specified
        // display.
        mClients.put(clientInvoker.asBinder(), new ClientState(clientInvoker, inputConnection,
                callerUid, callerPid, selfReportedDisplayId, deathRecipient));
    }

    @GuardedBy("ImfLock.class")
    boolean verifyClientAndPackageMatch(
            @NonNull IInputMethodClient client, @NonNull String packageName) {
        ClientState cs = mClients.get(client.asBinder());
        if (cs == null) {
            throw new IllegalArgumentException("unknown client " + client.asBinder());
        }
        return InputMethodUtils.checkIfPackageBelongsToUid(
                mPackageManagerInternal, cs.mUid, packageName);
    }

    static final class ClientState {
        final IInputMethodClientInvoker mClient;
        final IRemoteInputConnection mFallbackInputConnection;
        final int mUid;
        final int mPid;
        final int mSelfReportedDisplayId;
        final InputBinding mBinding;
        final IBinder.DeathRecipient mClientDeathRecipient;

        @GuardedBy("ImfLock.class")
        boolean mSessionRequested;

        @GuardedBy("ImfLock.class")
        boolean mSessionRequestedForAccessibility;

        @GuardedBy("ImfLock.class")
        InputMethodManagerService.SessionState mCurSession;

        @GuardedBy("ImfLock.class")
        SparseArray<InputMethodManagerService.AccessibilitySessionState> mAccessibilitySessions =
                new SparseArray<>();

        @Override
        public String toString() {
            return "ClientState{" + Integer.toHexString(
                    System.identityHashCode(this)) + " mUid=" + mUid
                    + " mPid=" + mPid + " mSelfReportedDisplayId=" + mSelfReportedDisplayId + "}";
        }

        ClientState(IInputMethodClientInvoker client,
                IRemoteInputConnection fallbackInputConnection,
                int uid, int pid, int selfReportedDisplayId,
                IBinder.DeathRecipient clientDeathRecipient) {
            mClient = client;
            mFallbackInputConnection = fallbackInputConnection;
            mUid = uid;
            mPid = pid;
            mSelfReportedDisplayId = selfReportedDisplayId;
            mBinding = new InputBinding(null /*conn*/, mFallbackInputConnection.asBinder(), mUid,
                    mPid);
            mClientDeathRecipient = clientDeathRecipient;
        }
    }
}
