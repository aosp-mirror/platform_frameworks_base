/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.musicrecognition;

import static android.Manifest.permission.MANAGE_MUSIC_RECOGNITION;
import static android.content.PermissionChecker.PERMISSION_GRANTED;
import static android.media.musicrecognition.MusicRecognitionManager.RECOGNITION_FAILED_SERVICE_UNAVAILABLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.musicrecognition.IMusicRecognitionManager;
import android.media.musicrecognition.IMusicRecognitionManagerCallback;
import android.media.musicrecognition.RecognitionRequest;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;

import java.io.FileDescriptor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service which allows audio to be securely streamed to a designated {@link
 * MusicRecognitionService}.
 */
public class MusicRecognitionManagerService extends
        AbstractMasterSystemService<MusicRecognitionManagerService,
                MusicRecognitionManagerPerUserService> {

    private static final String TAG = MusicRecognitionManagerService.class.getSimpleName();
    private static final int MAX_TEMP_SERVICE_SUBSTITUTION_DURATION_MS = 60_000;

    private MusicRecognitionManagerStub mMusicRecognitionManagerStub;
    final ExecutorService mExecutorService = Executors.newCachedThreadPool();

    /**
     * Initializes the system service.
     *
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     *
     * @param context The system server context.
     */
    public MusicRecognitionManagerService(@NonNull Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context,
                        com.android.internal.R.string.config_defaultMusicRecognitionService),
                /** disallowProperty */null);
    }

    @Nullable
    @Override
    protected MusicRecognitionManagerPerUserService newServiceLocked(int resolvedUserId,
            boolean disabled) {
        return new MusicRecognitionManagerPerUserService(this, mLock, resolvedUserId);
    }

    @Override
    public void onStart() {
        mMusicRecognitionManagerStub = new MusicRecognitionManagerStub();
        publishBinderService(Context.MUSIC_RECOGNITION_SERVICE, mMusicRecognitionManagerStub);
    }

    private void enforceCaller(String func) {
        Context ctx = getContext();
        if (ctx.checkCallingPermission(android.Manifest.permission.MANAGE_MUSIC_RECOGNITION)
                == PERMISSION_GRANTED) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid()
                + " doesn't hold " + android.Manifest.permission.MANAGE_MUSIC_RECOGNITION;
        throw new SecurityException(msg);
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_MUSIC_RECOGNITION, TAG);
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_SUBSTITUTION_DURATION_MS;
    }

    final class MusicRecognitionManagerStub extends IMusicRecognitionManager.Stub {
        @Override
        public void beginRecognition(
                @NonNull RecognitionRequest recognitionRequest,
                @NonNull IBinder callback) {
            enforceCaller("beginRecognition");

            synchronized (mLock) {
                int userId = UserHandle.getCallingUserId();
                final MusicRecognitionManagerPerUserService service = getServiceForUserLocked(
                        userId);
                if (service != null && (isDefaultServiceLocked(userId)
                        || isCalledByServiceAppLocked("beginRecognition"))) {
                    service.beginRecognitionLocked(recognitionRequest, callback);
                } else {
                    try {
                        IMusicRecognitionManagerCallback.Stub.asInterface(callback)
                                .onRecognitionFailed(RECOGNITION_FAILED_SERVICE_UNAVAILABLE);
                    } catch (RemoteException e) {
                        // ignored.
                    }
                }
            }
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in,
                @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args,
                @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) throws RemoteException {
            new MusicRecognitionManagerServiceShellCommand(
                    MusicRecognitionManagerService.this).exec(this, in, out, err, args, callback,
                    resultReceiver);
        }

        /** True if the currently set handler service is not overridden by the shell. */
        @GuardedBy("mLock")
        private boolean isDefaultServiceLocked(int userId) {
            final String defaultServiceName = mServiceNameResolver.getDefaultServiceName(userId);
            if (defaultServiceName == null) {
                return false;
            }

            final String currentServiceName = mServiceNameResolver.getServiceName(userId);
            return defaultServiceName.equals(currentServiceName);
        }

        /** True if the caller of the api is the same app which hosts the default service. */
        @GuardedBy("mLock")
        private boolean isCalledByServiceAppLocked(@NonNull String methodName) {
            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            final String serviceName = mServiceNameResolver.getServiceName(userId);
            if (serviceName == null) {
                Slog.e(TAG, methodName + ": called by UID " + callingUid
                        + ", but there's no service set for user " + userId);
                return false;
            }

            final ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
            if (serviceComponent == null) {
                Slog.w(TAG, methodName + ": invalid service name: " + serviceName);
                return false;
            }

            final String servicePackageName = serviceComponent.getPackageName();

            final PackageManager pm = getContext().getPackageManager();
            final int serviceUid;
            try {
                serviceUid = pm.getPackageUidAsUser(servicePackageName,
                        UserHandle.getCallingUserId());
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, methodName + ": could not verify UID for " + serviceName);
                return false;
            }
            if (callingUid != serviceUid) {
                Slog.e(TAG, methodName + ": called by UID " + callingUid + ", but service UID is "
                        + serviceUid);
                return false;
            }

            return true;
        }
    }
}
