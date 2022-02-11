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

import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_POINTER;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerPolicyConstants.APPLICATION_LAYER;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_FULLSCREEN_MAGNIFICATION;
import static android.window.DisplayAreaOrganizer.FEATURE_IME_PLACEHOLDER;
import static android.window.DisplayAreaOrganizer.FEATURE_ONE_HANDED;
import static android.window.DisplayAreaOrganizer.FEATURE_ONE_HANDED_BACKGROUND_PANEL;
import static android.window.DisplayAreaOrganizer.FEATURE_ROOT;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_LAST;
import static android.window.DisplayAreaOrganizer.FEATURE_WINDOWED_MAGNIFICATION;
import static android.window.DisplayAreaOrganizer.KEY_ROOT_DISPLAY_AREA_ID;

import static com.android.server.wm.DisplayArea.Type.ABOVE_TASKS;
import static com.android.server.wm.DisplayAreaPolicyBuilder.Feature;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import static java.util.stream.Collectors.toList;

import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import com.google.android.collect.Lists;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Build/Install/Run:
 *  atest WmTests:DisplayAreaPolicyBuilderTest
 */
@Presubmit
public class DisplayAreaPolicyBuilderTest {

    @Rule
    public final SystemServicesTestRule mSystemServices = new SystemServicesTestRule();

    private TestWindowManagerPolicy mPolicy = new TestWindowManagerPolicy(null, null);
    private WindowManagerService mWms;
    private RootDisplayArea mRoot;
    private DisplayArea.Tokens mImeContainer;
    private DisplayContent mDisplayContent;
    private TaskDisplayArea mDefaultTaskDisplayArea;
    private List<TaskDisplayArea> mTaskDisplayAreaList;
    private RootDisplayArea mGroupRoot1;
    private RootDisplayArea mGroupRoot2;
    private TaskDisplayArea mTda1;
    private TaskDisplayArea mTda2;

    @Before
    public void setup() {
        mWms = mSystemServices.getWindowManagerService();
        mRoot = new SurfacelessDisplayAreaRoot(mWms);
        mImeContainer = new DisplayArea.Tokens(mWms, ABOVE_TASKS, "ImeContainer");
        mDisplayContent = mock(DisplayContent.class);
        doReturn(true).when(mDisplayContent).isTrusted();
        mDisplayContent.isDefaultDisplay = true;
        mDefaultTaskDisplayArea = new TaskDisplayArea(mDisplayContent, mWms, "Tasks",
                FEATURE_DEFAULT_TASK_CONTAINER);
        mTaskDisplayAreaList = new ArrayList<>();
        mTaskDisplayAreaList.add(mDefaultTaskDisplayArea);
        mGroupRoot1 = new SurfacelessDisplayAreaRoot(mWms, "group1", FEATURE_VENDOR_FIRST + 1);
        mGroupRoot2 = new SurfacelessDisplayAreaRoot(mWms, "group2", FEATURE_VENDOR_FIRST + 2);
        mTda1 = new TaskDisplayArea(mDisplayContent, mWms, "tda1", FEATURE_VENDOR_FIRST + 3);
        mTda2 = new TaskDisplayArea(mDisplayContent, mWms, "tda2", FEATURE_VENDOR_FIRST + 4);
    }

