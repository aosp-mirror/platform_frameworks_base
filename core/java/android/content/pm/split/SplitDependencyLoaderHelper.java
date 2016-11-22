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
package android.content.pm.split;

import android.annotation.Nullable;
import android.util.IntArray;
import android.util.SparseIntArray;

/**
 * A helper class that implements the dependency tree traversal for splits. Callbacks
 * are implemented by subclasses to notify whether a split has already been constructed
 * and is cached, and to actually create the split requested.
 *
 * This helper is meant to be subclassed so as to reduce the number of allocations
 * needed to make use of it.
 *
 * All inputs and outputs are assumed to be indices into an array of splits.
 *
 * @hide
 */
public abstract class SplitDependencyLoaderHelper<E extends Exception> {
    @Nullable private final SparseIntArray mDependencies;

    /**
     * Construct a new SplitDependencyLoaderHelper. Meant to be called from the
     * subclass constructor.
     * @param dependencies The dependency tree of splits. Can be null, which leads to
     *                     just the implicit dependency of all splits on the base.
     */
    protected SplitDependencyLoaderHelper(@Nullable SparseIntArray dependencies) {
        mDependencies = dependencies;
    }

    /**
     * Traverses the dependency tree and constructs any splits that are not already
     * cached. This routine short-circuits and skips the creation of splits closer to the
     * root if they are cached, as reported by the subclass implementation of
     * {@link #isSplitCached(int)}. The construction of splits is delegated to the subclass
     * implementation of {@link #constructSplit(int, int)}.
     * @param splitIdx The index of the split to load. Can be -1, which represents the
     *                 base Application.
     */
    protected void loadDependenciesForSplit(int splitIdx) throws E {
        // Quick check before any allocations are done.
        if (isSplitCached(splitIdx)) {
            return;
        }

        final IntArray linearDependencies = new IntArray();
        linearDependencies.add(splitIdx);

        // Collect all the dependencies that need to be constructed.
        // They will be listed from leaf to root.
        while (splitIdx >= 0) {
            splitIdx = mDependencies != null ? mDependencies.get(splitIdx, -1) : -1;
            if (isSplitCached(splitIdx)) {
                break;
            }
            linearDependencies.add(splitIdx);
        }

        // Visit each index, from right to left (root to leaf).
        int parentIdx = splitIdx;
        for (int i = linearDependencies.size() - 1; i >= 0; i--) {
            final int idx = linearDependencies.get(i);
            constructSplit(idx, parentIdx);
            parentIdx = idx;
        }
    }

    /**
     * Subclass to report whether the split at `splitIdx` is cached and need not be constructed.
     * It is assumed that if `splitIdx` is cached, any parent of `splitIdx` is also cached.
     * @param splitIdx The index of the split to check for in the cache.
     * @return true if the split is cached and does not need to be constructed.
     */
    protected abstract boolean isSplitCached(int splitIdx);

    /**
     * Subclass to construct a split at index `splitIdx` with parent split `parentSplitIdx`.
     * The result is expected to be cached by the subclass in its own structures.
     * @param splitIdx The index of the split to construct. Can be -1, which represents the
     *                 base Application.
     * @param parentSplitIdx The index of the parent split. Can be -1, which represents the
     *                       base Application.
     * @throws E
     */
    protected abstract void constructSplit(int splitIdx, int parentSplitIdx) throws E;

    /**
     * Returns true if `splitName` represents a Configuration split of `featureSplitName`.
     *
     * A Configuration split's name is prefixed with the associated Feature split's name
     * or the empty string if the split is for the base Application APK. It is then followed by the
     * dollar sign character "$" and some unique string that should represent the configurations
     * the split contains.
     *
     * Example:
     * <table>
     *     <tr>
     *         <th>Feature split name</th>
     *         <th>Configuration split name: xhdpi</th>
     *         <th>Configuration split name: fr-rFR</th>
     *     </tr>
     *     <tr>
     *         <td>(base APK)</td>
     *         <td><code>$xhdpi</code></td>
     *         <td><code>$fr-rFR</code></td>
     *     </tr>
     *     <tr>
     *         <td><code>Extras</code></td>
     *         <td><code>Extras$xhdpi</code></td>
     *         <td><code>Extras$fr-rFR</code></td>
     *     </tr>
     * </table>
     *
     * @param splitName The name of the split to check.
     * @param featureSplitName The name of the Feature split. May be null or "" if checking
     *                         the base Application APK.
     * @return true if the splitName represents a Configuration split of featureSplitName.
     */
    protected static boolean isConfigurationSplitOf(String splitName, String featureSplitName) {
        if (featureSplitName == null || featureSplitName.length() == 0) {
            // We are looking for configuration splits of the base, which have some legacy support.
            if (splitName.startsWith("config_")) {
                return true;
            } else if (splitName.startsWith("$")) {
                return true;
            } else {
                return false;
            }
        } else {
            return splitName.startsWith(featureSplitName + "$");
        }
    }
}
