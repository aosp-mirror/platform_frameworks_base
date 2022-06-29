/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
import static android.content.res.Configuration.EMPTY;
import static android.content.res.Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Test class for {@link ConfigurationContainer}.
 *
 * Build/Install/Run:
 *  atest WmTests:ConfigurationContainerTests
 */
@SmallTest
@Presubmit
public class ConfigurationContainerTests {

    @Test
    public void testConfigurationInit() {
        // Check root container initial config.
        final TestConfigurationContainer root = new TestConfigurationContainer();
        assertEquals(EMPTY, root.getRequestedOverrideConfiguration());
        assertEquals(EMPTY, root.getMergedOverrideConfiguration());
        assertEquals(EMPTY, root.getConfiguration());

        // Check child initial config.
        final TestConfigurationContainer child1 = root.addChild();
        assertEquals(EMPTY, child1.getRequestedOverrideConfiguration());
        assertEquals(EMPTY, child1.getMergedOverrideConfiguration());
        assertEquals(EMPTY, child1.getConfiguration());

        // Check child initial config if root has overrides.
        final Configuration rootOverrideConfig = new Configuration();
        rootOverrideConfig.fontScale = 1.3f;
        root.onRequestedOverrideConfigurationChanged(rootOverrideConfig);
        final TestConfigurationContainer child2 = root.addChild();
        assertEquals(EMPTY, child2.getRequestedOverrideConfiguration());
        assertEquals(rootOverrideConfig, child2.getMergedOverrideConfiguration());
        assertEquals(rootOverrideConfig, child2.getConfiguration());

        // Check child initial config if root has parent config set.
        final Configuration rootParentConfig = new Configuration();
        rootParentConfig.fontScale = 0.8f;
        rootParentConfig.orientation = SCREEN_ORIENTATION_LANDSCAPE;
        root.onConfigurationChanged(rootParentConfig);
        final Configuration rootFullConfig = new Configuration(rootParentConfig);
        rootFullConfig.updateFrom(rootOverrideConfig);

        final TestConfigurationContainer child3 = root.addChild();
        assertEquals(EMPTY, child3.getRequestedOverrideConfiguration());
        assertEquals(rootOverrideConfig, child3.getMergedOverrideConfiguration());
        assertEquals(rootFullConfig, child3.getConfiguration());
    }

    @Test
    public void testConfigurationChangeOnAddRemove() {
        // Init root's config.
        final TestConfigurationContainer root = new TestConfigurationContainer();
        final Configuration rootOverrideConfig = new Configuration();
        rootOverrideConfig.fontScale = 1.3f;
        root.onRequestedOverrideConfigurationChanged(rootOverrideConfig);

        // Init child's config.
        final TestConfigurationContainer child = root.addChild();
        final Configuration childOverrideConfig = new Configuration();
        childOverrideConfig.densityDpi = 320;
        child.onRequestedOverrideConfigurationChanged(childOverrideConfig);
        final Configuration mergedOverrideConfig = new Configuration(root.getConfiguration());
        mergedOverrideConfig.updateFrom(childOverrideConfig);

        // Check configuration update when child is removed from parent.
        root.removeChild(child);
        assertEquals(childOverrideConfig, child.getRequestedOverrideConfiguration());
        assertEquals(mergedOverrideConfig, child.getMergedOverrideConfiguration());
        assertEquals(mergedOverrideConfig, child.getConfiguration());

        // It may be paranoia... but let's check if parent's config didn't change after removal.
        assertEquals(rootOverrideConfig, root.getRequestedOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getMergedOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getConfiguration());

        // Init different root
        final TestConfigurationContainer root2 = new TestConfigurationContainer();
        final Configuration rootOverrideConfig2 = new Configuration();
        rootOverrideConfig2.fontScale = 1.1f;
        root2.onRequestedOverrideConfigurationChanged(rootOverrideConfig2);

        // Check configuration update when child is added to different parent.
        mergedOverrideConfig.setTo(rootOverrideConfig2);
        mergedOverrideConfig.updateFrom(childOverrideConfig);
        root2.addChild(child);
        assertEquals(childOverrideConfig, child.getRequestedOverrideConfiguration());
        assertEquals(mergedOverrideConfig, child.getMergedOverrideConfiguration());
        assertEquals(mergedOverrideConfig, child.getConfiguration());
    }

