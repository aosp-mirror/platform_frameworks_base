/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.impl.RenderAction;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegate overriding selected methods of android.os.HandlerThread
 *
 * Through the layoutlib_create tool, selected methods of Handler have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 *
 */
public class HandlerThread_Delegate {

    private static Map<BridgeContext, List<HandlerThread>> sThreads =
            new HashMap<BridgeContext, List<HandlerThread>>();

    public static void cleanUp(BridgeContext context) {
        List<HandlerThread> list = sThreads.get(context);
        if (list != null) {
            for (HandlerThread thread : list) {
                thread.quit();
            }

            list.clear();
            sThreads.remove(context);
        }
    }

    // -------- Delegate methods

    @LayoutlibDelegate
    /*package*/ static void run(HandlerThread theThread) {
        // record the thread so that it can be quit() on clean up.
        BridgeContext context = RenderAction.getCurrentContext();
        List<HandlerThread> list = sThreads.get(context);
        if (list == null) {
            list = new ArrayList<HandlerThread>();
            sThreads.put(context, list);
        }

        list.add(theThread);

        // ---- START DEFAULT IMPLEMENTATION.

        theThread.mTid = Process.myTid();
        Looper.prepare();
        synchronized (theThread) {
            theThread.mLooper = Looper.myLooper();
            theThread.notifyAll();
        }
        Process.setThreadPriority(theThread.mPriority);
        theThread.onLooperPrepared();
        Looper.loop();
        theThread.mTid = -1;
    }
}
