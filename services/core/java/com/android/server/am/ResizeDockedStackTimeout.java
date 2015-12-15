/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.am;

import android.graphics.Rect;
import android.os.Handler;

import static com.android.server.am.ActivityStackSupervisor.PRESERVE_WINDOWS;

/**
 * When resizing the docked stack, a caller can temporarily supply task bounds that are different
 * from the stack bounds. In order to return to a sane state if the caller crashes or has a bug,
 * this class manages this cycle.
 */
class ResizeDockedStackTimeout {

    private static final long TIMEOUT_MS = 10 * 1000;
    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mSupervisor;
    private final Handler mHandler;
    private final Rect mCurrentDockedBounds = new Rect();

    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mService) {
                mSupervisor.resizeDockedStackLocked(mCurrentDockedBounds, null, null, null, null,
                        PRESERVE_WINDOWS);
            }
        }
    };

    ResizeDockedStackTimeout(ActivityManagerService service, ActivityStackSupervisor supervisor,
            Handler handler) {
        mService = service;
        mSupervisor = supervisor;
        mHandler = handler;
    }

    void notifyResizing(Rect dockedBounds, boolean hasTempBounds) {
        mHandler.removeCallbacks(mTimeoutRunnable);
        if (!hasTempBounds) {
            return;
        }
        mCurrentDockedBounds.set(dockedBounds);
        mHandler.postDelayed(mTimeoutRunnable, TIMEOUT_MS);
    }

}
