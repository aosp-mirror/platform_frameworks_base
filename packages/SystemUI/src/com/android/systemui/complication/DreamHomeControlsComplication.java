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

package com.android.systemui.complication;

import static com.android.systemui.complication.dagger.DreamHomeControlsComplicationComponent.DreamHomeControlsModule.DREAM_HOME_CONTROLS_CHIP_VIEW;
import static com.android.systemui.complication.dagger.RegisteredComplicationsModule.DREAM_HOME_CONTROLS_CHIP_LAYOUT_PARAMS;
import static com.android.systemui.complication.dagger.RegisteredComplicationsModule.OPEN_HUB_CHIP_REPLACE_HOME_CONTROLS;
import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE;
import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE_AFTER_UNLOCK;
import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.UNAVAILABLE;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.Utils;
import com.android.systemui.CoreStartable;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.complication.dagger.DreamHomeControlsComplicationComponent;
import com.android.systemui.controls.ControlsServiceInfo;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.controls.management.ControlsListingController;
import com.android.systemui.controls.ui.ControlsActivity;
import com.android.systemui.controls.ui.ControlsUiController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.SystemUser;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.condition.ConditionalCoreStartable;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * A dream complication that shows a home controls chip to launch device controls (to control
 * devices at home like lights and thermostats).
 */
public class DreamHomeControlsComplication implements Complication {
    private final Resources mResources;
    private final DreamHomeControlsComplicationComponent.Factory mComponentFactory;

    @Inject
    public DreamHomeControlsComplication(
            @Main Resources resources,
            DreamHomeControlsComplicationComponent.Factory componentFactory) {
        mResources = resources;
        mComponentFactory = componentFactory;
    }

    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return mComponentFactory.create(mResources).getViewHolder();
    }

    @Override
    public int getRequiredTypeAvailability() {
        return COMPLICATION_TYPE_HOME_CONTROLS;
    }

    /**
     * {@link CoreStartable} for registering the complication with SystemUI on startup.
     */
    public static class Registrant extends ConditionalCoreStartable {
        private final DreamHomeControlsComplication mComplication;
        private final DreamOverlayStateController mDreamOverlayStateController;
        private final ControlsComponent mControlsComponent;
        private final boolean mReplacedByOpenHub;

        private boolean mOverlayActive = false;

        // Callback for when the home controls service availability changes.
        private final ControlsListingController.ControlsListingCallback mControlsCallback =
                services -> updateHomeControlsComplication();

        private final DreamOverlayStateController.Callback mOverlayStateCallback =
                new DreamOverlayStateController.Callback() {
                    @Override
                    public void onStateChanged() {
                        if (mOverlayActive == mDreamOverlayStateController.isOverlayActive()) {
                            return;
                        }

                        mOverlayActive = !mOverlayActive;

                        if (mOverlayActive) {
                            updateHomeControlsComplication();
                        }
                    }
                };

        @Inject
        public Registrant(DreamHomeControlsComplication complication,
                DreamOverlayStateController dreamOverlayStateController,
                ControlsComponent controlsComponent,
                @SystemUser Monitor monitor,
                @Named(OPEN_HUB_CHIP_REPLACE_HOME_CONTROLS) boolean replacedByOpenHub) {
            super(monitor);
            mComplication = complication;
            mControlsComponent = controlsComponent;
            mDreamOverlayStateController = dreamOverlayStateController;
            mReplacedByOpenHub = replacedByOpenHub;
        }

        @Override
        public void onStart() {
            mControlsComponent.getControlsListingController().ifPresent(
                    c -> c.addCallback(mControlsCallback));
            mDreamOverlayStateController.addCallback(mOverlayStateCallback);
        }

        private void updateHomeControlsComplication() {
            mControlsComponent.getControlsListingController().ifPresent(c -> {
                if (isHomeControlsAvailable(c.getCurrentServices())) {
                    mDreamOverlayStateController.addComplication(mComplication);
                } else {
                    mDreamOverlayStateController.removeComplication(mComplication);
                }
            });
        }

        private boolean isHomeControlsAvailable(List<ControlsServiceInfo> controlsServices) {
            if (controlsServices.isEmpty()) {
                return false;
            }

            final boolean hasFavorites = mControlsComponent.getControlsController()
                    .map(c -> !c.getFavorites().isEmpty())
                    .orElse(false);
            boolean hasPanels = false;
            for (int i = 0; i < controlsServices.size(); i++) {
                if (controlsServices.get(i).getPanelActivity() != null) {
                    hasPanels = true;
                    break;
                }
            }
            final ControlsComponent.Visibility visibility = mControlsComponent.getVisibility();
            return (hasFavorites || hasPanels) && visibility != UNAVAILABLE;
        }
    }

    /**
     * Contains values/logic associated with the dream complication view.
     */
    public static class DreamHomeControlsChipViewHolder implements ViewHolder {
        private final ImageView mView;
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
        public ImageView getView() {
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
        private final ConfigurationController mConfigurationController;
        private final ControlsComponent mControlsComponent;

        private final UiEventLogger mUiEventLogger;

        private final ConfigurationController.ConfigurationListener mConfigurationListener =
                new ConfigurationController.ConfigurationListener() {
                    @Override
                    public void onUiModeChanged() {
                        reloadResources();
                    }
                };

        @Inject
        DreamHomeControlsChipViewController(
                @Named(DREAM_HOME_CONTROLS_CHIP_VIEW) ImageView view,
                ActivityStarter activityStarter,
                Context context,
                ConfigurationController configurationController,
                ControlsComponent controlsComponent,
                UiEventLogger uiEventLogger) {
            super(view);

            mActivityStarter = activityStarter;
            mContext = context;
            mConfigurationController = configurationController;
            mControlsComponent = controlsComponent;
            mUiEventLogger = uiEventLogger;
        }

        @Override
        protected void onViewAttached() {
            reloadResources();
            mView.setOnClickListener(this::onClickHomeControls);
            mConfigurationController.addCallback(mConfigurationListener);
        }

        @Override
        protected void onViewDetached() {
            mConfigurationController.removeCallback(mConfigurationListener);
        }

        private void reloadResources() {
            final String title = getControlsTitle();
            if (title != null) {
                mView.setContentDescription(title);
            }
            mView.setImageResource(mControlsComponent.getTileImageId());
            mView.setImageTintList(Utils.getColorAttr(mContext, android.R.attr.textColorPrimary));
            final Drawable background = mView.getBackground();
            if (background != null) {
                background.setTintList(
                        Utils.getColorAttr(mContext, com.android.internal.R.attr.colorSurface));
            }
        }

        @Nullable
        private String getControlsTitle() {
            try {
                return mContext.getString(mControlsComponent.getTileTitleId());
            } catch (Resources.NotFoundException e) {
                return null;
            }
        }

        private void onClickHomeControls(View v) {
            if (DEBUG) Log.d(TAG, "home controls complication tapped");

            mUiEventLogger.log(DreamOverlayUiEvent.DREAM_HOME_CONTROLS_TAPPED);

            final Intent intent = new Intent(mContext, ControlsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ControlsUiController.EXTRA_ANIMATE, true)
                    .putExtra(ControlsUiController.EXIT_TO_DREAM, true);

            final ActivityTransitionAnimator.Controller controller =
                    v != null
                            ? ActivityTransitionAnimator.Controller.fromView(v, null /* cujType */)
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
