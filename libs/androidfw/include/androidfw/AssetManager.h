/*
 * Copyright (C) 2006 The Android Open Source Project
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

//
// Asset management class.  AssetManager objects are thread-safe.
//
#ifndef __LIBS_ASSETMANAGER_H
#define __LIBS_ASSETMANAGER_H

#include <androidfw/Asset.h>
#include <androidfw/AssetDir.h>
#include <androidfw/ZipFileRO.h>
#include <utils/KeyedVector.h>
#include <utils/SortedVector.h>
#include <utils/String16.h>
#include <utils/String8.h>
#include <utils/threads.h>
#include <utils/Vector.h>

/*
 * Native-app access is via the opaque typedef struct AAssetManager in the C namespace.
 */
struct AAssetManager { };

/*
 * Now the proper C++ android-namespace definitions
 */

namespace android {

class Asset;        // fwd decl for things that include Asset.h first
class ResTable;
struct ResTable_config;

/*
 * Every application that uses assets needs one instance of this.  A
 * single instance may be shared across multiple threads, and a single
 * thread may have more than one instance (the latter is discouraged).
 *
 * The purpose of the AssetManager is to create Asset objects.
 *
 * The asset hierarchy may be examined like a filesystem, using
 * AssetDir objects to peruse a single directory.
 */
class AssetManager : public AAssetManager {
public:
    static const char* RESOURCES_FILENAME;
    static const char* IDMAP_BIN;
    static const char* VENDOR_OVERLAY_DIR;
    static const char* PRODUCT_OVERLAY_DIR;
    static const char* SYSTEM_EXT_OVERLAY_DIR;
    static const char* ODM_OVERLAY_DIR;
    static const char* OEM_OVERLAY_DIR;
    /*
     * If OVERLAY_THEME_DIR_PROPERTY is set, search for runtime resource overlay
     * APKs in VENDOR_OVERLAY_DIR/<value of OVERLAY_THEME_DIR_PROPERTY> in
     * addition to VENDOR_OVERLAY_DIR.
     */
    static const char* OVERLAY_THEME_DIR_PROPERTY;
    static const char* TARGET_PACKAGE_NAME;
    static const char* TARGET_APK_PATH;
    static const char* IDMAP_DIR;

    AssetManager();
    virtual ~AssetManager(void);

    static int32_t getGlobalCount();

    /*
     * Add a new source for assets.  This can be called multiple times to
     * look in multiple places for assets.  It can be either a directory (for
     * finding assets as raw files on the disk) or a ZIP file.  This newly
     * added asset path will be examined first when searching for assets,
     * before any that were previously added, the assets are added as shared
     * library if appAsLib is true.
     *
     * Returns "true" on success, "false" on failure.  If 'cookie' is non-NULL,
     * then on success, *cookie is set to the value corresponding to the
     * newly-added asset source.
     */
    bool addAssetPath(const String8& path, int32_t* cookie,
        bool appAsLib=false, bool isSystemAsset=false);
    bool addOverlayPath(const String8& path, int32_t* cookie);

    /*
     * Add a new source for assets from an already open file descriptor.
     * This does not give full AssetManager functionality for these assets,
     * since the origin of the file is not known for purposes of sharing,
     * overlay resolution, and other features.  However it does allow you
     * to do simple access to the contents of the given fd as an apk file.
     *
     * Returns "true" on success, "false" on failure.  If 'cookie' is non-NULL,
     * then on success, *cookie is set to the value corresponding to the
     * newly-added asset source.
     */
    bool addAssetFd(int fd, const String8& debugPathName, int32_t* cookie,
        bool appAsLib=false, bool assume_ownership=true);

    /*
     * Convenience for adding the standard system assets.  Uses the
     * ANDROID_ROOT environment variable to find them.
     */
    bool addDefaultAssets();

