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
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.messages.nano.SystemMessageProto;

import java.util.Arrays;

/**
 * Foreground service controller, a/k/a Dianne's Dungeon.
 */
public class ForegroundServiceControllerImpl
        implements ForegroundServiceController {

    // shelf life of foreground services before they go bad
    public static final long FG_SERVICE_GRACE_MILLIS = 5000;

    private static final String TAG = "FgServiceController";
    private static final boolean DBG = false;

    private final Context mContext;
    private final SparseArray<UserServices> mUserServices = new SparseArray<>();
    private final Object mMutex = new Object();

    public ForegroundServiceControllerImpl(Context context) {
        mContext = context;
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
    public boolean isSystemAlertWarningNeeded(int userId, String pkg) {
        synchronized (mMutex) {
            final UserServices services = mUserServices.get(userId);
            if (services == null) return false;
            return services.getStandardLayoutKey(pkg) == null;
        }
    }

    @Override
    public String getStandardLayoutKey(int userId, String pkg) {
        synchronized (mMutex) {
            final UserServices services = mUserServices.get(userId);
            if (services == null) return null;
            return services.getStandardLayoutKey(pkg);
        }
    }

    @Override
    public ArraySet<Integer> getAppOps(int userId, String pkg) {
        synchronized (mMutex) {
            final UserServices services = mUserServices.get(userId);
            if (services == null) {
                return null;
            }
            return services.getFeatures(pkg);
        }
    }

    @Override
    public void onAppOpChanged(int code, int uid, String packageName, boolean active) {
        int userId = UserHandle.getUserId(uid);
        synchronized (mMutex) {
            UserServices userServices = mUserServices.get(userId);
            if (userServices == null) {
                userServices = new UserServices();
                mUserServices.put(userId, userServices);
            }
            if (active) {
                userServices.addOp(packageName, code);
            } else {
                userServices.removeOp(packageName, code);
            }
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
                userServices.setRunningServices(null, 0);
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
                    userServices.setRunningServices(svcs, sbn.getNotification().when);
                }
            } else {
                userServices.removeNotification(sbn.getPackageName(), sbn.getKey());
                if (0 != (sbn.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE)) {
                    if (newImportance > NotificationManager.IMPORTANCE_MIN) {
                        userServices.addImportantNotification(sbn.getPackageName(), sbn.getKey());
                    }
                    final Notification.Builder builder = Notification.Builder.recoverBuilder(
                            mContext, sbn.getNotification());
                    if (builder.usesStandardHeader()) {
                        userServices.addStandardLayoutNotification(
                                sbn.getPackageName(), sbn.getKey());
                    }
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

    @Override
    public boolean isSystemAlertNotification(StatusBarNotification sbn) {
        return sbn.getPackageName().equals("android")
                && sbn.getTag() != null
                && sbn.getTag().contains("AlertWindowNotification");
    }

    /**
     * Struct to track relevant packages and notifications for a userid's foreground services.
     */
    private static class UserServices {
        private String[] mRunning = null;
        private long mServiceStartTime = 0;
        // package -> sufficiently important posted notification keys
        private ArrayMap<String, ArraySet<String>> mImportantNotifications = new ArrayMap<>(1);
        // package -> standard layout posted notification keys
        private ArrayMap<String, ArraySet<String>> mStandardLayoutNotifications = new ArrayMap<>(1);

        // package -> app ops
        private ArrayMap<String, ArraySet<Integer>> mAppOps = new ArrayMap<>(1);

        public void setRunningServices(String[] pkgs, long serviceStartTime) {
            mRunning = pkgs != null ? Arrays.copyOf(pkgs, pkgs.length) : null;
            mServiceStartTime = serviceStartTime;
        }

        public void addOp(String pkg, int op) {
            if (mAppOps.get(pkg) == null) {
                mAppOps.put(pkg, new ArraySet<>(3));
            }
            mAppOps.get(pkg).add(op);
        }

        public boolean removeOp(String pkg, int op) {
            final boolean found;
            final ArraySet<Integer> keys = mAppOps.get(pkg);
            if (keys == null) {
                found = false;
            } else {
                found = keys.remove(op);
                if (keys.size() == 0) {
                    mAppOps.remove(pkg);
                }
            }
            return found;
        }

        public void addImportantNotification(String pkg, String key) {
            addNotification(mImportantNotifications, pkg, key);
        }

        public boolean removeImportantNotification(String pkg, String key) {
            return removeNotification(mImportantNotifications, pkg, key);
        }

        public void addStandardLayoutNotification(String pkg, String key) {
            addNotification(mStandardLayoutNotifications, pkg, key);
        }

        public boolean removeStandardLayoutNotification(String pkg, String key) {
            return removeNotification(mStandardLayoutNotifications, pkg, key);
        }

        public boolean removeNotification(String pkg, String key) {
            boolean removed = false;
            removed |= removeImportantNotification(pkg, key);
            removed |= removeStandardLayoutNotification(pkg, key);
            return removed;
        }

        public void addNotification(ArrayMap<String, ArraySet<String>> map, String pkg,
                String key) {
            if (map.get(pkg) == null) {
                map.put(pkg, new ArraySet<>());
            }
            map.get(pkg).add(key);
        }

        public boolean removeNotification(ArrayMap<String, ArraySet<String>> map,
                String pkg, String key) {
            final boolean found;
            final ArraySet<String> keys = map.get(pkg);
            if (keys == null) {
                found = false;
            } else {
                found = keys.remove(key);
                if (keys.size() == 0) {
                    map.remove(pkg);
                }
            }
            return found;
        }

        public boolean isDungeonNeeded() {
            if (mRunning != null
                && System.currentTimeMillis() - mServiceStartTime >= FG_SERVICE_GRACE_MILLIS) {

                for (String pkg : mRunning) {
                    final ArraySet<String> set = mImportantNotifications.get(pkg);
                    if (set == null || set.size() == 0) {
                        return true;
                    }
                }
            }
            return false;
        }

        public ArraySet<Integer> getFeatures(String pkg) {
            return mAppOps.get(pkg);
        }

        public String getStandardLayoutKey(String pkg) {
            final ArraySet<String> set = mStandardLayoutNotifications.get(pkg);
            if (set == null || set.size() == 0) {
                return null;
            }
            return set.valueAt(0);
        }

        @Override
        public String toString() {
            return "UserServices{" +
                    "mRunning=" + Arrays.toString(mRunning) +
                    ", mServiceStartTime=" + mServiceStartTime +
                    ", mImportantNotifications=" + mImportantNotifications +
                    ", mStandardLayoutNotifications=" + mStandardLayoutNotifications +
                    '}';
        }
    }
}
