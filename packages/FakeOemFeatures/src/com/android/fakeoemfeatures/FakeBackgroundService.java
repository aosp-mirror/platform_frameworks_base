/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.fakeoemfeatures;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.app.Dialog;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Random;

public class FakeBackgroundService extends Service {
    final ArrayList<int[]> mAllocs = new ArrayList<int[]>();

    final Random mRandom = new Random();

    static final long TICK_DELAY = 30*1000; // 30 seconds
    static final int MSG_TICK = 1;
    final Handler mHandler = new Handler() {
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TICK:
                    // We are awesome!  To prove we are doing awesome stuff,
                    // we must use some memory!  It wouldn't be awesome if
                    // we didn't use memory!
                    for (int i=0; i<5; i++) {
                        try {
                            int[] alloc = new int[FakeApp.PAGE_SIZE/4];
                            mAllocs.add(alloc);
                            final int VAL = mRandom.nextInt();
                            for (int j=0; j<FakeApp.PAGE_SIZE/4; j++) {
                                alloc[j] = VAL;
                            }
                        } catch (OutOfMemoryError e) {
                        }
                    }
                    sendEmptyMessageDelayed(MSG_TICK, TICK_DELAY);
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        mHandler.sendEmptyMessageDelayed(MSG_TICK, TICK_DELAY);

        final DisplayManager dm = getSystemService(DisplayManager.class);
        final Display display = dm.getDisplay(DEFAULT_DISPLAY);
        final Context windowContext = createDisplayContext(display)
                .createWindowContext(TYPE_APPLICATION_OVERLAY, null /* options */);

        // Make a fake window that is always around eating graphics resources.
        FakeView view = new FakeView(windowContext);
        Dialog dialog = new Dialog(windowContext, android.R.style.Theme_Holo_Dialog);
        dialog.getWindow().setType(TYPE_APPLICATION_OVERLAY);
        dialog.getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.getWindow().setDimAmount(0);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        // Create an instance of WindowManager that is adjusted to the area of the display dedicated
        // for windows with type TYPE_APPLICATION_OVERLAY.
        final WindowManager wm = windowContext.getSystemService(WindowManager.class);
        Rect maxWindowBounds = wm.getMaximumWindowMetrics().getBounds();
        int maxSize = Math.max(maxWindowBounds.width(), maxWindowBounds.height());
        maxSize *= 2;
        lp.x = maxSize;
        lp.y = maxSize;
        lp.setTitle(getPackageName() + ":background");
        dialog.getWindow().setAttributes(lp);
        dialog.getWindow().setContentView(view);
        dialog.show();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        mHandler.removeMessages(MSG_TICK);
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