    /*
     * Iterate over the asset paths in this manager.  (Previously
     * added via addAssetPath() and addDefaultAssets().)  On first call,
     * 'cookie' must be 0, resulting in the first cookie being returned.
     * Each next cookie will be returned there-after, until -1 indicating
     * the end has been reached.
     */
    int32_t nextAssetPath(const int32_t cookie) const;

    /*
     * Return an asset path in the manager.  'cookie' must be a non-negative value
     * previously returned from addAssetPath() or nextAssetPath().
     */
    String8 getAssetPath(const int32_t cookie) const;

    /*
     * Sets various device configuration parameters, like screen orientation, layout,
     * size, locale, etc.
     * The optional 'locale' string takes precedence over the locale within 'config'
     * and must be in bcp47 format.
     */
    void setConfiguration(const ResTable_config& config, const char* locale = NULL);

    void getConfiguration(ResTable_config* outConfig) const;

    typedef Asset::AccessMode AccessMode;       // typing shortcut

    /*
     * Open an asset.
     *
     * The object returned does not depend on the AssetManager.  It should
     * be freed by calling Asset::close().
     */
    Asset* open(const char* fileName, AccessMode mode);

    /*
     * Open a non-asset file as an asset.
     *
     * This is for opening files that are included in an asset package
     * but aren't assets.  These sit outside the usual "assets/"
     * path hierarchy, and will not be seen by "AssetDir".
     */
    Asset* openNonAsset(const char* fileName, AccessMode mode, int32_t* outCookie = NULL);

    /*
     * Explicit non-asset file.  The file explicitly named by the cookie (the
     * resource set to look in) and fileName will be opened and returned.
     */
    Asset* openNonAsset(const int32_t cookie, const char* fileName, AccessMode mode);

    /*
     * Open a directory within the asset hierarchy.
     *
     * To open the top-level directory, pass in "".
     */
    AssetDir* openDir(const char* dirName);

    /*
     * Open a directory within a particular path of the asset manager.
     *
     * To open the top-level directory, pass in "".
     */
    AssetDir* openNonAssetDir(const int32_t cookie, const char* dirName);

    /*
     * Get the type of a file in the asset hierarchy.  They will either
     * be "regular" or "directory".  [Currently only works for "regular".]
     *
     * Can also be used as a quick test for existence of a file.
     */
    FileType getFileType(const char* fileName);

    /*
     * Return the complete resource table to find things in the package.
     */
    const ResTable& getResources(bool required = true) const;

    /*
     * Return true if the files this AssetManager references are all
     * up-to-date (have not been changed since it was created).  If false
     * is returned, you will need to create a new AssetManager to get
     * the current data.
     */
    bool isUpToDate();

    /**
     * Get the known locales for this asset manager object.
     */
    void getLocales(Vector<String8>* locales, bool includeSystemLocales=true) const;

    /**
     * Generate idmap data to translate resources IDs between a package and a
     * corresponding overlay package.
     */
    bool createIdmap(const char* targetApkPath, const char* overlayApkPath,
        uint32_t targetCrc, uint32_t overlayCrc, uint32_t** outData, size_t* outSize);

private:
    class SharedZip;

    struct asset_path
    {
        asset_path() : path(""), rawFd(-1), type(kFileTypeRegular), idmap(""),
                       isSystemOverlay(false), isSystemAsset(false), assumeOwnership(false) {}
        String8 path;
        int rawFd;
        FileType type;
        String8 idmap;
        bool isSystemOverlay;
        bool isSystemAsset;
        bool assumeOwnership;
        sp<SharedZip> zip;
    };

    Asset* openNonAssetInPathLocked(const char* fileName, AccessMode mode,
        asset_path& path);
    String8 createPathNameLocked(const asset_path& path, const char* rootDir);
    String8 createZipSourceNameLocked(const String8& zipFileName,
        const String8& dirName, const String8& fileName);

