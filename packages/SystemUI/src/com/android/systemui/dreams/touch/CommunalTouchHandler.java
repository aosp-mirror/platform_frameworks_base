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

import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.graphics.Rect;
import android.graphics.Region;
import android.util.LayoutDirection;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;

import com.android.systemui.ambient.touch.TouchHandler;
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor;
import com.android.systemui.communal.domain.interactor.CommunalInteractor;
import com.android.systemui.dreams.touch.dagger.CommunalTouchModule;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import kotlinx.coroutines.Job;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

/** {@link TouchHandler} responsible for handling touches to open communal hub. **/
public class CommunalTouchHandler implements TouchHandler {
    private final int mInitiationWidth;
    private final Optional<CentralSurfaces> mCentralSurfaces;
    private final Lifecycle mLifecycle;
    private final CommunalInteractor mCommunalInteractor;

    private final ConfigurationInteractor mConfigurationInteractor;
    private Boolean mIsEnabled = false;

    private ArrayList<Job> mFlows = new ArrayList<>();

    private int mLayoutDirection = LayoutDirection.LTR;

    @VisibleForTesting
    final Consumer<Boolean> mIsCommunalAvailableCallback = isAvailable -> setIsEnabled(isAvailable);

    @VisibleForTesting
    final Consumer<Integer> mLayoutDirectionCallback = direction -> mLayoutDirection = direction;

    @Inject
    public CommunalTouchHandler(
            Optional<CentralSurfaces> centralSurfaces,
            @Named(CommunalTouchModule.COMMUNAL_GESTURE_INITIATION_WIDTH) int initiationWidth,
            CommunalInteractor communalInteractor,
            ConfigurationInteractor configurationInteractor,
            Lifecycle lifecycle) {
        mInitiationWidth = initiationWidth;
        mCentralSurfaces = centralSurfaces;
        mLifecycle = lifecycle;
        mCommunalInteractor = communalInteractor;
        mConfigurationInteractor = configurationInteractor;

        mFlows.add(collectFlow(
                mLifecycle,
                mCommunalInteractor.isCommunalAvailable(),
                mIsCommunalAvailableCallback
        ));

        mFlows.add(collectFlow(
                mLifecycle,
                mConfigurationInteractor.getLayoutDirection(),
                mLayoutDirectionCallback
        ));
    }

    @Override
    public Boolean isEnabled() {
        return mIsEnabled;
    }

    @Override
    public void setIsEnabled(Boolean enabled) {
        mIsEnabled = enabled;
    }

    @Override
    public void onSessionStart(TouchSession session) {
        if (!mIsEnabled) {
            return;
        }
        mCentralSurfaces.ifPresent(surfaces -> handleSessionStart(surfaces, session));
    }

    @Override
    public void getTouchInitiationRegion(Rect bounds, Region region, Rect exclusionRect) {
        final Rect outBounds = new Rect(bounds);
        final int inset = outBounds.width() - mInitiationWidth;

        // Touch initiation area is defined in terms of LTR. The insets must be flipped for RTL
        if (mLayoutDirection == LayoutDirection.LTR) {
            outBounds.inset(inset, 0, 0, 0);
        } else {
            outBounds.inset(0, 0, inset, 0);
        }

        region.op(outBounds, Region.Op.UNION);
    }

    private void handleSessionStart(CentralSurfaces surfaces, TouchSession session) {
        // Notification shade window has its own logic to be visible if the hub is open, no need to
        // do anything here other than send touch events over.
        session.registerInputListener(ev -> {
            surfaces.handleCommunalHubTouch((MotionEvent) ev);
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

    @Override
    public void onDestroy() {
        for (Job job : mFlows) {
            job.cancel(new CancellationException());
        }
        mFlows.clear();
        TouchHandler.super.onDestroy();
    }
}
