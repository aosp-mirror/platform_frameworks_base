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
import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.media.IMediaResourceMonitor;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.Slog;
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
                Slog.d(TAG, "notifyResourceGranted(pid=" + pid + ", type=" + type + ")");
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                String pkgNames[] = getPackageNamesFromPid(pid);
                if (pkgNames == null) {
                    return;
                }
                UserManager manager = (UserManager) getContext().getSystemService(
                        Context.USER_SERVICE);
                int[] userIds = manager.getEnabledProfileIds(ActivityManager.getCurrentUser());
                if (userIds == null || userIds.length == 0) {
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_MEDIA_RESOURCE_GRANTED);
                intent.putExtra(Intent.EXTRA_PACKAGES, pkgNames);
                intent.putExtra(Intent.EXTRA_MEDIA_RESOURCE_TYPE, type);
                for (int userId : userIds) {
                    getContext().sendBroadcastAsUser(intent, UserHandle.of(userId),
                            android.Manifest.permission.RECEIVE_MEDIA_RESOURCE_USAGE);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private String[] getPackageNamesFromPid(int pid) {
            try {
                for (ActivityManager.RunningAppProcessInfo proc :
                        ActivityManagerNative.getDefault().getRunningAppProcesses()) {
                    if (proc.pid == pid) {
                        return proc.pkgList;
                    }
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "ActivityManager.getRunningAppProcesses() failed");
            }
            return null;
        }
    }
}
