/*
 * Copyright 2024 The Android Open Source Project
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

package android.view.accessibility.a11ychecker;

import static android.view.accessibility.a11ychecker.MockAccessibilityNodeInfoBuilder.PACKAGE_NAME;

import static com.google.common.truth.Truth.assertThat;

import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.RecyclerView;

import com.google.common.collect.ImmutableList;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AccessibilityNodePathBuilderTest {

    public static final String RESOURCE_ID_PREFIX = PACKAGE_NAME + ":id/";

    @Test
    public void createNodePath_pathWithResourceNames() {
        AccessibilityNodeInfo child = new MockAccessibilityNodeInfoBuilder()
                .setViewIdResourceName(RESOURCE_ID_PREFIX + "child_node")
                .build();
        AccessibilityNodeInfo parent =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName(RESOURCE_ID_PREFIX + "parent_node")
                        .addChildren(ImmutableList.of(child))
                        .build();
        AccessibilityNodeInfo root =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName(RESOURCE_ID_PREFIX + "root_node")
                        .addChildren(ImmutableList.of(parent))
                        .build();

        assertThat(AccessibilityNodePathBuilder.createNodePath(child))
                .isEqualTo(PACKAGE_NAME + ":root_node/parent_node[1]/child_node[1]");
        assertThat(AccessibilityNodePathBuilder.createNodePath(parent))
                .isEqualTo(PACKAGE_NAME + ":root_node/parent_node[1]");
        assertThat(AccessibilityNodePathBuilder.createNodePath(root))
                .isEqualTo(PACKAGE_NAME + ":root_node");
    }

    @Test
    public void createNodePath_pathWithoutResourceNames() {
        AccessibilityNodeInfo child =
                new MockAccessibilityNodeInfoBuilder()
                        .setClassName(TextView.class.getName())
                        .build();
        AccessibilityNodeInfo parent =

                new MockAccessibilityNodeInfoBuilder()
                        .setClassName(RecyclerView.class.getName())
                        .addChildren(ImmutableList.of(child))
                        .build();
        AccessibilityNodeInfo root =
                new MockAccessibilityNodeInfoBuilder()
                        .setClassName(FrameLayout.class.getName())
                        .addChildren(ImmutableList.of(parent))
                        .build();

        assertThat(AccessibilityNodePathBuilder.createNodePath(child))
                .isEqualTo(PACKAGE_NAME + ":FrameLayout/RecyclerView[1]/TextView[1]");
        assertThat(AccessibilityNodePathBuilder.createNodePath(parent))
                .isEqualTo(PACKAGE_NAME + ":FrameLayout/RecyclerView[1]");
        assertThat(AccessibilityNodePathBuilder.createNodePath(root))
                .isEqualTo(PACKAGE_NAME + ":FrameLayout");
    }

    @Test
    public void createNodePath_parentWithMultipleChildren() {
        AccessibilityNodeInfo child1 =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName(RESOURCE_ID_PREFIX + "child1")
                        .build();
        AccessibilityNodeInfo child2 =
                new MockAccessibilityNodeInfoBuilder()
                        .setClassName(TextView.class.getName())
                        .build();
        AccessibilityNodeInfo parent =
                new MockAccessibilityNodeInfoBuilder()
                        .setClassName(FrameLayout.class.getName())
                        .addChildren(ImmutableList.of(child1, child2))
                        .build();

        assertThat(AccessibilityNodePathBuilder.createNodePath(child1))
                .isEqualTo(PACKAGE_NAME + ":FrameLayout/child1[1]");
        assertThat(AccessibilityNodePathBuilder.createNodePath(child2))
                .isEqualTo(PACKAGE_NAME + ":FrameLayout/TextView[2]");
        assertThat(AccessibilityNodePathBuilder.createNodePath(parent))
                .isEqualTo(PACKAGE_NAME + ":FrameLayout");
    }

    @Test
    public void createNodePath_handlesDifferentIdFormats() {
        AccessibilityNodeInfo child1 =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName(RESOURCE_ID_PREFIX + "childId")
                        .build();
        AccessibilityNodeInfo child2 =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName(RESOURCE_ID_PREFIX + "child/Id/With/Slash")
                        .build();
        AccessibilityNodeInfo child3 =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName("childIdWithoutPrefix")
                        .build();
        AccessibilityNodeInfo parent =
                new MockAccessibilityNodeInfoBuilder()
                        .addChildren(ImmutableList.of(child1, child2, child3))
                        .setViewIdResourceName(RESOURCE_ID_PREFIX + "parentId")
                        .build();

        assertThat(AccessibilityNodePathBuilder.createNodePath(child1))
                .isEqualTo(PACKAGE_NAME + ":parentId/childId[1]");
        assertThat(AccessibilityNodePathBuilder.createNodePath(child2))
                .isEqualTo(PACKAGE_NAME + ":parentId/child/Id/With/Slash[2]");
        assertThat(AccessibilityNodePathBuilder.createNodePath(child3))
                .isEqualTo(PACKAGE_NAME + ":parentId/childIdWithoutPrefix[3]");
        assertThat(AccessibilityNodePathBuilder.createNodePath(parent))
                .isEqualTo(PACKAGE_NAME + ":parentId");
    }

}