    @Test
    public void testConfigurationChangePropagation() {
        // Builds 3-level vertical hierarchy with one configuration container on each level.
        // In addition to different overrides on each level, everyone in hierarchy will have one
        // common overridden value - orientation;

        // Init root's config.
        final TestConfigurationContainer root = new TestConfigurationContainer();
        final Configuration rootOverrideConfig = new Configuration();
        rootOverrideConfig.fontScale = 1.3f;
        rootOverrideConfig.orientation = SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        root.onRequestedOverrideConfigurationChanged(rootOverrideConfig);

        // Init children.
        final TestConfigurationContainer child1 = root.addChild();
        final Configuration childOverrideConfig1 = new Configuration();
        childOverrideConfig1.densityDpi = 320;
        childOverrideConfig1.orientation = SCREEN_ORIENTATION_LANDSCAPE;
        child1.onRequestedOverrideConfigurationChanged(childOverrideConfig1);

        final TestConfigurationContainer child2 = child1.addChild();
        final Configuration childOverrideConfig2 = new Configuration();
        childOverrideConfig2.screenWidthDp = 150;
        childOverrideConfig2.orientation = SCREEN_ORIENTATION_PORTRAIT;
        child2.onRequestedOverrideConfigurationChanged(childOverrideConfig2);

        // Check configuration on all levels when root override is updated.
        rootOverrideConfig.smallestScreenWidthDp = 200;
        root.onRequestedOverrideConfigurationChanged(rootOverrideConfig);

        final Configuration mergedOverrideConfig1 = new Configuration(rootOverrideConfig);
        mergedOverrideConfig1.updateFrom(childOverrideConfig1);
        final Configuration mergedConfig1 = new Configuration(mergedOverrideConfig1);

        final Configuration mergedOverrideConfig2 = new Configuration(mergedOverrideConfig1);
        mergedOverrideConfig2.updateFrom(childOverrideConfig2);
        final Configuration mergedConfig2 = new Configuration(mergedOverrideConfig2);

        assertEquals(rootOverrideConfig, root.getRequestedOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getMergedOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getConfiguration());

        assertEquals(childOverrideConfig1, child1.getRequestedOverrideConfiguration());
        assertEquals(mergedOverrideConfig1, child1.getMergedOverrideConfiguration());
        assertEquals(mergedConfig1, child1.getConfiguration());

        assertEquals(childOverrideConfig2, child2.getRequestedOverrideConfiguration());
        assertEquals(mergedOverrideConfig2, child2.getMergedOverrideConfiguration());
        assertEquals(mergedConfig2, child2.getConfiguration());

        // Check configuration on all levels when root parent config is updated.
        final Configuration rootParentConfig = new Configuration();
        rootParentConfig.screenHeightDp = 100;
        rootParentConfig.orientation = SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        root.onConfigurationChanged(rootParentConfig);
        final Configuration mergedRootConfig = new Configuration(rootParentConfig);
        mergedRootConfig.updateFrom(rootOverrideConfig);

        mergedConfig1.setTo(mergedRootConfig);
        mergedConfig1.updateFrom(mergedOverrideConfig1);

        mergedConfig2.setTo(mergedConfig1);
        mergedConfig2.updateFrom(mergedOverrideConfig2);

        assertEquals(rootOverrideConfig, root.getRequestedOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getMergedOverrideConfiguration());
        assertEquals(mergedRootConfig, root.getConfiguration());

        assertEquals(childOverrideConfig1, child1.getRequestedOverrideConfiguration());
        assertEquals(mergedOverrideConfig1, child1.getMergedOverrideConfiguration());
        assertEquals(mergedConfig1, child1.getConfiguration());

        assertEquals(childOverrideConfig2, child2.getRequestedOverrideConfiguration());
        assertEquals(mergedOverrideConfig2, child2.getMergedOverrideConfiguration());
        assertEquals(mergedConfig2, child2.getConfiguration());
    }

