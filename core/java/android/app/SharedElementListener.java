/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.app;

import android.view.View;

import java.util.List;
import java.util.Map;

/**
 * Listener provided in
 * {@link Activity#setEnterSharedElementListener(SharedElementListener)} and
 * {@link Activity#setExitSharedElementListener(SharedElementListener)}
 * to monitor the Activity transitions. The events can be used to customize Activity
 * Transition behavior.
 */
public abstract class SharedElementListener {

    static final SharedElementListener NULL_LISTENER = new SharedElementListener() {
    };

    /**
     * Called to allow the listener to customize the start state of the shared element when
     * transferring in shared element state.
     * <p>
     *     The shared element will start at the size and position of the shared element
     *     in the launching Activity or Fragment. It will also transfer ImageView scaleType
     *     and imageMatrix if the shared elements in the calling and called Activities are
     *     ImageViews. Some applications may want to make additional changes, such as
     *     changing the clip bounds, scaling, or rotation if the shared element end state
     *     does not map well to the start state.
     * </p>
     *
     * @param sharedElementNames The names of the shared elements that were accepted into
     *                           the View hierarchy.
     * @param sharedElements The shared elements that are part of the View hierarchy.
     * @param sharedElementSnapshots The Views containing snap shots of the shared element
     *                               from the launching Window. These elements will not
     *                               be part of the scene, but will be positioned relative
     *                               to the Window decor View.
     */
    public void setSharedElementStart(List<String> sharedElementNames,
            List<View> sharedElements, List<View> sharedElementSnapshots) {}

    /**
     * Called to allow the listener to customize the end state of the shared element when
     * transferring in shared element state.
     * <p>
     *     Any customization done in
     *     {@link #setSharedElementStart(java.util.List, java.util.List, java.util.List)}
     *     may need to be modified to the final state of the shared element if it is not
     *     automatically corrected by layout. For example, rotation or scale will not
     *     be affected by layout and if changed in {@link #setSharedElementStart(java.util.List,
     *     java.util.List, java.util.List)}, it will also have to be set here again to correct
     *     the end state.
     * </p>
     *
     * @param sharedElementNames The names of the shared elements that were accepted into
     *                           the View hierarchy.
     * @param sharedElements The shared elements that are part of the View hierarchy.
     * @param sharedElementSnapshots The Views containing snap shots of the shared element
     *                               from the launching Window. These elements will not
     *                               be part of the scene, but will be positioned relative
     *                               to the Window decor View.
     */
    public void setSharedElementEnd(List<String> sharedElementNames,
            List<View> sharedElements, List<View> sharedElementSnapshots) {}

    /**
     * Called after {@link #remapSharedElements(java.util.List, java.util.Map)} when
     * transferring shared elements in. Any shared elements that have no mapping will be in
     * <var>rejectedSharedElements</var>. The elements remaining in
     * <var>rejectedSharedElements</var> will be transitioned out of the Scene. If a
     * View is removed from <var>rejectedSharedElements</var>, it must be handled by the
     * <code>SharedElementListener</code>.
     * <p>
     * Views in rejectedSharedElements will have their position and size set to the
     * position of the calling shared element, relative to the Window decor View and contain
     * snapshots of the View from the calling Activity or Fragment. This
     * view may be safely added to the decor View's overlay to remain in position.
     * </p>
     *
     * @param rejectedSharedElements Views containing visual information of shared elements
     *                               that are not part of the entering scene. These Views
     *                               are positioned relative to the Window decor View. A
     *                               View removed from this list will not be transitioned
     *                               automatically.
     */
    public void handleRejectedSharedElements(List<View> rejectedSharedElements) {}

    /**
     * Lets the ActivityTransitionListener adjust the mapping of shared element names to
     * Views.
     *
     * @param names The names of all shared elements transferred from the calling Activity
     *              to the started Activity.
     * @param sharedElements The mapping of shared element names to Views. The best guess
     *                       will be filled into sharedElements based on the View names.
     */
    public void remapSharedElements(List<String> names, Map<String, View> sharedElements) {}
}
