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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Utility class to create developer-friendly {@link AccessibilityNodeInfo} path Strings for use
 * in reporting AccessibilityCheck results.
 *
 * @hide
 */
public final class AccessibilityNodePathBuilder {

    /**
     * Returns the path of the node within its accessibility hierarchy starting from the root node
     * down to the given node itself, and prefixed by the package name. This path is not guaranteed
     * to be unique. This can return null in case the node's hierarchy changes while scanning.
     *
     * <p>Each element in the path is represented by its View ID resource name, when available, or
     * the
     * simple class name if not. The path also includes the index of each child node relative to
     * its
     * parent. See {@link AccessibilityNodeInfo#getViewIdResourceName()}.
     *
     * <p>For example,
     * "com.example.app:RootElementClassName/parent_resource_name[1]/TargetElementClassName[3]"
     * indicates the element has type {@code TargetElementClassName}, and is the third child of an
     * element with the resource name {@code parent_resource_name}, which is the first child of an
     * element of type {@code RootElementClassName}.
     *
     * <p>This format is consistent with elements paths in Pre-Launch Reports and the Accessibility
     * Scanner, starting from the window's root node instead of the first resource name.
     * TODO (b/344607035): link to ClusteringUtils when AATF is merged in main.
     */
    public static @Nullable String createNodePath(@NonNull AccessibilityNodeInfo nodeInfo) {
        StringBuilder resourceIdBuilder = getNodePathBuilder(nodeInfo);
        return resourceIdBuilder == null ? null : String.valueOf(nodeInfo.getPackageName()) + ':'
                + resourceIdBuilder;
    }

    private static @Nullable StringBuilder getNodePathBuilder(AccessibilityNodeInfo nodeInfo) {
        AccessibilityNodeInfo parent = nodeInfo.getParent();
        if (parent == null) {
            return new StringBuilder(getShortUiElementName(nodeInfo));
        }
        StringBuilder parentNodePath = getNodePathBuilder(parent);
        if (parentNodePath == null) {
            return null;
        }
        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            if (!nodeInfo.equals(parent.getChild(i))) {
                continue;
            }
            CharSequence uiElementName = getShortUiElementName(nodeInfo);
            if (uiElementName != null) {
                parentNodePath.append('/').append(uiElementName).append('[').append(i + 1).append(
                        ']');
            } else {
                parentNodePath.append(":nth-child(").append(i + 1).append(')');
            }
            return parentNodePath;
        }
        return null;
    }

    //Returns the part of the element's View ID resource name after the qualifier
    // "package_name:id/"  or the last '/', when available. Otherwise, returns the element's
    // simple class name.
    private static CharSequence getShortUiElementName(AccessibilityNodeInfo nodeInfo) {
        String viewIdResourceName = nodeInfo.getViewIdResourceName();
        if (viewIdResourceName != null) {
            String idQualifier = ":id/";
            int idQualifierStartIndex = viewIdResourceName.indexOf(idQualifier);
            int unqualifiedNameStartIndex = idQualifierStartIndex == -1 ? 0
                    : (idQualifierStartIndex + idQualifier.length());
            return viewIdResourceName.substring(unqualifiedNameStartIndex);
        }
        return getSimpleClassName(nodeInfo);
    }

    private static CharSequence getSimpleClassName(AccessibilityNodeInfo nodeInfo) {
        CharSequence name = nodeInfo.getClassName();
        for (int i = name.length() - 1; i > 0; i--) {
            char ithChar = name.charAt(i);
            if (ithChar == '.' || ithChar == '$') {
                return name.subSequence(i + 1, name.length());
            }
        }
        return name;
    }

    private AccessibilityNodePathBuilder() {
    }
}