    @Test
    public void testSetAlwaysOnTop() {
        final TestConfigurationContainer root = new TestConfigurationContainer();
        final TestConfigurationContainer child1 = root.addChild();
        final TestConfigurationContainer child2 = root.addChild();
        root.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        root.setAlwaysOnTop(true);
        final TestConfigurationContainer child3 = root.addChild();
        assertEquals(true, root.isAlwaysOnTop());
        assertEquals(false, child1.isAlwaysOnTop());
        assertEquals(false, child2.isAlwaysOnTop());
        assertEquals(false, child3.isAlwaysOnTop());
    }

    @Test
    public void testSetWindowingMode() {
        final TestConfigurationContainer root = new TestConfigurationContainer();
        root.setWindowingMode(WINDOWING_MODE_UNDEFINED);
        final TestConfigurationContainer child = root.addChild();
        child.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(WINDOWING_MODE_UNDEFINED, root.getWindowingMode());
        assertEquals(WINDOWING_MODE_FREEFORM, child.getWindowingMode());

        root.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertEquals(WINDOWING_MODE_FULLSCREEN, root.getWindowingMode());
        assertEquals(WINDOWING_MODE_FREEFORM, child.getWindowingMode());
    }

    @Test
    public void testSetActivityType() {
        final TestConfigurationContainer root = new TestConfigurationContainer();
        root.setActivityType(ACTIVITY_TYPE_UNDEFINED);
        final TestConfigurationContainer child = root.addChild();
        child.setActivityType(ACTIVITY_TYPE_STANDARD);
        assertEquals(ACTIVITY_TYPE_UNDEFINED, root.getActivityType());
        assertEquals(ACTIVITY_TYPE_STANDARD, child.getActivityType());

        boolean gotException = false;
        try {
            // Can't change activity type once set.
            child.setActivityType(ACTIVITY_TYPE_HOME);
        } catch (IllegalStateException e) {
            gotException = true;
        }
        assertTrue("Can't change activity type once set.", gotException);

        // TODO: Commenting out for now until we figure-out a good way to test these rules that
        // should only apply to system process.
        /*
        gotException = false;
        try {
            // Parent can't change child's activity type once set.
            root.setActivityType(ACTIVITY_TYPE_HOME);
        } catch (IllegalStateException e) {
            gotException = true;
        }
        assertTrue("Parent can't change activity type once set.", gotException);
        assertEquals(ACTIVITY_TYPE_HOME, root.getActivityType());

        final TestConfigurationContainer child2 = new TestConfigurationContainer();
        child2.setActivityType(ACTIVITY_TYPE_RECENTS);

        gotException = false;
        try {
            // Can't re-parent to a different activity type.
            root.addChild(child2);
        } catch (IllegalStateException e) {
            gotException = true;
        }
        assertTrue("Can't re-parent to a different activity type.", gotException);
        */

    }

    @Test
    public void testRegisterConfigurationChangeListener() {
        final TestConfigurationContainer container = new TestConfigurationContainer();
        final TestConfigurationChangeListener listener = new TestConfigurationChangeListener();
        final Configuration config = new Configuration();
        config.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        config.windowConfiguration.setAppBounds(10, 10, 10, 10);
        container.onRequestedOverrideConfigurationChanged(config);
        container.registerConfigurationChangeListener(listener);
        // Assert listener got the current config. of the container after it was registered.
        assertEquals(config, listener.mOverrideConfiguration);
        // Assert listener gets changes to override configuration.
        container.onRequestedOverrideConfigurationChanged(EMPTY);
        assertEquals(EMPTY, listener.mOverrideConfiguration);
    }

