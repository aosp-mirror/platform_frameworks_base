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
 * limitations under the License.
 */

package com.android.systemui.shared.recents;

import android.view.MotionEvent;
import com.android.systemui.shared.recents.ISystemUiProxy;

oneway interface IOverviewProxy {
    void onBind(in ISystemUiProxy sysUiProxy);

    /**
     * Called once immediately prior to the first onMotionEvent() call, providing a hint to the
     * target the initial source of the subsequent motion events.
     *
     * @param downHitTarget is one of the {@link NavigationBarCompat.HitTarget}s
     */
    void onPreMotionEvent(int downHitTarget);

    /**
     * Proxies motion events from the nav bar in SystemUI to the OverviewProxyService. The sender
     * guarantees the following order of events:
     *
     * Normal gesture: DOWN, (MOVE/POINTER_DOWN/POINTER_UP)*, UP
     * Quick switch: DOWN, (MOVE/POINTER_DOWN/POINTER_UP)*, SWITCH
     * Quick scrub: DOWN, (MOVE/POINTER_DOWN/POINTER_UP)*, SCRUB_START, SCRUB_PROGRESS*, SCRUB_END
     *
     * Once quick switch/scrub is sent, then no further motion events will be provided.
     */
    void onMotionEvent(in MotionEvent event);

    /**
     * Sent when a user has quickly flinged on the nav bar to switch tasks. Once this event is sent
     * the caller will stop sending any motion events and will no longer preemptively cancel any
     * recents animations started as a part of the motion event handling.
     */
    void onQuickSwitch();

    /**
     * Sent when the user starts to actively scrub the nav bar to switch tasks. Once this event is
     * sent the caller will stop sending any motion events and will no longer preemptively cancel
     * any recents animations started as a part of the motion event handling.
     */
    void onQuickScrubStart();

    /**
     * Sent when the user stops actively scrubbing the nav bar to switch tasks.
     */
    void onQuickScrubEnd();

    /**
     * Sent for each movement over the nav bar while the user is scrubbing it to switch tasks.
     */
    void onQuickScrubProgress(float progress);

    /**
     * Sent when overview button is pressed to toggle show/hide of overview.
     */
    void onOverviewToggle();

    /**
     * Sent when overview is to be shown.
     */
    void onOverviewShown(boolean triggeredFromAltTab);

    /**
     * Sent when overview is to be hidden.
     */
    void onOverviewHidden(boolean triggeredFromAltTab, boolean triggeredFromHomeKey);
}
