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

package com.android.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.gadget.GadgetManager;
import android.gadget.GadgetInfo;

import com.android.internal.gadget.IGadgetService;

class GadgetService extends IGadgetService.Stub
{
    private static final String TAG = "GadgetService";

    Context mContext;

    GadgetService(Context context) {
        mContext = context;
    }

    public int allocateGadgetId(String hostPackage) {
        return 42;
    }

    public void deleteGadgetId(int gadgetId) {
    }

    public void bindGadgetId(int gadgetId, ComponentName provider) {
        sendEnabled(provider);
    }

    void sendEnabled(ComponentName provider) {
        Intent intent = new Intent(GadgetManager.GADGET_ENABLE_ACTION);
        intent.setComponent(provider);
        mContext.sendBroadcast(intent);
    }

    public GadgetInfo getGadgetInfo(int gadgetId) {
        GadgetInfo info = new GadgetInfo();
        info.provider = new ComponentName("com.android.gadgethost",
                "com.android.gadgethost.TestGadgetProvider");
        info.minWidth = 0;
        info.minHeight = 0;
        info.updatePeriodMillis = 60 * 1000; // 60s
        return info;
    }
}

