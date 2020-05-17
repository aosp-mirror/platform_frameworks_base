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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManagerPolicyConstants.APPLICATION_LAYER;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.policy.WindowManagerPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A builder for instantiating a complex {@link DisplayAreaPolicy}
 *
 * <p>Given a set of features (that each target a set of window types), it builds the necessary
 * DisplayArea hierarchy.
 *
 * <p>Example: <br />
 *
 * <pre>
 *     // Feature for targeting everything below the magnification overlay:
 *     new DisplayAreaPolicyBuilder(...)
 *             .addFeature(new Feature.Builder(..., "Magnification")
 *                     .upTo(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
 *                     .build())
 *             .build(...)
 *
 *     // Builds a policy with the following hierarchy:
 *      - DisplayArea.Root
 *        - Magnification
 *          - DisplayArea.Tokens (Wallpapers are attached here)
 *          - TaskDisplayArea
 *          - DisplayArea.Tokens (windows above Tasks up to IME are attached here)
 *          - ImeContainers
 *          - DisplayArea.Tokens (windows above IME up to TYPE_ACCESSIBILITY_OVERLAY attached here)
 *        - DisplayArea.Tokens (TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY and up are attached here)
 *
 * </pre>
 *
 * // TODO(display-area): document more complex scenarios where we need multiple areas per feature.
 */
class DisplayAreaPolicyBuilder {

    private final ArrayList<Feature> mFeatures = new ArrayList<>();

    /**
     * A feature that requires {@link DisplayArea DisplayArea(s)}.
     */
    static class Feature {
        private final String mName;
        private final int mId;
        private final boolean[] mWindowLayers;

        private Feature(String name, int id, boolean[] windowLayers) {
            mName = name;
            mId = id;
            mWindowLayers = windowLayers;
        }

        /**
         * Returns the id of the feature.
         *
         * Must be unique among the features added to a {@link DisplayAreaPolicyBuilder}.
         *
         * @see android.window.DisplayAreaOrganizer#FEATURE_SYSTEM_FIRST
         * @see android.window.DisplayAreaOrganizer#FEATURE_VENDOR_FIRST
         */
        public int getId() {
            return mId;
        }

        @Override
        public String toString() {
            return "Feature(\"" + mName + "\", " + mId + '}';
        }

        static class Builder {
            private final WindowManagerPolicy mPolicy;
            private final String mName;
            private final int mId;
            private final boolean[] mLayers;

            /**
             * Build a new feature that applies to a set of window types as specified by the builder
             * methods.
             *
             * <p>The set of types is updated iteratively in the order of the method invocations.
             * For example, {@code all().except(TYPE_STATUS_BAR)} expresses that a feature should
             * apply to all types except TYPE_STATUS_BAR.
             *
             * The builder starts out with the feature not applying to any types.
             *
             * @param name the name of the feature.
             * @param id of the feature. {@see Feature#getId}
             */
            Builder(WindowManagerPolicy policy, String name, int id) {
                mPolicy = policy;
                mName = name;
                mId = id;
                mLayers = new boolean[mPolicy.getMaxWindowLayer()];
            }

            /**
             * Set that the feature applies to all window types.
             */
            Builder all() {
                Arrays.fill(mLayers, true);
                return this;
            }

            /**
             * Set that the feature applies to the given window types.
             */
            Builder and(int... types) {
                for (int i = 0; i < types.length; i++) {
                    int type = types[i];
                    set(type, true);
                }
                return this;
            }

            /**
             * Set that the feature does not apply to the given window types.
             */
            Builder except(int... types) {
                for (int i = 0; i < types.length; i++) {
                    int type = types[i];
                    set(type, false);
                }
                return this;
            }

            /**
             * Set that the feature applies window types that are layerd at or below the layer of
             * the given window type.
             */
            Builder upTo(int typeInclusive) {
                final int max = layerFromType(typeInclusive, false);
                for (int i = 0; i < max; i++) {
                    mLayers[i] = true;
                }
                set(typeInclusive, true);
                return this;
            }

            Feature build() {
                return new Feature(mName, mId, mLayers.clone());
            }

            private void set(int type, boolean value) {
                mLayers[layerFromType(type, true)] = value;
                if (type == TYPE_APPLICATION_OVERLAY) {
                    mLayers[layerFromType(type, true)] = value;
                    mLayers[layerFromType(TYPE_SYSTEM_ALERT, false)] = value;
                    mLayers[layerFromType(TYPE_SYSTEM_OVERLAY, false)] = value;
                    mLayers[layerFromType(TYPE_SYSTEM_ERROR, false)] = value;
                }
            }

            private int layerFromType(int type, boolean internalWindows) {
                return mPolicy.getWindowLayerFromTypeLw(type, internalWindows);
            }
        }
    }

    static class Result extends DisplayAreaPolicy {
        private static final int LEAF_TYPE_TASK_CONTAINERS = 1;
        private static final int LEAF_TYPE_IME_CONTAINERS = 2;
        private static final int LEAF_TYPE_TOKENS = 0;

        private final int mMaxWindowLayer = mWmService.mPolicy.getMaxWindowLayer();

        private final ArrayList<Feature> mFeatures;
        private final Map<Feature, List<DisplayArea<? extends WindowContainer>>> mAreas;
        private final DisplayArea.Tokens[] mAreaForLayer = new DisplayArea.Tokens[mMaxWindowLayer];

        Result(WindowManagerService wmService, DisplayContent content, DisplayArea.Root root,
                DisplayArea<? extends WindowContainer> imeContainer,
                List<TaskDisplayArea> taskDisplayAreas, ArrayList<Feature> features) {
            super(wmService, content, root, imeContainer, taskDisplayAreas);
            mFeatures = features;
            mAreas = new HashMap<>(features.size());
            for (int i = 0; i < mFeatures.size(); i++) {
                mAreas.put(mFeatures.get(i), new ArrayList<>());
            }
        }

        @Override
        public void attachDisplayAreas() {
            // This method constructs the layer hierarchy with the following properties:
            // (1) Every feature maps to a set of DisplayAreas
            // (2) After adding a window, for every feature the window's type belongs to,
            //     it is a descendant of one of the corresponding DisplayAreas of the feature.
            // (3) Z-order is maintained, i.e. if z-range(area) denotes the set of layers of windows
            //     within a DisplayArea:
            //      for every pair of DisplayArea siblings (a,b), where a is below b, it holds that
            //      max(z-range(a)) <= min(z-range(b))
            //
            // The algorithm below iteratively creates such a hierarchy:
            //  - Initially, all windows are attached to the root.
            //  - For each feature we create a set of DisplayAreas, by looping over the layers
            //    - if the feature does apply to the current layer, we need to find a DisplayArea
            //      for it to satisfy (2)
            //      - we can re-use the previous layer's area if:
            //         the current feature also applies to the previous layer, (to satisfy (3))
            //         and the last feature that applied to the previous layer is the same as
            //           the last feature that applied to the current layer (to satisfy (2))
            //      - otherwise we create a new DisplayArea below the last feature that applied
            //        to the current layer


            PendingArea[] areaForLayer = new PendingArea[mMaxWindowLayer];
            final PendingArea root = new PendingArea(null, 0, null);
            Arrays.fill(areaForLayer, root);

            final int size = mFeatures.size();
            for (int i = 0; i < size; i++) {
                PendingArea featureArea = null;
                for (int layer = 0; layer < mMaxWindowLayer; layer++) {
                    final Feature feature = mFeatures.get(i);
                    if (feature.mWindowLayers[layer]) {
                        if (featureArea == null || featureArea.mParent != areaForLayer[layer]) {
                            // No suitable DisplayArea - create a new one under the previous area
                            // for this layer.
                            featureArea = new PendingArea(feature, layer, areaForLayer[layer]);
                            areaForLayer[layer].mChildren.add(featureArea);
                        }
                        areaForLayer[layer] = featureArea;
                    } else {
                        featureArea = null;
                    }
                }
            }

            PendingArea leafArea = null;
            int leafType = LEAF_TYPE_TOKENS;
            for (int layer = 0; layer < mMaxWindowLayer; layer++) {
                int type = typeOfLayer(mWmService.mPolicy, layer);
                if (leafArea == null || leafArea.mParent != areaForLayer[layer]
                        || type != leafType) {
                    leafArea = new PendingArea(null, layer, areaForLayer[layer]);
                    areaForLayer[layer].mChildren.add(leafArea);
                    leafType = type;
                    if (leafType == LEAF_TYPE_TASK_CONTAINERS) {
                        addTaskDisplayAreasToLayer(areaForLayer[layer], layer);
                    } else if (leafType == LEAF_TYPE_IME_CONTAINERS) {
                        leafArea.mExisting = mImeContainer;
                    }
                }
                leafArea.mMaxLayer = layer;
            }
            root.computeMaxLayer();
            root.instantiateChildren(mRoot, mAreaForLayer, 0, mAreas);
        }

        /** Adds all task display areas to the specified layer */
        private void addTaskDisplayAreasToLayer(PendingArea parentPendingArea, int layer) {
            final int count = mTaskDisplayAreas.size();
            for (int i = 0; i < count; i++) {
                PendingArea leafArea = new PendingArea(null, layer, parentPendingArea);
                leafArea.mExisting = mTaskDisplayAreas.get(i);
                leafArea.mMaxLayer = layer;
                parentPendingArea.mChildren.add(leafArea);
            }
        }

        @Override
        public void addWindow(WindowToken token) {
            DisplayArea.Tokens area = findAreaForToken(token);
            area.addChild(token);
        }

        @VisibleForTesting
        DisplayArea.Tokens findAreaForToken(WindowToken token) {
            int windowLayerFromType = token.getWindowLayerFromType();
            if (windowLayerFromType == APPLICATION_LAYER) {
                // TODO(display-area): Better handle AboveAppWindows in APPLICATION_LAYER
                windowLayerFromType += 1;
            } else if (token.mRoundedCornerOverlay) {
                windowLayerFromType = mMaxWindowLayer - 1;
            }
            return mAreaForLayer[windowLayerFromType];
        }

        @VisibleForTesting
        ArrayList<Feature> getFeatures() {
            return mFeatures;
        }

        @Override
        public List<DisplayArea<? extends WindowContainer>> getDisplayAreas(int featureId) {
            for (int i = 0; i < mFeatures.size(); i++) {
                Feature feature = mFeatures.get(i);
                if (feature.getId() == featureId) {
                    return getDisplayAreas(feature);
                }
            }
            return new ArrayList<>();
        }

        public List<DisplayArea<? extends WindowContainer>> getDisplayAreas(Feature feature) {
            return mAreas.get(feature);
        }

        private static int typeOfLayer(WindowManagerPolicy policy, int layer) {
            if (layer == APPLICATION_LAYER) {
                return LEAF_TYPE_TASK_CONTAINERS;
            } else if (layer == policy.getWindowLayerFromTypeLw(TYPE_INPUT_METHOD)
                    || layer == policy.getWindowLayerFromTypeLw(TYPE_INPUT_METHOD_DIALOG)) {
                return LEAF_TYPE_IME_CONTAINERS;
            } else {
                return LEAF_TYPE_TOKENS;
            }
        }
    }

    DisplayAreaPolicyBuilder addFeature(Feature feature) {
        mFeatures.add(feature);
        return this;
    }

    protected List<Feature> getFeatures() {
        return mFeatures;
    }

    Result build(WindowManagerService wmService,
            DisplayContent content, DisplayArea.Root root,
            DisplayArea<? extends WindowContainer> imeContainer,
            List<TaskDisplayArea> taskDisplayAreas) {

        return new Result(wmService, content, root, imeContainer, taskDisplayAreas, new ArrayList<>(
                mFeatures));
    }

    static class PendingArea {
        final int mMinLayer;
        final ArrayList<PendingArea> mChildren = new ArrayList<>();
        final Feature mFeature;
        final PendingArea mParent;
        int mMaxLayer;
        DisplayArea mExisting;

        PendingArea(Feature feature,
                int minLayer,
                PendingArea parent) {
            mMinLayer = minLayer;
            mFeature = feature;
            mParent = parent;
        }

        int computeMaxLayer() {
            for (int i = 0; i < mChildren.size(); i++) {
                mMaxLayer = Math.max(mMaxLayer, mChildren.get(i).computeMaxLayer());
            }
            return mMaxLayer;
        }

        void instantiateChildren(DisplayArea<DisplayArea> parent,
                DisplayArea.Tokens[] areaForLayer, int level, Map<Feature, List<DisplayArea<?
                extends WindowContainer>>> areas) {
            mChildren.sort(Comparator.comparingInt(pendingArea -> pendingArea.mMinLayer));
            for (int i = 0; i < mChildren.size(); i++) {
                final PendingArea child = mChildren.get(i);
                final DisplayArea area = child.createArea(parent, areaForLayer);
                parent.addChild(area, WindowContainer.POSITION_TOP);
                if (child.mFeature != null) {
                    areas.get(child.mFeature).add(area);
                }
                child.instantiateChildren(area, areaForLayer, level + 1, areas);
            }
        }

        private DisplayArea createArea(DisplayArea<DisplayArea> parent,
                DisplayArea.Tokens[] areaForLayer) {
            if (mExisting != null) {
                return mExisting;
            }
            DisplayArea.Type type;
            if (mMinLayer > APPLICATION_LAYER) {
                type = DisplayArea.Type.ABOVE_TASKS;
            } else if (mMaxLayer < APPLICATION_LAYER) {
                type = DisplayArea.Type.BELOW_TASKS;
            } else {
                type = DisplayArea.Type.ANY;
            }
            if (mFeature == null) {
                final DisplayArea.Tokens leaf = new DisplayArea.Tokens(parent.mWmService, type,
                        "Leaf:" + mMinLayer + ":" + mMaxLayer);
                for (int i = mMinLayer; i <= mMaxLayer; i++) {
                    areaForLayer[i] = leaf;
                }
                return leaf;
            } else {
                return new DisplayArea(parent.mWmService, type, mFeature.mName + ":"
                        + mMinLayer + ":" + mMaxLayer, mFeature.mId);
            }
        }
    }
}
