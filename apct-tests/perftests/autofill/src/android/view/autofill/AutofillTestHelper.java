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

package android.view.autofill;

import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.app.assist.AssistStructure.WindowNode;
import android.service.autofill.FillContext;
import android.util.Log;

import java.util.List;

/**
 * Helper for common funcionalities.
 */
public class AutofillTestHelper {
    private static final String TAG = "AutofillTestHelper";

    /**
     * Gets a node given its Android resource id, or {@code null} if not found.
     */
    public static ViewNode findNodeByResourceId(List<FillContext> contexts, String resourceId) {
        for (FillContext context : contexts) {
            ViewNode node = findNodeByResourceId(context.getStructure(), resourceId);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    /**
     * Gets a node if it matches the filter criteria for the given id.
     */
    private static ViewNode findNodeByResourceId(AssistStructure structure, String id) {
        Log.v(TAG, "Parsing request for activity " + structure.getActivityComponent());
        final int nodes = structure.getWindowNodeCount();
        for (int i = 0; i < nodes; i++) {
            final WindowNode windowNode = structure.getWindowNodeAt(i);
            final ViewNode rootNode = windowNode.getRootViewNode();
            final ViewNode node = findNodeByResourceId(rootNode, id);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    /**
     * Gets a node if it matches the filter criteria for the given id.
     */
    private static ViewNode findNodeByResourceId(ViewNode node, String id) {
        if (id.equals(node.getIdEntry())) {
            return node;
        }
        final int childrenSize = node.getChildCount();
        if (childrenSize > 0) {
            for (int i = 0; i < childrenSize; i++) {
                final ViewNode found = findNodeByResourceId(node.getChildAt(i), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
