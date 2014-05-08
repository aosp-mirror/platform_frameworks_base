/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.os.ParcelableParcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import com.android.internal.app.IUsageStats;

/**
 * Access to usage stats data.
 * @hide
 */
public class UsageStatsManager {
    final Context mContext;
    final IUsageStats mService;

    /** @hide */
    public UsageStatsManager(Context context) {
        mContext = context;
        mService = IUsageStats.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
    }

    public UsageStats getCurrentStats() {
        try {
            ParcelableParcel in = mService.getCurrentStats(mContext.getOpPackageName());
            if (in != null) {
                return new UsageStats(in.getParcel(), false);
            }
        } catch (RemoteException e) {
            // About to die.
        }
        return new UsageStats();
    }
}
