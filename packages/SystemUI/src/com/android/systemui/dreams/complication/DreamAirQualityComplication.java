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

import static com.android.systemui.dreams.complication.dagger.DreamAirQualityComplicationComponent.DreamAirQualityComplicationModule.DREAM_AQI_COMPLICATION_LAYOUT_PARAMS;
import static com.android.systemui.dreams.complication.dagger.DreamAirQualityComplicationComponent.DreamAirQualityComplicationModule.DREAM_AQI_COMPLICATION_VIEW;
import static com.android.systemui.dreams.complication.dagger.RegisteredComplicationsModule.SMARTSPACE_TRAMPOLINE_ACTIVITY_COMPONENT;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.systemui.CoreStartable;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.complication.dagger.DreamAirQualityComplicationComponent;
import com.android.systemui.dreams.smartspace.DreamSmartspaceController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Air quality complication which produces view holder responsible for showing AQI over dreams.
 */
public class DreamAirQualityComplication implements Complication {
    // TODO(b/236024839): Move to SmartspaceTarget
    public static final int FEATURE_AIR_QUALITY = 46;

    private final DreamAirQualityComplicationComponent.Factory mComponentFactory;

    @Inject
    public DreamAirQualityComplication(
            DreamAirQualityComplicationComponent.Factory componentFactory) {
        mComponentFactory = componentFactory;
    }

    @Override
    public int getRequiredTypeAvailability() {
        return COMPLICATION_TYPE_AIR_QUALITY;
    }

    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return mComponentFactory.create().getViewHolder();
    }

    /**
     * {@link CoreStartable} for registering {@link DreamAirQualityComplication} with SystemUI.
     */
    public static class Registrant extends CoreStartable {
        private final DreamOverlayStateController mDreamOverlayStateController;
        private final DreamAirQualityComplication mComplication;

        /**
         * Default constructor to register {@link DreamAirQualityComplication}.
         */
        @Inject
        public Registrant(Context context,
                DreamOverlayStateController dreamOverlayStateController,
                DreamAirQualityComplication complication) {
            super(context);
            mDreamOverlayStateController = dreamOverlayStateController;
            mComplication = complication;
        }

        @Override
        public void start() {
            // TODO(b/221500478): Only add complication once we have data to show.
            mDreamOverlayStateController.addComplication(mComplication);
        }
    }

    /**
     * ViewHolder to contain value/logic associated with the AQI complication view.
     */
    public static class DreamAirQualityViewHolder implements ViewHolder {
        private final TextView mView;
        private final DreamAirQualityViewController mController;
        private final ComplicationLayoutParams mLayoutParams;

        @Inject
        DreamAirQualityViewHolder(@Named(DREAM_AQI_COMPLICATION_VIEW) TextView view,
                DreamAirQualityViewController controller,
                @Named(DREAM_AQI_COMPLICATION_LAYOUT_PARAMS)
                        ComplicationLayoutParams layoutParams) {
            mView = view;
            mLayoutParams = layoutParams;
            mController = controller;
            mController.init();
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

    static class DreamAirQualityViewController extends ViewController<TextView> {
        private final DreamSmartspaceController mSmartspaceController;
        private final String mSmartspaceTrampolineComponent;
        private final ActivityStarter mActivityStarter;
        private final AirQualityColorPicker mAirQualityColorPicker;

        private final SmartspaceTargetListener mSmartspaceTargetListener = targets -> {
            final SmartspaceTarget target = targets.stream()
                    .filter(t -> t instanceof SmartspaceTarget)
                    .map(t -> (SmartspaceTarget) t)
                    .filter(t -> t.getFeatureType() == FEATURE_AIR_QUALITY)
                    .findFirst()
                    .orElse(null);
            updateView(target);
        };

        @Inject
        DreamAirQualityViewController(@Named(DREAM_AQI_COMPLICATION_VIEW) TextView view,
                DreamSmartspaceController smartspaceController,
                @Named(SMARTSPACE_TRAMPOLINE_ACTIVITY_COMPONENT)
                        String smartspaceTrampolineComponent,
                ActivityStarter activityStarter,
                AirQualityColorPicker airQualityColorPicker) {
            super(view);
            mSmartspaceController = smartspaceController;
            mSmartspaceTrampolineComponent = smartspaceTrampolineComponent;
            mActivityStarter = activityStarter;
            mAirQualityColorPicker = airQualityColorPicker;
        }

        @Override
        protected void onViewAttached() {
            mSmartspaceController.addUnfilteredListener(mSmartspaceTargetListener);
        }

        @Override
        protected void onViewDetached() {
            mSmartspaceController.removeUnfilteredListener(mSmartspaceTargetListener);
        }

        private void updateView(@Nullable SmartspaceTarget target) {
            final SmartspaceAction headerAction = target == null ? null : target.getHeaderAction();
            if (headerAction == null || TextUtils.isEmpty(headerAction.getTitle())) {
                mView.setVisibility(View.GONE);
                return;
            }
            mView.setVisibility(View.VISIBLE);

            final String airQuality = headerAction.getTitle().toString();
            mView.setText(airQuality);

            final Drawable background = mView.getBackground().mutate();
            final int color = mAirQualityColorPicker.getColorForValue(airQuality);

            if (background instanceof ShapeDrawable) {
                ((ShapeDrawable) background).getPaint().setColor(color);
            } else if (background instanceof GradientDrawable) {
                ((GradientDrawable) background).setColor(color);
            }
            mView.setBackground(background);

            final Intent intent = headerAction.getIntent();
            if (intent != null && intent.getComponent() != null
                    && intent.getComponent().getClassName().equals(
                    mSmartspaceTrampolineComponent)) {
                mView.setOnClickListener(v -> {
                    mActivityStarter.postStartActivityDismissingKeyguard(intent, /* delay= */ 0);
                });
            }
        }
    }
}
