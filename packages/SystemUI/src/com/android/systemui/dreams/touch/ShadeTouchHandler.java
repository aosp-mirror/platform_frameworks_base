/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.systemui.dreams.touch.dagger.ShadeModule.NOTIFICATION_SHADE_GESTURE_INITIATION_HEIGHT;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link ShadeTouchHandler} is responsible for handling swipe down gestures over dream
 * to bring down the shade.
 */
public class ShadeTouchHandler implements DreamTouchHandler {
    private final Optional<CentralSurfaces> mSurfaces;
    private final int mInitiationHeight;

    @Inject
    ShadeTouchHandler(Optional<CentralSurfaces> centralSurfaces,
            @Named(NOTIFICATION_SHADE_GESTURE_INITIATION_HEIGHT) int initiationHeight) {
        mSurfaces = centralSurfaces;
        mInitiationHeight = initiationHeight;
    }

    @Override
    public void onSessionStart(TouchSession session) {
        if (mSurfaces.map(CentralSurfaces::isBouncerShowing).orElse(false)) {
            session.pop();
            return;
        }

        session.registerInputListener(ev -> {
            final NotificationPanelViewController viewController =
                    mSurfaces.map(CentralSurfaces::getNotificationPanelViewController).orElse(null);

            if (viewController != null) {
                viewController.handleExternalTouch((MotionEvent) ev);
            }

            if (ev instanceof MotionEvent) {
                if (((MotionEvent) ev).getAction() == MotionEvent.ACTION_UP) {
                    session.pop();
                }
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

    @Override
    public void getTouchInitiationRegion(Rect bounds, Region region) {
        final Rect outBounds = new Rect(bounds);
        outBounds.inset(0, 0, 0, outBounds.height() - mInitiationHeight);
        region.op(outBounds, Region.Op.UNION);
    }
}
