/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.WindowManagerPolicyConstants.APPLICATION_LAYER;

import static com.android.server.wm.DisplayAreaPolicyBuilder.Feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Root of a {@link DisplayArea} hierarchy. It can be either the {@link DisplayContent} as the root
 * of the whole logical display, or the root of a {@link DisplayArea} group.
 */
class RootDisplayArea extends DisplayArea<DisplayArea> {

    /** {@link Feature} that are supported in this {@link DisplayArea} hierarchy. */
    List<DisplayAreaPolicyBuilder.Feature> mFeatures;

    /**
     * Mapping from policy supported {@link Feature} to list of {@link DisplayArea} created to cover
     * all the window types that the {@link Feature} will be applied to.
     */
    Map<Feature, List<DisplayArea<? extends WindowContainer>>> mFeatureToDisplayAreas;

    /** Mapping from window layer to {@link DisplayArea.Tokens} that holds windows on that layer. */
    private DisplayArea.Tokens[] mAreaForLayer;

    /** Whether the hierarchy has been built. */
    private boolean mHasBuiltHierarchy;

    RootDisplayArea(WindowManagerService wms, String name, int featureId) {
        super(wms, Type.ANY, name, featureId);
    }

    /** Finds the {@link DisplayArea.Tokens} that this type of window should be attached to. */
    DisplayArea.Tokens findAreaForToken(WindowToken token) {
        int windowLayerFromType = token.getWindowLayerFromType();
        if (windowLayerFromType == APPLICATION_LAYER) {
            throw new IllegalArgumentException(
                    "There shouldn't be WindowToken on APPLICATION_LAYER");
        } else if (token.mRoundedCornerOverlay) {
            windowLayerFromType = mAreaForLayer.length - 1;
        }
        return mAreaForLayer[windowLayerFromType];
    }

    /** Callback after {@link DisplayArea} hierarchy has been built. */
    void onHierarchyBuilt(ArrayList<Feature> features, DisplayArea.Tokens[] areaForLayer,
            Map<Feature, List<DisplayArea<? extends WindowContainer>>> featureToDisplayAreas) {
        if (mHasBuiltHierarchy) {
            throw new IllegalStateException("Root should only build the hierarchy once");
        }
        mHasBuiltHierarchy = true;
        mFeatures = Collections.unmodifiableList(features);
        mAreaForLayer = areaForLayer;
        mFeatureToDisplayAreas = featureToDisplayAreas;
    }
}