    @Test
    public void testConfigurationConstraints() {
        // Init root config.
        final TestConfigurationContainer root = new TestConfigurationContainer();
        final Configuration rootOverrideConfig = new Configuration();
        rootOverrideConfig.smallestScreenWidthDp = 140;
        root.onRequestedOverrideConfigurationChanged(rootOverrideConfig);

        // Init child with constraint
        final TestConfigurationChangeListener listener = new TestConfigurationChangeListener();
        final TestConfigurationContainer child1 = root.addConstraintChild();
        child1.registerConfigurationChangeListener(listener);
        final Configuration childOverrideConfig1 = new Configuration();
        childOverrideConfig1.orientation = SCREEN_ORIENTATION_LANDSCAPE;
        child1.onRequestedOverrideConfigurationChanged(childOverrideConfig1);

        assertEquals(SMALLEST_SCREEN_WIDTH_DP_UNDEFINED,
                child1.getRequestedOverrideConfiguration().smallestScreenWidthDp);
        assertEquals(100, child1.getConfiguration().smallestScreenWidthDp);
        assertEquals(100, listener.mOverrideConfiguration.smallestScreenWidthDp);

        // Check configuration on all levels when root override is updated.
        rootOverrideConfig.smallestScreenWidthDp = 80;
        root.onRequestedOverrideConfigurationChanged(rootOverrideConfig);

        assertEquals(SMALLEST_SCREEN_WIDTH_DP_UNDEFINED,
                child1.getRequestedOverrideConfiguration().smallestScreenWidthDp);
        assertEquals(80, child1.getConfiguration().smallestScreenWidthDp);
        assertEquals(80, listener.mOverrideConfiguration.smallestScreenWidthDp);

        rootOverrideConfig.smallestScreenWidthDp = 180;
        root.onRequestedOverrideConfigurationChanged(rootOverrideConfig);

        assertEquals(SMALLEST_SCREEN_WIDTH_DP_UNDEFINED,
                child1.getRequestedOverrideConfiguration().smallestScreenWidthDp);
        assertEquals(100, child1.getConfiguration().smallestScreenWidthDp);
        assertEquals(100, child1.getMergedOverrideConfiguration().smallestScreenWidthDp);
        assertEquals(100, listener.mOverrideConfiguration.smallestScreenWidthDp);
    }

    @Test
    public void testSetMaxBoundsByHierarchy() {
        final TestConfigurationContainer root =
                new TestConfigurationContainer(true /* providesMaxBounds */);
        final Rect bounds = new Rect(0, 0, 10, 10);
        final TestConfigurationContainer child = new TestConfigurationContainer();
        root.addChild(child);

        root.setBounds(bounds);

        assertEquals(bounds, root.getBounds());
        assertEquals(bounds, root.getConfiguration().windowConfiguration.getBounds());
        assertEquals(bounds, child.getBounds());
        assertEquals(bounds, child.getConfiguration().windowConfiguration.getBounds());

        assertEquals(bounds, root.getMaxBounds());
        assertEquals(bounds, root.getConfiguration().windowConfiguration.getMaxBounds());
        assertEquals(bounds, child.getMaxBounds());
        assertEquals(bounds, child.getConfiguration().windowConfiguration.getMaxBounds());
    }

    @Test
    public void testSetBoundsNotOverrideMaxBounds() {
        final TestConfigurationContainer root = new TestConfigurationContainer();
        final Rect bounds = new Rect(0, 0, 10, 10);
        final TestConfigurationContainer child = new TestConfigurationContainer();
        root.addChild(child);

        root.setBounds(bounds);

        assertEquals(bounds, root.getBounds());
        assertEquals(bounds, root.getConfiguration().windowConfiguration.getBounds());
        assertEquals(bounds, child.getBounds());
        assertEquals(bounds, child.getConfiguration().windowConfiguration.getBounds());

        assertTrue(root.getMaxBounds().isEmpty());
        assertTrue(root.getConfiguration().windowConfiguration.getMaxBounds().isEmpty());
        assertTrue(child.getMaxBounds().isEmpty());
        assertTrue(child.getConfiguration().windowConfiguration.getMaxBounds().isEmpty());
    }

