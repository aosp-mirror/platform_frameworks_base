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

import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManagerPolicyConstants.APPLICATION_LAYER;
import static android.window.DisplayAreaOrganizer.FEATURE_IME_PLACEHOLDER;

import static com.android.server.wm.DisplayAreaPolicyBuilder.Feature;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.util.Slog;

import com.android.server.policy.WindowManagerPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Root of a {@link DisplayArea} hierarchy. It can be either the {@link DisplayContent} as the root
 * of the whole logical display, or a {@link DisplayAreaGroup} as the root of a partition of the
 * logical display.
 */
class RootDisplayArea extends DisplayArea.Dimmable {

    /** {@link Feature} that are supported in this {@link DisplayArea} hierarchy. */
    List<DisplayAreaPolicyBuilder.Feature> mFeatures;

    /**
     * Mapping from policy supported {@link Feature} to list of {@link DisplayArea} created to cover
     * all the window types that the {@link Feature} will be applied to.
     */
    Map<Feature, List<DisplayArea<WindowContainer>>> mFeatureToDisplayAreas;

    /** Mapping from window layer to {@link DisplayArea.Tokens} that holds windows on that layer. */
    private DisplayArea.Tokens[] mAreaForLayer;

    /** Whether the hierarchy has been built. */
    private boolean mHasBuiltHierarchy;

    RootDisplayArea(WindowManagerService wms, String name, int featureId) {
        super(wms, Type.ANY, name, featureId);
    }

    @Override
    RootDisplayArea getRootDisplayArea() {
        return this;
    }

    @Override
    RootDisplayArea asRootDisplayArea() {
        return this;
    }

    /** Whether the orientation (based on dimensions) of this root is different from the Display. */
    boolean isOrientationDifferentFromDisplay() {
        return false;
    }

    /**
     * Places the IME container below this root, so that it's bounds and config will be updated to
     * match the root.
     *
     * @return {@code true} if the IME container is reparented to this root.
     */
    boolean placeImeContainer(DisplayArea.Tokens imeContainer) {
        final RootDisplayArea previousRoot = imeContainer.getRootDisplayArea();

        List<Feature> features = mFeatures;
        for (int i = 0; i < features.size(); i++) {
            Feature feature = features.get(i);
            if (feature.getId() == FEATURE_IME_PLACEHOLDER) {
                List<DisplayArea<WindowContainer>> imeDisplayAreas =
                        mFeatureToDisplayAreas.get(feature);
                if (imeDisplayAreas.size() != 1) {
                    throw new IllegalStateException("There must be exactly one DisplayArea for the "
                            + "FEATURE_IME_PLACEHOLDER");
                }

                previousRoot.updateImeContainerForLayers(null /* imeContainer */);
                imeContainer.reparent(imeDisplayAreas.get(0), POSITION_TOP);
                updateImeContainerForLayers(imeContainer);
                return true;
            }
        }

        // Some device UX may not have the need to update the IME bounds and position for IME target
        // in a child DisplayArea, so instead of throwing exception, we just allow the IME container
        // to remain in its previous root.
        if (!isDescendantOf(previousRoot)) {
            // When this RootDisplayArea is a descendant of the current RootDisplayArea, it will be
            // at the APPLICATION_LAYER, and the IME container will always be on top and have bounds
            // equal or larger than the input target.
            // If it is not a descendant, the DisplayAreaPolicy owner should make sure the IME is
            // working correctly. Print a warning in case they are not.
            Slog.w(TAG_WM, "The IME target is not in the same root as the IME container, but there "
                    + "is no DisplayArea of FEATURE_IME_PLACEHOLDER in the target RootDisplayArea");
        }
        return false;
    }

    /**
     * Finds the {@link DisplayArea.Tokens} in {@code mAreaForLayer} that this type of window
     * should be attached to.
     * <p>
     * Note that in most cases, users are expected to call
     * {@link DisplayContent#findAreaForToken(WindowToken)} to find a {@link DisplayArea} in
     * {@link DisplayContent} level instead of calling this inner method.
     * </p>
     */
    @Nullable
    DisplayArea.Tokens findAreaForTokenInLayer(WindowToken token) {
        return findAreaForWindowTypeInLayer(token.windowType, token.mOwnerCanManageAppTokens,
                token.mRoundedCornerOverlay);
    }

    /** @see #findAreaForTokenInLayer(WindowToken)  */
    @Nullable
    DisplayArea.Tokens findAreaForWindowTypeInLayer(int windowType, boolean ownerCanManageAppTokens,
            boolean roundedCornerOverlay) {
        int windowLayerFromType = mWmService.mPolicy.getWindowLayerFromTypeLw(windowType,
                ownerCanManageAppTokens, roundedCornerOverlay);
        if (windowLayerFromType == APPLICATION_LAYER) {
            throw new IllegalArgumentException(
                    "There shouldn't be WindowToken on APPLICATION_LAYER");
        }
        return mAreaForLayer[windowLayerFromType];
    }

    /** Callback after {@link DisplayArea} hierarchy has been built. */
    void onHierarchyBuilt(ArrayList<Feature> features, DisplayArea.Tokens[] areaForLayer,
            Map<Feature, List<DisplayArea<WindowContainer>>> featureToDisplayAreas) {
        if (mHasBuiltHierarchy) {
            throw new IllegalStateException("Root should only build the hierarchy once");
        }
        mHasBuiltHierarchy = true;
        mFeatures = Collections.unmodifiableList(features);
        mAreaForLayer = areaForLayer;
        mFeatureToDisplayAreas = featureToDisplayAreas;
    }

    private void updateImeContainerForLayers(@Nullable DisplayArea.Tokens imeContainer) {
        final WindowManagerPolicy policy = mWmService.mPolicy;
        mAreaForLayer[policy.getWindowLayerFromTypeLw(TYPE_INPUT_METHOD)] = imeContainer;
        mAreaForLayer[policy.getWindowLayerFromTypeLw(TYPE_INPUT_METHOD_DIALOG)] = imeContainer;
    }
}
