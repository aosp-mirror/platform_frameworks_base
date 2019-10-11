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

import android.annotation.Nullable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.messages.nano.SystemMessageProto;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Tracks state of foreground services and notifications related to foreground services per user.
 */
@Singleton
public class ForegroundServiceController {

    private final SparseArray<ForegroundServicesUserState> mUserServices = new SparseArray<>();
    private final Object mMutex = new Object();

    @Inject
    public ForegroundServiceController() {
    }

    /**
     * @return true if this user has services missing notifications and therefore needs a
     * disclosure notification.
     */
    public boolean isDisclosureNeededForUser(int userId) {
        synchronized (mMutex) {
            final ForegroundServicesUserState services = mUserServices.get(userId);
            if (services == null) return false;
            return services.isDisclosureNeeded();
        }
    }

    /**
     * @return true if this user/pkg has a missing or custom layout notification and therefore needs
     * a disclosure notification for system alert windows.
     */
    public boolean isSystemAlertWarningNeeded(int userId, String pkg) {
        synchronized (mMutex) {
            final ForegroundServicesUserState services = mUserServices.get(userId);
            if (services == null) return false;
            return services.getStandardLayoutKey(pkg) == null;
        }
    }

    /**
     * Returns the key of the foreground service from this package using the standard template,
     * if one exists.
     */
    @Nullable
    public String getStandardLayoutKey(int userId, String pkg) {
        synchronized (mMutex) {
            final ForegroundServicesUserState services = mUserServices.get(userId);
            if (services == null) return null;
            return services.getStandardLayoutKey(pkg);
        }
    }

    /**
     * Gets active app ops for this user and package
     */
    @Nullable
    public ArraySet<Integer> getAppOps(int userId, String pkg) {
        synchronized (mMutex) {
            final ForegroundServicesUserState services = mUserServices.get(userId);
            if (services == null) {
                return null;
            }
            return services.getFeatures(pkg);
        }
    }

    /**
     * Records active app ops. App Ops are stored in FSC in addition to NotificationData in
     * case they change before we have a notification to tag.
     */
    public void onAppOpChanged(int code, int uid, String packageName, boolean active) {
        int userId = UserHandle.getUserId(uid);
        synchronized (mMutex) {
            ForegroundServicesUserState userServices = mUserServices.get(userId);
            if (userServices == null) {
                userServices = new ForegroundServicesUserState();
                mUserServices.put(userId, userServices);
            }
            if (active) {
                userServices.addOp(packageName, code);
            } else {
                userServices.removeOp(packageName, code);
            }
        }
    }

    /**
     * Looks up the {@link ForegroundServicesUserState} for the given {@code userId}, then performs
     * the given {@link UserStateUpdateCallback} on it.  If no state exists for the user ID, creates
     * a new one if {@code createIfNotFound} is true, then performs the update on the new state.
     * If {@code createIfNotFound} is false, no update is performed.
     *
     * @return false if no user state was found and none was created; true otherwise.
     */
    boolean updateUserState(int userId,
            UserStateUpdateCallback updateCallback,
            boolean createIfNotFound) {
        synchronized (mMutex) {
            ForegroundServicesUserState userState = mUserServices.get(userId);
            if (userState == null) {
                if (createIfNotFound) {
                    userState = new ForegroundServicesUserState();
                    mUserServices.put(userId, userState);
                } else {
                    return false;
                }
            }
            return updateCallback.updateUserState(userState);
        }
    }

    /**
     * @return true if {@code sbn} is the system-provided disclosure notification containing the
     * list of running foreground services.
     */
    public boolean isDisclosureNotification(StatusBarNotification sbn) {
        return sbn.getId() == SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES
                && sbn.getTag() == null
                && sbn.getPackageName().equals("android");
    }

    /**
     * @return true if sbn is one of the window manager "drawing over other apps" notifications
     */
    public boolean isSystemAlertNotification(StatusBarNotification sbn) {
        return sbn.getPackageName().equals("android")
                && sbn.getTag() != null
                && sbn.getTag().contains("AlertWindowNotification");
    }

    /**
     * Callback provided to {@link #updateUserState(int, UserStateUpdateCallback, boolean)}
     * to perform the update.
     */
    interface UserStateUpdateCallback {
        /**
         * Perform update operations on the provided {@code userState}.
         *
         * @return true if the update succeeded.
         */
        boolean updateUserState(ForegroundServicesUserState userState);

        /** Called if the state was not found and was not created. */
        default void userStateNotFound(int userId) {
        }
    }
}
