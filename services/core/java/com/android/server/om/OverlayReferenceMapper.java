/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.om;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Track visibility of a targets and overlays to actors.
 *
 * 4 cases to handle:
 * <ol>
 *     <li>Target adds/changes an overlayable to add a reference to an actor
 *         <ul>
 *             <li>Must expose target to actor</li>
 *             <li>Must expose any overlays that pointed to that overlayable name to the actor</li>
 *         </ul>
 *     </li>
 *     <li>Target removes/changes an overlayable to remove a reference to an actor
 *         <ul>
 *             <li>If this target has no other overlayables referencing the actor, hide the
 *             target</li>
 *             <li>For all overlays targeting this overlayable, if the overlay is only visible to
 *             the actor through this overlayable, hide the overlay</li>
 *         </ul>
 *     </li>
 *     <li>Overlay adds/changes an overlay tag to add a reference to an overlayable name
 *         <ul>
 *             <li>Expose this overlay to the actor defined by the target overlayable</li>
 *         </ul>
 *     </li>
 *     <li>Overlay removes/changes an overlay tag to remove a reference to an overlayable name
 *         <ul>
 *             <li>If this overlay is only visible to an actor through this overlayable name's
 *             target's actor</li>
 *         </ul>
 *     </li>
 * </ol>
 *
 * In this class, the names "actor", "target", and "overlay" all refer to the ID representations.
 * All other use cases are named appropriate. "actor" is actor name, "target" is target package
 * name, and "overlay" is overlay package name.
 */
public class OverlayReferenceMapper {

    private static final String TAG = "OverlayReferenceMapper";

    private final Object mLock = new Object();

    /**
     * Keys are actors, values are maps which map target to a set of overlays targeting it.
     * The presence of a target in the value map means the actor and targets are connected, even
     * if the corresponding target's set is empty.
     * See class comment for specific types.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, ArrayMap<String, ArraySet<String>>> mActorToTargetToOverlays =
            new ArrayMap<>();

    /**
     * Keys are actor package names, values are generic package names the actor should be able
     * to see.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, Set<String>> mActorPkgToPkgs = new ArrayMap<>();

    @GuardedBy("mLock")
    private boolean mDeferRebuild;

    @NonNull
    private final Provider mProvider;

    /**
     * @param deferRebuild whether or not to defer rebuild calls on add/remove until first get call;
     *                     useful during boot when multiple packages are added in rapid succession
     *                     and queries in-between are not expected
     */
    public OverlayReferenceMapper(boolean deferRebuild, @Nullable Provider provider) {
        this.mDeferRebuild = deferRebuild;
        this.mProvider = provider != null ? provider : new Provider() {
            @Nullable
            @Override
            public String getActorPkg(String actor) {
                Map<String, Map<String, String>> namedActors = SystemConfig.getInstance()
                        .getNamedActors();

                Pair<String, OverlayActorEnforcer.ActorState> actorPair =
                        OverlayActorEnforcer.getPackageNameForActor(actor, namedActors);
                return actorPair.first;
            }

            @NonNull
            @Override
            public Map<String, Set<String>> getTargetToOverlayables(@NonNull AndroidPackage pkg) {
                String target = pkg.getOverlayTarget();
                if (TextUtils.isEmpty(target)) {
                    return Collections.emptyMap();
                }

                String overlayable = pkg.getOverlayTargetName();
                Map<String, Set<String>> targetToOverlayables = new HashMap<>();
                Set<String> overlayables = new HashSet<>();
                overlayables.add(overlayable);
                targetToOverlayables.put(target, overlayables);
                return targetToOverlayables;
            }
        };
    }

    /**
     * @return mapping of actor package to a set of packages it can view
     */
    @NonNull
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public Map<String, Set<String>> getActorPkgToPkgs() {
        return mActorPkgToPkgs;
    }

    public boolean isValidActor(@NonNull String targetName, @NonNull String actorPackageName) {
        synchronized (mLock) {
            ensureMapBuilt();
            Set<String> validSet = mActorPkgToPkgs.get(actorPackageName);
            return validSet != null && validSet.contains(targetName);
        }
    }

    /**
     * Add a package to be considered for visibility. Currently supports adding as a target and/or
     * an overlay. Adding an actor is not supported. Those are configured as part of
     * {@link SystemConfig#getNamedActors()}.
     *
     * @param pkg the package to add
     * @param otherPkgs map of other packages to consider, excluding {@param pkg}
     * @return Set of packages that may have changed visibility
     */
    public ArraySet<String> addPkg(AndroidPackage pkg, Map<String, AndroidPackage> otherPkgs) {
        synchronized (mLock) {
            ArraySet<String> changed = new ArraySet<>();

            if (!pkg.getOverlayables().isEmpty()) {
                addTarget(pkg, otherPkgs, changed);
            }

            // TODO(b/135203078): Replace with isOverlay boolean flag check; fix test mocks
            if (!mProvider.getTargetToOverlayables(pkg).isEmpty()) {
                addOverlay(pkg, otherPkgs, changed);
            }

            if (!mDeferRebuild) {
                rebuild();
            }

            return changed;
        }
    }

