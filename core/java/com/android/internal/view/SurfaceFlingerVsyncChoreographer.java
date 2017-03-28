/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.view;

import android.os.Handler;
import android.os.Message;
import android.view.Choreographer;
import android.view.Display;

/**
 * Utility class to schedule things at vsync-sf instead of vsync-app
 * @hide
 */
public class SurfaceFlingerVsyncChoreographer {

    private static final long ONE_MS_IN_NS = 1000000;
    private static final long ONE_S_IN_NS = ONE_MS_IN_NS * 1000;

    private final Handler mHandler;
    private final Choreographer mChoreographer;

    /**
     * The offset between vsync-app and vsync-surfaceflinger. See
     * {@link #calculateAppSurfaceFlingerVsyncOffsetMs} why this is necessary.
     */
    private long mSurfaceFlingerOffsetMs;

    public SurfaceFlingerVsyncChoreographer(Handler handler, Display display,
            Choreographer choreographer) {
        mHandler = handler;
        mChoreographer = choreographer;
        mSurfaceFlingerOffsetMs = calculateAppSurfaceFlingerVsyncOffsetMs(display);
    }

    public long getSurfaceFlingerOffsetMs() {
        return mSurfaceFlingerOffsetMs;
    }

    /**
     * This method calculates the offset between vsync-surfaceflinger and vsync-app. If vsync-app
     * is a couple of milliseconds before vsync-sf, a touch or animation event that causes a surface
     * flinger transaction are sometimes processed before the vsync-sf tick, and sometimes after,
     * which leads to jank. Figure out this difference here and then post all the touch/animation
     * events to start being processed at vsync-sf.
     *
     * @return The offset between vsync-app and vsync-sf, or 0 if vsync app happens after vsync-sf.
     */
    private long calculateAppSurfaceFlingerVsyncOffsetMs(Display display) {

        // Calculate vsync offset from SurfaceFlinger.
        // See frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp:getDisplayConfigs
        long vsyncPeriod = (long) (ONE_S_IN_NS / display.getRefreshRate());
        long sfVsyncOffset = vsyncPeriod - (display.getPresentationDeadlineNanos() - ONE_MS_IN_NS);
        return Math.max(0, (sfVsyncOffset - display.getAppVsyncOffsetNanos()) / ONE_MS_IN_NS);
    }

    public void scheduleAtSfVsync(Runnable r) {
        final long delay = calculateDelay();
        if (delay <= 0) {
            r.run();
        } else {
            mHandler.postDelayed(r, delay);
        }
    }

    public void scheduleAtSfVsync(Handler h, Message m) {
        final long delay = calculateDelay();
        if (delay <= 0) {
            h.handleMessage(m);
        } else {
            m.setAsynchronous(true);
            h.sendMessageDelayed(m, delay);
        }
    }

    private long calculateDelay() {
        final long sinceFrameStart = System.nanoTime() - mChoreographer.getLastFrameTimeNanos();
        return mSurfaceFlingerOffsetMs - sinceFrameStart / 1000000;
    }
}
