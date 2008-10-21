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

#include <utils/Asset.h>
#include <utils/AssetDir.h>
#include <utils/KeyedVector.h>
#include <utils/String8.h>
#include <utils/Vector.h>
#include <utils/String16.h>
#include <utils/ZipFileRO.h>
#include <utils/threads.h>

namespace android {

class Asset;        // fwd decl for things that include Asset.h first
class ResTable;
struct ResTable_config;

/*
 * Every application that uses assets needs one instance of this.  A
 * single instance may be shared across multiple threads, and a single
 * thread may have more than one instance (the latter is discouraged).
 *
 * The purpose of the AssetManager is to create Asset objects.  To do
 * this efficiently it may cache information about the locations of
 * files it has seen.  This can be controlled with the "cacheMode"
 * argument.
 *
 * The asset hierarchy may be examined like a filesystem, using
 * AssetDir objects to peruse a single directory.
 */
class AssetManager {
public:
    typedef enum CacheMode {
        CACHE_UNKNOWN = 0,
        CACHE_OFF,          // don't try to cache file locations
        CACHE_DEFER,        // construct cache as pieces are needed
        //CACHE_SCAN,         // scan full(!) asset hierarchy at init() time
    } CacheMode;

    AssetManager(CacheMode cacheMode = CACHE_OFF);
    virtual ~AssetManager(void);

    static int32_t getGlobalCount();
    
    /*                                                                       
     * Add a new source for assets.  This can be called multiple times to
     * look in multiple places for assets.  It can be either a directory (for
     * finding assets as raw files on the disk) or a ZIP file.  This newly
     * added asset path will be examined first when searching for assets,
     * before any that were previously added.
     *
     * Returns "true" on success, "false" on failure.  If 'cookie' is non-NULL,
     * then on success, *cookie is set to the value corresponding to the
     * newly-added asset source.
     */
    bool addAssetPath(const String8& path, void** cookie);

    /*                                                                       
     * Convenience for adding the standard system assets.  Uses the
     * ANDROID_ROOT environment variable to find them.
     */
    bool addDefaultAssets();

    /*                                                                       
     * Iterate over the asset paths in this manager.  (Previously
     * added via addAssetPath() and addDefaultAssets().)  On first call,
     * 'cookie' must be NULL, resulting in the first cookie being returned.
     * Each next cookie will be returned there-after, until NULL indicating
     * the end has been reached.
     */
    void* nextAssetPath(void* cookie) const;

    /*                                                                       
     * Return an asset path in the manager.  'which' must be between 0 and
     * countAssetPaths().
     */
    String8 getAssetPath(void* cookie) const;

    /*
     * Set the current locale and vendor.  The locale can change during
     * the lifetime of an AssetManager if the user updates the device's
     * language setting.  The vendor is less likely to change.
     *
     * Pass in NULL to indicate no preference.
     */
    void setLocale(const char* locale);
    void setVendor(const char* vendor);

    /*
     * Choose screen orientation for resources values returned.
     */
    void setConfiguration(const ResTable_config& config, const char* locale = NULL);

    typedef Asset::AccessMode AccessMode;       // typing shortcut

    /*
     * Open an asset.
     *
     * This will search through locale-specific and vendor-specific
     * directories and packages to find the file.
     *
     * The object returned does not depend on the AssetManager.  It should
     * be freed by calling Asset::close().
     */
    Asset* open(const char* fileName, AccessMode mode);

    /*
     * Open a non-asset file as an asset.
     *
     * This is for opening files that are included in an asset package
     * but aren't assets.  These sit outside the usual "locale/vendor"
     * path hierarchy, and will not be seen by "AssetDir" or included
     * in our filename cache.
     */
    Asset* openNonAsset(const char* fileName, AccessMode mode);

    /*
     * Explicit non-asset file.  The file explicitly named by the cookie (the
     * resource set to look in) and fileName will be opened and returned.
     */
    Asset* openNonAsset(void* cookie, const char* fileName, AccessMode mode);

