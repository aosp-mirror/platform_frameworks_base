/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.pm.split;

import android.annotation.NonNull;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.split.SplitDependencyLoader;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.SparseArray;

import com.android.server.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils.ParseFlags;

import libcore.io.IoUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Loads AssetManagers for splits and their dependencies. This SplitAssetLoader implementation
 * is to be used when an application opts-in to isolated split loading.
 * @hide
 */
public class SplitAssetDependencyLoader extends SplitDependencyLoader<IllegalArgumentException>
        implements SplitAssetLoader {
    private final String[] mSplitPaths;
    private final @ParseFlags int mFlags;
    private final ApkAssets[][] mCachedSplitApks;
    private final AssetManager[] mCachedAssetManagers;

    public SplitAssetDependencyLoader(PackageLite pkg,
            SparseArray<int[]> dependencies, @ParseFlags int flags) {
        super(dependencies);

        // The base is inserted into index 0, so we need to shift all the splits by 1.
        mSplitPaths = new String[pkg.getSplitApkPaths().length + 1];
        mSplitPaths[0] = pkg.getBaseApkPath();
        System.arraycopy(pkg.getSplitApkPaths(), 0, mSplitPaths, 1, pkg.getSplitApkPaths().length);

        mFlags = flags;
        mCachedSplitApks = new ApkAssets[mSplitPaths.length][];
        mCachedAssetManagers = new AssetManager[mSplitPaths.length];
    }

    @Override
    protected boolean isSplitCached(int splitIdx) {
        return mCachedAssetManagers[splitIdx] != null;
    }

    private static ApkAssets loadApkAssets(String path, @ParseFlags int flags)
            throws IllegalArgumentException {
        if ((flags & ParsingPackageUtils.PARSE_MUST_BE_APK) != 0
                && !ApkLiteParseUtils.isApkPath(path)) {
            throw new IllegalArgumentException("Invalid package file: " + path);
        }

        try {
            return ApkAssets.loadFromPath(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load APK at path " + path, e);
        }
    }

    private static AssetManager createAssetManagerWithAssets(ApkAssets[] apkAssets) {
        final AssetManager assets = new AssetManager();
        assets.setConfiguration(0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Build.VERSION.RESOURCES_SDK_INT);
        assets.setApkAssets(apkAssets, false /*invalidateCaches*/);
        return assets;
    }

    @Override
    protected void constructSplit(int splitIdx, @NonNull int[] configSplitIndices,
            int parentSplitIdx) throws IllegalArgumentException {
        final ArrayList<ApkAssets> assets = new ArrayList<>();

        // Include parent ApkAssets.
        if (parentSplitIdx >= 0) {
            Collections.addAll(assets, mCachedSplitApks[parentSplitIdx]);
        }

        // Include this ApkAssets.
        assets.add(loadApkAssets(mSplitPaths[splitIdx], mFlags));

        // Load and include all config splits for this feature.
        for (int configSplitIdx : configSplitIndices) {
            assets.add(loadApkAssets(mSplitPaths[configSplitIdx], mFlags));
        }

        // Cache the results.
        mCachedSplitApks[splitIdx] = assets.toArray(new ApkAssets[assets.size()]);
        mCachedAssetManagers[splitIdx] = createAssetManagerWithAssets(mCachedSplitApks[splitIdx]);
    }

    @Override
    public AssetManager getBaseAssetManager() throws IllegalArgumentException {
        loadDependenciesForSplit(0);
        return mCachedAssetManagers[0];
    }

    @Override
    public AssetManager getSplitAssetManager(int idx) throws IllegalArgumentException {
        // Since we insert the base at position 0, and ParsingPackageUtils keeps splits separate
        // from the base, we need to adjust the index.
        loadDependenciesForSplit(idx + 1);
        return mCachedAssetManagers[idx + 1];
    }

    @Override
    public ApkAssets getBaseApkAssets() {
        return mCachedSplitApks[0][0];
    }

    @Override
    public void close() throws Exception {
        for (AssetManager assets : mCachedAssetManagers) {
            IoUtils.closeQuietly(assets);
        }
    }
}
