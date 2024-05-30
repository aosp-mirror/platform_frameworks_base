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

package com.android.systemui.complication;

import static com.android.systemui.complication.dagger.OpenHubComplicationComponent.OpenHubModule.OPEN_HUB_CHIP_VIEW;
import static com.android.systemui.complication.dagger.RegisteredComplicationsModule.OPEN_HUB_CHIP_LAYOUT_PARAMS;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.settingslib.Utils;
import com.android.systemui.CoreStartable;
import com.android.systemui.Flags;
import com.android.systemui.communal.domain.interactor.CommunalInteractor;
import com.android.systemui.communal.shared.model.CommunalScenes;
import com.android.systemui.complication.dagger.OpenHubComplicationComponent;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.SystemUser;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.condition.ConditionalCoreStartable;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * A dream complication that shows a chip to open the glanceable hub.
 */
// TODO(b/339667383): delete or properly implement this once a product decision is made
public class OpenHubComplication implements Complication {
    private final Resources mResources;
    private final OpenHubComplicationComponent.Factory mComponentFactory;

    @Inject
    public OpenHubComplication(
            @Main Resources resources,
            OpenHubComplicationComponent.Factory componentFactory) {
        mResources = resources;
        mComponentFactory = componentFactory;
    }

    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return mComponentFactory.create(mResources).getViewHolder();
    }

    @Override
    public int getRequiredTypeAvailability() {
        // TODO(b/339667383): create a new complication type if we decide to productionize this
        return COMPLICATION_TYPE_HOME_CONTROLS;
    }

    /**
     * {@link CoreStartable} for registering the complication with SystemUI on startup.
     */
    public static class Registrant extends ConditionalCoreStartable {
        private final OpenHubComplication mComplication;
        private final DreamOverlayStateController mDreamOverlayStateController;

        private boolean mOverlayActive = false;

        private final DreamOverlayStateController.Callback mOverlayStateCallback =
                new DreamOverlayStateController.Callback() {
                    @Override
                    public void onStateChanged() {
                        if (mOverlayActive == mDreamOverlayStateController.isOverlayActive()) {
                            return;
                        }

                        mOverlayActive = !mOverlayActive;

                        if (mOverlayActive) {
                            updateOpenHubComplication();
                        }
                    }
                };

        @Inject
        public Registrant(OpenHubComplication complication,
                DreamOverlayStateController dreamOverlayStateController,
                @SystemUser Monitor monitor) {
            super(monitor);
            mComplication = complication;
            mDreamOverlayStateController = dreamOverlayStateController;
        }

        @Override
        public void onStart() {
            mDreamOverlayStateController.addCallback(mOverlayStateCallback);
        }

        private void updateOpenHubComplication() {
            // TODO(b/339667383): don't show the complication if glanceable hub is disabled
            if (Flags.glanceableHubShortcutButton()) {
                mDreamOverlayStateController.addComplication(mComplication);
            } else {
                mDreamOverlayStateController.removeComplication(mComplication);
            }
        }
    }

    /**
     * Contains values/logic associated with the dream complication view.
     */
    public static class OpenHubChipViewHolder implements ViewHolder {
        private final ImageView mView;
        private final ComplicationLayoutParams mLayoutParams;
        private final OpenHubChipViewController mViewController;

        @Inject
        OpenHubChipViewHolder(
                OpenHubChipViewController dreamOpenHubChipViewController,
                @Named(OPEN_HUB_CHIP_VIEW) ImageView view,
                @Named(OPEN_HUB_CHIP_LAYOUT_PARAMS) ComplicationLayoutParams layoutParams
        ) {
            mView = view;
            mLayoutParams = layoutParams;
            mViewController = dreamOpenHubChipViewController;
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
    static class OpenHubChipViewController extends ViewController<ImageView> {
        private static final String TAG = "OpenHubCtrl";
        private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

        private final Context mContext;
        private final ConfigurationController mConfigurationController;

        private final ConfigurationController.ConfigurationListener mConfigurationListener =
                new ConfigurationController.ConfigurationListener() {
                    @Override
                    public void onUiModeChanged() {
                        reloadResources();
                    }
                };
        private final CommunalInteractor mCommunalInteractor;

        @Inject
        OpenHubChipViewController(
                @Named(OPEN_HUB_CHIP_VIEW) ImageView view,
                Context context,
                ConfigurationController configurationController,
                CommunalInteractor communalInteractor) {
            super(view);

            mContext = context;
            mConfigurationController = configurationController;
            mCommunalInteractor = communalInteractor;
        }

        @Override
        protected void onViewAttached() {
            reloadResources();
            mView.setOnClickListener(this::onClickOpenHub);
            mConfigurationController.addCallback(mConfigurationListener);
        }

        @Override
        protected void onViewDetached() {
            mConfigurationController.removeCallback(mConfigurationListener);
        }

        private void reloadResources() {
            mView.setImageTintList(Utils.getColorAttr(mContext, android.R.attr.textColorPrimary));
            final Drawable background = mView.getBackground();
            if (background != null) {
                background.setTintList(
                        Utils.getColorAttr(mContext, com.android.internal.R.attr.colorSurface));
            }
        }

        private void onClickOpenHub(View v) {
            if (DEBUG) Log.d(TAG, "open hub complication tapped");

            mCommunalInteractor.changeScene(CommunalScenes.Communal, null);
        }
    }
}