    @Test
    public void testBuilder() {
        final Feature foo;
        final Feature bar;
        DisplayAreaPolicyBuilder.HierarchyBuilder rootHierarchy =
                new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                        .addFeature(foo = new Feature.Builder(mPolicy, "Foo",
                                FEATURE_VENDOR_FIRST)
                                .upTo(TYPE_STATUS_BAR)
                                .and(TYPE_NAVIGATION_BAR)
                                .build())
                        .addFeature(bar = new Feature.Builder(mPolicy, "Bar",
                                FEATURE_VENDOR_FIRST + 1)
                                .all()
                                .except(TYPE_STATUS_BAR)
                                .build())
                        .setImeContainer(mImeContainer)
                        .setTaskDisplayAreas(mTaskDisplayAreaList);
        DisplayAreaPolicyBuilder.Result policy = new DisplayAreaPolicyBuilder()
                .setRootHierarchy(rootHierarchy)
                .build(mWms);

        assertThat(policy.getDisplayAreas(foo.getId())).isNotEmpty();
        assertThat(policy.getDisplayAreas(bar.getId())).isNotEmpty();

        Matcher<WindowContainer> fooDescendantMatcher = descendantOfOneOf(
                policy.getDisplayAreas(foo.getId()));
        Matcher<WindowContainer> barDescendantMatcher = descendantOfOneOf(
                policy.getDisplayAreas(bar.getId()));

        // There is a DA of TYPE_STATUS_BAR below foo, but not below bar
        assertThat(fooDescendantMatcher.matches(
                policy.findAreaForToken(tokenOfType(TYPE_STATUS_BAR)))).isTrue();
        assertThat(barDescendantMatcher.matches(
                policy.findAreaForToken(tokenOfType(TYPE_STATUS_BAR)))).isFalse();

        // The TDA is below both foo and bar.
        assertThat(fooDescendantMatcher.matches(mDefaultTaskDisplayArea)).isTrue();
        assertThat(barDescendantMatcher.matches(mDefaultTaskDisplayArea)).isTrue();

        // The IME is below both foo and bar.
        assertThat(fooDescendantMatcher.matches(mImeContainer)).isTrue();
        assertThat(barDescendantMatcher.matches(mImeContainer)).isTrue();
        assertThat(policy.findAreaForToken(tokenOfType(TYPE_INPUT_METHOD)))
                .isEqualTo(mImeContainer);
        assertThat(policy.findAreaForToken(tokenOfType(TYPE_INPUT_METHOD_DIALOG)))
                .isEqualTo(mImeContainer);

        List<DisplayArea<?>> actualOrder = collectLeafAreas(mRoot);
        Map<DisplayArea<?>, Set<Integer>> zSets = calculateZSets(policy, mImeContainer,
                mDefaultTaskDisplayArea);
        actualOrder = actualOrder.stream().filter(zSets::containsKey).collect(toList());

        Map<DisplayArea<?>, Integer> expectedByMinLayer = mapValues(zSets,
                v -> v.stream().min(Integer::compareTo).get());
        Map<DisplayArea<?>, Integer> expectedByMaxLayer = mapValues(zSets,
                v -> v.stream().max(Integer::compareTo).get());

        // Make sure the DAs' order is the same as their layer order.
        assertMatchLayerOrder(actualOrder, expectedByMinLayer);
        assertMatchLayerOrder(actualOrder, expectedByMaxLayer);
    }

    @Test
    public void testBuilder_defaultPolicy_hasOneHandedFeature() {
        final DisplayAreaPolicy.Provider defaultProvider = DisplayAreaPolicy.Provider.fromResources(
                resourcesWithProvider(""));
        final DisplayAreaPolicyBuilder.Result defaultPolicy =
                (DisplayAreaPolicyBuilder.Result) defaultProvider.instantiate(mWms, mDisplayContent,
                        mRoot, mImeContainer);
        if (mDisplayContent.isDefaultDisplay) {
            final List<Feature> features = defaultPolicy.getFeatures();
            boolean hasOneHandedFeature = false;
            for (Feature feature : features) {
                hasOneHandedFeature |= feature.getId() == FEATURE_ONE_HANDED;
            }

            assertThat(hasOneHandedFeature).isTrue();
        }
    }

    @Test
    public void testBuilder_defaultPolicy_hasOneHandedBackgroundFeature() {
        final DisplayAreaPolicy.Provider defaultProvider = DisplayAreaPolicy.Provider.fromResources(
                resourcesWithProvider(""));
        final DisplayAreaPolicyBuilder.Result defaultPolicy =
                (DisplayAreaPolicyBuilder.Result) defaultProvider.instantiate(mWms, mDisplayContent,
                        mRoot, mImeContainer);
        if (mDisplayContent.isDefaultDisplay) {
            final List<Feature> features = defaultPolicy.getFeatures();
            boolean hasOneHandedBackgroundFeature = false;
            for (Feature feature : features) {
                hasOneHandedBackgroundFeature |=
                        feature.getId() == FEATURE_ONE_HANDED_BACKGROUND_PANEL;
            }

            assertThat(hasOneHandedBackgroundFeature).isTrue();
        }
    }

