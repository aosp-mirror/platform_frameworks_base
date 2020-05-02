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
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManagerPolicyConstants.APPLICATION_LAYER;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_ONE_HANDED;

import static com.android.server.wm.DisplayArea.Type.ABOVE_TASKS;
import static com.android.server.wm.DisplayAreaPolicyBuilder.Feature;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static java.util.stream.Collectors.toList;

import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import androidx.test.filters.FlakyTest;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
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

@Presubmit
public class DisplayAreaPolicyBuilderTest {

    @Rule
    public final SystemServicesTestRule mSystemServices = new SystemServicesTestRule();

    private TestWindowManagerPolicy mPolicy = new TestWindowManagerPolicy(null, null);

    @Test
    @FlakyTest(bugId = 149760939)
    public void testBuilder() {
        WindowManagerService wms = mSystemServices.getWindowManagerService();
        DisplayArea.Root root = new SurfacelessDisplayAreaRoot(wms);
        DisplayArea<WindowContainer> ime = new DisplayArea<>(wms, ABOVE_TASKS, "Ime");
        DisplayContent displayContent = mock(DisplayContent.class);
        TaskDisplayArea taskDisplayArea = new TaskDisplayArea(displayContent, wms, "Tasks",
                FEATURE_DEFAULT_TASK_CONTAINER);
        List<TaskDisplayArea> taskDisplayAreaList = new ArrayList<>();
        taskDisplayAreaList.add(taskDisplayArea);

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
                .build(wms, displayContent, root, ime, taskDisplayAreaList);

        policy.attachDisplayAreas();

        assertThat(policy.getDisplayAreas(foo), is(not(empty())));
        assertThat(policy.getDisplayAreas(bar), is(not(empty())));

        assertThat(policy.findAreaForToken(tokenOfType(TYPE_STATUS_BAR)),
                is(decendantOfOneOf(policy.getDisplayAreas(foo))));
        assertThat(policy.findAreaForToken(tokenOfType(TYPE_STATUS_BAR)),
                is(not(decendantOfOneOf(policy.getDisplayAreas(bar)))));

        assertThat(taskDisplayArea,
                is(decendantOfOneOf(policy.getDisplayAreas(foo))));
        assertThat(taskDisplayArea,
                is(decendantOfOneOf(policy.getDisplayAreas(bar))));

        assertThat(ime,
                is(decendantOfOneOf(policy.getDisplayAreas(foo))));
        assertThat(ime,
                is(decendantOfOneOf(policy.getDisplayAreas(bar))));

        List<DisplayArea<?>> actualOrder = collectLeafAreas(root);
        Map<DisplayArea<?>, Set<Integer>> zSets = calculateZSets(policy, root, ime,
                taskDisplayArea);
        actualOrder = actualOrder.stream().filter(zSets::containsKey).collect(toList());

        Map<DisplayArea<?>, Integer> expectedByMinLayer = mapValues(zSets,
                v -> v.stream().min(Integer::compareTo).get());
        Map<DisplayArea<?>, Integer> expectedByMaxLayer = mapValues(zSets,
                v -> v.stream().max(Integer::compareTo).get());

        assertThat(expectedByMinLayer, is(equalTo(expectedByMaxLayer)));
        assertThat(actualOrder, is(equalTo(expectedByMaxLayer)));
    }

    @Test
    public void testBuilder_defaultPolicy_hasOneHandedFeature() {
        WindowManagerService wms = mSystemServices.getWindowManagerService();
        DisplayArea.Root root = new SurfacelessDisplayAreaRoot(wms);
        DisplayArea<WindowContainer> ime = new DisplayArea<>(wms, ABOVE_TASKS, "Ime");
        DisplayContent displayContent = mock(DisplayContent.class);
        TaskDisplayArea taskDisplayArea = new TaskDisplayArea(displayContent, wms, "Tasks",
                FEATURE_DEFAULT_TASK_CONTAINER);
        List<TaskDisplayArea> taskDisplayAreaList = new ArrayList<>();
        taskDisplayAreaList.add(taskDisplayArea);

        final DisplayAreaPolicy.Provider defaultProvider = DisplayAreaPolicy.Provider.fromResources(
                resourcesWithProvider(""));
        final DisplayAreaPolicyBuilder.Result defaultPolicy =
                (DisplayAreaPolicyBuilder.Result) defaultProvider.instantiate(wms, displayContent,
                        root, ime);
        final List<Feature> features = defaultPolicy.getFeatures();
        boolean hasOneHandedFeature = false;
        for (int i = 0; i < features.size(); i++) {
            hasOneHandedFeature |= features.get(i).getId() == FEATURE_ONE_HANDED;
        }

        assertTrue(hasOneHandedFeature);
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

    private Matcher<WindowContainer> decendantOfOneOf(List<? extends WindowContainer> expected) {
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
        when(m.getWindowLayerFromType()).thenReturn(mPolicy.getWindowLayerFromTypeLw(type));
        return m;
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