    /**
     * Removes a package to be considered for visibility. Currently supports removing as a target
     * and/or an overlay. Removing an actor is not supported. Those are staticly configured as part
     * of {@link SystemConfig#getNamedActors()}.
     *
     * @param pkgName name to remove, as was added through {@link #addPkg(AndroidPackage, Map)}
     * @return Set of packages that may have changed visibility
     */
    public ArraySet<String> removePkg(String pkgName) {
        synchronized (mLock) {
            ArraySet<String> changedPackages = new ArraySet<>();
            removeTarget(pkgName, changedPackages);
            removeOverlay(pkgName, changedPackages);

            if (!mDeferRebuild) {
                rebuild();
            }

            return changedPackages;
        }
    }

    /**
     * @param changedPackages Ongoing collection of packages that may have changed visibility
     */
    private void removeTarget(String target, @NonNull Collection<String> changedPackages) {
        synchronized (mLock) {
            int size = mActorToTargetToOverlays.size();
            for (int index = size - 1; index >= 0; index--) {
                ArrayMap<String, ArraySet<String>> targetToOverlays =
                        mActorToTargetToOverlays.valueAt(index);
                if (targetToOverlays.containsKey(target)) {
                    targetToOverlays.remove(target);

                    String actor = mActorToTargetToOverlays.keyAt(index);
                    changedPackages.add(mProvider.getActorPkg(actor));

                    if (targetToOverlays.isEmpty()) {
                        mActorToTargetToOverlays.removeAt(index);
                    }
                }
            }
        }
    }

    /**
     * Associate an actor with an association of a new target to overlays for that target.
     *
     * If a target overlays itself, it will not be associated with itself, as only one half of the
     * relationship needs to exist for visibility purposes.
     *
     * @param changedPackages Ongoing collection of packages that may have changed visibility
     */
    private void addTarget(AndroidPackage targetPkg, Map<String, AndroidPackage> otherPkgs,
            @NonNull Collection<String> changedPackages) {
        synchronized (mLock) {
            String target = targetPkg.getPackageName();
            removeTarget(target, changedPackages);

            Map<String, String> overlayablesToActors = targetPkg.getOverlayables();
            for (String overlayable : overlayablesToActors.keySet()) {
                String actor = overlayablesToActors.get(overlayable);
                addTargetToMap(actor, target, changedPackages);

                for (AndroidPackage overlayPkg : otherPkgs.values()) {
                    Map<String, Set<String>> targetToOverlayables =
                            mProvider.getTargetToOverlayables(overlayPkg);
                    Set<String> overlayables = targetToOverlayables.get(target);
                    if (CollectionUtils.isEmpty(overlayables)) {
                        continue;
                    }

                    if (overlayables.contains(overlayable)) {
                        String overlay = overlayPkg.getPackageName();
                        addOverlayToMap(actor, target, overlay, changedPackages);
                    }
                }
            }
        }
    }

    /**
     * @param changedPackages Ongoing collection of packages that may have changed visibility
     */
    private void removeOverlay(String overlay, @NonNull Collection<String> changedPackages) {
        synchronized (mLock) {
            int actorsSize = mActorToTargetToOverlays.size();
            for (int actorIndex = actorsSize - 1; actorIndex >= 0; actorIndex--) {
                ArrayMap<String, ArraySet<String>> targetToOverlays =
                        mActorToTargetToOverlays.valueAt(actorIndex);
                int targetsSize = targetToOverlays.size();
                for (int targetIndex = targetsSize - 1; targetIndex >= 0; targetIndex--) {
                    final Set<String> overlays = targetToOverlays.valueAt(targetIndex);

                    if (overlays.remove(overlay)) {
                        String actor = mActorToTargetToOverlays.keyAt(actorIndex);
                        changedPackages.add(mProvider.getActorPkg(actor));

                        // targetToOverlays should not be removed here even if empty as the actor
                        // will still have visibility to the target even if no overlays exist
                    }
                }

                if (targetToOverlays.isEmpty()) {
                    mActorToTargetToOverlays.removeAt(actorIndex);
                }
            }
        }
    }

