/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package com.android.server;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver
{
    @Override
    public void onReceive(Context context, Intent intent)
    {
        Intent service = new Intent(context, com.android.server.LoadAverageService.class);
        ContentResolver res = context.getContentResolver();
        boolean shown = Settings.System.getInt(
                res, Settings.System.SHOW_PROCESSES, 0) != 0;
        if (shown) {
            context.startService(service);
        }
    }
}

