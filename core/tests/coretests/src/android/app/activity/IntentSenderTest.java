/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app.activity;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.test.suitebuilder.annotation.Suppress;
import android.os.Bundle;
import android.test.suitebuilder.annotation.Suppress;

public class IntentSenderTest extends BroadcastTest {

    public void testRegisteredReceivePermissionGranted() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_REG});
        registerMyReceiver(new IntentFilter(BROADCAST_REGISTERED), PERMISSION_GRANTED);
        addIntermediate("after-register");
        PendingIntent is = PendingIntent.getBroadcast(getContext(), 0,
                makeBroadcastIntent(BROADCAST_REGISTERED), 0);
        is.send();
        waitForResultOrThrow(BROADCAST_TIMEOUT);
        is.cancel();
    }

    public void testRegisteredReceivePermissionDenied() throws Exception {
        final Intent intent = makeBroadcastIntent(BROADCAST_REGISTERED);

        setExpectedReceivers(new String[]{RECEIVER_RESULTS});
        registerMyReceiver(new IntentFilter(BROADCAST_REGISTERED), PERMISSION_DENIED);
        addIntermediate("after-register");

        PendingIntent.OnFinished finish = new PendingIntent.OnFinished() {
            public void onSendFinished(PendingIntent pi, Intent intent,
                    int resultCode, String resultData, Bundle resultExtras) {
                gotReceive(RECEIVER_RESULTS, intent);
            }
        };

        PendingIntent is = PendingIntent.getBroadcast(getContext(), 0, intent, 0);
        is.send(Activity.RESULT_CANCELED, finish, null);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
        is.cancel();
    }

    public void testLocalReceivePermissionGranted() throws Exception {
        setExpectedReceivers(new String[]{RECEIVER_LOCAL});
        PendingIntent is = PendingIntent.getBroadcast(getContext(), 0,
                makeBroadcastIntent(BROADCAST_LOCAL_GRANTED), 0);
        is.send();
        waitForResultOrThrow(BROADCAST_TIMEOUT);
        is.cancel();
    }

    public void testLocalReceivePermissionDenied() throws Exception {
        final Intent intent = makeBroadcastIntent(BROADCAST_LOCAL_DENIED);

        setExpectedReceivers(new String[]{RECEIVER_RESULTS});

        PendingIntent.OnFinished finish = new PendingIntent.OnFinished() {
            public void onSendFinished(PendingIntent pi, Intent intent,
                    int resultCode, String resultData, Bundle resultExtras) {
                gotReceive(RECEIVER_RESULTS, intent);
            }
        };

        PendingIntent is = PendingIntent.getBroadcast(getContext(), 0, intent, 0);
        is.send(Activity.RESULT_CANCELED, finish, null);
        waitForResultOrThrow(BROADCAST_TIMEOUT);
        is.cancel();
    }
}
