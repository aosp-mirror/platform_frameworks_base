/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.content.om;

import android.content.om.OverlayInfo;

/**
 * Api for getting information about overlay packages.
 *
 * {@hide}
 */
interface IOverlayManager {
    /**
     * Returns information about all installed overlay packages for the
     * specified user. If there are no installed overlay packages for this user,
     * an empty map is returned (i.e. null is never returned). The returned map is a
     * mapping of target package names to lists of overlays. Each list for a
     * given target package is sorted in priority order, with the overlay with
     * the highest priority at the end of the list.
     *
     * @param userId The user to get the OverlayInfos for.
     * @return A Map<String, List<OverlayInfo>> with target package names
     *         mapped to lists of overlays; if no overlays exist for the
     *         requested user, an empty map is returned.
     */
    @UnsupportedAppUsage
    Map getAllOverlays(in int userId);

    /**
     * Returns information about all overlays for the given target package for
     * the specified user. The returned list is ordered according to the
     * overlay priority with the highest priority at the end of the list.
     *
     * @param targetPackageName The name of the target package.
     * @param userId The user to get the OverlayInfos for.
     * @return A list of OverlayInfo objects; if no overlays exist for the
     *         requested package, an empty list is returned.
     */
    List getOverlayInfosForTarget(in String targetPackageName, in int userId);

    /**
     * Returns information about the overlay with the given package name for the
     * specified user.
     *
     * @param packageName The name of the overlay package.
     * @param userId The user to get the OverlayInfo for.
     * @return The OverlayInfo for the overlay package; or null if no such
     *         overlay package exists.
     */
    @UnsupportedAppUsage
    OverlayInfo getOverlayInfo(in String packageName, in int userId);

    /**
     * Request that an overlay package be enabled or disabled when possible to
     * do so.
     *
     * It is always possible to disable an overlay, but due to technical and
     * security reasons it may not always be possible to enable an overlay. An
     * example of the latter is when the related target package is not
     * installed. If the technical obstacle is later overcome, the overlay is
     * automatically enabled at that point in time.
     *
     * An enabled overlay is a part of target package's resources, i.e. it will
     * be part of any lookups performed via {@link android.content.res.Resources}
     * and {@link android.content.res.AssetManager}. A disabled overlay will no
     * longer affect the resources of the target package. If the target is
     * currently running, its outdated resources will be replaced by new ones.
     * This happens the same way as when an application enters or exits split
     * window mode.
     *
     * @param packageName The name of the overlay package.
     * @param enable true to enable the overlay, false to disable it.
     * @param userId The user for which to change the overlay.
     * @return true if the system successfully registered the request, false otherwise.
     */
    boolean setEnabled(in String packageName, in boolean enable, in int userId);

    /**
     * Request that an overlay package is enabled and any other overlay packages with the same
     * target package are disabled.
     *
     * See {@link #setEnabled} for the details on overlay packages.
     *
     * @param packageName the name of the overlay package to enable.
     * @param enabled must be true, otherwise the operation fails.
     * @param userId The user for which to change the overlay.
     * @return true if the system successfully registered the request, false otherwise.
     */
    boolean setEnabledExclusive(in String packageName, in boolean enable, in int userId);

    /**
     * Request that an overlay package is enabled and any other overlay packages with the same
     * target package and category are disabled.
     *
     * See {@link #setEnabled} for the details on overlay packages.
     *
     * @param packageName the name of the overlay package to enable.
     * @param userId The user for which to change the overlay.
     * @return true if the system successfully registered the request, false otherwise.
     */
    boolean setEnabledExclusiveInCategory(in String packageName, in int userId);

    /**
     * Change the priority of the given overlay to be just higher than the
     * overlay with package name newParentPackageName. Both overlay packages
     * must have the same target and user.
     *
     * @see getOverlayInfosForTarget
     *
     * @param packageName The name of the overlay package whose priority should
     *        be adjusted.
     * @param newParentPackageName The name of the overlay package the newly
     *        adjusted overlay package should just outrank.
     * @param userId The user for which to change the overlay.
     */
    boolean setPriority(in String packageName, in String newParentPackageName, in int userId);

    /**
     * Change the priority of the given overlay to the highest priority relative to
     * the other overlays with the same target and user.
     *
     * @see getOverlayInfosForTarget
     *
     * @param packageName The name of the overlay package whose priority should
     *        be adjusted.
     * @param userId The user for which to change the overlay.
     */
    boolean setHighestPriority(in String packageName, in int userId);

    /**
     * Change the priority of the overlay to the lowest priority relative to
     * the other overlays for the same target and user.
     *
     * @see getOverlayInfosForTarget
     *
     * @param packageName The name of the overlay package whose priority should
     *        be adjusted.
     * @param userId The user for which to change the overlay.
     */
    boolean setLowestPriority(in String packageName, in int userId);

    /**
     * Returns the list of default overlay packages, or an empty array if there are none.
     */
    String[] getDefaultOverlayPackages();

    /**
     * Invalidates and removes the idmap for an overlay,
     * @param packageName The name of the overlay package whose idmap should be deleted.
     */
    void invalidateCachesForOverlay(in String packageName, in int userIs);
}