    @Test
    public void testBuilder_defaultPolicy_hasWindowedMagnificationFeature() {
        final DisplayAreaPolicy.Provider defaultProvider = DisplayAreaPolicy.Provider.fromResources(
                resourcesWithProvider(""));
        final DisplayAreaPolicyBuilder.Result defaultPolicy =
                (DisplayAreaPolicyBuilder.Result) defaultProvider.instantiate(mWms, mDisplayContent,
                        mRoot, mImeContainer);
        final List<Feature> features = defaultPolicy.getFeatures();
        boolean hasWindowedMagnificationFeature = false;
        for (Feature feature : features) {
            hasWindowedMagnificationFeature |= feature.getId() == FEATURE_WINDOWED_MAGNIFICATION;
        }

        assertThat(hasWindowedMagnificationFeature).isTrue();
    }

    @Test
    public void testBuilder_defaultPolicy_hasFullscreenMagnificationFeature() {
        final DisplayAreaPolicy.Provider defaultProvider = DisplayAreaPolicy.Provider.fromResources(
                resourcesWithProvider(""));
        final DisplayAreaPolicyBuilder.Result defaultPolicy =
                (DisplayAreaPolicyBuilder.Result) defaultProvider.instantiate(mWms, mDisplayContent,
                        mRoot, mImeContainer);
        final List<Feature> features = defaultPolicy.getFeatures();
        boolean hasFullscreenMagnificationFeature = false;
        for (Feature feature : features) {
            hasFullscreenMagnificationFeature |=
                    feature.getId() == FEATURE_FULLSCREEN_MAGNIFICATION;
        }

        assertThat(hasFullscreenMagnificationFeature).isTrue();
    }

    @Test
    public void testBuilder_defaultPolicy_hasImePlaceholderFeature() {
        final DisplayAreaPolicy.Provider defaultProvider = DisplayAreaPolicy.Provider.fromResources(
                resourcesWithProvider(""));
        final DisplayAreaPolicyBuilder.Result defaultPolicy =
                (DisplayAreaPolicyBuilder.Result) defaultProvider.instantiate(mWms, mDisplayContent,
                        mRoot, mImeContainer);
        final List<Feature> features = defaultPolicy.getFeatures();
        boolean hasImePlaceholderFeature = false;
        for (Feature feature : features) {
            hasImePlaceholderFeature |= feature.getId() == FEATURE_IME_PLACEHOLDER;
        }

        assertThat(hasImePlaceholderFeature).isTrue();
    }

    @Test
    public void testBuilder_createCustomizedDisplayAreaForFeature() {
        final Feature dimmable;
        final Feature other;
        DisplayAreaPolicyBuilder.HierarchyBuilder rootHierarchy =
                new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                        .addFeature(dimmable = new Feature.Builder(mPolicy, "Dimmable",
                                FEATURE_VENDOR_FIRST)
                                .upTo(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                                .except(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                                .setNewDisplayAreaSupplier(DisplayArea.Dimmable::new)
                                .build())
                        .addFeature(other = new Feature.Builder(mPolicy, "Other",
                                FEATURE_VENDOR_FIRST + 1)
                                .all()
                                .build())
                        .setImeContainer(mImeContainer)
                        .setTaskDisplayAreas(mTaskDisplayAreaList);
        DisplayAreaPolicyBuilder.Result policy = new DisplayAreaPolicyBuilder()
                .setRootHierarchy(rootHierarchy)
                .build(mWms);

        List<DisplayArea<? extends WindowContainer>> dimmableDAs =
                policy.getDisplayAreas(dimmable.getId());
        List<DisplayArea<? extends WindowContainer>> otherDAs =
                policy.getDisplayAreas(other.getId());
        assertThat(dimmableDAs).hasSize(1);
        assertThat(dimmableDAs.get(0)).isInstanceOf(DisplayArea.Dimmable.class);
        for (DisplayArea otherDA : otherDAs) {
            assertThat(otherDA).isNotInstanceOf(DisplayArea.Dimmable.class);
        }
    }

    @Test
    public void testBuilder_singleRoot_validateSettings() {
        final DisplayAreaPolicyBuilder builder = new DisplayAreaPolicyBuilder();

        // Root must be set.
        assertThrows(IllegalStateException.class, () -> builder.build(mWms));

        // IME must be set.
        builder.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setTaskDisplayAreas(mTaskDisplayAreaList));

        assertThrows(IllegalStateException.class, () -> builder.build(mWms));

        // Default TaskDisplayArea must be set.
        builder.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(Lists.newArrayList(
                        new TaskDisplayArea(mDisplayContent, mWms, "testTda",
                                FEATURE_VENDOR_FIRST + 1))));

        assertThrows(IllegalStateException.class, () -> builder.build(mWms));

        // No exception
        builder.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(mTaskDisplayAreaList));

