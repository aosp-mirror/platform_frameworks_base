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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ReceiverCallNotAllowedException;
import android.content.ServiceConnection;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Parcel;

public class LocalReceiver extends BroadcastReceiver {
    public LocalReceiver() {
    }

    public void onReceive(Context context, Intent intent) {
        String resultString = LaunchpadActivity.RECEIVER_LOCAL;
        if (BroadcastTest.BROADCAST_FAIL_REGISTER.equals(intent.getAction())) {
            resultString = "Successfully registered, but expected it to fail";
            try {
                context.registerReceiver(this, new IntentFilter("foo.bar"));
                context.unregisterReceiver(this);
            } catch (ReceiverCallNotAllowedException e) {
                //resultString = "This is the correct behavior but not yet implemented";
                resultString = LaunchpadActivity.RECEIVER_LOCAL;
            }
        } else if (BroadcastTest.BROADCAST_FAIL_BIND.equals(intent.getAction())) {
            resultString = "Successfully bound to service, but expected it to fail";
            try {
                ServiceConnection sc = new ServiceConnection() {
                    public void onServiceConnected(ComponentName name, IBinder service) {
                    }

                    public void onServiceDisconnected(ComponentName name) {
                    }
                };
                context.bindService(new Intent(context, LocalService.class), sc, 0);
                context.unbindService(sc);
            } catch (ReceiverCallNotAllowedException e) {
                //resultString = "This is the correct behavior but not yet implemented";
                resultString = LaunchpadActivity.RECEIVER_LOCAL;
            }
        } else if (LaunchpadActivity.BROADCAST_REPEAT.equals(intent.getAction())) {
            Intent newIntent = new Intent(intent);
            newIntent.setAction(LaunchpadActivity.BROADCAST_LOCAL);
            context.sendOrderedBroadcast(newIntent, null);
        }
        try {
            IBinder caller = intent.getIBinderExtra("caller");
            Parcel data = Parcel.obtain();
            data.writeInterfaceToken(LaunchpadActivity.LAUNCH);
            data.writeString(resultString);
            caller.transact(LaunchpadActivity.GOT_RECEIVE_TRANSACTION, data, null, 0);
            data.recycle();
        } catch (RemoteException ex) {
        }
    }
}

