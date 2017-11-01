/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;


import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.res.Configuration;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.util.ArrayList;
import java.util.List;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
import static org.junit.Assert.assertEquals;

/**
 * Test class for {@link ConfigurationContainer}. Mostly duplicates configuration tests from
 * {@link com.android.server.wm.WindowContainerTests}.
 *
 * Build: mmma -j32 frameworks/base/services/tests/servicestests
 * Install: adb install -r out/target/product/$TARGET_PRODUCT/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -w -e class com.android.server.am.ConfigurationContainerTests com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ConfigurationContainerTests {

    @Test
    public void testConfigurationInit() throws Exception {
        // Check root container initial config.
        final TestConfigurationContainer root = new TestConfigurationContainer();
        assertEquals(Configuration.EMPTY, root.getOverrideConfiguration());
        assertEquals(Configuration.EMPTY, root.getMergedOverrideConfiguration());
        assertEquals(Configuration.EMPTY, root.getConfiguration());

        // Check child initial config.
        final TestConfigurationContainer child1 = root.addChild();
        assertEquals(Configuration.EMPTY, child1.getOverrideConfiguration());
        assertEquals(Configuration.EMPTY, child1.getMergedOverrideConfiguration());
        assertEquals(Configuration.EMPTY, child1.getConfiguration());

        // Check child initial config if root has overrides.
        final Configuration rootOverrideConfig = new Configuration();
        rootOverrideConfig.fontScale = 1.3f;
        root.onOverrideConfigurationChanged(rootOverrideConfig);
        final TestConfigurationContainer child2 = root.addChild();
        assertEquals(Configuration.EMPTY, child2.getOverrideConfiguration());
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
        assertEquals(Configuration.EMPTY, child3.getOverrideConfiguration());
        assertEquals(rootOverrideConfig, child3.getMergedOverrideConfiguration());
        assertEquals(rootFullConfig, child3.getConfiguration());
    }

    @Test
    public void testConfigurationChangeOnAddRemove() throws Exception {
        // Init root's config.
        final TestConfigurationContainer root = new TestConfigurationContainer();
        final Configuration rootOverrideConfig = new Configuration();
        rootOverrideConfig.fontScale = 1.3f;
        root.onOverrideConfigurationChanged(rootOverrideConfig);

        // Init child's config.
        final TestConfigurationContainer child = root.addChild();
        final Configuration childOverrideConfig = new Configuration();
        childOverrideConfig.densityDpi = 320;
        child.onOverrideConfigurationChanged(childOverrideConfig);
        final Configuration mergedOverrideConfig = new Configuration(root.getConfiguration());
        mergedOverrideConfig.updateFrom(childOverrideConfig);

        // Check configuration update when child is removed from parent.
        root.removeChild(child);
        assertEquals(childOverrideConfig, child.getOverrideConfiguration());
        assertEquals(mergedOverrideConfig, child.getMergedOverrideConfiguration());
        assertEquals(mergedOverrideConfig, child.getConfiguration());

        // It may be paranoia... but let's check if parent's config didn't change after removal.
        assertEquals(rootOverrideConfig, root.getOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getMergedOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getConfiguration());

        // Init different root
        final TestConfigurationContainer root2 = new TestConfigurationContainer();
        final Configuration rootOverrideConfig2 = new Configuration();
        rootOverrideConfig2.fontScale = 1.1f;
        root2.onOverrideConfigurationChanged(rootOverrideConfig2);

        // Check configuration update when child is added to different parent.
        mergedOverrideConfig.setTo(rootOverrideConfig2);
        mergedOverrideConfig.updateFrom(childOverrideConfig);
        root2.addChild(child);
        assertEquals(childOverrideConfig, child.getOverrideConfiguration());
        assertEquals(mergedOverrideConfig, child.getMergedOverrideConfiguration());
        assertEquals(mergedOverrideConfig, child.getConfiguration());
    }

    @Test
    public void testConfigurationChangePropagation() throws Exception {
        // Builds 3-level vertical hierarchy with one configuration container on each level.
        // In addition to different overrides on each level, everyone in hierarchy will have one
        // common overridden value - orientation;

        // Init root's config.
        final TestConfigurationContainer root = new TestConfigurationContainer();
        final Configuration rootOverrideConfig = new Configuration();
        rootOverrideConfig.fontScale = 1.3f;
        rootOverrideConfig.orientation = SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        root.onOverrideConfigurationChanged(rootOverrideConfig);

        // Init children.
        final TestConfigurationContainer child1 = root.addChild();
        final Configuration childOverrideConfig1 = new Configuration();
        childOverrideConfig1.densityDpi = 320;
        childOverrideConfig1.orientation = SCREEN_ORIENTATION_LANDSCAPE;
        child1.onOverrideConfigurationChanged(childOverrideConfig1);

        final TestConfigurationContainer child2 = child1.addChild();
        final Configuration childOverrideConfig2 = new Configuration();
        childOverrideConfig2.screenWidthDp = 150;
        childOverrideConfig2.orientation = SCREEN_ORIENTATION_PORTRAIT;
        child2.onOverrideConfigurationChanged(childOverrideConfig2);

        // Check configuration on all levels when root override is updated.
        rootOverrideConfig.smallestScreenWidthDp = 200;
        root.onOverrideConfigurationChanged(rootOverrideConfig);

        final Configuration mergedOverrideConfig1 = new Configuration(rootOverrideConfig);
        mergedOverrideConfig1.updateFrom(childOverrideConfig1);
        final Configuration mergedConfig1 = new Configuration(mergedOverrideConfig1);

        final Configuration mergedOverrideConfig2 = new Configuration(mergedOverrideConfig1);
        mergedOverrideConfig2.updateFrom(childOverrideConfig2);
        final Configuration mergedConfig2 = new Configuration(mergedOverrideConfig2);

        assertEquals(rootOverrideConfig, root.getOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getMergedOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getConfiguration());

        assertEquals(childOverrideConfig1, child1.getOverrideConfiguration());
        assertEquals(mergedOverrideConfig1, child1.getMergedOverrideConfiguration());
        assertEquals(mergedConfig1, child1.getConfiguration());

        assertEquals(childOverrideConfig2, child2.getOverrideConfiguration());
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

        assertEquals(rootOverrideConfig, root.getOverrideConfiguration());
        assertEquals(rootOverrideConfig, root.getMergedOverrideConfiguration());
        assertEquals(mergedRootConfig, root.getConfiguration());

        assertEquals(childOverrideConfig1, child1.getOverrideConfiguration());
        assertEquals(mergedOverrideConfig1, child1.getMergedOverrideConfiguration());
        assertEquals(mergedConfig1, child1.getConfiguration());

        assertEquals(childOverrideConfig2, child2.getOverrideConfiguration());
        assertEquals(mergedOverrideConfig2, child2.getMergedOverrideConfiguration());
        assertEquals(mergedConfig2, child2.getConfiguration());
    }

    /**
     * Contains minimal implementation of {@link ConfigurationContainer}'s abstract behavior needed
     * for testing.
     */
    private class TestConfigurationContainer
            extends ConfigurationContainer<TestConfigurationContainer> {
        private List<TestConfigurationContainer> mChildren = new ArrayList<>();
        private TestConfigurationContainer mParent;

        TestConfigurationContainer addChild(TestConfigurationContainer childContainer) {
            childContainer.mParent = this;
            childContainer.onParentChanged();
            mChildren.add(childContainer);
            return childContainer;
        }

        TestConfigurationContainer addChild() {
            return addChild(new TestConfigurationContainer());
        }

        void removeChild(TestConfigurationContainer child) {
            child.mParent = null;
            child.onParentChanged();
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
    }
}
