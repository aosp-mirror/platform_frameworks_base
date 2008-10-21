/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony.gsm.stk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.android.internal.telephony.gsm.stk.Service;
import android.util.Log;

/**
 * This class implements a Broadcast receiver. It waits for an intent sent by 
 * the STK service and install/uninstall the STK application. If no intent is 
 * received when the device finished booting, the application is then unistalled.
 */
public class StkAppStateReceiver extends BroadcastReceiver {

    private static final String TAG = "StkAppStateReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive");

        String action = intent.getAction();
        Service stkService = Service.getInstance();
        if (stkService == null) {
            return;
        }
        if (action.equals(StkAppInstaller.STK_APP_INSTALL_ACTION)) {
            stkService.setAppIndication(Service.APP_INDICATOR_INSTALLED_NORMAL);
            StkAppInstaller.installApp(context);
        } else if (action.equals(StkAppInstaller.STK_APP_UNINSTALL_ACTION)) {
            stkService.setAppIndication(Service.APP_INDICATOR_UNINSTALLED);
            StkAppInstaller.unInstallApp(context);
        }
    }
}
