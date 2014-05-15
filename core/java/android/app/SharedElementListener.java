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
 * {@link Activity#setSharedElementListener(SharedElementListener)}
 * to monitor the Activity transitions. The events can be used to customize or override Activity
 * Transition behavior.
 */
public class SharedElementListener {
    /**
     * Called to allow the listener to customize the start state of the shared element for
     * the shared element entering transition. By default, the shared element is placed in
     * the position and with the size of the shared element in the calling Activity or Fragment.
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
     * Called to allow the listener to customize the end state of the shared element for
     * the shared element entering transition.
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
     * If nothing is done, all shared elements that were not accepted by
     * {@link #remapSharedElements(java.util.List, java.util.Map)} will be Transitioned
     * out of the entering scene automatically. Any elements removed from
     * rejectedSharedElements must be handled by the ActivityTransitionListener.
     * <p>Views in rejectedSharedElements will have their position and size set to the
     * position of the calling shared element, relative to the Window decor View. This
     * view may be safely added to the decor View's overlay to remain in position.</p>
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
     * @param names The names of all shared elements transferred from the calling Activity
     *              to the started Activity.
     * @param sharedElements The mapping of shared element names to Views. The best guess
     *                       will be filled into sharedElements based on the View names.
     */
    public void remapSharedElements(List<String> names, Map<String, View> sharedElements) {}
}
