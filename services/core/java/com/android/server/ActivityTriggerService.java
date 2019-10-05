/*
* Copyright (c) 2019, The Linux Foundation. All rights reserved.
*
* Redistribution and use in source and binary forms, with or without
* modification, are permitted provided that the following conditions are
* met:
*     * Redistributions of source code must retain the above copyright
*       notice, this list of conditions and the following disclaimer.
*     * Redistributions in binary form must reproduce the above
*       copyright notice, this list of conditions and the following
*       disclaimer in the documentation and/or other materials provided
*       with the distribution.
*     * Neither the name of The Linux Foundation nor the names of its
*       contributors may be used to endorse or promote products derived
*       from this software without specific prior written permission.
*
* THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
* WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
* ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
* BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
* CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
* SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
* BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
* WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
* OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
* IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.android.server;

import android.content.pm.ApplicationInfo;
import android.util.Slog;
import android.content.Context;
import com.android.server.am.HostingRecord;
import java.lang.Runnable;
import android.os.HandlerThread;
import android.os.Handler;

public class ActivityTriggerService extends SystemService {
    private static String TAG = "ActivityTriggerService";
    public static final int PROC_ADDED_NOTIFICATION = 1;
    public static final int PROC_REMOVED_NOTIFICATION = 0;
    private EventHandlerThread eventHandler = new EventHandlerThread("EventHandlerThread");

    public ActivityTriggerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Starting ActivityTriggerService");
        eventHandler.start();
        publishLocalService(ActivityTriggerService.class, this);
    }

    /*make non-blocking call to add a new event to the handler's queue.
      the event handler is the one responsible for running each event.*/
    public void updateRecord(HostingRecord hr, ApplicationInfo info, int pid, int event) {
        if(hr != null) {
            eventHandler.getHandler().post(new LocalRunnable(info.packageName, info.longVersionCode, info.processName, pid, event));
        }
    }

    public class EventHandlerThread extends HandlerThread {
        private Handler handler;
        public EventHandlerThread(String name) {
            super(name); //no priority specified
        }

        @Override
        protected void onLooperPrepared() {
            //attach a handler to the thread
            handler = new Handler();
        }
        //get the handler that queues and runs Runnables
        public Handler getHandler() {
            return handler;
        }
    }

    static class LocalRunnable implements Runnable {
        private String packageName;
        private long lvCode;
        private String procName;
        private int pid;
        private int event;

        LocalRunnable(String packageName, long lvCode, String procName, int pid, int event) {
            this.packageName = packageName;
            this.lvCode = lvCode;
            this.procName = procName;
            this.pid = pid;
            this.event = event;
        }
        @Override
        public  void run() {
            notifyAction_native(packageName, lvCode, procName, pid, event);
        }
    }

    //Native methods
    static native void notifyAction_native(String pkgName, long vCode, String procName, int pid, int event);

}

