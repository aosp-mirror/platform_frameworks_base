/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.complication;

import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE;
import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE_AFTER_UNLOCK;
import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.UNAVAILABLE;
import static com.android.systemui.dreams.complication.dagger.DreamHomeControlsComplicationComponent.DreamHomeControlsModule.DREAM_HOME_CONTROLS_CHIP_VIEW;
import static com.android.systemui.dreams.complication.dagger.RegisteredComplicationsModule.DREAM_HOME_CONTROLS_CHIP_LAYOUT_PARAMS;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.CoreStartable;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.controls.management.ControlsListingController;
import com.android.systemui.controls.ui.ControlsActivity;
import com.android.systemui.controls.ui.ControlsUiController;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.complication.dagger.DreamHomeControlsComplicationComponent;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * A dream complication that shows a home controls chip to launch device controls (to control
 * devices at home like lights and thermostats).
 */
public class DreamHomeControlsComplication implements Complication {
    private final DreamHomeControlsComplicationComponent.Factory mComponentFactory;

    @Inject
    public DreamHomeControlsComplication(
            DreamHomeControlsComplicationComponent.Factory componentFactory) {
        mComponentFactory = componentFactory;
    }

    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return mComponentFactory.create().getViewHolder();
    }

    @Override
    public int getRequiredTypeAvailability() {
        return COMPLICATION_TYPE_HOME_CONTROLS;
    }

    /**
     * {@link CoreStartable} for registering the complication with SystemUI on startup.
     */
    public static class Registrant extends CoreStartable {
        private final DreamHomeControlsComplication mComplication;
        private final DreamOverlayStateController mDreamOverlayStateController;
        private final ControlsComponent mControlsComponent;

        private boolean mControlServicesAvailable = false;

        // Callback for when the home controls service availability changes.
        private final ControlsListingController.ControlsListingCallback mControlsCallback =
                serviceInfos -> {
                    boolean available = !serviceInfos.isEmpty();

                    if (available != mControlServicesAvailable) {
                        mControlServicesAvailable = available;
                        updateComplicationAvailability();
                    }
                };

        @Inject
        public Registrant(Context context, DreamHomeControlsComplication complication,
                DreamOverlayStateController dreamOverlayStateController,
                ControlsComponent controlsComponent) {
            super(context);

            mComplication = complication;
            mControlsComponent = controlsComponent;
            mDreamOverlayStateController = dreamOverlayStateController;
        }

        @Override
        public void start() {
            mControlsComponent.getControlsListingController().ifPresent(
                    c -> c.addCallback(mControlsCallback));
        }

        private void updateComplicationAvailability() {
            final boolean hasFavorites = mControlsComponent.getControlsController()
                    .map(c -> !c.getFavorites().isEmpty())
                    .orElse(false);
            if (!hasFavorites || !mControlServicesAvailable
                    || mControlsComponent.getVisibility() == UNAVAILABLE) {
                mDreamOverlayStateController.removeComplication(mComplication);
            } else {
                mDreamOverlayStateController.addComplication(mComplication);
            }
        }
    }

    /**
     * Contains values/logic associated with the dream complication view.
     */
    public static class DreamHomeControlsChipViewHolder implements ViewHolder {
        private final View mView;
        private final ComplicationLayoutParams mLayoutParams;
        private final DreamHomeControlsChipViewController mViewController;

        @Inject
        DreamHomeControlsChipViewHolder(
                DreamHomeControlsChipViewController dreamHomeControlsChipViewController,
                @Named(DREAM_HOME_CONTROLS_CHIP_VIEW) ImageView view,
                @Named(DREAM_HOME_CONTROLS_CHIP_LAYOUT_PARAMS) ComplicationLayoutParams layoutParams
        ) {
            mView = view;
            mLayoutParams = layoutParams;
            mViewController = dreamHomeControlsChipViewController;
            mViewController.init();
        }

        @Override
        public View getView() {
            return mView;
        }

        @Override
        public ComplicationLayoutParams getLayoutParams() {
            return mLayoutParams;
        }
    }

    /**
     * Controls behavior of the dream complication.
     */
    static class DreamHomeControlsChipViewController extends ViewController<ImageView> {
        private static final String TAG = "DreamHomeControlsCtrl";
        private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

        private final ActivityStarter mActivityStarter;
        private final Context mContext;
        private final ControlsComponent mControlsComponent;

        @Inject
        DreamHomeControlsChipViewController(
                @Named(DREAM_HOME_CONTROLS_CHIP_VIEW) ImageView view,
                ActivityStarter activityStarter,
                Context context,
                ControlsComponent controlsComponent) {
            super(view);

            mActivityStarter = activityStarter;
            mContext = context;
            mControlsComponent = controlsComponent;
        }

        @Override
        protected void onViewAttached() {
            mView.setImageResource(mControlsComponent.getTileImageId());
            mView.setContentDescription(mContext.getString(mControlsComponent.getTileTitleId()));
            mView.setOnClickListener(this::onClickHomeControls);
        }

        @Override
        protected void onViewDetached() {}

        private void onClickHomeControls(View v) {
            if (DEBUG) Log.d(TAG, "home controls complication tapped");

            final Intent intent = new Intent(mContext, ControlsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ControlsUiController.EXTRA_ANIMATE, true);

            final ActivityLaunchAnimator.Controller controller =
                    v != null ? ActivityLaunchAnimator.Controller.fromView(v, null /* cujType */)
                            : null;
            if (mControlsComponent.getVisibility() == AVAILABLE) {
                // Controls can be made visible.
                mActivityStarter.startActivity(intent, true /* dismissShade */, controller,
                        true /* showOverLockscreenWhenLocked */);
            } else if (mControlsComponent.getVisibility() == AVAILABLE_AFTER_UNLOCK) {
                // Controls can be made visible only after device unlock.
                mActivityStarter.postStartActivityDismissingKeyguard(intent, 0 /* delay */,
                        controller);
            }
        }
    }
}
