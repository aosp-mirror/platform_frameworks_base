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

#ifndef __AACTIVITYMANAGER_H__
#define __AACTIVITYMANAGER_H__

#include <sys/cdefs.h>
#include <sys/types.h>

__BEGIN_DECLS

struct AActivityManager_UidImportanceListener;
typedef struct AActivityManager_UidImportanceListener AActivityManager_UidImportanceListener;

/**
 * Callback interface when Uid Importance has changed for a uid.
 *
 * This callback will be called on an arbitrary thread. Calls to a given listener will be
 * serialized.
 *
 * @param uid the uid for which the importance has changed.
 * @param uidImportance the new uidImportance for the uid.
 * @cookie the same cookie when the UidImportanceListener was added.
 *
 * Introduced in API 31.
 */
typedef void (*AActivityManager_onUidImportance)(uid_t uid, int32_t uidImportance, void* cookie);

/**
 * ActivityManager Uid Importance constants.
 *
 * Introduced in API 31.
 */
enum {
    /**
     * Constant for Uid Importance: This process is running the
     * foreground UI; that is, it is the thing currently at the top of the screen
     * that the user is interacting with.
     */
    AACTIVITYMANAGER_IMPORTANCE_FOREGROUND = 100,

    /**
     * Constant for Uid Importance: This process is running a foreground
     * service, for example to perform music playback even while the user is
     * not immediately in the app.  This generally indicates that the process
     * is doing something the user actively cares about.
     */
    AACTIVITYMANAGER_IMPORTANCE_FOREGROUND_SERVICE = 125,

    /**
     * Constant for Uid Importance: This process is running something
     * that is actively visible to the user, though not in the immediate
     * foreground.  This may be running a window that is behind the current
     * foreground (so paused and with its state saved, not interacting with
     * the user, but visible to them to some degree); it may also be running
     * other services under the system's control that it inconsiders important.
     */
    AACTIVITYMANAGER_IMPORTANCE_VISIBLE = 200,

    /**
     * Constant for Uid Importance: This process is not something the user
     * is directly aware of, but is otherwise perceptible to them to some degree.
     */
    AACTIVITYMANAGER_IMPORTANCE_PERCEPTIBLE = 230,

    /**
     * Constant for Uid Importance: This process contains services
     * that should remain running.  These are background services apps have
     * started, not something the user is aware of, so they may be killed by
     * the system relatively freely (though it is generally desired that they
     * stay running as long as they want to).
     */
    AACTIVITYMANAGER_IMPORTANCE_SERVICE = 300,

    /**
     * Constant for Uid Importance: This process is running the foreground
     * UI, but the device is asleep so it is not visible to the user.  Though the
     * system will try hard to keep its process from being killed, in all other
     * ways we consider it a kind of cached process, with the limitations that go
     * along with that state: network access, running background services, etc.
     */
    AACTIVITYMANAGER_IMPORTANCE_TOP_SLEEPING = 325,

    /**
     * Constant for Uid Importance: This process is running an
     * application that can not save its state, and thus can't be killed
     * while in the background.  This will be used with apps that have
     * {@link android.R.attr#cantSaveState} set on their application tag.
     */
    AACTIVITYMANAGER_IMPORTANCE_CANT_SAVE_STATE = 350,

    /**
     * Constant for Uid Importance: This process process contains
     * cached code that is expendable, not actively running any app components
     * we care about.
     */
    AACTIVITYMANAGER_IMPORTANCE_CACHED = 400,

    /**
     * Constant for Uid Importance: This process does not exist.
     */
    AACTIVITYMANAGER_IMPORTANCE_GONE = 1000,
};

/**
 * Adds a UidImportanceListener to the ActivityManager.
 *
 * This API requires android.Manifest.permission.PACKAGE_USAGE_STATS permission.
 *
 * @param onUidImportance the listener callback that will receive change reports.
 *
 * @param importanceCutpoint the level of importance in which the caller is interested
 * in differences. For example, if AACTIVITYMANAGER_IMPORTANCE_PERCEPTIBLE is used
 * here, you will receive a call each time a uid's importance transitions between being
 * <= AACTIVITYMANAGER_IMPORTANCE_PERCEPTIBLE and > AACTIVITYMANAGER_IMPORTANCE_PERCEPTIBLE.
 *
 * @param cookie a cookie that will be passed back to the listener callback.
 *
 * @return an opaque pointer of AActivityManager_UidImportanceListener, or nullptr
 * upon failure. Upon success, the returned AActivityManager_UidImportanceListener pointer
 * must be removed and released through AActivityManager_removeUidImportanceListener.
 */
AActivityManager_UidImportanceListener* AActivityManager_addUidImportanceListener(
        AActivityManager_onUidImportance onUidImportance,
        int32_t importanceCutpoint,
        void* cookie) __INTRODUCED_IN(31);

/**
 * Removes a UidImportanceListener that was added with AActivityManager_addUidImportanceListener.
 *
 * When this returns, it's guaranteed the listener callback will no longer be invoked.
 *
 * @param listener the UidImportanceListener to be removed.
 */
void AActivityManager_removeUidImportanceListener(
        AActivityManager_UidImportanceListener* listener) __INTRODUCED_IN(31);

/**
 * Queries if a uid is currently active.
 *
 * This API requires android.Manifest.permission.PACKAGE_USAGE_STATS permission.
 *
 * @return true if the uid is active, false otherwise.
 */
bool AActivityManager_isUidActive(uid_t uid) __INTRODUCED_IN(31);

/**
 * Queries the current Uid Importance value of a uid.
 *
 * This API requires android.Manifest.permission.PACKAGE_USAGE_STATS permission.
 *
 * @param uid the uid for which the importance value is queried.
 * @return the current uid importance value for uid.
 */
int32_t AActivityManager_getUidImportance(uid_t uid) __INTRODUCED_IN(31);

__END_DECLS

#endif  // __AACTIVITYMANAGER_H__
