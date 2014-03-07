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

package com.android.systemui.recents;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.TaskStackView;
import com.android.systemui.recents.views.TaskViewTransform;


/* Service */
public class RecentsService extends Service {
    // XXX: This should be getting the message from recents definition
    final static int MSG_UPDATE_RECENTS_FOR_CONFIGURATION = 0;

    class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsService|handleMessage]", msg);
            if (msg.what == MSG_UPDATE_RECENTS_FOR_CONFIGURATION) {
                Context context = RecentsService.this;
                RecentsTaskLoader.initialize(context);
                RecentsConfiguration.reinitialize(context);

                try {
                    Bundle data = msg.getData();
                    Rect windowRect = (Rect) data.getParcelable("windowRect");
                    Rect systemInsets = (Rect) data.getParcelable("systemInsets");
                    RecentsConfiguration.getInstance().updateSystemInsets(systemInsets);

                    // Create a dummy task stack & compute the rect for the thumbnail to animate to
                    TaskStack stack = new TaskStack(context);
                    TaskStackView tsv = new TaskStackView(context, stack);
                    tsv.computeRects(windowRect.width(), windowRect.height() - systemInsets.top);
                    tsv.boundScroll();
                    TaskViewTransform transform = tsv.getStackTransform(0);

                    data.putParcelable("taskRect", transform.rect);
                    Message reply = Message.obtain(null, MSG_UPDATE_RECENTS_FOR_CONFIGURATION, 0, 0);
                    reply.setData(data);
                    msg.replyTo.send(reply);
                } catch (RemoteException re) {
                    re.printStackTrace();
                }
            }
        }
    }

    Messenger mMessenger = new Messenger(new MessageHandler());

    @Override
    public void onCreate() {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsService|onCreate]");
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsService|onBind]");
        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsService|onUnbind]");
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsService|onRebind]");
        super.onRebind(intent);
    }

    @Override
    public void onDestroy() {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsService|onDestroy]");
        super.onDestroy();
    }
}
