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
package android.content.pm;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;

import com.android.internal.infra.AndroidFuture;

/** {@hide} */
interface IShortcutService {

    oneway void setDynamicShortcuts(String packageName, in ParceledListSlice shortcutInfoList,
            int userId, in AndroidFuture callback);

    oneway void addDynamicShortcuts(String packageName, in ParceledListSlice shortcutInfoList,
            int userId, in AndroidFuture callback);

    oneway void removeDynamicShortcuts(String packageName, in List shortcutIds, int userId);

    oneway void removeAllDynamicShortcuts(String packageName, int userId);

    oneway void updateShortcuts(String packageName, in ParceledListSlice shortcuts, int userId,
            in AndroidFuture callback);

    oneway void requestPinShortcut(String packageName, in ShortcutInfo shortcut,
            in IntentSender resultIntent, int userId, in AndroidFuture callback);

    oneway void createShortcutResultIntent(String packageName, in ShortcutInfo shortcut,
            int userId, in AndroidFuture callback);

    oneway void disableShortcuts(String packageName, in List shortcutIds,
            CharSequence disabledMessage, int disabledMessageResId, int userId);

    oneway void enableShortcuts(String packageName, in List shortcutIds, int userId);

    int getMaxShortcutCountPerActivity(String packageName, int userId);

    int getRemainingCallCount(String packageName, int userId);

    long getRateLimitResetTime(String packageName, int userId);

    int getIconMaxDimensions(String packageName, int userId);

    oneway void reportShortcutUsed(String packageName, String shortcutId, int userId);

    oneway void resetThrottling(); // system only API for developer opsions

    oneway void onApplicationActive(String packageName, int userId); // system only API for sysUI

    byte[] getBackupPayload(int user);

    oneway void applyRestore(in byte[] payload, int user);

    boolean isRequestPinItemSupported(int user, int requestType);

    // System API used by framework's ShareSheet (ChooserActivity)
    oneway void getShareTargets(String packageName, in IntentFilter filter, int userId,
           in AndroidFuture<ParceledListSlice> callback);

    boolean hasShareTargets(String packageName, String packageToCheck, int userId);

    oneway void removeLongLivedShortcuts(String packageName, in List shortcutIds, int userId);

    oneway void getShortcuts(String packageName, int matchFlags, int userId,
            in AndroidFuture<ParceledListSlice<ShortcutInfo>> callback);

    oneway void pushDynamicShortcut(String packageName, in ShortcutInfo shortcut, int userId);

    oneway void updateShortcutVisibility(String callingPkg, String packageName,
            in byte[] certificate, in boolean visible, int userId);
}
