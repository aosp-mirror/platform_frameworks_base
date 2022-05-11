/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.appwidget;

import android.annotation.Nullable;
import android.util.ArraySet;

import java.util.Set;

/**
 * App widget manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class AppWidgetManagerInternal {

    /**
     * Gets the packages from which the uid hosts widgets.
     *
     * @param uid The potential host UID.
     * @return Whether the UID hosts widgets from the package.
     */
    public abstract @Nullable ArraySet<String> getHostedWidgetPackages(int uid);

    /**
     * Execute the widget-related work of unlocking a user.  This is intentionally
     * invoked just <em>before</em> the boot-completed broadcast is issued, after
     * the data-related work of unlock has completed.
     *
     * @param userId The user that is being unlocked.
     */
    public abstract void unlockUser(int userId);

    /**
     * Updates all widgets, applying changes to Runtime Resource Overlay affecting the specified
     * target packages.
     *
     * @param packageNames The names of all target packages for which an overlay was modified
     * @param userId The user for which overlay modifications occurred.
     * @param updateFrameworkRes Whether or not an overlay affected the values of framework
     *                           resources.
     */
    public abstract void applyResourceOverlaysToWidgets(Set<String> packageNames, int userId,
            boolean updateFrameworkRes);
}
