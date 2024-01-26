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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteInputConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * Store and manage {@link InputMethodManagerService} clients. This class was designed to be a
 * singleton in {@link InputMethodManagerService} since it stores information about all clients,
 * still the current client will be defined per display.
 *
 * <p>
 * As part of the re-architecture plan (described in go/imms-rearchitecture-plan), the following
 * fields and methods will be moved out from IMMS and placed here:
 * <ul>
 * <li>mClients (ArrayMap of ClientState indexed by IBinder)</li>
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
 * </ul>
 */
// TODO(b/314150112): Update the Javadoc above, by removing the re-architecture steps, once this
//  class is finalized
final class ClientController {

    // TODO(b/314150112): Make this field private when breaking the cycle with IMMS.
    @GuardedBy("ImfLock.class")
    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<>();

    @GuardedBy("ImfLock.class")
    private final List<ClientControllerCallback> mCallbacks = new ArrayList<>();

    private final PackageManagerInternal mPackageManagerInternal;

    interface ClientControllerCallback {

        void onClientRemoved(ClientState client);
    }

    ClientController(PackageManagerInternal packageManagerInternal) {
        mPackageManagerInternal = packageManagerInternal;
    }

    @GuardedBy("ImfLock.class")
    ClientState addClient(IInputMethodClientInvoker clientInvoker,
            IRemoteInputConnection inputConnection, int selfReportedDisplayId, int callerUid,
            int callerPid) {
        final IBinder.DeathRecipient deathRecipient = () -> {
            // Exceptionally holding ImfLock here since this is a internal lambda expression.
            synchronized (ImfLock.class) {
                removeClientAsBinder(clientInvoker.asBinder());
            }
        };

        // TODO(b/319457906): Optimize this linear search.
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
        final ClientState cs = new ClientState(clientInvoker, inputConnection,
                callerUid, callerPid, selfReportedDisplayId, deathRecipient);
        mClients.put(clientInvoker.asBinder(), cs);
        return cs;
    }

    @VisibleForTesting
    @GuardedBy("ImfLock.class")
    boolean removeClient(IInputMethodClient client) {
        return removeClientAsBinder(client.asBinder());
    }

    @GuardedBy("ImfLock.class")
    private boolean removeClientAsBinder(IBinder binder) {
        final ClientState cs = mClients.remove(binder);
        if (cs == null) {
            return false;
        }
        binder.unlinkToDeath(cs.mClientDeathRecipient, 0 /* flags */);
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onClientRemoved(cs);
        }
        return true;
    }

    @GuardedBy("ImfLock.class")
    void addClientControllerCallback(ClientControllerCallback callback) {
        mCallbacks.add(callback);
    }

    @GuardedBy("ImfLock.class")
    boolean verifyClientAndPackageMatch(
            @NonNull IInputMethodClient client, @NonNull String packageName) {
        final ClientState cs = mClients.get(client.asBinder());
        if (cs == null) {
            throw new IllegalArgumentException("unknown client " + client.asBinder());
        }
        return InputMethodUtils.checkIfPackageBelongsToUid(
                mPackageManagerInternal, cs.mUid, packageName);
    }
}