    /**
     * Associate an actor with an association of targets to overlays for a new overlay.
     *
     * If an overlay targets itself, it will not be associated with itself, as only one half of the
     * relationship needs to exist for visibility purposes.
     *
     * @param changedPackages Ongoing collection of packages that may have changed visibility
     */
    private void addOverlay(AndroidPackage overlayPkg, Map<String, AndroidPackage> otherPkgs,
            @NonNull Collection<String> changedPackages) {
        synchronized (mLock) {
            String overlay = overlayPkg.getPackageName();
            removeOverlay(overlay, changedPackages);

            Map<String, Set<String>> targetToOverlayables =
                    mProvider.getTargetToOverlayables(overlayPkg);
            for (Map.Entry<String, Set<String>> entry : targetToOverlayables.entrySet()) {
                String target = entry.getKey();
                Set<String> overlayables = entry.getValue();
                AndroidPackage targetPkg = otherPkgs.get(target);
                if (targetPkg == null) {
                    continue;
                }

                String targetPkgName = targetPkg.getPackageName();
                Map<String, String> overlayableToActor = targetPkg.getOverlayables();
                for (String overlayable : overlayables) {
                    String actor = overlayableToActor.get(overlayable);
                    if (TextUtils.isEmpty(actor)) {
                        continue;
                    }
                    addOverlayToMap(actor, targetPkgName, overlay, changedPackages);
                }
            }
        }
    }

    public void rebuildIfDeferred() {
        synchronized (mLock) {
            if (mDeferRebuild) {
                rebuild();
                mDeferRebuild = false;
            }
        }
    }

    private void ensureMapBuilt() {
        if (mDeferRebuild) {
            rebuildIfDeferred();
            Slog.w(TAG, "The actor map was queried before the system was ready, which may"
                    + "result in decreased performance.");
        }
    }

    private void rebuild() {
        synchronized (mLock) {
            mActorPkgToPkgs.clear();
            for (String actor : mActorToTargetToOverlays.keySet()) {
                String actorPkg = mProvider.getActorPkg(actor);
                if (TextUtils.isEmpty(actorPkg)) {
                    continue;
                }

                ArrayMap<String, ArraySet<String>> targetToOverlays =
                        mActorToTargetToOverlays.get(actor);
                Set<String> pkgs = new HashSet<>();

                for (String target : targetToOverlays.keySet()) {
                    Set<String> overlays = targetToOverlays.get(target);
                    pkgs.add(target);
                    pkgs.addAll(overlays);
                }

                mActorPkgToPkgs.put(actorPkg, pkgs);
            }
        }
    }

    /**
     * @param changedPackages Ongoing collection of packages that may have changed visibility
     */
    private void addTargetToMap(String actor, String target,
            @NonNull Collection<String> changedPackages) {
        ArrayMap<String, ArraySet<String>> targetToOverlays = mActorToTargetToOverlays.get(actor);
        if (targetToOverlays == null) {
            targetToOverlays = new ArrayMap<>();
            mActorToTargetToOverlays.put(actor, targetToOverlays);
        }

        ArraySet<String> overlays = targetToOverlays.get(target);
        if (overlays == null) {
            overlays = new ArraySet<>();
            targetToOverlays.put(target, overlays);
        }

        // For now, only actors themselves can gain or lose visibility through package changes
        changedPackages.add(mProvider.getActorPkg(actor));
    }

    /**
     * @param changedPackages Ongoing collection of packages that may have changed visibility
     */
    private void addOverlayToMap(String actor, String target, String overlay,
            @NonNull Collection<String> changedPackages) {
        synchronized (mLock) {
            ArrayMap<String, ArraySet<String>> targetToOverlays =
                    mActorToTargetToOverlays.get(actor);
            if (targetToOverlays == null) {
                targetToOverlays = new ArrayMap<>();
                mActorToTargetToOverlays.put(actor, targetToOverlays);
            }

            ArraySet<String> overlays = targetToOverlays.get(target);
            if (overlays == null) {
                overlays = new ArraySet<>();
                targetToOverlays.put(target, overlays);
            }

            overlays.add(overlay);
        }

        // For now, only actors themselves can gain or lose visibility through package changes
        changedPackages.add(mProvider.getActorPkg(actor));
    }

    public interface Provider {

        /**
         * Given the actor string from an overlayable definition, return the actor's package name.
         */
        @Nullable
        String getActorPkg(@NonNull String actor);

        /**
         * Mock response of multiple overlay tags.
         *
         * TODO(b/119899133): Replace with actual implementation; fix OverlayReferenceMapperTests
         */
        @NonNull
        Map<String, Set<String>> getTargetToOverlayables(@NonNull AndroidPackage pkg);
    }
}
