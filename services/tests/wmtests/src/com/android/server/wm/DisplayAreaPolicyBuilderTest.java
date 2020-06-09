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
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManagerPolicyConstants.APPLICATION_LAYER;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_ONE_HANDED;

import static com.android.server.wm.DisplayArea.Type.ABOVE_TASKS;
import static com.android.server.wm.DisplayAreaPolicyBuilder.Feature;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static java.util.stream.Collectors.toList;

import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

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
    private DisplayArea.Root mRoot;
    private DisplayArea<WindowContainer> mImeContainer;
    private DisplayContent mDisplayContent;
    private TaskDisplayArea mDefaultTaskDisplayArea;
    private List<TaskDisplayArea> mTaskDisplayAreaList;

    @Before
    public void setup() {
        mWms = mSystemServices.getWindowManagerService();
        mRoot = new SurfacelessDisplayAreaRoot(mWms);
        mImeContainer = new DisplayArea<>(mWms, ABOVE_TASKS, "Ime");
        mDisplayContent = mock(DisplayContent.class);
        mDefaultTaskDisplayArea = new TaskDisplayArea(mDisplayContent, mWms, "Tasks",
                FEATURE_DEFAULT_TASK_CONTAINER);
        mTaskDisplayAreaList = new ArrayList<>();
        mTaskDisplayAreaList.add(mDefaultTaskDisplayArea);
    }

    @Test
    public void testBuilder() {
        final Feature foo;
        final Feature bar;

        DisplayAreaPolicyBuilder.Result policy = new DisplayAreaPolicyBuilder()
                .addFeature(foo = new Feature.Builder(mPolicy, "Foo", 0)
                        .upTo(TYPE_STATUS_BAR)
                        .and(TYPE_NAVIGATION_BAR)
                        .build())
                .addFeature(bar = new Feature.Builder(mPolicy, "Bar", 1)
                        .all()
                        .except(TYPE_STATUS_BAR)
                        .build())
                .build(mWms, mDisplayContent, mRoot, mImeContainer, mTaskDisplayAreaList);

        policy.attachDisplayAreas();

        assertThat(policy.getDisplayAreas(foo)).isNotEmpty();
        assertThat(policy.getDisplayAreas(bar)).isNotEmpty();

        Matcher<WindowContainer> fooDescendantMatcher = descendantOfOneOf(
                policy.getDisplayAreas(foo));
        Matcher<WindowContainer> barDescendantMatcher = descendantOfOneOf(
                policy.getDisplayAreas(bar));

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

        List<DisplayArea<?>> actualOrder = collectLeafAreas(mRoot);
        Map<DisplayArea<?>, Set<Integer>> zSets = calculateZSets(policy, mRoot, mImeContainer,
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
        final List<Feature> features = defaultPolicy.getFeatures();
        boolean hasOneHandedFeature = false;
        for (int i = 0; i < features.size(); i++) {
            hasOneHandedFeature |= features.get(i).getId() == FEATURE_ONE_HANDED;
        }

        assertThat(hasOneHandedFeature).isTrue();
    }

    @Test
    public void testBuilder_createCustomizedDisplayAreaForFeature() {
        final Feature dimmable;
        final Feature other;
        DisplayAreaPolicyBuilder.Result policy = new DisplayAreaPolicyBuilder()
                .addFeature(dimmable = new Feature.Builder(mPolicy, "Dimmable", 0)
                        .upTo(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                        .except(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                        .setNewDisplayAreaSupplier(DisplayArea.Dimmable::new)
                        .build())
                .addFeature(other = new Feature.Builder(mPolicy, "Other", 1)
                        .all()
                        .build())
                .build(mWms, mDisplayContent, mRoot, mImeContainer, mTaskDisplayAreaList);

        policy.attachDisplayAreas();

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
            DisplayAreaPolicyBuilder.Result policy, DisplayArea.Root root,
            DisplayArea<WindowContainer> ime,
            DisplayArea<ActivityStack> tasks) {
        Map<DisplayArea<?>, Set<Integer>> zSets = new HashMap<>();
        int[] types = {TYPE_STATUS_BAR, TYPE_NAVIGATION_BAR, TYPE_PRESENTATION,
                TYPE_APPLICATION_OVERLAY};
        for (int type : types) {
            WindowToken token = tokenOfType(type);
            recordLayer(policy.findAreaForToken(token), token.getWindowLayerFromType(), zSets);
        }
        recordLayer(tasks, APPLICATION_LAYER, zSets);
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

    private WindowToken tokenOfType(int type) {
        WindowToken m = mock(WindowToken.class);
        when(m.getWindowLayerFromType()).thenReturn(
                mPolicy.getWindowLayerFromTypeLw(type, false /* canAddInternalSystemWindow */));
        return m;
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

    private static class SurfacelessDisplayAreaRoot extends DisplayArea.Root {

        SurfacelessDisplayAreaRoot(WindowManagerService wms) {
            super(wms);
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            return new MockSurfaceControlBuilder();
        }
    }

}
