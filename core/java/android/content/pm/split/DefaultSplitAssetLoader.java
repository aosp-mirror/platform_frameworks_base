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

import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;

/**
 * Loads the base and split APKs into a single AssetManager.
 * @hide
 */
public class DefaultSplitAssetLoader implements SplitAssetLoader {
    private final String mBaseCodePath;
    private final String[] mSplitCodePaths;
    private final int mFlags;

    private AssetManager mCachedAssetManager;

    public DefaultSplitAssetLoader(PackageParser.PackageLite pkg, int flags) {
        mBaseCodePath = pkg.baseCodePath;
        mSplitCodePaths = pkg.splitCodePaths;
        mFlags = flags;
    }

    private static void loadApkIntoAssetManager(AssetManager assets, String apkPath, int flags)
            throws PackageParser.PackageParserException {
        if ((flags & PackageParser.PARSE_MUST_BE_APK) != 0 && !PackageParser.isApkPath(apkPath)) {
            throw new PackageParser.PackageParserException(INSTALL_PARSE_FAILED_NOT_APK,
                    "Invalid package file: " + apkPath);
        }

        if (assets.addAssetPath(apkPath) == 0) {
            throw new PackageParser.PackageParserException(
                    INSTALL_PARSE_FAILED_BAD_MANIFEST,
                    "Failed adding asset path: " + apkPath);
        }
    }

    @Override
    public AssetManager getBaseAssetManager() throws PackageParser.PackageParserException {
        if (mCachedAssetManager != null) {
            return mCachedAssetManager;
        }

        AssetManager assets = new AssetManager();
        try {
            assets.setConfiguration(0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    Build.VERSION.RESOURCES_SDK_INT);
            loadApkIntoAssetManager(assets, mBaseCodePath, mFlags);

            if (!ArrayUtils.isEmpty(mSplitCodePaths)) {
                for (String apkPath : mSplitCodePaths) {
                    loadApkIntoAssetManager(assets, apkPath, mFlags);
                }
            }

            mCachedAssetManager = assets;
            assets = null;
            return mCachedAssetManager;
        } finally {
            if (assets != null) {
                IoUtils.closeQuietly(assets);
            }
        }
    }

    @Override
    public AssetManager getSplitAssetManager(int splitIdx)
            throws PackageParser.PackageParserException {
        return getBaseAssetManager();
    }

    @Override
    public void close() throws Exception {
        if (mCachedAssetManager != null) {
            IoUtils.closeQuietly(mCachedAssetManager);
        }
    }
}
