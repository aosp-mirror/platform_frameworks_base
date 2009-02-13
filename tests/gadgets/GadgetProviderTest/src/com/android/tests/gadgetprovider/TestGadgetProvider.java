/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.tests.gadgetprovider;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.gadget.GadgetManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

public class TestGadgetProvider extends BroadcastReceiver {

    static final String TAG = "TestGadgetProvider";

    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "intent=" + intent);

        if (GadgetManager.ACTION_GADGET_ENABLED.equals(action)) {
            Log.d(TAG, "ENABLED");
        }
        else if (GadgetManager.ACTION_GADGET_DISABLED.equals(action)) {
            Log.d(TAG, "DISABLED");
        }
        else if (GadgetManager.ACTION_GADGET_UPDATE.equals(action)) {
            Log.d(TAG, "UPDATE");
            Bundle extras = intent.getExtras();
            int[] gadgetIds = extras.getIntArray(GadgetManager.EXTRA_GADGET_IDS);

            GadgetManager gm = GadgetManager.getInstance(context);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.test_gadget);
            views.setTextViewText(R.id.oh_hai_text, "hai: " + SystemClock.elapsedRealtime());
            if (false) {
                gm.updateGadget(gadgetIds, views);
            } else {
                gm.updateGadget(new ComponentName("com.android.tests.gadgetprovider",
                            "com.android.tests.gadgetprovider.TestGadgetProvider"), views);
            }
        }
    }
}

