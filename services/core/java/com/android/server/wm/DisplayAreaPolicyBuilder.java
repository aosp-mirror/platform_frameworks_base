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
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;

import android.annotation.Nullable;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.policy.WindowManagerPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A builder for instantiating a complex {@link DisplayAreaPolicy}
 *
 * <p>Given a set of features (that each target a set of window types), it builds the necessary
 * {@link DisplayArea} hierarchy.
 *
 * <p>Example:
 *
 * <pre class="prettyprint">
 *      // Build root hierarchy of the logical display.
 *      DisplayAreaPolicyBuilder.HierarchyBuilder rootHierarchy =
 *          new DisplayAreaPolicyBuilder.HierarchyBuilder(root)
 *              // Feature for targeting everything below the magnification overlay
 *              .addFeature(new DisplayAreaPolicyBuilder.Feature.Builder(wmService.mPolicy,
 *                             "WindowedMagnification", FEATURE_WINDOWED_MAGNIFICATION)
 *                             .upTo(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
 *                             .except(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
 *                             // Make the DA dimmable so that the magnify window also mirrors the
 *                             // dim layer
 *                             .setNewDisplayAreaSupplier(DisplayArea.Dimmable::new)
 *                             .build())
 *              .setImeContainer(imeContainer)
 *              .setTaskDisplayAreas(rootTdaList);
 *
 *      // Build root hierarchy of front and rear DisplayAreaGroup.
 *      RootDisplayArea frontRoot = new RootDisplayArea(wmService, "FrontRoot", FEATURE_FRONT_ROOT);
 *      DisplayAreaPolicyBuilder.HierarchyBuilder frontGroupHierarchy =
 *          new DisplayAreaPolicyBuilder.HierarchyBuilder(frontRoot)
 *              // (Optional) .addFeature(...)
 *              .setTaskDisplayAreas(frontTdaList);
 *
 *      RootDisplayArea rearRoot = new RootDisplayArea(wmService, "RearRoot", FEATURE_REAR_ROOT);
 *      DisplayAreaPolicyBuilder.HierarchyBuilder rearGroupHierarchy =
 *          new DisplayAreaPolicyBuilder.HierarchyBuilder(rearRoot)
 *              // (Optional) .addFeature(...)
 *              .setTaskDisplayAreas(rearTdaList);
 *
 *      // Define the function to select root for window to attach.
 *      BiFunction<WindowToken, Bundle, RootDisplayArea> selectRootForWindowFunc =
 *                (windowToken, bundle) -> {
 *                    if (bundle == null) {
 *                        return root;
 *                    }
 *                    // OEMs need to define the condition.
 *                    if (...) {
 *                        return frontRoot;
 *                    }
 *                    if (...) {
 *                        return rearRoot;
 *                    }
 *                    return root;
 *                };
 *
 *      return new DisplayAreaPolicyBuilder()
 *                .setRootHierarchy(rootHierarchy)
 *                .addDisplayAreaGroupHierarchy(frontGroupHierarchy)
 *                .addDisplayAreaGroupHierarchy(rearGroupHierarchy)
 *                .setSelectRootForWindowFunc(selectRootForWindowFunc)
 *                .build(wmService, content);
 * </pre>
 *
 * This builds a policy with the following hierarchy:
 * <pre class="prettyprint">
 *      - RootDisplayArea (DisplayContent)
 *          - WindowedMagnification
 *              - DisplayArea.Tokens (Wallpapers can be attached here)
 *              - TaskDisplayArea
 *              - RootDisplayArea (FrontRoot)
 *                  - DisplayArea.Tokens (Wallpapers can be attached here)
 *                  - TaskDisplayArea
 *                  - DisplayArea.Tokens (windows above Tasks up to IME can be attached here)
 *                  - DisplayArea.Tokens (windows above IME can be attached here)
 *              - RootDisplayArea (RearRoot)
 *                  - DisplayArea.Tokens (Wallpapers can be attached here)
 *                  - TaskDisplayArea
 *                  - DisplayArea.Tokens (windows above Tasks up to IME can be attached here)
 *                  - DisplayArea.Tokens (windows above IME can be attached here)
 *              - DisplayArea.Tokens (windows above Tasks up to IME can be attached here)
 *              - ImeContainers
 *              - DisplayArea.Tokens (windows above IME up to TYPE_ACCESSIBILITY_OVERLAY can be
 *                                    attached here)
 *          - DisplayArea.Tokens (TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY and up can be attached
 *                                here)
 * </pre>
 * When a {@link WindowToken} of Wallpaper needs to be attached, the policy will call the OEM
 * defined {@link #mSelectRootForWindowFunc} to get a {@link RootDisplayArea}. It will then place
 * the window to the corresponding {@link DisplayArea.Tokens} under the returned root
 * {@link RootDisplayArea}.
 */
class DisplayAreaPolicyBuilder {
    @Nullable private HierarchyBuilder mRootHierarchyBuilder;
    private ArrayList<HierarchyBuilder> mDisplayAreaGroupHierarchyBuilders = new ArrayList<>();

    /**
     * When a window is created, the policy will use this function to select the
     * {@link RootDisplayArea} to place that window in. The selected root can be either the one of
     * the {@link #mRootHierarchyBuilder} or the one of any of the
     * {@link #mDisplayAreaGroupHierarchyBuilders}.
     **/
    @Nullable private BiFunction<WindowToken, Bundle, RootDisplayArea> mSelectRootForWindowFunc;

    /** Defines the root hierarchy for the whole logical display. */
    DisplayAreaPolicyBuilder setRootHierarchy(HierarchyBuilder rootHierarchyBuilder) {
        mRootHierarchyBuilder = rootHierarchyBuilder;
        return this;
    }

    /**
     * Defines a DisplayAreaGroup hierarchy. Its root will be added as a child of the root
     * hierarchy.
     */
    DisplayAreaPolicyBuilder addDisplayAreaGroupHierarchy(
            HierarchyBuilder displayAreaGroupHierarchy) {
        mDisplayAreaGroupHierarchyBuilders.add(displayAreaGroupHierarchy);
        return this;
    }

    /** The policy will use this function to find the root to place windows in. */
    DisplayAreaPolicyBuilder setSelectRootForWindowFunc(
            BiFunction<WindowToken, Bundle, RootDisplayArea> selectRootForWindowFunc) {
        mSelectRootForWindowFunc = selectRootForWindowFunc;
        return this;
    }

    /** Makes sure the setting meets the requirement. */
    private void validate() {
        if (mRootHierarchyBuilder == null) {
            throw new IllegalStateException("Root must be set for the display area policy.");
        }

        boolean containsImeContainer = mRootHierarchyBuilder.mImeContainer != null;
        boolean containsDefaultTda = containsDefaultTaskDisplayArea(mRootHierarchyBuilder);
        for (int i = 0; i < mDisplayAreaGroupHierarchyBuilders.size(); i++) {
            HierarchyBuilder hierarchyBuilder = mDisplayAreaGroupHierarchyBuilders.get(i);
            if (hierarchyBuilder.mTaskDisplayAreas.isEmpty()) {
                throw new IllegalStateException(
                        "DisplayAreaGroup must contain at least one TaskDisplayArea.");
            }

            containsImeContainer = containsImeContainer || hierarchyBuilder.mImeContainer != null;
            if (containsDefaultTda) {
                if (containsDefaultTaskDisplayArea(hierarchyBuilder)) {
                    throw new IllegalStateException("Only one TaskDisplayArea can have the feature "
                            + "of FEATURE_DEFAULT_TASK_CONTAINER");
                }
            } else {
                containsDefaultTda = containsDefaultTaskDisplayArea(hierarchyBuilder);
            }
        }

        if (!containsImeContainer) {
            throw new IllegalStateException("IME container must be set.");
        }

        if (!containsDefaultTda) {
            throw new IllegalStateException("There must be a default TaskDisplayArea.");
        }
    }

    /** Checks if the given hierarchy contains the default {@link TaskDisplayArea}. */
    private static boolean containsDefaultTaskDisplayArea(HierarchyBuilder displayAreaHierarchy) {
        for (int i = 0; i < displayAreaHierarchy.mTaskDisplayAreas.size(); i++) {
            if (displayAreaHierarchy.mTaskDisplayAreas.get(i).mFeatureId
                    == FEATURE_DEFAULT_TASK_CONTAINER) {
                return true;
            }
        }
        return false;
    }

    Result build(WindowManagerService wmService) {
        validate();

        // Attach DA group roots to screen hierarchy before adding windows to group hierarchies.
        mRootHierarchyBuilder.build(mDisplayAreaGroupHierarchyBuilders);
        List<RootDisplayArea> displayAreaGroupRoots = new ArrayList<>(
                mDisplayAreaGroupHierarchyBuilders.size());
        for (int i = 0; i < mDisplayAreaGroupHierarchyBuilders.size(); i++) {
            HierarchyBuilder hierarchyBuilder = mDisplayAreaGroupHierarchyBuilders.get(i);
            hierarchyBuilder.build();
            displayAreaGroupRoots.add(hierarchyBuilder.mRoot);
        }
        return new Result(wmService, mRootHierarchyBuilder.mRoot, displayAreaGroupRoots,
                mSelectRootForWindowFunc);
    }

    /**
     *  Builder to define {@link Feature} and {@link DisplayArea} hierarchy under a
     * {@link RootDisplayArea}
     */
    static class HierarchyBuilder {
        private static final int LEAF_TYPE_TASK_CONTAINERS = 1;
        private static final int LEAF_TYPE_IME_CONTAINERS = 2;
        private static final int LEAF_TYPE_TOKENS = 0;

        private final RootDisplayArea mRoot;
        private final ArrayList<DisplayAreaPolicyBuilder.Feature> mFeatures = new ArrayList<>();
        private final ArrayList<TaskDisplayArea> mTaskDisplayAreas = new ArrayList<>();
        @Nullable
        private DisplayArea<? extends WindowContainer> mImeContainer;

        HierarchyBuilder(RootDisplayArea root) {
            mRoot = root;
        }

        /** Adds {@link Feature} that applies to layers under this container. */
        HierarchyBuilder addFeature(DisplayAreaPolicyBuilder.Feature feature) {
            mFeatures.add(feature);
            return this;
        }

        /**
         * Sets {@link TaskDisplayArea} that are children of this hierarchy root.
         * {@link DisplayArea} group must have at least one {@link TaskDisplayArea}.
         */
        HierarchyBuilder setTaskDisplayAreas(List<TaskDisplayArea> taskDisplayAreas) {
            mTaskDisplayAreas.clear();
            mTaskDisplayAreas.addAll(taskDisplayAreas);
            return this;
        }

        /** Sets IME container as a child of this hierarchy root. */
        HierarchyBuilder setImeContainer(DisplayArea<? extends WindowContainer> imeContainer) {
            mImeContainer = imeContainer;
            return this;
        }

        /** Builds the {@link DisplayArea} hierarchy below root. */
        private void build() {
            build(null /* displayAreaGroupHierarchyBuilders */);
        }

        /**
         * Builds the {@link DisplayArea} hierarchy below root. And adds the roots of those
         * {@link HierarchyBuilder} as children.
         */
        private void build(@Nullable List<HierarchyBuilder> displayAreaGroupHierarchyBuilders) {
            final WindowManagerPolicy policy = mRoot.mWmService.mPolicy;
            final int maxWindowLayerCount = policy.getMaxWindowLayer();
            final DisplayArea.Tokens[] displayAreaForLayer =
                    new DisplayArea.Tokens[maxWindowLayerCount];
            final Map<Feature, List<DisplayArea<? extends WindowContainer>>> featureAreas =
                    new ArrayMap<>(mFeatures.size());
            for (int i = 0; i < mFeatures.size(); i++) {
                featureAreas.put(mFeatures.get(i), new ArrayList<>());
            }

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

            PendingArea[] areaForLayer = new PendingArea[maxWindowLayerCount];
            final PendingArea root = new PendingArea(null, 0, null);
            Arrays.fill(areaForLayer, root);

            // Create DisplayAreas to cover all defined features.
            final int size = mFeatures.size();
            for (int i = 0; i < size; i++) {
                // Traverse the features with the order they are defined, so that the early defined
                // feature will be on the top in the hierarchy.
                final Feature feature = mFeatures.get(i);
                PendingArea featureArea = null;
                for (int layer = 0; layer < maxWindowLayerCount; layer++) {
                    if (feature.mWindowLayers[layer]) {
                        // This feature will be applied to this window layer.
                        //
                        // We need to find a DisplayArea for it:
                        // We can reuse the existing one if it was created for this feature for the
                        // previous layer AND the last feature that applied to the previous layer is
                        // the same as the feature that applied to the current layer (so they are ok
                        // to share the same parent DisplayArea).
                        if (featureArea == null || featureArea.mParent != areaForLayer[layer]) {
                            // No suitable DisplayArea:
                            // Create a new one under the previous area (as parent) for this layer.
                            featureArea = new PendingArea(feature, layer, areaForLayer[layer]);
                            areaForLayer[layer].mChildren.add(featureArea);
                        }
                        areaForLayer[layer] = featureArea;
                    } else {
                        // This feature won't be applied to this window layer. If it needs to be
                        // applied to the next layer, we will need to create a new DisplayArea for
                        // that.
                        featureArea = null;
                    }
                }
            }

            // Create Tokens as leaf for every layer.
            PendingArea leafArea = null;
            int leafType = LEAF_TYPE_TOKENS;
            for (int layer = 0; layer < maxWindowLayerCount; layer++) {
                int type = typeOfLayer(policy, layer);
                // Check whether we can reuse the same Tokens with the previous layer. This happens
                // if the previous layer is the same type as the current layer AND there is no
                // feature that applies to only one of them.
                if (leafArea == null || leafArea.mParent != areaForLayer[layer]
                        || type != leafType) {
                    // Create a new Tokens for this layer.
                    leafArea = new PendingArea(null /* feature */, layer, areaForLayer[layer]);
                    areaForLayer[layer].mChildren.add(leafArea);
                    leafType = type;
                    if (leafType == LEAF_TYPE_TASK_CONTAINERS) {
                        // We use the passed in TaskDisplayAreas for task container type of layer.
                        // Skip creating Tokens even if there is no TDA.
                        addTaskDisplayAreasToApplicationLayer(areaForLayer[layer]);
                        addDisplayAreaGroupsToApplicationLayer(areaForLayer[layer],
                                displayAreaGroupHierarchyBuilders);
                        leafArea.mSkipTokens = true;
                    } else if (leafType == LEAF_TYPE_IME_CONTAINERS) {
                        // We use the passed in ImeContainer for ime container type of layer.
                        // Skip creating Tokens even if there is no ime container.
                        leafArea.mExisting = mImeContainer;
                        leafArea.mSkipTokens = true;
                    }
                }
                leafArea.mMaxLayer = layer;
            }
            root.computeMaxLayer();

            // We built a tree of PendingAreas above with all the necessary info to represent the
            // hierarchy, now create and attach real DisplayAreas to the root.
            root.instantiateChildren(mRoot, displayAreaForLayer, 0, featureAreas);

            // Notify the root that we have finished attaching all the DisplayAreas. Cache all the
            // feature related collections there for fast access.
            mRoot.onHierarchyBuilt(mFeatures, displayAreaForLayer, featureAreas);
        }

        /** Adds all {@link TaskDisplayArea} to the application layer. */
        private void addTaskDisplayAreasToApplicationLayer(PendingArea parentPendingArea) {
            final int count = mTaskDisplayAreas.size();
            for (int i = 0; i < count; i++) {
                PendingArea leafArea =
                        new PendingArea(null /* feature */, APPLICATION_LAYER, parentPendingArea);
                leafArea.mExisting = mTaskDisplayAreas.get(i);
                leafArea.mMaxLayer = APPLICATION_LAYER;
                parentPendingArea.mChildren.add(leafArea);
            }
        }

        /** Adds roots of the DisplayAreaGroups to the application layer. */
        private void addDisplayAreaGroupsToApplicationLayer(
                DisplayAreaPolicyBuilder.PendingArea parentPendingArea,
                @Nullable List<HierarchyBuilder> displayAreaGroupHierarchyBuilders) {
            if (displayAreaGroupHierarchyBuilders == null) {
                return;
            }
            final int count = displayAreaGroupHierarchyBuilders.size();
            for (int i = 0; i < count; i++) {
                DisplayAreaPolicyBuilder.PendingArea
                        leafArea = new DisplayAreaPolicyBuilder.PendingArea(
                        null /* feature */, APPLICATION_LAYER, parentPendingArea);
                leafArea.mExisting = displayAreaGroupHierarchyBuilders.get(i).mRoot;
                leafArea.mMaxLayer = APPLICATION_LAYER;
                parentPendingArea.mChildren.add(leafArea);
            }
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

    /** Supplier interface to provide a new created {@link DisplayArea}. */
    interface NewDisplayAreaSupplier {
        DisplayArea create(WindowManagerService wms, DisplayArea.Type type, String name,
                int featureId);
    }

    /**
     * A feature that requires {@link DisplayArea DisplayArea(s)}.
     */
    static class Feature {
        private final String mName;
        private final int mId;
        private final boolean[] mWindowLayers;
        private final NewDisplayAreaSupplier mNewDisplayAreaSupplier;

        private Feature(String name, int id, boolean[] windowLayers,
                NewDisplayAreaSupplier newDisplayAreaSupplier) {
            mName = name;
            mId = id;
            mWindowLayers = windowLayers;
            mNewDisplayAreaSupplier = newDisplayAreaSupplier;
        }

        /**
         * Returns the id of the feature.
         *
         * <p>Must be unique among the features added to a {@link DisplayAreaPolicyBuilder}.
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
            private NewDisplayAreaSupplier mNewDisplayAreaSupplier = DisplayArea::new;

            /**
             * Builds a new feature that applies to a set of window types as specified by the
             * builder methods.
             *
             * <p>The set of types is updated iteratively in the order of the method invocations.
             * For example, {@code all().except(TYPE_STATUS_BAR)} expresses that a feature should
             * apply to all types except TYPE_STATUS_BAR.
             *
             * <p>The builder starts out with the feature not applying to any types.
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

            /**
             * Sets the function to create new {@link DisplayArea} for this feature. By default, it
             * uses {@link DisplayArea}'s constructor.
             */
            Builder setNewDisplayAreaSupplier(NewDisplayAreaSupplier newDisplayAreaSupplier) {
                mNewDisplayAreaSupplier = newDisplayAreaSupplier;
                return this;
            }

            Feature build() {
                return new Feature(mName, mId, mLayers.clone(), mNewDisplayAreaSupplier);
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
        final List<RootDisplayArea> mDisplayAreaGroupRoots;
        final BiFunction<WindowToken, Bundle, RootDisplayArea> mSelectRootForWindowFunc;
        private final TaskDisplayArea mDefaultTaskDisplayArea;

        Result(WindowManagerService wmService, RootDisplayArea root,
                List<RootDisplayArea> displayAreaGroupRoots,
                @Nullable BiFunction<WindowToken, Bundle, RootDisplayArea>
                        selectRootForWindowFunc) {
            super(wmService, root);
            mDisplayAreaGroupRoots = Collections.unmodifiableList(displayAreaGroupRoots);
            mSelectRootForWindowFunc = selectRootForWindowFunc == null
                    // Always return the highest level root of the logical display when the func is
                    // not specified.
                    ? (window, options) -> mRoot
                    : selectRootForWindowFunc;

            // Cache the default TaskDisplayArea for quick access.
            mDefaultTaskDisplayArea = mRoot.getItemFromTaskDisplayAreas(taskDisplayArea ->
                    taskDisplayArea.mFeatureId == FEATURE_DEFAULT_TASK_CONTAINER
                            ? taskDisplayArea
                            : null);
            if (mDefaultTaskDisplayArea == null) {
                throw new IllegalStateException(
                        "No display area with FEATURE_DEFAULT_TASK_CONTAINER");
            }
        }

        @Override
        public void addWindow(WindowToken token) {
            DisplayArea.Tokens area = findAreaForToken(token);
            area.addChild(token);
        }

        @VisibleForTesting
        DisplayArea.Tokens findAreaForToken(WindowToken token) {
            return mSelectRootForWindowFunc.apply(token, token.mOptions).findAreaForToken(token);
        }

        @VisibleForTesting
        List<Feature> getFeatures() {
            Set<Feature> features = new ArraySet<>();
            features.addAll(mRoot.mFeatures);
            for (int i = 0; i < mDisplayAreaGroupRoots.size(); i++) {
                features.addAll(mDisplayAreaGroupRoots.get(i).mFeatures);
            }
            return new ArrayList<>(features);
        }

        @Override
        public List<DisplayArea<? extends WindowContainer>> getDisplayAreas(int featureId) {
            List<DisplayArea<? extends WindowContainer>> displayAreas = new ArrayList<>();
            getDisplayAreas(mRoot, featureId, displayAreas);
            for (int i = 0; i < mDisplayAreaGroupRoots.size(); i++) {
                getDisplayAreas(mDisplayAreaGroupRoots.get(i), featureId, displayAreas);
            }
            return displayAreas;
        }

        private static void getDisplayAreas(RootDisplayArea root, int featureId,
                List<DisplayArea<? extends WindowContainer>> displayAreas) {
            List<Feature> features = root.mFeatures;
            for (int i = 0; i < features.size(); i++) {
                Feature feature = features.get(i);
                if (feature.mId == featureId) {
                    displayAreas.addAll(root.mFeatureToDisplayAreas.get(feature));
                }
            }
        }

        @Override
        public TaskDisplayArea getDefaultTaskDisplayArea() {
            return mDefaultTaskDisplayArea;
        }
    }

    static class PendingArea {
        final int mMinLayer;
        final ArrayList<PendingArea> mChildren = new ArrayList<>();
        final Feature mFeature;
        final PendingArea mParent;
        int mMaxLayer;

        /** If not {@code null}, use this instead of creating a {@link DisplayArea.Tokens}. */
        @Nullable DisplayArea mExisting;

        /**
         * Whether to skip creating a {@link DisplayArea.Tokens} if {@link #mExisting} is
         * {@code null}.
         *
         * <p>This will be set for {@link HierarchyBuilder#LEAF_TYPE_IME_CONTAINERS} and
         * {@link HierarchyBuilder#LEAF_TYPE_TASK_CONTAINERS}, because we don't want to create
         * {@link DisplayArea.Tokens} for them even if they are not set.
         */
        boolean mSkipTokens = false;

        PendingArea(Feature feature, int minLayer, PendingArea parent) {
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

        void instantiateChildren(DisplayArea<DisplayArea> parent, DisplayArea.Tokens[] areaForLayer,
                int level, Map<Feature, List<DisplayArea<? extends WindowContainer>>> areas) {
            mChildren.sort(Comparator.comparingInt(pendingArea -> pendingArea.mMinLayer));
            for (int i = 0; i < mChildren.size(); i++) {
                final PendingArea child = mChildren.get(i);
                final DisplayArea area = child.createArea(parent, areaForLayer);
                if (area == null) {
                    // TaskDisplayArea and ImeContainer can be set at different hierarchy, so it can
                    // be null.
                    continue;
                }
                parent.addChild(area, WindowContainer.POSITION_TOP);
                if (child.mFeature != null) {
                    areas.get(child.mFeature).add(area);
                }
                child.instantiateChildren(area, areaForLayer, level + 1, areas);
            }
        }

        @Nullable
        private DisplayArea createArea(DisplayArea<DisplayArea> parent,
                DisplayArea.Tokens[] areaForLayer) {
            if (mExisting != null) {
                return mExisting;
            }
            if (mSkipTokens) {
                return null;
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
                return mFeature.mNewDisplayAreaSupplier.create(parent.mWmService, type,
                        mFeature.mName + ":" + mMinLayer + ":" + mMaxLayer, mFeature.mId);
            }
        }
    }
}
