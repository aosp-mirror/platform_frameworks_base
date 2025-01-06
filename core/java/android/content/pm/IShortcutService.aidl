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

/** {@hide} */
interface IShortcutService {

    boolean setDynamicShortcuts(String packageName, in ParceledListSlice shortcutInfoList,
            int userId);

    boolean addDynamicShortcuts(String packageName, in ParceledListSlice shortcutInfoList,
            int userId);

    void removeDynamicShortcuts(String packageName, in List<String> shortcutIds, int userId);

    void removeAllDynamicShortcuts(String packageName, int userId);

    boolean updateShortcuts(String packageName, in ParceledListSlice shortcuts, int userId);

    boolean requestPinShortcut(String packageName, in ShortcutInfo shortcut,
            in IntentSender resultIntent, int userId);

    Intent createShortcutResultIntent(String packageName, in ShortcutInfo shortcut,
            int userId);

    void disableShortcuts(String packageName, in List<String> shortcutIds,
            CharSequence disabledMessage, int disabledMessageResId, int userId);

    void enableShortcuts(String packageName, in List<String> shortcutIds, int userId);

    int getMaxShortcutCountPerActivity(String packageName, int userId);

    int getRemainingCallCount(String packageName, int userId);

    long getRateLimitResetTime(String packageName, int userId);

    int getIconMaxDimensions(String packageName, int userId);

    void reportShortcutUsed(String packageName, String shortcutId, int userId);

    void resetThrottling(); // system only API for developer opsions

    oneway void onApplicationActive(String packageName, int userId); // system only API for sysUI

    byte[] getBackupPayload(int user);

    void applyRestore(in byte[] payload, int user);

    boolean isRequestPinItemSupported(int user, int requestType);

    // System API used by framework's ShareSheet (ChooserActivity)
    ParceledListSlice getShareTargets(String packageName, in IntentFilter filter, int userId);

    boolean hasShareTargets(String packageName, String packageToCheck, int userId);

    void removeLongLivedShortcuts(String packageName, in List<String> shortcutIds, int userId);

    ParceledListSlice getShortcuts(String packageName, int matchFlags, int userId);

    void pushDynamicShortcut(String packageName, in ShortcutInfo shortcut, int userId);
}