    /*
     * Open a directory within the asset hierarchy.
     *
     * The contents of the directory are an amalgam of vendor-specific,
     * locale-specific, and generic assets stored loosely or in asset
     * packages.  Depending on the cache setting and previous accesses,
     * this call may incur significant disk overhead.
     *
     * To open the top-level directory, pass in "".
     */
    AssetDir* openDir(const char* dirName);

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
     * Discard cached filename information.  This only needs to be called
     * if somebody has updated the set of "loose" files, and we want to
     * discard our cached notion of what's where.
     */
    void purge(void) { purgeFileNameCacheLocked(); }

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
    void getLocales(Vector<String8>* locales) const;

private:
    struct asset_path
    {
        String8 path;
        FileType type;
    };

    Asset* openInPathLocked(const char* fileName, AccessMode mode,
        const asset_path& path);
    Asset* openNonAssetInPathLocked(const char* fileName, AccessMode mode,
        const asset_path& path);
    Asset* openInLocaleVendorLocked(const char* fileName, AccessMode mode,
        const asset_path& path, const char* locale, const char* vendor);
    String8 createPathNameLocked(const asset_path& path, const char* locale,
        const char* vendor);
    String8 createPathNameLocked(const asset_path& path, const char* rootDir);
    String8 createZipSourceNameLocked(const String8& zipFileName,
        const String8& dirName, const String8& fileName);

    ZipFileRO* getZipFileLocked(const asset_path& path);
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

    void loadFileNameCacheLocked(void);
    void fncScanLocked(SortedVector<AssetDir::FileInfo>* pMergedInfo,
        const char* dirName);
    bool fncScanAndMergeDirLocked(
        SortedVector<AssetDir::FileInfo>* pMergedInfo,
        const asset_path& path, const char* locale, const char* vendor,
        const char* dirName);
    void purgeFileNameCacheLocked(void);

    const ResTable* getResTable(bool required = true) const;
    void setLocaleLocked(const char* locale);
    void updateResourceParamsLocked() const;

    class SharedZip : public RefBase {
    public:
        static sp<SharedZip> get(const String8& path);

        ZipFileRO* getZip();

        Asset* getResourceTableAsset();
        Asset* setResourceTableAsset(Asset* asset);

        bool isUpToDate();
        
    protected:
        ~SharedZip();

    private:
        SharedZip(const String8& path, time_t modWhen);
        SharedZip(); // <-- not implemented

        String8 mPath;
        ZipFileRO* mZipFile;
        time_t mModWhen;

        Asset* mResourceTableAsset;

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
        ZipSet(void);
        ~ZipSet(void);

        /*
         * Return a ZipFileRO structure for a ZipFileRO with the specified
         * parameters.
         */
        ZipFileRO* getZip(const String8& path);

        Asset* getZipResourceTable(const String8& path);
        Asset* setZipResourceTable(const String8& path, Asset* asset);

        // generate path, e.g. "common/en-US-noogle.zip"
        static String8 getPathName(const char* path);

        bool isUpToDate();
        
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
    char*           mVendor;

    mutable ResTable* mResources;
    ResTable_config* mConfig;

    /*
     * Cached data for "loose" files.  This lets us avoid poking at the
     * filesystem when searching for loose assets.  Each entry is the
     * "extended partial" path, e.g. "default/default/foo/bar.txt".  The
     * full set of files is present, including ".EXCLUDE" entries.
     *
     * We do not cache directory names.  We don't retain the ".gz",
     * because to our clients "foo" and "foo.gz" both look like "foo".
     */
    CacheMode       mCacheMode;         // is the cache enabled?
    bool            mCacheValid;        // clear when locale or vendor changes
    SortedVector<AssetDir::FileInfo> mCache;
};

}; // namespace android

#endif // __LIBS_ASSETMANAGER_H
