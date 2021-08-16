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
package android.content.componentalias.tests.app.s;

import static android.content.componentalias.tests.common.ComponentAliasTestCommon.TAG;
import static android.content.componentalias.tests.common.ComponentAliasTestCommon.TEST_PACKAGE;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.componentalias.tests.common.ComponentAliasMessage;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.android.compatibility.common.util.BroadcastMessenger;

public class BaseService extends Service {
    private String getMyIdentity() {
        return (new ComponentName(this.getPackageName(), this.getClass().getCanonicalName()))
                .flattenToShortString();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: on " + getMyIdentity() + " intent=" + intent);
        ComponentAliasMessage m = new ComponentAliasMessage()
                .setSenderIdentity(getMyIdentity())
                .setMethodName("onStartCommand")
                .setIntent(intent);
        BroadcastMessenger.send(this, TEST_PACKAGE, m);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: on " + getMyIdentity());

        ComponentAliasMessage m = new ComponentAliasMessage()
                .setSenderIdentity(getMyIdentity())
                .setMethodName("onDestroy");
        BroadcastMessenger.send(this, TEST_PACKAGE, m);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind: on " + getMyIdentity() + " intent=" + intent);

        ComponentAliasMessage m = new ComponentAliasMessage()
                .setSenderIdentity(getMyIdentity())
                .setMethodName("onBind")
                .setIntent(intent);
        BroadcastMessenger.send(this, TEST_PACKAGE, m);

        return new Binder();
    }
}
