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

    AndroidFuture setDynamicShortcuts(String packageName,
            in ParceledListSlice shortcutInfoList, int userId);

    AndroidFuture addDynamicShortcuts(String packageName,
            in ParceledListSlice shortcutInfoList, int userId);

    AndroidFuture removeDynamicShortcuts(String packageName, in List shortcutIds, int userId);

    AndroidFuture removeAllDynamicShortcuts(String packageName, int userId);

    AndroidFuture updateShortcuts(String packageName, in ParceledListSlice shortcuts,
            int userId);

    AndroidFuture requestPinShortcut(String packageName, in ShortcutInfo shortcut,
            in IntentSender resultIntent, int userId);

    AndroidFuture<Intent> createShortcutResultIntent(String packageName, in ShortcutInfo shortcut,
            int userId);

    AndroidFuture disableShortcuts(String packageName, in List shortcutIds,
            CharSequence disabledMessage, int disabledMessageResId, int userId);

    AndroidFuture enableShortcuts(String packageName, in List shortcutIds, int userId);

    int getMaxShortcutCountPerActivity(String packageName, int userId);

    int getRemainingCallCount(String packageName, int userId);

    long getRateLimitResetTime(String packageName, int userId);

    int getIconMaxDimensions(String packageName, int userId);

    AndroidFuture reportShortcutUsed(String packageName, String shortcutId, int userId);

    void resetThrottling(); // system only API for developer opsions

    AndroidFuture onApplicationActive(String packageName, int userId); // system only API for sysUI

    byte[] getBackupPayload(int user);

    AndroidFuture applyRestore(in byte[] payload, int user);

    boolean isRequestPinItemSupported(int user, int requestType);

    // System API used by framework's ShareSheet (ChooserActivity)
    AndroidFuture<ParceledListSlice> getShareTargets(String packageName, in IntentFilter filter,
            int userId);

    boolean hasShareTargets(String packageName, String packageToCheck, int userId);

    AndroidFuture removeLongLivedShortcuts(String packageName, in List shortcutIds, int userId);

    AndroidFuture<ParceledListSlice> getShortcuts(String packageName, int matchFlags, int userId);

    AndroidFuture pushDynamicShortcut(String packageName, in ShortcutInfo shortcut, int userId);
}
