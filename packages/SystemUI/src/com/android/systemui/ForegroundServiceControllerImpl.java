/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto;

import java.util.Arrays;

/**
 * Foreground service controller, a/k/a Dianne's Dungeon.
 */
public class ForegroundServiceControllerImpl
        implements ForegroundServiceController {
    private static final String TAG = "FgServiceController";
    private static final boolean DBG = false;

    private final SparseArray<UserServices> mUserServices = new SparseArray<>();
    private final Object mMutex = new Object();

    public ForegroundServiceControllerImpl(Context context) {
    }

    @Override
    public boolean isDungeonNeededForUser(int userId) {
        synchronized (mMutex) {
            final UserServices services = mUserServices.get(userId);
            if (services == null) return false;
            return services.isDungeonNeeded();
        }
    }

    @Override
    public void addNotification(StatusBarNotification sbn, int importance) {
        updateNotification(sbn, importance);
    }

    @Override
    public boolean removeNotification(StatusBarNotification sbn) {
        synchronized (mMutex) {
            final UserServices userServices = mUserServices.get(sbn.getUserId());
            if (userServices == null) {
                if (DBG) {
                    Log.w(TAG, String.format(
                            "user %d with no known notifications got removeNotification for %s",
                            sbn.getUserId(), sbn));
                }
                return false;
            }
            if (isDungeonNotification(sbn)) {
                // if you remove the dungeon entirely, we take that to mean there are
                // no running services
                userServices.setRunningServices(null);
                return true;
            } else {
                // this is safe to call on any notification, not just FLAG_FOREGROUND_SERVICE
                return userServices.removeNotification(sbn.getPackageName(), sbn.getKey());
            }
        }
    }

    @Override
    public void updateNotification(StatusBarNotification sbn, int newImportance) {
        synchronized (mMutex) {
            UserServices userServices = mUserServices.get(sbn.getUserId());
            if (userServices == null) {
                userServices = new UserServices();
                mUserServices.put(sbn.getUserId(), userServices);
            }

            if (isDungeonNotification(sbn)) {
                final Bundle extras = sbn.getNotification().extras;
                if (extras != null) {
                    final String[] svcs = extras.getStringArray(Notification.EXTRA_FOREGROUND_APPS);
                    userServices.setRunningServices(svcs); // null ok
                }
            } else {
                userServices.removeNotification(sbn.getPackageName(), sbn.getKey());
                if (0 != (sbn.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE)
                        && newImportance > NotificationManager.IMPORTANCE_MIN) {
                    userServices.addNotification(sbn.getPackageName(), sbn.getKey());
                }
            }
        }
    }

    @Override
    public boolean isDungeonNotification(StatusBarNotification sbn) {
        return sbn.getId() == SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES
                && sbn.getTag() == null
                && sbn.getPackageName().equals("android");
    }

    /**
     * Struct to track relevant packages and notifications for a userid's foreground services.
     */
    private static class UserServices {
        private String[] mRunning = null;
        private ArrayMap<String, ArraySet<String>> mNotifications = new ArrayMap<>(1);
        public void setRunningServices(String[] pkgs) {
            mRunning = pkgs != null ? Arrays.copyOf(pkgs, pkgs.length) : null;
        }
        public void addNotification(String pkg, String key) {
            if (mNotifications.get(pkg) == null) {
                mNotifications.put(pkg, new ArraySet<String>());
            }
            mNotifications.get(pkg).add(key);
        }
        public boolean removeNotification(String pkg, String key) {
            final boolean found;
            final ArraySet<String> keys = mNotifications.get(pkg);
            if (keys == null) {
                found = false;
            } else {
                found = keys.remove(key);
                if (keys.size() == 0) {
                    mNotifications.remove(pkg);
                }
            }
            return found;
        }
        public boolean isDungeonNeeded() {
            if (mRunning != null) {
                for (String pkg : mRunning) {
                    final ArraySet<String> set = mNotifications.get(pkg);
                    if (set == null || set.size() == 0) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
