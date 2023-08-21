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

package com.android.server.security.rkp;

import android.content.Context;
import android.os.Binder;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.security.rkp.IGetRegistrationCallback;
import android.security.rkp.IRemoteProvisioning;
import android.security.rkp.service.RegistrationProxy;
import android.util.Log;

import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Implements the remote provisioning system service. This service is backed by a mainline
 * module, allowing the underlying implementation to be updated. The code here is a thin
 * proxy for the code in android.security.rkp.service.
 *
 * @hide
 */
public class RemoteProvisioningService extends SystemService {
    public static final String TAG = "RemoteProvisionSysSvc";
    private static final Duration CREATE_REGISTRATION_TIMEOUT = Duration.ofSeconds(10);
    private final RemoteProvisioningImpl mBinderImpl = new RemoteProvisioningImpl();

    private static class RegistrationReceiver implements
            OutcomeReceiver<RegistrationProxy, Exception> {
        private final Executor mExecutor;
        private final IGetRegistrationCallback mCallback;

        RegistrationReceiver(Executor executor, IGetRegistrationCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onResult(RegistrationProxy registration) {
            try {
                mCallback.onSuccess(new RemoteProvisioningRegistration(registration, mExecutor));
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling success callback " + mCallback.asBinder().hashCode(), e);
            }
        }

        @Override
        public void onError(Exception error) {
            try {
                mCallback.onError(error.toString());
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling error callback " + mCallback.asBinder().hashCode(), e);
            }
        }
    }

    /** @hide */
    public RemoteProvisioningService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.REMOTE_PROVISIONING_SERVICE, mBinderImpl);
    }

    private final class RemoteProvisioningImpl extends IRemoteProvisioning.Stub {
        @Override
        public void getRegistration(String irpcName, IGetRegistrationCallback callback)
                throws RemoteException {
            final int callerUid = Binder.getCallingUidOrThrow();
            final long callingIdentity = Binder.clearCallingIdentity();
            final Executor executor = getContext().getMainExecutor();
            try {
                Log.i(TAG, "getRegistration(" + irpcName + ")");
                RegistrationProxy.createAsync(getContext(), callerUid, irpcName,
                        CREATE_REGISTRATION_TIMEOUT, executor,
                        new RegistrationReceiver(executor, callback));
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;
            final int callerUid = Binder.getCallingUidOrThrow();
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                new RemoteProvisioningShellCommand(getContext(), callerUid).dump(pw);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public int handleShellCommand(ParcelFileDescriptor in, ParcelFileDescriptor out,
                ParcelFileDescriptor err, String[] args) {
            final int callerUid = Binder.getCallingUidOrThrow();
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                return new RemoteProvisioningShellCommand(getContext(), callerUid).exec(this,
                        in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(),
                        args);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }
    }
}