    ZipFileRO* getZipFileLocked(asset_path& path);
    Asset* openAssetFromFileLocked(const String8& fileName, AccessMode mode);
    Asset* openAssetFromZipLocked(const ZipFileRO* pZipFile,
        const ZipEntryRO entry, AccessMode mode, const String8& entryName);

    bool scanAndMergeDirLocked(SortedVector<AssetDir::FileInfo>* pMergedInfo,
        const asset_path& path, const char* rootDir, const char* dirName);
    SortedVector<AssetDir::FileInfo>* scanDirLocked(const String8& path);
    bool scanAndMergeZipLocked(SortedVector<AssetDir::FileInfo>* pMergedInfo,
        const asset_path& path, const char* rootDir, const char* dirName);
    void mergeInfoLocked(SortedVector<AssetDir::FileInfo>* pMergedInfo,
        const SortedVector<AssetDir::FileInfo>* pContents);

    const ResTable* getResTable(bool required = true) const;
    void setLocaleLocked(const char* locale);
    void updateResourceParamsLocked() const;
    bool appendPathToResTable(asset_path& ap, bool appAsLib=false) const;

    Asset* openIdmapLocked(const struct asset_path& ap) const;

    void addSystemOverlays(const char* pathOverlaysList, const String8& targetPackagePath,
            ResTable* sharedRes, size_t offset) const;

    class SharedZip : public RefBase {
    public:
        static sp<SharedZip> get(const String8& path, bool createIfNotPresent = true);
        static sp<SharedZip> create(int fd, const String8& path);

        ZipFileRO* getZip();

        Asset* getResourceTableAsset();
        Asset* setResourceTableAsset(Asset* asset);

        ResTable* getResourceTable();
        ResTable* setResourceTable(ResTable* res);

        bool isUpToDate();

        void addOverlay(const asset_path& ap);
        bool getOverlay(size_t idx, asset_path* out) const;

    protected:
        ~SharedZip();

    private:
        SharedZip(const String8& path, time_t modWhen);
        SharedZip(int fd, const String8& path);
        SharedZip(); // <-- not implemented

        String8 mPath;
        ZipFileRO* mZipFile;
        time_t mModWhen;

        Asset* mResourceTableAsset;
        ResTable* mResourceTable;

        Vector<asset_path> mOverlays;

        static Mutex gLock;
        static DefaultKeyedVector<String8, wp<SharedZip> > gOpen;
    };

    /*
     * Manage a set of Zip files.  For each file we need a pointer to the
     * ZipFile and a time_t with the file's modification date.
     *
     * We currently only have two zip files (current app, "common" app).
     * (This was originally written for 8, based on app/locale/vendor.)
     */
    class ZipSet {
    public:
        ZipSet() = default;
        ~ZipSet();

        /*
         * Return a ZipFileRO structure for a ZipFileRO with the specified
         * parameters.
         */
        ZipFileRO* getZip(const String8& path);

        const sp<SharedZip> getSharedZip(const String8& path);

        Asset* getZipResourceTableAsset(const String8& path);
        Asset* setZipResourceTableAsset(const String8& path, Asset* asset);

        ResTable* getZipResourceTable(const String8& path);
        ResTable* setZipResourceTable(const String8& path, ResTable* res);

        // generate path, e.g. "common/en-US-noogle.zip"
        static String8 getPathName(const char* path);

        bool isUpToDate();

        void addOverlay(const String8& path, const asset_path& overlay);
        bool getOverlay(const String8& path, size_t idx, asset_path* out) const;

    private:
        void closeZip(int idx);

        int getIndex(const String8& zip) const;
        mutable Vector<String8> mZipPath;
        mutable Vector<sp<SharedZip> > mZipFile;
    };

    // Protect all internal state.
    mutable Mutex   mLock;

    ZipSet          mZipSet;

    Vector<asset_path> mAssetPaths;
    char*           mLocale;

    mutable ResTable* mResources;
    ResTable_config* mConfig;
};

}; // namespace android

#endif // __LIBS_ASSETMANAGER_H
