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

package com.android.server.resources;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.IResourcesManager;
import android.content.res.ResourceTimer;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;

import com.android.server.SystemService;
import com.android.server.am.ActivityManagerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A service for managing information about ResourcesManagers
 */
public class ResourcesManagerService extends SystemService {
    private ActivityManagerService mActivityManagerService;

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public ResourcesManagerService(@NonNull Context context) {
        super(context);
        publishBinderService(Context.RESOURCES_SERVICE, mService);
    }

    @Override
    public void onStart() {
        ResourceTimer.start();
    }

    private final IBinder mService = new IResourcesManager.Stub() {
        @Override
        public boolean dumpResources(String process, ParcelFileDescriptor fd,
                RemoteCallback callback) throws RemoteException {
            final int callingUid = Binder.getCallingUid();
            if (callingUid != Process.ROOT_UID && callingUid != Process.SHELL_UID) {
                callback.sendResult(null);
                throw new SecurityException("dump should only be called by shell");
            }
            return mActivityManagerService.dumpResources(process, fd, callback);
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd,
                @NonNull PrintWriter pw, @Nullable String[] args) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(fd)) {
                mActivityManagerService.dumpAllResources(pfd, pw);
            } catch (Exception e) {
                pw.println("Exception while trying to dump all resources: " + e.getMessage());
                e.printStackTrace(pw);
            }
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out,
                @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return (new ResourcesManagerShellCommand(this)).exec(
                    this,
                    in.getFileDescriptor(),
                    out.getFileDescriptor(),
                    err.getFileDescriptor(),
                    args);
        }
    };

    public void setActivityManagerService(
            ActivityManagerService activityManagerService) {
        mActivityManagerService = activityManagerService;
    }
}
