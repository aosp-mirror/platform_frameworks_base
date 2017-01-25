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

import android.content.pm.PackageParser;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.SparseIntArray;

import libcore.io.IoUtils;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Loads AssetManagers for splits and their dependencies. This SplitAssetLoader implementation
 * is to be used when an application opts-in to isolated split loading.
 * @hide
 */
public class SplitAssetDependencyLoader
        extends SplitDependencyLoaderHelper<PackageParser.PackageParserException>
        implements SplitAssetLoader {
    private static final int BASE_ASSET_PATH_IDX = -1;
    private final String mBasePath;
    private final String[] mSplitNames;
    private final String[] mSplitPaths;
    private final int mFlags;

    private String[] mCachedBasePaths;
    private AssetManager mCachedBaseAssetManager;

    private String[][] mCachedSplitPaths;
    private AssetManager[] mCachedAssetManagers;

    public SplitAssetDependencyLoader(PackageParser.PackageLite pkg, SparseIntArray dependencies,
            int flags) {
        super(dependencies);
        mBasePath = pkg.baseCodePath;
        mSplitNames = pkg.splitNames;
        mSplitPaths = pkg.splitCodePaths;
        mFlags = flags;
        mCachedBasePaths = null;
        mCachedBaseAssetManager = null;
        mCachedSplitPaths = new String[mSplitNames.length][];
        mCachedAssetManagers = new AssetManager[mSplitNames.length];
    }

    @Override
    protected boolean isSplitCached(int splitIdx) {
        if (splitIdx != -1) {
            return mCachedAssetManagers[splitIdx] != null;
        }
        return mCachedBaseAssetManager != null;
    }

    // Adds all non-code configuration splits for this split name. The split name is expected
    // to represent a feature split.
    private void addAllConfigSplits(String splitName, ArrayList<String> outAssetPaths) {
        for (int i = 0; i < mSplitNames.length; i++) {
            if (isConfigurationSplitOf(mSplitNames[i], splitName)) {
                outAssetPaths.add(mSplitPaths[i]);
            }
        }
    }

    private static AssetManager createAssetManagerWithPaths(String[] assetPaths, int flags)
            throws PackageParser.PackageParserException {
        final AssetManager assets = new AssetManager();
        try {
            assets.setConfiguration(0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
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
    protected void constructSplit(int splitIdx, int parentSplitIdx) throws
            PackageParser.PackageParserException {
        final ArrayList<String> assetPaths = new ArrayList<>();
        if (splitIdx == BASE_ASSET_PATH_IDX) {
            assetPaths.add(mBasePath);
            addAllConfigSplits(null, assetPaths);
            mCachedBasePaths = assetPaths.toArray(new String[assetPaths.size()]);
            mCachedBaseAssetManager = createAssetManagerWithPaths(mCachedBasePaths, mFlags);
            return;
        }

        if (parentSplitIdx == BASE_ASSET_PATH_IDX) {
            Collections.addAll(assetPaths, mCachedBasePaths);
        } else {
            Collections.addAll(assetPaths, mCachedSplitPaths[parentSplitIdx]);
        }

        assetPaths.add(mSplitPaths[splitIdx]);
        addAllConfigSplits(mSplitNames[splitIdx], assetPaths);
        mCachedSplitPaths[splitIdx] = assetPaths.toArray(new String[assetPaths.size()]);
        mCachedAssetManagers[splitIdx] = createAssetManagerWithPaths(mCachedSplitPaths[splitIdx],
                mFlags);
    }

    @Override
    public AssetManager getBaseAssetManager() throws PackageParser.PackageParserException {
        loadDependenciesForSplit(BASE_ASSET_PATH_IDX);
        return mCachedBaseAssetManager;
    }

    @Override
    public AssetManager getSplitAssetManager(int idx) throws PackageParser.PackageParserException {
        loadDependenciesForSplit(idx);
        return mCachedAssetManagers[idx];
    }

    @Override
    public void close() throws Exception {
        IoUtils.closeQuietly(mCachedBaseAssetManager);
        for (AssetManager assets : mCachedAssetManagers) {
            IoUtils.closeQuietly(assets);
        }
    }
}
