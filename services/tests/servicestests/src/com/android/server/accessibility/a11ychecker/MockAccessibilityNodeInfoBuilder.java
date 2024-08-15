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

package com.android.server.accessibility.a11ychecker;

import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_APP_PACKAGE_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_WINDOW_TITLE;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

final class MockAccessibilityNodeInfoBuilder {
    private final AccessibilityNodeInfo mMockNodeInfo = mock(AccessibilityNodeInfo.class);

    MockAccessibilityNodeInfoBuilder() {
        setPackageName(TEST_APP_PACKAGE_NAME);

        AccessibilityWindowInfo windowInfo = new AccessibilityWindowInfo();
        windowInfo.setTitle(TEST_WINDOW_TITLE);
        when(mMockNodeInfo.getWindow()).thenReturn(windowInfo);
    }

    MockAccessibilityNodeInfoBuilder setPackageName(String packageName) {
        when(mMockNodeInfo.getPackageName()).thenReturn(packageName);
        return this;
    }

    MockAccessibilityNodeInfoBuilder setClassName(String className) {
        when(mMockNodeInfo.getClassName()).thenReturn(className);
        return this;
    }

    MockAccessibilityNodeInfoBuilder setViewIdResourceName(String
            viewIdResourceName) {
        when(mMockNodeInfo.getViewIdResourceName()).thenReturn(viewIdResourceName);
        return this;
    }

    MockAccessibilityNodeInfoBuilder addChildren(List<AccessibilityNodeInfo>
            children) {
        when(mMockNodeInfo.getChildCount()).thenReturn(children.size());
        for (int i = 0; i < children.size(); i++) {
            when(mMockNodeInfo.getChild(i)).thenReturn(children.get(i));
            when(children.get(i).getParent()).thenReturn(mMockNodeInfo);
        }
        return this;
    }

    AccessibilityNodeInfo build() {
        return mMockNodeInfo;
    }
}
