/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.dreams.touch;

import static com.android.systemui.dreams.touch.dagger.ShadeModule.COMMUNAL_GESTURE_INITIATION_WIDTH;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

/** {@link DreamTouchHandler} responsible for handling touches to open communal hub. **/
public class CommunalTouchHandler implements DreamTouchHandler {
    private final int mInitiationWidth;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final Optional<CentralSurfaces> mCentralSurfaces;

    @Inject
    public CommunalTouchHandler(
            Optional<CentralSurfaces> centralSurfaces,
            NotificationShadeWindowController notificationShadeWindowController,
            @Named(COMMUNAL_GESTURE_INITIATION_WIDTH) int initiationWidth) {
        mInitiationWidth = initiationWidth;
        mCentralSurfaces = centralSurfaces;
        mNotificationShadeWindowController = notificationShadeWindowController;
    }

    @Override
    public void onSessionStart(TouchSession session) {
        mCentralSurfaces.ifPresent(surfaces -> handleSessionStart(surfaces, session));
    }

    @Override
    public void getTouchInitiationRegion(Rect bounds, Region region) {
        final Rect outBounds = new Rect(bounds);
        outBounds.inset(outBounds.width() - mInitiationWidth, 0, 0, 0);
        region.op(outBounds, Region.Op.UNION);
    }

    private void handleSessionStart(CentralSurfaces surfaces, TouchSession session) {
        // Force the notification shade window open (otherwise the hub won't show while swiping).
        mNotificationShadeWindowController.setForcePluginOpen(true, this);

        session.registerInputListener(ev -> {
            surfaces.handleDreamTouch((MotionEvent) ev);
            if (ev != null && ((MotionEvent) ev).getAction() == MotionEvent.ACTION_UP) {
                var unused = session.pop();
            }
        });

        session.registerGestureListener(new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
                    float distanceY) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                    float velocityY) {
                return true;
            }
        });
    }
}
