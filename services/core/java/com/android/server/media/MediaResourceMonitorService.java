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

package com.android.server.media;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.media.IMediaResourceMonitor;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.server.SystemService;

import java.util.List;

/** This class provides a system service that monitors media resource usage. */
public class MediaResourceMonitorService extends SystemService {
    private static final String TAG = "MediaResourceMonitor";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String SERVICE_NAME = "media_resource_monitor";

    private final MediaResourceMonitorImpl mMediaResourceMonitorImpl;

    public MediaResourceMonitorService(Context context) {
        super(context);
        mMediaResourceMonitorImpl = new MediaResourceMonitorImpl();
    }

    @Override
    public void onStart() {
        publishBinderService(SERVICE_NAME, mMediaResourceMonitorImpl);
    }

    class MediaResourceMonitorImpl extends IMediaResourceMonitor.Stub {
        @Override
        public void notifyResourceGranted(int pid, int type)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "notifyResourceGranted(pid=" + pid + ", type=" + type + ")");
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                String pkgNames[] = getPackageNamesFromPid(pid);
                if (pkgNames == null) {
                    return;
                }
                UserManager manager = getContext().createContextAsUser(
                        UserHandle.of(ActivityManager.getCurrentUser()), /*flags=*/0)
                        .getSystemService(UserManager.class);
                List<UserHandle> enabledProfiles = manager.getEnabledProfiles();
                if (enabledProfiles.isEmpty()) {
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_MEDIA_RESOURCE_GRANTED);
                intent.putExtra(Intent.EXTRA_PACKAGES, pkgNames);
                intent.putExtra(Intent.EXTRA_MEDIA_RESOURCE_TYPE, type);
                for (UserHandle userHandle : enabledProfiles) {
                    getContext().sendBroadcastAsUser(intent, userHandle,
                            android.Manifest.permission.RECEIVE_MEDIA_RESOURCE_USAGE);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private String[] getPackageNamesFromPid(int pid) {
            ActivityManager manager = getContext().getSystemService(ActivityManager.class);
            for (ActivityManager.RunningAppProcessInfo proc : manager.getRunningAppProcesses()) {
                if (proc.pid == pid) {
                    return proc.pkgList;
                }
            }
            return null;
        }
    }
}
