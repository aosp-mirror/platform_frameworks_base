/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams.appwidgets;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.view.Gravity;

import androidx.constraintlayout.widget.ConstraintSet;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.OverlayHostView;
import com.android.systemui.dreams.dagger.AppWidgetOverlayComponent;

import javax.inject.Inject;

/**
 * {@link AppWidgetOverlayPrimer} reads the configured App Widget Overlay from resources on start
 * and populates them into the {@link DreamOverlayStateController}.
 */
public class AppWidgetOverlayPrimer extends SystemUI {
    private final Resources mResources;
    private final DreamOverlayStateController mDreamOverlayStateController;
    private final AppWidgetOverlayComponent.Factory mComponentFactory;

    @Inject
    public AppWidgetOverlayPrimer(Context context, @Main Resources resources,
            DreamOverlayStateController overlayStateController,
            AppWidgetOverlayComponent.Factory appWidgetOverlayFactory) {
        super(context);
        mResources = resources;
        mDreamOverlayStateController = overlayStateController;
        mComponentFactory = appWidgetOverlayFactory;
    }

    @Override
    public void start() {
    }

    @Override
    protected void onBootCompleted() {
        super.onBootCompleted();
        loadDefaultWidgets();
    }

    /**
     * Generates the {@link OverlayHostView.LayoutParams} for a given gravity. Default dimension
     * constraints are also included in the params.
     * @param gravity The gravity for the layout as defined by {@link Gravity}.
     * @param resources The resourcs from which default dimensions will be extracted from.
     * @return {@link OverlayHostView.LayoutParams} representing the provided gravity and default
     * parameters.
     */
    private static OverlayHostView.LayoutParams getLayoutParams(int gravity, Resources resources) {
        final OverlayHostView.LayoutParams params = new OverlayHostView.LayoutParams(
                OverlayHostView.LayoutParams.MATCH_CONSTRAINT,
                OverlayHostView.LayoutParams.MATCH_CONSTRAINT);

        if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            params.bottomToBottom = ConstraintSet.PARENT_ID;
        }

        if ((gravity & Gravity.TOP) == Gravity.TOP) {
            params.topToTop = ConstraintSet.PARENT_ID;
        }

        if ((gravity & Gravity.END) == Gravity.END) {
            params.endToEnd = ConstraintSet.PARENT_ID;
        }

        if ((gravity & Gravity.START) == Gravity.START) {
            params.startToStart = ConstraintSet.PARENT_ID;
        }

        // For now, apply the same sizing constraints on every widget.
        params.matchConstraintPercentHeight =
                resources.getFloat(R.dimen.config_dreamOverlayComponentHeightPercent);
        params.matchConstraintPercentWidth =
                resources.getFloat(R.dimen.config_dreamOverlayComponentWidthPercent);

        return params;
    }


    /**
     * Helper method for loading widgets based on configuration.
     */
    private void loadDefaultWidgets() {
        final int[] positions = mResources.getIntArray(R.array.config_dreamOverlayPositions);
        final String[] components =
                mResources.getStringArray(R.array.config_dreamOverlayComponents);

        for (int i = 0; i < Math.min(positions.length, components.length); i++) {
            final AppWidgetOverlayComponent component = mComponentFactory.build(
                    ComponentName.unflattenFromString(components[i]),
                    getLayoutParams(positions[i], mResources));

            mDreamOverlayStateController.addOverlay(component.getAppWidgetOverlayProvider());
        }
    }
}
