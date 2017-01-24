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
package android.view.autofill;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;

/**
 * This class is the contract a client should implement to enable support of a
 * virtual view hierarchy rooted at a given view for auto-fill purposes.
 *
 * <p>The view hierarchy is typically created through the
 * {@link View#onProvideAutoFillVirtualStructure(android.view.ViewStructure, int)} call and client
 * add virtual children by calling {@link ViewStructure#newChild(int, int, int)} or
 * {@link ViewStructure#asyncNewChild(int, int, int)}, where the client provides the
 * {@code virtualId} of the children - the same {@code virtualId} is used in the methods of this
 * class.
 *
 * <p>Objects of this class are typically created by overriding
 * {@link View#getAutoFillVirtualViewDelegate(Callback)} and saving the passed callback, which must
 * be notified upon changes on the hierarchy.
 *
 * <p>The main use case of these API is to enable custom views that draws its content - such as
 * {@link android.webkit.WebView} providers - to support the AutoFill Framework:
 *
 * <ol>
 *   <li>Client populates the virtual hierarchy on
 * {@link View#onProvideAutoFillVirtualStructure(android.view.ViewStructure, int)}
 *   <li>Android System generates the proper {@link AutoFillId} - encapsulating the view and the
 * virtual node ids - and pass it to the {@link android.service.autofill.AutoFillService}.
 *   <li>The service uses the {@link AutoFillId} to populate the auto-fill {@link Dataset}s and pass
 *   it back to the Android System.
 *   <li>Android System uses the {@link AutoFillId} to find the proper custom view and calls
 *   {@link #autoFill(int, AutoFillValue)} on that view passing the virtual id.
 *   <li>This provider than finds the node in the hierarchy and auto-fills it.
 * </ol>
 *
 */
public abstract class VirtualViewDelegate {

    // TODO(b/33197203): set to false once stable
    private static final boolean DEBUG = true;

    private static final String TAG = "VirtualViewDelegate";

    /**
     * Auto-fills a virtual view with the {@code value}.
     *
     * @param virtualId id identifying the virtual node inside the custom view.
     * @param value value to be auto-filled.
     */
    public abstract void autoFill(int virtualId, AutoFillValue value);

    /**
     * Callback used to notify the AutoFill Framework of changes made on the view hierarchy while
     * an {@link android.app.Activity} is being auto filled.
     */
    public abstract static class Callback {

        /**
         * Sent when the auto-fill bar for a child must be updated.
         *
         * See {@link AutoFillManager#updateAutoFillInput(View, int, android.graphics.Rect, int)}
         * for more details.
         */
        // TODO(b/33197203): do we really need it, or should the parent view just call
        // AutoFillManager.updateAutoFillInput() directly?
        public void onAutoFillInputUpdated(int virtualId, @Nullable Rect boundaries, int flags) {
            if (DEBUG) {
                Log.v(TAG, "onAutoFillInputUpdated(): virtualId=" + virtualId + ", boundaries="
                        + boundaries + ", flags=" + flags);
            }
        }

        /**
         * Sent when the value of a node was changed.
         *
         * <p>This method should only be called when the change was not caused by the AutoFill
         * Framework itselft (i.e, through {@link VirtualViewDelegate#autoFill(int, AutoFillValue)},
         * but by external causes (for example, when the user changed the value through the view's
         * UI).
         *
         * @param virtualId id of the node whose value changed.
         */
        public void onValueChanged(int virtualId) {
            if (DEBUG) Log.d(TAG, "onValueChanged() for" + virtualId);
        }

        /**
         * Sent when nodes were removed (or had their ids changed) after the hierarchy has been
         * committed to
         * {@link View#onProvideAutoFillVirtualStructure(android.view.ViewStructure, int)}.
         *
         * <p>For example, when the view is rendering an {@code HTML} page, it should call this
         * method when:
         * <ul>
         * <li>User navigated to another page and some (or all) nodes are gone.
         * <li>The page's {@code DOM} was changed by {@code JavaScript} and some nodes moved (and
         * are now identified by different ids).
         * </ul>
         *
         * @param virtualIds id of the nodes that were removed.
         */
        public void onNodeRemoved(int... virtualIds) {
            if (DEBUG) Log.d(TAG, "onNodeRemoved(): " + virtualIds);
        }
    }
}
