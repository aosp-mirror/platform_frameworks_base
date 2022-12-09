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

import static com.android.systemui.dreams.complication.dagger.ComplicationHostViewModule.SCOPED_COMPLICATIONS_LAYOUT;
import static com.android.systemui.dreams.complication.dagger.ComplicationModule.SCOPED_COMPLICATIONS_MODEL;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.Debug;
import android.util.Log;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.LifecycleOwner;

import com.android.systemui.util.ViewController;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * The {@link ComplicationHostViewController} is responsible for displaying complications within
 * a given container. It monitors the available {@link Complication} instances from
 * {@link com.android.systemui.dreams.DreamOverlayStateController} and inserts/removes them through
 * a {@link ComplicationLayoutEngine}.
 */
public class ComplicationHostViewController extends ViewController<ConstraintLayout> {
    private static final String TAG = "ComplicationHostVwCtrl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ComplicationLayoutEngine mLayoutEngine;
    private final LifecycleOwner mLifecycleOwner;
    private final ComplicationCollectionViewModel mComplicationCollectionViewModel;
    private final HashMap<ComplicationId, Complication.ViewHolder> mComplications = new HashMap<>();

    @Inject
    protected ComplicationHostViewController(
            @Named(SCOPED_COMPLICATIONS_LAYOUT) ConstraintLayout view,
            ComplicationLayoutEngine layoutEngine,
            LifecycleOwner lifecycleOwner,
            @Named(SCOPED_COMPLICATIONS_MODEL) ComplicationCollectionViewModel viewModel) {
        super(view);
        mLayoutEngine = layoutEngine;
        mLifecycleOwner = lifecycleOwner;
        mComplicationCollectionViewModel = viewModel;
    }

    @Override
    protected void onInit() {
        super.onInit();
        mComplicationCollectionViewModel.getComplications().observe(mLifecycleOwner,
                complicationViewModels -> updateComplications(complicationViewModels));
    }

    /**
     * Returns the region in display space occupied by complications. Touches in this region
     * (composed of a collection of individual rectangular regions) should be directed to the
     * complications rather than the region underneath.
     */
    public Region getTouchRegions() {
        final Region region = new Region();
        final Rect rect = new Rect();
        final int childCount = mView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mView.getChildAt(i);
            if (child.getGlobalVisibleRect(rect)) {
                region.op(rect, Region.Op.UNION);
            }
        }

        return region;
    }

    private void updateComplications(Collection<ComplicationViewModel> complications) {
        if (DEBUG) {
            Log.d(TAG, "updateComplications called. Callers = " + Debug.getCallers(25));
            Log.d(TAG, "    mComplications = " + mComplications.toString());
            Log.d(TAG, "    complications = " + complications.toString());
        }
        final Collection<ComplicationId> ids = complications.stream()
                .map(complicationViewModel -> complicationViewModel.getId())
                .collect(Collectors.toSet());

        final Collection<ComplicationId> removedComplicationIds =
                mComplications.keySet().stream()
                        .filter(complicationId -> !ids.contains(complicationId))
                        .collect(Collectors.toSet());

        // Trim removed complications
        removedComplicationIds.forEach(complicationId -> {
            mLayoutEngine.removeComplication(complicationId);
            mComplications.remove(complicationId);
        });

        // Add new complications
        final Collection<ComplicationViewModel> newComplications = complications
                .stream()
                .filter(complication -> !mComplications.containsKey(complication.getId()))
                .collect(Collectors.toSet());

        newComplications
                .forEach(complication -> {
                    final ComplicationId id = complication.getId();
                    final Complication.ViewHolder viewHolder = complication.getComplication()
                            .createView(complication);
                    mComplications.put(id, viewHolder);
                    if (viewHolder.getView().getParent() != null) {
                        Log.e(TAG, "View for complication "
                                + complication.getComplication().getClass()
                                + " already has a parent. Make sure not to reuse complication "
                                + "views!");
                    }
                    mLayoutEngine.addComplication(id, viewHolder.getView(),
                            viewHolder.getLayoutParams(), viewHolder.getCategory());
                });
    }

    @Override
    protected void onViewAttached() {
    }

    @Override
    protected void onViewDetached() {
    }

    /**
     * Exposes the associated {@link View}. Since this {@link View} is instantiated through dagger
     * in the {@link ComplicationHostViewController} constructor, the
     * {@link ComplicationHostViewController} is responsible for surfacing it so that it can be
     * included in the parent view hierarchy.
     */
    public View getView() {
        return mView;
    }

    /**
     * Gets an unordered list of all the views at a particular position.
     */
    public List<View> getViewsAtPosition(@ComplicationLayoutParams.Position int position) {
        return mLayoutEngine.getViewsAtPosition(position);
    }
}