    @Test
    public void testOnRequestedOverrideConfigurationChangedOverrideMaxBounds() {
        final TestConfigurationContainer root =
                new TestConfigurationContainer(true /* providesMaxBounds */);
        final Rect bounds = new Rect(0, 0, 10, 10);
        final TestConfigurationContainer child = new TestConfigurationContainer();
        root.addChild(child);
        final Configuration configuration = new Configuration();
        configuration.windowConfiguration.setBounds(bounds);

        root.onRequestedOverrideConfigurationChanged(configuration);

        assertEquals(bounds, root.getBounds());
        assertEquals(bounds, root.getConfiguration().windowConfiguration.getBounds());
        assertEquals(bounds, child.getBounds());
        assertEquals(bounds, child.getConfiguration().windowConfiguration.getBounds());

        assertEquals(bounds, root.getMaxBounds());
        assertEquals(bounds, root.getConfiguration().windowConfiguration.getMaxBounds());
        assertEquals(bounds, child.getMaxBounds());
        assertEquals(bounds, child.getConfiguration().windowConfiguration.getMaxBounds());
    }


    /**
     * Contains minimal implementation of {@link ConfigurationContainer}'s abstract behavior needed
     * for testing.
     */
    private class TestConfigurationContainer
            extends ConfigurationContainer<TestConfigurationContainer> {
        private List<TestConfigurationContainer> mChildren = new ArrayList<>();
        private TestConfigurationContainer mParent;

        private boolean mProvidesMaxBounds = false;

        TestConfigurationContainer() {}

        TestConfigurationContainer(boolean providesMaxBounds) {
            mProvidesMaxBounds = providesMaxBounds;
        }

        TestConfigurationContainer addChild(TestConfigurationContainer childContainer) {
            final ConfigurationContainer oldParent = childContainer.getParent();
            childContainer.mParent = this;
            childContainer.onParentChanged(this, oldParent);
            mChildren.add(childContainer);
            return childContainer;
        }

        TestConfigurationContainer addChild() {
            return addChild(new TestConfigurationContainer());
        }

        TestConfigurationContainer addConstraintChild() {
            return addChild(new TestConfigurationContainerWithConstraints());
        }

        void removeChild(TestConfigurationContainer child) {
            final ConfigurationContainer oldParent = child.getParent();
            child.mParent = null;
            child.onParentChanged(null, oldParent);
        }

        @Override
        protected int getChildCount() {
            return mChildren.size();
        }

        @Override
        protected TestConfigurationContainer getChildAt(int index) {
            return mChildren.get(index);
        }

        @Override
        protected ConfigurationContainer getParent() {
            return mParent;
        }

        @Override
        public boolean providesMaxBounds() {
            return mProvidesMaxBounds;
        }
    }

    /**
     * Contains minimal implementation of {@link ConfigurationContainer}'s abstract behavior needed
     * for testing.
     */
    private class TestConfigurationContainerWithConstraints
            extends TestConfigurationContainer {

        @Override
        public void resolveOverrideConfiguration(Configuration newParentConfig) {
            // Restrict smallestScreenWidthDp to 100
            getResolvedOverrideConfiguration().setTo(getRequestedOverrideConfiguration());
            int smallestScreenWidthDp =
                    getResolvedOverrideConfiguration().smallestScreenWidthDp
                            == SMALLEST_SCREEN_WIDTH_DP_UNDEFINED
                    ? newParentConfig.smallestScreenWidthDp
                        : getResolvedOverrideConfiguration().smallestScreenWidthDp;
            if (smallestScreenWidthDp != SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
                getResolvedOverrideConfiguration().smallestScreenWidthDp =
                        Math.min(smallestScreenWidthDp, 100);
            }
        }
    }

    private static class TestConfigurationChangeListener implements ConfigurationContainerListener {

        final Configuration mOverrideConfiguration = new Configuration();

        @Override
        public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
            mOverrideConfiguration.setTo(overrideConfiguration);
        }
    }
}