        builder.build(mWms);
    }

    @Test
    public void testBuilder_displayAreaGroup_validateSettings() {
        final DisplayAreaPolicyBuilder builder1 = new DisplayAreaPolicyBuilder();

        // IME must be set to one of the roots.
        builder1.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot));
        builder1.addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                mGroupRoot1)
                .setTaskDisplayAreas(mTaskDisplayAreaList));

        assertThrows(IllegalStateException.class, () -> builder1.build(mWms));

        // Default TaskDisplayArea must be set.
        final DisplayAreaPolicyBuilder builder2 = new DisplayAreaPolicyBuilder();
        builder2.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot));
        builder2.addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                mGroupRoot1)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(Lists.newArrayList(mTda1)));

        assertThrows(IllegalStateException.class, () -> builder2.build(mWms));

        // Each DisplayAreaGroup must have at least one TaskDisplayArea.
        final DisplayAreaPolicyBuilder builder3 = new DisplayAreaPolicyBuilder();
        builder3.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot));
        builder3.addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                mGroupRoot1)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(mTaskDisplayAreaList));
        builder3.addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                mGroupRoot2));

        assertThrows(IllegalStateException.class, () -> builder3.build(mWms));

        // No exception
        final DisplayAreaPolicyBuilder builder4 = new DisplayAreaPolicyBuilder();
        builder4.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot));
        builder4.addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                mGroupRoot1)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(mTaskDisplayAreaList));
        builder4.addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                mGroupRoot2)
                .setTaskDisplayAreas(Lists.newArrayList(mTda1)));

        builder4.build(mWms);
    }

    @Test
    public void testBuilder_rootHasUniqueId() {
        // Root must have different id from all roots.
        final DisplayAreaPolicyBuilder builder1 = new DisplayAreaPolicyBuilder();
        builder1.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(mTaskDisplayAreaList));
        final RootDisplayArea groupRoot1 = new SurfacelessDisplayAreaRoot(mWms, "group1",
                mRoot.mFeatureId);
        builder1.addDisplayAreaGroupHierarchy(
                new DisplayAreaPolicyBuilder.HierarchyBuilder(groupRoot1)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda1)));

        assertThrows(IllegalStateException.class, () -> builder1.build(mWms));

        // Root must have different id from all TDAs.
        final DisplayAreaPolicyBuilder builder2 = new DisplayAreaPolicyBuilder();
        builder2.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(Lists.newArrayList(
                        mDefaultTaskDisplayArea,
                        new TaskDisplayArea(mDisplayContent, mWms, "testTda",
                                mRoot.mFeatureId))));

        assertThrows(IllegalStateException.class, () -> builder2.build(mWms));

        // Root must have different id from all features.
        final DisplayAreaPolicyBuilder builder3 = new DisplayAreaPolicyBuilder();
        builder3.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(mTaskDisplayAreaList)
                .addFeature(new Feature.Builder(mPolicy, "testFeature", mRoot.mFeatureId)
                        .all()
                        .build()));

        assertThrows(IllegalStateException.class, () -> builder3.build(mWms));
    }

    @Test
    public void testBuilder_taskDisplayAreaHasUniqueId() {
        // TDA must have different id from all TDAs.
        final DisplayAreaPolicyBuilder builder = new DisplayAreaPolicyBuilder();
        final List<TaskDisplayArea> tdaList = Lists.newArrayList(
                mDefaultTaskDisplayArea,
                mTda1,
                new TaskDisplayArea(mDisplayContent, mWms, "tda2", mTda1.mFeatureId));
        builder.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(tdaList));

        assertThrows(IllegalStateException.class, () -> builder.build(mWms));

        // TDA must have different id from all features.
        final DisplayAreaPolicyBuilder builder2 = new DisplayAreaPolicyBuilder();
        builder2.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(Lists.newArrayList(
                        mDefaultTaskDisplayArea,
                        mTda1))
                .addFeature(new Feature.Builder(mPolicy, "testFeature", mTda1.mFeatureId)
                        .all()
                        .build()));

        assertThrows(IllegalStateException.class, () -> builder2.build(mWms));
    }

    @Test
    public void testBuilder_featureHasUniqueId() {
        // Feature must have different id from features below the same root.
        final DisplayAreaPolicyBuilder builder = new DisplayAreaPolicyBuilder();
        builder.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(mTaskDisplayAreaList)
                .addFeature(new Feature.Builder(mPolicy, "feature1", FEATURE_VENDOR_FIRST + 10)
                        .all()
                        .build())
                .addFeature(new Feature.Builder(mPolicy, "feature2", FEATURE_VENDOR_FIRST + 10)
                        .upTo(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                        .build()));

        assertThrows(IllegalStateException.class, () -> builder.build(mWms));

        // Features below different root can have the same id.
        final DisplayAreaPolicyBuilder builder2 = new DisplayAreaPolicyBuilder();
        builder2.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(mTaskDisplayAreaList)
                .addFeature(new Feature.Builder(mPolicy, "feature1", FEATURE_VENDOR_FIRST + 10)
                        .all()
                        .build()));
        builder2.addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                mGroupRoot1)
                .setTaskDisplayAreas(Lists.newArrayList(mTda1))
                .addFeature(new Feature.Builder(mPolicy, "feature2", FEATURE_VENDOR_FIRST + 10)
                        .all()
                        .build()));

        builder2.build(mWms);
    }

    @Test
    public void testBuilder_idsNotGreaterThanFeatureVendorLast() {
        // Root id should not be greater than FEATURE_VENDOR_LAST.
        final DisplayAreaPolicyBuilder builder1 = new DisplayAreaPolicyBuilder();
        final RootDisplayArea root = new SurfacelessDisplayAreaRoot(mWms, "testRoot",
                FEATURE_VENDOR_LAST + 1);
        builder1.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(root)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(mTaskDisplayAreaList));

        assertThrows(IllegalStateException.class, () -> builder1.build(mWms));

        // TDA id should not be greater than FEATURE_VENDOR_LAST.
        final DisplayAreaPolicyBuilder builder2 = new DisplayAreaPolicyBuilder();
        builder2.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(root)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(Lists.newArrayList(
                        mDefaultTaskDisplayArea,
                        new TaskDisplayArea(mDisplayContent, mWms, "testTda",
                                FEATURE_VENDOR_LAST + 1))));

        assertThrows(IllegalStateException.class, () -> builder2.build(mWms));

        // Feature id should not be greater than FEATURE_VENDOR_LAST.
        final DisplayAreaPolicyBuilder builder3 = new DisplayAreaPolicyBuilder();
        builder3.setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                .setImeContainer(mImeContainer)
                .setTaskDisplayAreas(mTaskDisplayAreaList)
                .addFeature(new Feature.Builder(mPolicy, "testFeature", FEATURE_VENDOR_LAST + 1)
                        .all()
                        .build()));

        assertThrows(IllegalStateException.class, () -> builder3.build(mWms));
    }

    @Test
    public void testBuilder_displayAreaGroup_attachDisplayAreas() {
        final DisplayAreaPolicyBuilder.Result policy = new DisplayAreaPolicyBuilder()
                .setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                        .setTaskDisplayAreas(mTaskDisplayAreaList))
                .addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                        mGroupRoot1)
                        .setImeContainer(mImeContainer)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda1)))
                .addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                        mGroupRoot2)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda2)))
                .build(mWms);

        assertThat(mDefaultTaskDisplayArea.isDescendantOf(mRoot)).isTrue();
        assertThat(mGroupRoot1.isDescendantOf(mRoot)).isTrue();
        assertThat(mGroupRoot2.isDescendantOf(mRoot)).isTrue();
        assertThat(mImeContainer.isDescendantOf(mGroupRoot1)).isTrue();
        assertThat(mTda1.isDescendantOf(mGroupRoot1)).isTrue();
        assertThat(mTda2.isDescendantOf(mGroupRoot2)).isTrue();
        assertThat(isSibling(mDefaultTaskDisplayArea, mGroupRoot1)).isTrue();
        assertThat(isSibling(mDefaultTaskDisplayArea, mGroupRoot2)).isTrue();
    }

    @Test
    public void testBuilder_displayAreaGroup_createFeatureOnGroup() {
        final Feature feature1 = new Feature.Builder(mWms.mPolicy, "feature1",
                FEATURE_VENDOR_FIRST + 5)
                .all()
                .except(TYPE_STATUS_BAR)
                .build();
        final Feature feature2 = new Feature.Builder(mWms.mPolicy, "feature2",
                FEATURE_VENDOR_FIRST + 6)
                .upTo(TYPE_STATUS_BAR)
                .and(TYPE_NAVIGATION_BAR)
                .build();
        final DisplayAreaPolicyBuilder.Result policy = new DisplayAreaPolicyBuilder()
                .setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                        .setTaskDisplayAreas(mTaskDisplayAreaList))
                .addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                        mGroupRoot1)
                        .setImeContainer(mImeContainer)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda1))
                        .addFeature(feature1))
                .addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                        mGroupRoot2)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda2))
                        .addFeature(feature2))
                .build(mWms);

        List<DisplayArea<? extends WindowContainer>> feature1DAs =
                policy.getDisplayAreas(feature1.getId());
        List<DisplayArea<? extends WindowContainer>> feature2DAs =
                policy.getDisplayAreas(feature2.getId());
        for (DisplayArea<? extends WindowContainer> da : feature1DAs) {
            assertThat(da.isDescendantOf(mGroupRoot1)).isTrue();
        }
        for (DisplayArea<? extends WindowContainer> da : feature2DAs) {
            assertThat(da.isDescendantOf(mGroupRoot2)).isTrue();
        }
    }

    @Test
    public void testBuilder_addWindow_selectContainerForWindowFunc_defaultFunc() {
        final DisplayAreaPolicyBuilder.Result policy = new DisplayAreaPolicyBuilder()
                .setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                        .setTaskDisplayAreas(mTaskDisplayAreaList))
                .addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                        mGroupRoot1)
                        .setImeContainer(mImeContainer)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda1)))
                .addDisplayAreaGroupHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(
                        mGroupRoot2)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda2)))
                .build(mWms);

        final WindowToken token = new WindowToken.Builder(mWms, mock(IBinder.class),
                TYPE_STATUS_BAR)
                .setDisplayContent(mDisplayContent)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .build();

        policy.addWindow(token);

        // By default, window are always added to the root.
        assertThat(token.isDescendantOf(mRoot)).isTrue();
        assertThat(token.isDescendantOf(mGroupRoot1)).isFalse();
        assertThat(token.isDescendantOf(mGroupRoot2)).isFalse();

        // When the window has options for target root id, attach it to the target root.
        final Bundle options = new Bundle();
        options.putInt(KEY_ROOT_DISPLAY_AREA_ID, mGroupRoot2.mFeatureId);
        final WindowToken token2 = new WindowToken.Builder(mWms, mock(IBinder.class),
                TYPE_STATUS_BAR)
                .setDisplayContent(mDisplayContent)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .setOptions(options)
                .build();
        policy.addWindow(token2);

        assertThat(token2.isDescendantOf(mGroupRoot2)).isTrue();
    }

    @Test
    public void testBuilder_addWindow_selectContainerForWindowFunc_selectBasedOnType() {
        final DisplayAreaPolicyBuilder.HierarchyBuilder hierarchy1 =
                new DisplayAreaPolicyBuilder.HierarchyBuilder(mGroupRoot1)
                        .setImeContainer(mImeContainer)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda1));
        final DisplayAreaPolicyBuilder.HierarchyBuilder hierarchy2 =
                new DisplayAreaPolicyBuilder.HierarchyBuilder(mGroupRoot2)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda2));
        final DisplayAreaPolicyBuilder.Result policy = new DisplayAreaPolicyBuilder()
                .setRootHierarchy(new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                        .setTaskDisplayAreas(mTaskDisplayAreaList))
                .addDisplayAreaGroupHierarchy(hierarchy1)
                .addDisplayAreaGroupHierarchy(hierarchy2)
                .setSelectRootForWindowFunc((type, options) -> {
                    if (type == TYPE_STATUS_BAR) {
                        return mGroupRoot1;
                    }
                    return mGroupRoot2;
                })
                .build(mWms);

        final WindowToken token1 = new WindowToken.Builder(mWms, mock(IBinder.class),
                TYPE_STATUS_BAR)
                .setDisplayContent(mDisplayContent)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .build();
        final WindowToken token2 = new WindowToken.Builder(mWms, mock(IBinder.class),
                TYPE_WALLPAPER)
                .setDisplayContent(mDisplayContent)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .build();
        policy.addWindow(token1);
        policy.addWindow(token2);

        assertThat(token1.isDescendantOf(mGroupRoot1)).isTrue();
        assertThat(token2.isDescendantOf(mGroupRoot2)).isTrue();
    }

    @Test
    public void testBuilder_addWindow_selectContainerForWindowFunc_selectBasedOnOptions() {
        final DisplayAreaPolicyBuilder.HierarchyBuilder hierarchy0 =
                new DisplayAreaPolicyBuilder.HierarchyBuilder(mRoot)
                        .setTaskDisplayAreas(mTaskDisplayAreaList);
        final DisplayAreaPolicyBuilder.HierarchyBuilder hierarchy1 =
                new DisplayAreaPolicyBuilder.HierarchyBuilder(
                        mGroupRoot1)
                        .setImeContainer(mImeContainer)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda1));
        final DisplayAreaPolicyBuilder.HierarchyBuilder hierarchy2 =
                new DisplayAreaPolicyBuilder.HierarchyBuilder(
                        mGroupRoot2)
                        .setTaskDisplayAreas(Lists.newArrayList(mTda2));
        final DisplayAreaPolicyBuilder.Result policy = new DisplayAreaPolicyBuilder()
                .setRootHierarchy(hierarchy0)
                .addDisplayAreaGroupHierarchy(hierarchy1)
                .addDisplayAreaGroupHierarchy(hierarchy2)
                .setSelectRootForWindowFunc((token, options) -> {
                    if (options == null) {
                        return mRoot;
                    }
                    if (options.getInt("HIERARCHY_ROOT_ID") == mGroupRoot1.mFeatureId) {
                        return mGroupRoot1;
                    }
                    if (options.getInt("HIERARCHY_ROOT_ID") == mGroupRoot2.mFeatureId) {
                        return mGroupRoot2;
                    }
                    return mRoot;
                })
                .build(mWms);

        final Bundle options1 = new Bundle();
        options1.putInt("HIERARCHY_ROOT_ID", mGroupRoot1.mFeatureId);
        final Bundle options2 = new Bundle();
        options2.putInt("HIERARCHY_ROOT_ID", mGroupRoot2.mFeatureId);
        final WindowToken token0 = new WindowToken.Builder(mWms, mock(IBinder.class),
                TYPE_STATUS_BAR)
                .setDisplayContent(mDisplayContent)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .build();
        final WindowToken token1 = new WindowToken.Builder(mWms, mock(IBinder.class),
                TYPE_STATUS_BAR)
                .setDisplayContent(mDisplayContent)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .setOptions(options1)
                .build();
        final WindowToken token2 = new WindowToken.Builder(mWms, mock(IBinder.class),
                TYPE_STATUS_BAR)
                .setDisplayContent(mDisplayContent)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .setOptions(options2)
                .build();

        policy.addWindow(token0);
        policy.addWindow(token1);
        policy.addWindow(token2);

        assertThat(token0.isDescendantOf(mRoot)).isTrue();
        assertThat(token1.isDescendantOf(mGroupRoot1)).isTrue();
        assertThat(token2.isDescendantOf(mGroupRoot2)).isTrue();
    }

    @Test
    public void testFeatureNotThrowArrayIndexOutOfBoundsException() {
        final Feature feature1 = new Feature.Builder(mWms.mPolicy, "feature1",
                FEATURE_VENDOR_FIRST + 5)
                .all()
                .except(TYPE_POINTER)
                .build();
    }

    private static Resources resourcesWithProvider(String provider) {
        Resources mock = mock(Resources.class);
        when(mock.getString(
                com.android.internal.R.string.config_deviceSpecificDisplayAreaPolicyProvider))
                .thenReturn(provider);
        return mock;
    }

    private <K, V, R> Map<K, R> mapValues(Map<K, V> zSets, Function<V, R> f) {
        return zSets.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> f.apply(e.getValue())));
    }

    private List<DisplayArea<?>> collectLeafAreas(DisplayArea<?> root) {
        ArrayList<DisplayArea<?>> leafs = new ArrayList<>();
        traverseLeafAreas(root, leafs::add);
        return leafs;
    }

    private Map<DisplayArea<?>, Set<Integer>> calculateZSets(
            DisplayAreaPolicyBuilder.Result policy,
            DisplayArea.Tokens ime,
            TaskDisplayArea taskDisplayArea) {
        Map<DisplayArea<?>, Set<Integer>> zSets = new HashMap<>();
        int[] types = {TYPE_STATUS_BAR, TYPE_NAVIGATION_BAR, TYPE_PRESENTATION,
                TYPE_APPLICATION_OVERLAY};
        for (int type : types) {
            WindowToken token = tokenOfType(type);
            recordLayer(policy.findAreaForToken(token), token.getWindowLayerFromType(), zSets);
        }
        recordLayer(taskDisplayArea, APPLICATION_LAYER, zSets);
        recordLayer(ime, mPolicy.getWindowLayerFromTypeLw(TYPE_INPUT_METHOD), zSets);
        return zSets;
    }

    private void recordLayer(DisplayArea<?> area, int layer,
            Map<DisplayArea<?>,  Set<Integer>> zSets) {
        zSets.computeIfAbsent(area, k -> new HashSet<>()).add(layer);
    }

    private Matcher<WindowContainer> descendantOfOneOf(List<? extends WindowContainer> expected) {
        return new CustomTypeSafeMatcher<WindowContainer>("descendant of one of " + expected) {
            @Override
            protected boolean matchesSafely(WindowContainer actual) {
                for (WindowContainer expected : expected) {
                    WindowContainer candidate = actual;
                    while (candidate != null && candidate.getParent() != candidate) {
                        if (candidate.getParent() == expected) {
                            return true;
                        }
                        candidate = candidate.getParent();
                    }
                }
                return false;
            }

            @Override
            protected void describeMismatchSafely(WindowContainer item,
                    Description description) {
                description.appendText("was ").appendValue(item);
                while (item != null && item.getParent() != item) {
                    item = item.getParent();
                    description.appendText(", child of ").appendValue(item);
                }
            }
        };
    }

    private boolean isSibling(WindowContainer da1, WindowContainer da2) {
        return da1.getParent() != null && da1.getParent() == da2.getParent();
    }

    private WindowToken tokenOfType(int type) {
        return new WindowToken.Builder(mWms, new Binder(), type)
                .setDisplayContent(mDisplayContent).build();
    }

    private static void assertMatchLayerOrder(List<DisplayArea<?>> actualOrder,
            Map<DisplayArea<?>, Integer> areaToLayerMap) {
        for (int i = 0; i < actualOrder.size() - 1; i++) {
            DisplayArea<?> curr = actualOrder.get(i);
            DisplayArea<?> next = actualOrder.get(i + 1);
            assertThat(areaToLayerMap.get(curr)).isLessThan(areaToLayerMap.get(next));
        }
    }

    private static void traverseLeafAreas(DisplayArea<?> root, Consumer<DisplayArea<?>> consumer) {
        boolean leaf = true;
        for (int i = 0; i < root.getChildCount(); i++) {
            WindowContainer child = root.getChildAt(i);
            if (child instanceof DisplayArea<?>) {
                traverseLeafAreas((DisplayArea<?>) child, consumer);
                leaf = false;
            }
        }
        if (leaf) {
            consumer.accept(root);
        }
    }

    static class SurfacelessDisplayAreaRoot extends RootDisplayArea {

        SurfacelessDisplayAreaRoot(WindowManagerService wms) {
            this(wms, "SurfacelessDisplayAreaRoot", FEATURE_ROOT);
        }

        SurfacelessDisplayAreaRoot(WindowManagerService wms, String name, int featureId) {
            super(wms, name, featureId);
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            return new MockSurfaceControlBuilder();
        }
    }

}
