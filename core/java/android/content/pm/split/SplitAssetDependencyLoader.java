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

import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NOT_APK;

import android.annotation.NonNull;
import android.content.pm.PackageParser;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.SparseArray;

import libcore.io.IoUtils;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Loads AssetManagers for splits and their dependencies. This SplitAssetLoader implementation
 * is to be used when an application opts-in to isolated split loading.
 * @hide
 */
public class SplitAssetDependencyLoader
        extends SplitDependencyLoader<PackageParser.PackageParserException>
        implements SplitAssetLoader {
    private final String[] mSplitPaths;
    private final int mFlags;

    private String[][] mCachedPaths;
    private AssetManager[] mCachedAssetManagers;

    public SplitAssetDependencyLoader(PackageParser.PackageLite pkg,
            SparseArray<int[]> dependencies, int flags) {
        super(dependencies);

        // The base is inserted into index 0, so we need to shift all the splits by 1.
        mSplitPaths = new String[pkg.splitCodePaths.length + 1];
        mSplitPaths[0] = pkg.baseCodePath;
        System.arraycopy(pkg.splitCodePaths, 0, mSplitPaths, 1, pkg.splitCodePaths.length);

        mFlags = flags;
        mCachedPaths = new String[mSplitPaths.length][];
        mCachedAssetManagers = new AssetManager[mSplitPaths.length];
    }

    @Override
    protected boolean isSplitCached(int splitIdx) {
        return mCachedAssetManagers[splitIdx] != null;
    }

    private static AssetManager createAssetManagerWithPaths(String[] assetPaths, int flags)
            throws PackageParser.PackageParserException {
        final AssetManager assets = new AssetManager();
        try {
            assets.setConfiguration(0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    Build.VERSION.RESOURCES_SDK_INT);

            for (String assetPath : assetPaths) {
                if ((flags & PackageParser.PARSE_MUST_BE_APK) != 0 &&
                        !PackageParser.isApkPath(assetPath)) {
                    throw new PackageParser.PackageParserException(INSTALL_PARSE_FAILED_NOT_APK,
                            "Invalid package file: " + assetPath);
                }

                if (assets.addAssetPath(assetPath) == 0) {
                    throw new PackageParser.PackageParserException(
                            INSTALL_PARSE_FAILED_BAD_MANIFEST,
                            "Failed adding asset path: " + assetPath);
                }
            }
            return assets;
        } catch (Throwable e) {
            IoUtils.closeQuietly(assets);
            throw e;
        }
    }

    @Override
    protected void constructSplit(int splitIdx, @NonNull int[] configSplitIndices,
            int parentSplitIdx) throws PackageParser.PackageParserException {
        final ArrayList<String> assetPaths = new ArrayList<>();
        if (parentSplitIdx >= 0) {
            Collections.addAll(assetPaths, mCachedPaths[parentSplitIdx]);
        }

        assetPaths.add(mSplitPaths[splitIdx]);
        for (int configSplitIdx : configSplitIndices) {
            assetPaths.add(mSplitPaths[configSplitIdx]);
        }
        mCachedPaths[splitIdx] = assetPaths.toArray(new String[assetPaths.size()]);
        mCachedAssetManagers[splitIdx] = createAssetManagerWithPaths(mCachedPaths[splitIdx],
                mFlags);
    }

    @Override
    public AssetManager getBaseAssetManager() throws PackageParser.PackageParserException {
        loadDependenciesForSplit(0);
        return mCachedAssetManagers[0];
    }

    @Override
    public AssetManager getSplitAssetManager(int idx) throws PackageParser.PackageParserException {
        // Since we insert the base at position 0, and PackageParser keeps splits separate from
        // the base, we need to adjust the index.
        loadDependenciesForSplit(idx + 1);
        return mCachedAssetManagers[idx + 1];
    }

    @Override
    public void close() throws Exception {
        for (AssetManager assets : mCachedAssetManagers) {
            IoUtils.closeQuietly(assets);
        }
    }
}
