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

package com.android.systemui.ambient.touch;

import static com.android.systemui.ambient.touch.dagger.ShadeModule.NOTIFICATION_SHADE_GESTURE_INITIATION_HEIGHT;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.phone.CentralSurfaces;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link ShadeTouchHandler} is responsible for handling swipe down gestures over dream
 * to bring down the shade.
 */
public class ShadeTouchHandler implements TouchHandler {
    private final Optional<CentralSurfaces> mSurfaces;
    private final int mInitiationHeight;

    /**
     * Tracks whether or not we are capturing a given touch. Will be null before and after a touch.
     */
    private Boolean mCapture;

    @Inject
    ShadeTouchHandler(Optional<CentralSurfaces> centralSurfaces,
            @Named(NOTIFICATION_SHADE_GESTURE_INITIATION_HEIGHT) int initiationHeight) {
        mSurfaces = centralSurfaces;
        mInitiationHeight = initiationHeight;
    }

    @Override
    public void onSessionStart(TouchSession session) {
        if (mSurfaces.isEmpty()) {
            session.pop();
            return;
        }

        session.registerCallback(() -> mCapture = null);

        session.registerInputListener(ev -> {
            if (ev instanceof MotionEvent) {
                if (mCapture != null && mCapture) {
                    mSurfaces.get().handleExternalShadeWindowTouch((MotionEvent) ev);
                }
                if (((MotionEvent) ev).getAction() == MotionEvent.ACTION_UP) {
                    session.pop();
                }
            }
        });

        session.registerGestureListener(new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float distanceX,
                    float distanceY) {
                if (mCapture == null) {
                    // Only capture swipes that are going downwards.
                    mCapture = Math.abs(distanceY) > Math.abs(distanceX) && distanceY < 0;
                    if (mCapture) {
                        // Send the initial touches over, as the input listener has already
                        // processed these touches.
                        mSurfaces.get().handleExternalShadeWindowTouch(e1);
                        mSurfaces.get().handleExternalShadeWindowTouch(e2);
                    }
                }
                return mCapture;
            }

            @Override
            public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX,
                    float velocityY) {
                return mCapture;
            }
        });
    }

    @Override
    public void getTouchInitiationRegion(Rect bounds, Region region, Rect exclusionRect) {
        final Rect outBounds = new Rect(bounds);
        outBounds.inset(0, 0, 0, outBounds.height() - mInitiationHeight);
        region.op(outBounds, Region.Op.UNION);
    }
}
