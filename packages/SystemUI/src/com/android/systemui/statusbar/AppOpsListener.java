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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.app.AppOpsManager;
import android.content.Context;

import com.android.systemui.Dependency;
import com.android.systemui.ForegroundServiceController;

/**
 * This class handles listening to notification updates and passing them along to
 * NotificationPresenter to be displayed to the user.
 */
public class AppOpsListener implements AppOpsManager.OnOpActiveChangedListener {
    private static final String TAG = "NotificationListener";

    // Dependencies:
    private final ForegroundServiceController mFsc =
            Dependency.get(ForegroundServiceController.class);

    private final Context mContext;
    protected NotificationPresenter mPresenter;
    protected NotificationEntryManager mEntryManager;
    protected final AppOpsManager mAppOps;

    protected static final int[] OPS = new int[] {AppOpsManager.OP_CAMERA,
            AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
            AppOpsManager.OP_RECORD_AUDIO};

    public AppOpsListener(Context context) {
        mContext = context;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationEntryManager entryManager) {
        mPresenter = presenter;
        mEntryManager = entryManager;
        mAppOps.startWatchingActive(OPS, this);
    }

    public void destroy() {
        mAppOps.stopWatchingActive(this);
    }

    @Override
    public void onOpActiveChanged(int code, int uid, String packageName, boolean active) {
        mFsc.onAppOpChanged(code, uid, packageName, active);
        mPresenter.getHandler().post(() -> {
          mEntryManager.updateNotificationsForAppOp(code, uid, packageName, active);
        });
    }
}
