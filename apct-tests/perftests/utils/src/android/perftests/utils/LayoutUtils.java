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
 * limitations under the License.
 */

package android.perftests.utils;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

public class LayoutUtils {

    private static void recursivelyGather(ViewGroup currentNode, List<View> nodeList) {
        nodeList.add(currentNode);
        int count = currentNode.getChildCount();
        for (int i = 0; i < count; i++) {
            View view = currentNode.getChildAt(i);
            if (view instanceof ViewGroup) {
                recursivelyGather((ViewGroup) view, nodeList);
            } else {
                nodeList.add(view);
            }
        }
    }

    /**
     * Flattern the whole view tree into a list of View.
     */
    public static List<View> gatherViewTree(ViewGroup root) {
        List<View> result = new ArrayList<View>();
        recursivelyGather(root, result);
        return result;
    }

    /**
     * For every node in the list, call requestLayout.
     */
    public static void requestLayoutForAllNodes(List<View> nodeList) {
        int count = nodeList.size();
        for (int i = 0; i < count; i++) {
            nodeList.get(i).requestLayout();
        }
    }
}
