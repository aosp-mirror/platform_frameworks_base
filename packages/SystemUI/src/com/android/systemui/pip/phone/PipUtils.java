/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;

import android.app.ActivityManager.StackInfo;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

public class PipUtils {

    private static final String TAG = "PipUtils";

    /**
     * @return the ComponentName and user id of the top non-SystemUI activity in the pinned stack.
     *         The component name may be null if no such activity exists.
     */
    public static Pair<ComponentName, Integer> getTopPinnedActivity(Context context,
            IActivityManager activityManager) {
        try {
            final String sysUiPackageName = context.getPackageName();
            final StackInfo pinnedStackInfo = activityManager.getStackInfo(PINNED_STACK_ID);
            if (pinnedStackInfo != null && pinnedStackInfo.taskIds != null &&
                    pinnedStackInfo.taskIds.length > 0) {
                for (int i = pinnedStackInfo.taskNames.length - 1; i >= 0; i--) {
                    ComponentName cn = ComponentName.unflattenFromString(
                            pinnedStackInfo.taskNames[i]);
                    if (cn != null && !cn.getPackageName().equals(sysUiPackageName)) {
                        return new Pair<>(cn, pinnedStackInfo.taskUserIds[i]);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to get pinned stack.");
        }
        return new Pair<>(null, 0);
    }
}
