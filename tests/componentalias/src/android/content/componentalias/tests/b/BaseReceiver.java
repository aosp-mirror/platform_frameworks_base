/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.content.componentalias.tests.b;

import static android.content.componentalias.tests.ComponentAliasTestCommon.TAG;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.componentalias.tests.ComponentAliasMessage;
import android.util.Log;

import com.android.compatibility.common.util.BroadcastMessenger;

public class BaseReceiver extends BroadcastReceiver {
    private String getMyIdentity(Context context) {
        return (new ComponentName(context.getPackageName(), this.getClass().getCanonicalName()))
                .flattenToShortString();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "onReceive: on " + getMyIdentity(context) + " intent=" + intent);
        ComponentAliasMessage m = new ComponentAliasMessage()
                .setSenderIdentity(getMyIdentity(context))
                .setMethodName("onReceive")
                .setIntent(intent);
        BroadcastMessenger.send(context, TAG, m);
    }
}
