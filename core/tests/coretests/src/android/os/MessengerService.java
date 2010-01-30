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

package android.os;

import android.app.Service;
import android.content.Intent;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;

public class MessengerService extends Service {
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Message reply = Message.obtain();
            reply.copyFrom(msg);
            try {
                msg.replyTo.send(reply);
            } catch (RemoteException e) {
            }
        }
    };
    
    private final Messenger mMessenger = new Messenger(mHandler);
    
    public MessengerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }
}

