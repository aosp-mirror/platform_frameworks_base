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
import com.android.server.pm.parsing.pkg.AndroidPackage;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.server.SystemConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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

    private final Object mLock = new Object();

    /**
     * Keys are actors, values are maps which map target to a set of overlays targeting it.
     * The presence of a target in the value map means the actor and targets are connected, even
     * if the corresponding target's set is empty.
     * See class comment for specific types.
     */
    @GuardedBy("mLock")
    private final Map<String, Map<String, Set<String>>> mActorToTargetToOverlays = new HashMap<>();

    /**
     * Keys are actor package names, values are generic package names the actor should be able
     * to see.
     */
    @GuardedBy("mLock")
    private final Map<String, Set<String>> mActorPkgToPkgs = new HashMap<>();

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
            assertMapBuilt();
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
     */
    public void addPkg(AndroidPackage pkg, Map<String, AndroidPackage> otherPkgs) {
        synchronized (mLock) {
            if (!pkg.getOverlayables().isEmpty()) {
                addTarget(pkg, otherPkgs);
            }

            // TODO(b/135203078): Replace with isOverlay boolean flag check; fix test mocks
            if (!mProvider.getTargetToOverlayables(pkg).isEmpty()) {
                addOverlay(pkg, otherPkgs);
            }

            if (!mDeferRebuild) {
                rebuild();
            }
        }
    }

    /**
     * Removes a package to be considered for visibility. Currently supports removing as a target
     * and/or an overlay. Removing an actor is not supported. Those are staticly configured as part
     * of {@link SystemConfig#getNamedActors()}.
     *
     * @param pkgName name to remove, as was added through {@link #addPkg(AndroidPackage, Map)}
     */
    public void removePkg(String pkgName) {
        synchronized (mLock) {
            removeTarget(pkgName);
            removeOverlay(pkgName);

            if (!mDeferRebuild) {
                rebuild();
            }
        }
    }

    private void removeTarget(String target) {
        synchronized (mLock) {
            Iterator<Map<String, Set<String>>> iterator =
                    mActorToTargetToOverlays.values().iterator();
            while (iterator.hasNext()) {
                Map<String, Set<String>> next = iterator.next();
                next.remove(target);
                if (next.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Associate an actor with an association of a new target to overlays for that target.
     *
     * If a target overlays itself, it will not be associated with itself, as only one half of the
     * relationship needs to exist for visibility purposes.
     */
    private void addTarget(AndroidPackage targetPkg, Map<String, AndroidPackage> otherPkgs) {
        synchronized (mLock) {
            String target = targetPkg.getPackageName();
            removeTarget(target);

            Map<String, String> overlayablesToActors = targetPkg.getOverlayables();
            for (String overlayable : overlayablesToActors.keySet()) {
                String actor = overlayablesToActors.get(overlayable);
                addTargetToMap(actor, target);

                for (AndroidPackage overlayPkg : otherPkgs.values()) {
                    Map<String, Set<String>> targetToOverlayables =
                            mProvider.getTargetToOverlayables(overlayPkg);
                    Set<String> overlayables = targetToOverlayables.get(target);
                    if (CollectionUtils.isEmpty(overlayables)) {
                        continue;
                    }

                    if (overlayables.contains(overlayable)) {
                        addOverlayToMap(actor, target, overlayPkg.getPackageName());
                    }
                }
            }
        }
    }

    private void removeOverlay(String overlay) {
        synchronized (mLock) {
            for (Map<String, Set<String>> targetToOverlays : mActorToTargetToOverlays.values()) {
                for (Set<String> overlays : targetToOverlays.values()) {
                    overlays.remove(overlay);
                }
            }
        }
    }

    /**
     * Associate an actor with an association of targets to overlays for a new overlay.
     *
     * If an overlay targets itself, it will not be associated with itself, as only one half of the
     * relationship needs to exist for visibility purposes.
     */
    private void addOverlay(AndroidPackage overlayPkg, Map<String, AndroidPackage> otherPkgs) {
        synchronized (mLock) {
            String overlay = overlayPkg.getPackageName();
            removeOverlay(overlay);

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
                    addOverlayToMap(actor, targetPkgName, overlay);
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

    private void assertMapBuilt() {
        if (mDeferRebuild) {
            throw new IllegalStateException("The actor map must be built by calling "
                    + "rebuildIfDeferred before it is queried");
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

                Map<String, Set<String>> targetToOverlays = mActorToTargetToOverlays.get(actor);
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

    private void addTargetToMap(String actor, String target) {
        Map<String, Set<String>> targetToOverlays = mActorToTargetToOverlays.get(actor);
        if (targetToOverlays == null) {
            targetToOverlays = new HashMap<>();
            mActorToTargetToOverlays.put(actor, targetToOverlays);
        }

        Set<String> overlays = targetToOverlays.get(target);
        if (overlays == null) {
            overlays = new HashSet<>();
            targetToOverlays.put(target, overlays);
        }
    }

    private void addOverlayToMap(String actor, String target, String overlay) {
        synchronized (mLock) {
            Map<String, Set<String>> targetToOverlays = mActorToTargetToOverlays.get(actor);
            if (targetToOverlays == null) {
                targetToOverlays = new HashMap<>();
                mActorToTargetToOverlays.put(actor, targetToOverlays);
            }

            Set<String> overlays = targetToOverlays.get(target);
            if (overlays == null) {
                overlays = new HashSet<>();
                targetToOverlays.put(target, overlays);
            }

            overlays.add(overlay);
        }
    }

    public interface Provider {

        /**
         * Given the actor string from an overlayable definition, return the actor's package name.
         */
        @Nullable
        String getActorPkg(String actor);

        /**
         * Mock response of multiple overlay tags.
         *
         * TODO(b/119899133): Replace with actual implementation; fix OverlayReferenceMapperTests
         */
        @NonNull
        Map<String, Set<String>> getTargetToOverlayables(@NonNull AndroidPackage pkg);
    }
}
