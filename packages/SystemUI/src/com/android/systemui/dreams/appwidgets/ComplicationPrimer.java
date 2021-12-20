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

import com.android.systemui.CoreStartable;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.ComplicationHostView;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.appwidgets.dagger.AppWidgetComponent;

import javax.inject.Inject;

/**
 * {@link ComplicationPrimer} reads the configured AppWidget Complications from resources on start
 * and populates them into the {@link DreamOverlayStateController}.
 */
public class ComplicationPrimer extends CoreStartable {
    private final Resources mResources;
    private final DreamOverlayStateController mDreamOverlayStateController;
    private final AppWidgetComponent.Factory mComponentFactory;

    @Inject
    public ComplicationPrimer(Context context, @Main Resources resources,
            DreamOverlayStateController overlayStateController,
            AppWidgetComponent.Factory appWidgetOverlayFactory) {
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
     * Generates the {@link ComplicationHostView.LayoutParams} for a given gravity. Default
     * dimension constraints are also included in the params.
     * @param gravity The gravity for the layout as defined by {@link Gravity}.
     * @param resources The resourcs from which default dimensions will be extracted from.
     * @return {@link ComplicationHostView.LayoutParams} representing the provided gravity and
     *         default parameters.
     */
    private static ComplicationHostView.LayoutParams getLayoutParams(int gravity,
            Resources resources) {
        final ComplicationHostView.LayoutParams params = new ComplicationHostView.LayoutParams(
                ComplicationHostView.LayoutParams.MATCH_CONSTRAINT,
                ComplicationHostView.LayoutParams.MATCH_CONSTRAINT);

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
                resources.getFloat(R.dimen.config_dreamComplicationHeightPercent);
        params.matchConstraintPercentWidth =
                resources.getFloat(R.dimen.config_dreamComplicationWidthPercent);

        return params;
    }

    /**
     * Helper method for loading widgets based on configuration.
     */
    private void loadDefaultWidgets() {
        final int[] positions = mResources.getIntArray(R.array.config_dreamComplicationPositions);
        final String[] components =
                mResources.getStringArray(R.array.config_dreamAppWidgetComplications);

        for (int i = 0; i < Math.min(positions.length, components.length); i++) {
            final AppWidgetComponent component = mComponentFactory.build(
                    ComponentName.unflattenFromString(components[i]),
                    getLayoutParams(positions[i], mResources));

            mDreamOverlayStateController.addComplication(
                    component.getAppWidgetComplicationProvider());
        }
    }
}
