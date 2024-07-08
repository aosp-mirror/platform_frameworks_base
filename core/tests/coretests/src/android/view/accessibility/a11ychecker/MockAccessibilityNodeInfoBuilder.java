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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

final class MockAccessibilityNodeInfoBuilder {
    static final String PACKAGE_NAME = "com.example.app";
    private final AccessibilityNodeInfo mMockNodeInfo = mock(AccessibilityNodeInfo.class);

    MockAccessibilityNodeInfoBuilder() {
        when(mMockNodeInfo.getPackageName()).thenReturn(PACKAGE_NAME);
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
