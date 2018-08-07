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

package com.android.systemui.recents;

import android.graphics.Rect;

/**
 * Due to the fact that RecentsActivity is per-user, we need to establish an
 * interface (this) for the non-system user to register itself for callbacks and to
 * callback to the system user to update internal state.
 */
oneway interface IRecentsSystemUserCallbacks {
    void registerNonSystemUserCallbacks(IBinder nonSystemUserCallbacks, int userId);

    void updateRecentsVisibility(boolean visible);
    void startScreenPinning(int taskId);
    void sendRecentsDrawnEvent();
    void sendDockingTopTaskEvent(int dragMode, in Rect initialRect);
    void sendLaunchRecentsEvent();
    void sendDockedFirstAnimationFrameEvent();
    void setWaitingForTransitionStartEvent(boolean waitingForTransitionStart);
}
