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

import static com.android.systemui.dreams.complication.dagger.DreamWeatherComplicationComponent.DreamWeatherComplicationModule.DREAM_WEATHER_COMPLICATION_LAYOUT_PARAMS;
import static com.android.systemui.dreams.complication.dagger.DreamWeatherComplicationComponent.DreamWeatherComplicationModule.DREAM_WEATHER_COMPLICATION_VIEW;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.widget.TextView;

import com.android.systemui.CoreStartable;
import com.android.systemui.R;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.complication.dagger.DreamWeatherComplicationComponent;
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Weather Complication that produce Weather view holder.
 */
public class DreamWeatherComplication implements Complication {
    DreamWeatherComplicationComponent.Factory mComponentFactory;

    /**
     * Default constructor for {@link DreamWeatherComplication}.
     */
    @Inject
    public DreamWeatherComplication(
            DreamWeatherComplicationComponent.Factory componentFactory) {
        mComponentFactory = componentFactory;
    }

    @Override
    public int getRequiredTypeAvailability() {
        return COMPLICATION_TYPE_WEATHER;
    }

    /**
     * Create {@link DreamWeatherViewHolder}.
     */
    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return mComponentFactory.create().getViewHolder();
    }

    /**
     * {@link CoreStartable} for registering {@link DreamWeatherComplication} with SystemUI.
     */
    public static class Registrant extends CoreStartable {
        private final LockscreenSmartspaceController mSmartSpaceController;
        private final DreamOverlayStateController mDreamOverlayStateController;
        private final DreamWeatherComplication mComplication;

        /**
         * Default constructor to register {@link DreamWeatherComplication}.
         */
        @Inject
        public Registrant(Context context,
                LockscreenSmartspaceController smartspaceController,
                DreamOverlayStateController dreamOverlayStateController,
                DreamWeatherComplication dreamWeatherComplication) {
            super(context);
            mSmartSpaceController = smartspaceController;
            mDreamOverlayStateController = dreamOverlayStateController;
            mComplication = dreamWeatherComplication;
        }

        @Override
        public void start() {
            if (mSmartSpaceController.isEnabled()) {
                mDreamOverlayStateController.addComplication(mComplication);
            }
        }
    }

    /**
     * ViewHolder to contain value/logic associated with a Weather Complication View.
     */
    public static class DreamWeatherViewHolder implements ViewHolder {
        private final TextView mView;
        private final ComplicationLayoutParams mLayoutParams;
        private final DreamWeatherViewController mViewController;

        @Inject
        DreamWeatherViewHolder(
                @Named(DREAM_WEATHER_COMPLICATION_VIEW) TextView view,
                DreamWeatherViewController controller,
                @Named(DREAM_WEATHER_COMPLICATION_LAYOUT_PARAMS)
                        ComplicationLayoutParams layoutParams) {
            mView = view;
            mLayoutParams = layoutParams;
            mViewController = controller;
            mViewController.init();
        }

        @Override
        public TextView getView() {
            return mView;
        }

        @Override
        public ComplicationLayoutParams getLayoutParams() {
            return mLayoutParams;
        }
    }

    /**
     * ViewController to contain value/logic associated with a Weather Complication View.
     */
    static class DreamWeatherViewController extends ViewController<TextView> {
        private final LockscreenSmartspaceController mSmartSpaceController;
        private SmartspaceTargetListener mSmartspaceTargetListener;

        @Inject
        DreamWeatherViewController(
                @Named(DREAM_WEATHER_COMPLICATION_VIEW) TextView view,
                LockscreenSmartspaceController smartspaceController
        ) {
            super(view);
            mSmartSpaceController = smartspaceController;
        }

        @Override
        protected void onViewAttached() {
            mSmartspaceTargetListener = targets -> targets.forEach(
                    t -> {
                        if (t instanceof SmartspaceTarget
                                && ((SmartspaceTarget) t).getFeatureType()
                                == SmartspaceTarget.FEATURE_WEATHER) {
                            final SmartspaceTarget target = (SmartspaceTarget) t;
                            final SmartspaceAction headerAction = target.getHeaderAction();
                            if (headerAction == null || TextUtils.isEmpty(
                                    headerAction.getTitle())) {
                                return;
                            }

                            String temperature = headerAction.getTitle().toString();
                            mView.setText(temperature);
                            final Icon icon = headerAction.getIcon();
                            if (icon != null) {
                                final int iconSize =
                                        getResources().getDimensionPixelSize(
                                                R.dimen.smart_action_button_icon_size);
                                final Drawable iconDrawable = icon.loadDrawable(getContext());
                                iconDrawable.setBounds(0, 0, iconSize, iconSize);
                                mView.setCompoundDrawables(iconDrawable, null, null, null);
                                mView.setCompoundDrawablePadding(
                                        getResources().getDimensionPixelSize(
                                                R.dimen.smart_action_button_icon_padding));

                            }
                        }
                    });
            mSmartSpaceController.addListener(mSmartspaceTargetListener);
        }

        @Override
        protected void onViewDetached() {
            mSmartSpaceController.removeListener(mSmartspaceTargetListener);
        }
    }
}
