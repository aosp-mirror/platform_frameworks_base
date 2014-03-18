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

import java.lang.ref.WeakReference;


/** The message handler to process Recents SysUI messages */
class SystemUIMessageHandler extends Handler {
    WeakReference<Context> mContext;

    SystemUIMessageHandler(Context context) {
        // Keep a weak ref to the context instead of a strong ref
        mContext = new WeakReference<Context>(context);
    }

    @Override
    public void handleMessage(Message msg) {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake,
                "[RecentsService|handleMessage]", msg);

        Context context = mContext.get();
        if (context == null) return;

        if (msg.what == RecentsService.MSG_UPDATE_RECENTS_FOR_CONFIGURATION) {
            RecentsTaskLoader.initialize(context);
            RecentsConfiguration.reinitialize(context);

            try {
                Bundle data = msg.getData();
                Rect windowRect = (Rect) data.getParcelable("windowRect");
                Rect systemInsets = (Rect) data.getParcelable("systemInsets");

                // Create a dummy task stack & compute the rect for the thumbnail to animate to
                TaskStack stack = new TaskStack(context);
                TaskStackView tsv = new TaskStackView(context, stack);
                // Since the nav bar height is already accounted for in the windowRect, don't pass
                // in a bottom inset
                tsv.computeRects(windowRect.width(), windowRect.height() - systemInsets.top, 0);
                tsv.boundScroll();
                TaskViewTransform transform = tsv.getStackTransform(0);
                Rect taskRect = new Rect(transform.rect);

                data.putParcelable("taskRect", taskRect);
                Message reply = Message.obtain(null,
                        RecentsService.MSG_UPDATE_RECENTS_FOR_CONFIGURATION, 0, 0);
                reply.setData(data);
                msg.replyTo.send(reply);
            } catch (RemoteException re) {
                re.printStackTrace();
            }
        } else if (msg.what == RecentsService.MSG_CLOSE_RECENTS) {
            // Do nothing
        } else if (msg.what == RecentsService.MSG_TOGGLE_RECENTS) {
            // Send a broadcast to toggle recents
            Intent intent = new Intent(RecentsService.ACTION_TOGGLE_RECENTS_ACTIVITY);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);

            // Time this path
            Console.logTraceTime(Constants.DebugFlags.App.TimeRecentsStartup,
                    Constants.DebugFlags.App.TimeRecentsStartupKey, "receivedToggleRecents");
            Console.logTraceTime(Constants.DebugFlags.App.TimeRecentsLaunchTask,
                    Constants.DebugFlags.App.TimeRecentsLaunchKey, "receivedToggleRecents");
        }
    }
}

/* Service */
public class RecentsService extends Service {
    final static String ACTION_FINISH_RECENTS_ACTIVITY = "action_finish_recents_activity";
    final static String ACTION_TOGGLE_RECENTS_ACTIVITY = "action_toggle_recents_activity";

    // XXX: This should be getting the message from recents definition
    final static int MSG_UPDATE_RECENTS_FOR_CONFIGURATION = 0;
    final static int MSG_CLOSE_RECENTS = 4;
    final static int MSG_TOGGLE_RECENTS = 5;

    Messenger mSystemUIMessenger = new Messenger(new SystemUIMessageHandler(this));

    @Override
    public void onCreate() {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsService|onCreate]");
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsService|onBind]");
        return mSystemUIMessenger.getBinder();
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

    @Override
    public void onTrimMemory(int level) {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        if (loader != null) {
            loader.onTrimMemory(level);
        }
    }
}
