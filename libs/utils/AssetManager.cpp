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
// Provide access to read-only assets.
//

#define LOG_TAG "asset"
//#define LOG_NDEBUG 0

#include <utils/AssetManager.h>
#include <utils/AssetDir.h>
#include <utils/Asset.h>
#include <utils/Atomic.h>
#include <utils/String8.h>
#include <utils/ResourceTypes.h>
#include <utils/String8.h>
#include <utils/ZipFileRO.h>
#include <utils/Log.h>
#include <utils/Timers.h>
#include <utils/threads.h>

#include <dirent.h>
#include <errno.h>
#include <assert.h>
#include <strings.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef TEMP_FAILURE_RETRY
/* Used to retry syscalls that can return EINTR. */
#define TEMP_FAILURE_RETRY(exp) ({         \
    typeof (exp) _rc;                      \
    do {                                   \
        _rc = (exp);                       \
    } while (_rc == -1 && errno == EINTR); \
    _rc; })
#endif

using namespace android;

/*
 * Names for default app, locale, and vendor.  We might want to change
 * these to be an actual locale, e.g. always use en-US as the default.
 */
static const char* kDefaultLocale = "default";
static const char* kDefaultVendor = "default";
static const char* kAssetsRoot = "assets";
static const char* kAppZipName = NULL; //"classes.jar";
static const char* kSystemAssets = "framework/framework-res.apk";
static const char* kIdmapCacheDir = "resource-cache";

static const char* kExcludeExtension = ".EXCLUDE";

static Asset* const kExcludedAsset = (Asset*) 0xd000000d;

static volatile int32_t gCount = 0;

namespace {
    // Transform string /a/b/c.apk to /data/resource-cache/a@b@c.apk@idmap
    String8 idmapPathForPackagePath(const String8& pkgPath)
    {
        const char* root = getenv("ANDROID_DATA");
        LOG_ALWAYS_FATAL_IF(root == NULL, "ANDROID_DATA not set");
        String8 path(root);
        path.appendPath(kIdmapCacheDir);

        char buf[256]; // 256 chars should be enough for anyone...
        strncpy(buf, pkgPath.string(), 255);
        buf[255] = '\0';
        char* filename = buf;
        while (*filename && *filename == '/') {
            ++filename;
        }
        char* p = filename;
        while (*p) {
            if (*p == '/') {
                *p = '@';
            }
            ++p;
        }
        path.appendPath(filename);
        path.append("@idmap");

        return path;
    }
}

/*
 * ===========================================================================
 *      AssetManager
 * ===========================================================================
 */

int32_t AssetManager::getGlobalCount()
{
    return gCount;
}

AssetManager::AssetManager(CacheMode cacheMode)
    : mLocale(NULL), mVendor(NULL),
      mResources(NULL), mConfig(new ResTable_config),
      mCacheMode(cacheMode), mCacheValid(false)
{
    int count = android_atomic_inc(&gCount)+1;
    //ALOGI("Creating AssetManager %p #%d\n", this, count);
    memset(mConfig, 0, sizeof(ResTable_config));
}

AssetManager::~AssetManager(void)
{
    int count = android_atomic_dec(&gCount);
    //ALOGI("Destroying AssetManager in %p #%d\n", this, count);

    delete mConfig;
    delete mResources;

    // don't have a String class yet, so make sure we clean up
    delete[] mLocale;
    delete[] mVendor;
}

bool AssetManager::addAssetPath(const String8& path, void** cookie)
{
    AutoMutex _l(mLock);

    asset_path ap;

    String8 realPath(path);
    if (kAppZipName) {
        realPath.appendPath(kAppZipName);
    }
    ap.type = ::getFileType(realPath.string());
    if (ap.type == kFileTypeRegular) {
        ap.path = realPath;
    } else {
        ap.path = path;
        ap.type = ::getFileType(path.string());
        if (ap.type != kFileTypeDirectory && ap.type != kFileTypeRegular) {
            ALOGW("Asset path %s is neither a directory nor file (type=%d).",
                 path.string(), (int)ap.type);
            return false;
        }
    }

    // Skip if we have it already.
    for (size_t i=0; i<mAssetPaths.size(); i++) {
        if (mAssetPaths[i].path == ap.path) {
            if (cookie) {
                *cookie = (void*)(i+1);
            }
            return true;
        }
    }

    ALOGV("In %p Asset %s path: %s", this,
         ap.type == kFileTypeDirectory ? "dir" : "zip", ap.path.string());

    mAssetPaths.add(ap);

    // new paths are always added at the end
    if (cookie) {
        *cookie = (void*)mAssetPaths.size();
    }

    // add overlay packages for /system/framework; apps are handled by the
    // (Java) package manager
    if (strncmp(path.string(), "/system/framework/", 18) == 0) {
        // When there is an environment variable for /vendor, this
        // should be changed to something similar to how ANDROID_ROOT
        // and ANDROID_DATA are used in this file.
        String8 overlayPath("/vendor/overlay/framework/");
        overlayPath.append(path.getPathLeaf());
        if (TEMP_FAILURE_RETRY(access(overlayPath.string(), R_OK)) == 0) {
            asset_path oap;
            oap.path = overlayPath;
            oap.type = ::getFileType(overlayPath.string());
            bool addOverlay = (oap.type == kFileTypeRegular); // only .apks supported as overlay
            if (addOverlay) {
                oap.idmap = idmapPathForPackagePath(overlayPath);

                if (isIdmapStaleLocked(ap.path, oap.path, oap.idmap)) {
                    addOverlay = createIdmapFileLocked(ap.path, oap.path, oap.idmap);
                }
            }
            if (addOverlay) {
                mAssetPaths.add(oap);
            } else {
                ALOGW("failed to add overlay package %s\n", overlayPath.string());
            }
        }
    }

    return true;
}

bool AssetManager::isIdmapStaleLocked(const String8& originalPath, const String8& overlayPath,
                                      const String8& idmapPath)
{
    struct stat st;
    if (TEMP_FAILURE_RETRY(stat(idmapPath.string(), &st)) == -1) {
        if (errno == ENOENT) {
            return true; // non-existing idmap is always stale
        } else {
            ALOGW("failed to stat file %s: %s\n", idmapPath.string(), strerror(errno));
            return false;
        }
    }
    if (st.st_size < ResTable::IDMAP_HEADER_SIZE_BYTES) {
        ALOGW("file %s has unexpectedly small size=%zd\n", idmapPath.string(), (size_t)st.st_size);
        return false;
    }
    int fd = TEMP_FAILURE_RETRY(::open(idmapPath.string(), O_RDONLY));
    if (fd == -1) {
        ALOGW("failed to open file %s: %s\n", idmapPath.string(), strerror(errno));
        return false;
    }
    char buf[ResTable::IDMAP_HEADER_SIZE_BYTES];
    ssize_t bytesLeft = ResTable::IDMAP_HEADER_SIZE_BYTES;
    for (;;) {
        ssize_t r = TEMP_FAILURE_RETRY(read(fd, buf + ResTable::IDMAP_HEADER_SIZE_BYTES - bytesLeft,
                                            bytesLeft));
        if (r < 0) {
            TEMP_FAILURE_RETRY(close(fd));
            return false;
        }
        bytesLeft -= r;
        if (bytesLeft == 0) {
            break;
        }
    }
    TEMP_FAILURE_RETRY(close(fd));

    uint32_t cachedOriginalCrc, cachedOverlayCrc;
    if (!ResTable::getIdmapInfo(buf, ResTable::IDMAP_HEADER_SIZE_BYTES,
                                &cachedOriginalCrc, &cachedOverlayCrc)) {
        return false;
    }

    uint32_t actualOriginalCrc, actualOverlayCrc;
    if (!getZipEntryCrcLocked(originalPath, "resources.arsc", &actualOriginalCrc)) {
        return false;
    }
    if (!getZipEntryCrcLocked(overlayPath, "resources.arsc", &actualOverlayCrc)) {
        return false;
    }
    return cachedOriginalCrc != actualOriginalCrc || cachedOverlayCrc != actualOverlayCrc;
}

bool AssetManager::getZipEntryCrcLocked(const String8& zipPath, const char* entryFilename,
                                        uint32_t* pCrc)
{
    asset_path ap;
    ap.path = zipPath;
    const ZipFileRO* zip = getZipFileLocked(ap);
    if (zip == NULL) {
        return false;
    }
    const ZipEntryRO entry = zip->findEntryByName(entryFilename);
    if (entry == NULL) {
        return false;
    }
    if (!zip->getEntryInfo(entry, NULL, NULL, NULL, NULL, NULL, (long*)pCrc)) {
        return false;
    }
    return true;
}

bool AssetManager::createIdmapFileLocked(const String8& originalPath, const String8& overlayPath,
                                         const String8& idmapPath)
{
    ALOGD("%s: originalPath=%s overlayPath=%s idmapPath=%s\n",
         __FUNCTION__, originalPath.string(), overlayPath.string(), idmapPath.string());
    ResTable tables[2];
    const String8* paths[2] = { &originalPath, &overlayPath };
    uint32_t originalCrc, overlayCrc;
    bool retval = false;
    ssize_t offset = 0;
    int fd = 0;
    uint32_t* data = NULL;
    size_t size;

    for (int i = 0; i < 2; ++i) {
        asset_path ap;
        ap.type = kFileTypeRegular;
        ap.path = *paths[i];
        Asset* ass = openNonAssetInPathLocked("resources.arsc", Asset::ACCESS_BUFFER, ap);
        if (ass == NULL) {
            ALOGW("failed to find resources.arsc in %s\n", ap.path.string());
            goto error;
        }
        tables[i].add(ass, (void*)1, false);
    }

    if (!getZipEntryCrcLocked(originalPath, "resources.arsc", &originalCrc)) {
        ALOGW("failed to retrieve crc for resources.arsc in %s\n", originalPath.string());
        goto error;
    }
    if (!getZipEntryCrcLocked(overlayPath, "resources.arsc", &overlayCrc)) {
        ALOGW("failed to retrieve crc for resources.arsc in %s\n", overlayPath.string());
        goto error;
    }

    if (tables[0].createIdmap(tables[1], originalCrc, overlayCrc,
                              (void**)&data, &size) != NO_ERROR) {
        ALOGW("failed to generate idmap data for file %s\n", idmapPath.string());
        goto error;
    }

    // This should be abstracted (eg replaced by a stand-alone
    // application like dexopt, triggered by something equivalent to
    // installd).
    fd = TEMP_FAILURE_RETRY(::open(idmapPath.string(), O_WRONLY | O_CREAT | O_TRUNC, 0644));
    if (fd == -1) {
        ALOGW("failed to write idmap file %s (open: %s)\n", idmapPath.string(), strerror(errno));
        goto error_free;
    }
    for (;;) {
        ssize_t written = TEMP_FAILURE_RETRY(write(fd, data + offset, size));
        if (written < 0) {
            ALOGW("failed to write idmap file %s (write: %s)\n", idmapPath.string(),
                 strerror(errno));
            goto error_close;
        }
        size -= (size_t)written;
        offset += written;
        if (size == 0) {
            break;
        }
    }

    retval = true;
error_close:
    TEMP_FAILURE_RETRY(close(fd));
error_free:
    free(data);
error:
    return retval;
}

bool AssetManager::addDefaultAssets()
{
    const char* root = getenv("ANDROID_ROOT");
    LOG_ALWAYS_FATAL_IF(root == NULL, "ANDROID_ROOT not set");

    String8 path(root);
    path.appendPath(kSystemAssets);

    return addAssetPath(path, NULL);
}

void* AssetManager::nextAssetPath(void* cookie) const
{
    AutoMutex _l(mLock);
    size_t next = ((size_t)cookie)+1;
    return next > mAssetPaths.size() ? NULL : (void*)next;
}

String8 AssetManager::getAssetPath(void* cookie) const
{
    AutoMutex _l(mLock);
    const size_t which = ((size_t)cookie)-1;
    if (which < mAssetPaths.size()) {
        return mAssetPaths[which].path;
    }
    return String8();
}

/*
 * Set the current locale.  Use NULL to indicate no locale.
 *
 * Close and reopen Zip archives as appropriate, and reset cached
 * information in the locale-specific sections of the tree.
 */
void AssetManager::setLocale(const char* locale)
{
    AutoMutex _l(mLock);
    setLocaleLocked(locale);
}

void AssetManager::setLocaleLocked(const char* locale)
{
    if (mLocale != NULL) {
        /* previously set, purge cached data */
        purgeFileNameCacheLocked();
        //mZipSet.purgeLocale();
        delete[] mLocale;
    }
    mLocale = strdupNew(locale);
    
    updateResourceParamsLocked();
}

/*
 * Set the current vendor.  Use NULL to indicate no vendor.
 *
 * Close and reopen Zip archives as appropriate, and reset cached
 * information in the vendor-specific sections of the tree.
 */
void AssetManager::setVendor(const char* vendor)
{
    AutoMutex _l(mLock);

    if (mVendor != NULL) {
        /* previously set, purge cached data */
        purgeFileNameCacheLocked();
        //mZipSet.purgeVendor();
        delete[] mVendor;
    }
    mVendor = strdupNew(vendor);
}

void AssetManager::setConfiguration(const ResTable_config& config, const char* locale)
{
    AutoMutex _l(mLock);
    *mConfig = config;
    if (locale) {
        setLocaleLocked(locale);
    } else if (config.language[0] != 0) {
        char spec[9];
        spec[0] = config.language[0];
        spec[1] = config.language[1];
        if (config.country[0] != 0) {
            spec[2] = '_';
            spec[3] = config.country[0];
            spec[4] = config.country[1];
            spec[5] = 0;
        } else {
            spec[3] = 0;
        }
        setLocaleLocked(spec);
    } else {
        updateResourceParamsLocked();
    }
}

void AssetManager::getConfiguration(ResTable_config* outConfig) const
{
    AutoMutex _l(mLock);
    *outConfig = *mConfig;
}

/*
 * Open an asset.
 *
 * The data could be;
 *  - In a file on disk (assetBase + fileName).
 *  - In a compressed file on disk (assetBase + fileName.gz).
 *  - In a Zip archive, uncompressed or compressed.
 *
 * It can be in a number of different directories and Zip archives.
 * The search order is:
 *  - [appname]
 *    - locale + vendor
 *    - "default" + vendor
 *    - locale + "default"
 *    - "default + "default"
 *  - "common"
 *    - (same as above)
 *
 * To find a particular file, we have to try up to eight paths with
 * all three forms of data.
 *
 * We should probably reject requests for "illegal" filenames, e.g. those
 * with illegal characters or "../" backward relative paths.
 */
Asset* AssetManager::open(const char* fileName, AccessMode mode)
{
    AutoMutex _l(mLock);

    LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");


    if (mCacheMode != CACHE_OFF && !mCacheValid)
        loadFileNameCacheLocked();

    String8 assetName(kAssetsRoot);
    assetName.appendPath(fileName);

    /*
     * For each top-level asset path, search for the asset.
     */

    size_t i = mAssetPaths.size();
    while (i > 0) {
        i--;
        ALOGV("Looking for asset '%s' in '%s'\n",
                assetName.string(), mAssetPaths.itemAt(i).path.string());
        Asset* pAsset = openNonAssetInPathLocked(assetName.string(), mode, mAssetPaths.itemAt(i));
        if (pAsset != NULL) {
            return pAsset != kExcludedAsset ? pAsset : NULL;
        }
    }

    return NULL;
}

/*
 * Open a non-asset file as if it were an asset.
 *
 * The "fileName" is the partial path starting from the application
 * name.
 */
Asset* AssetManager::openNonAsset(const char* fileName, AccessMode mode)
{
    AutoMutex _l(mLock);

    LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");


    if (mCacheMode != CACHE_OFF && !mCacheValid)
        loadFileNameCacheLocked();

    /*
     * For each top-level asset path, search for the asset.
     */

    size_t i = mAssetPaths.size();
    while (i > 0) {
        i--;
        ALOGV("Looking for non-asset '%s' in '%s'\n", fileName, mAssetPaths.itemAt(i).path.string());
        Asset* pAsset = openNonAssetInPathLocked(
            fileName, mode, mAssetPaths.itemAt(i));
        if (pAsset != NULL) {
            return pAsset != kExcludedAsset ? pAsset : NULL;
        }
    }

    return NULL;
}

Asset* AssetManager::openNonAsset(void* cookie, const char* fileName, AccessMode mode)
{
    const size_t which = ((size_t)cookie)-1;

    AutoMutex _l(mLock);

    LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");


    if (mCacheMode != CACHE_OFF && !mCacheValid)
        loadFileNameCacheLocked();

    if (which < mAssetPaths.size()) {
        ALOGV("Looking for non-asset '%s' in '%s'\n", fileName,
                mAssetPaths.itemAt(which).path.string());
        Asset* pAsset = openNonAssetInPathLocked(
            fileName, mode, mAssetPaths.itemAt(which));
        if (pAsset != NULL) {
            return pAsset != kExcludedAsset ? pAsset : NULL;
        }
    }

    return NULL;
}

/*
 * Get the type of a file in the asset namespace.
 *
 * This currently only works for regular files.  All others (including
 * directories) will return kFileTypeNonexistent.
 */
FileType AssetManager::getFileType(const char* fileName)
{
    Asset* pAsset = NULL;

    /*
     * Open the asset.  This is less efficient than simply finding the
     * file, but it's not too bad (we don't uncompress or mmap data until
     * the first read() call).
     */
    pAsset = open(fileName, Asset::ACCESS_STREAMING);
    delete pAsset;

    if (pAsset == NULL)
        return kFileTypeNonexistent;
    else
        return kFileTypeRegular;
}

const ResTable* AssetManager::getResTable(bool required) const
{
    ResTable* rt = mResources;
    if (rt) {
        return rt;
    }

    // Iterate through all asset packages, collecting resources from each.

    AutoMutex _l(mLock);

    if (mResources != NULL) {
        return mResources;
    }

    if (required) {
        LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");
    }

    if (mCacheMode != CACHE_OFF && !mCacheValid)
        const_cast<AssetManager*>(this)->loadFileNameCacheLocked();

    const size_t N = mAssetPaths.size();
    for (size_t i=0; i<N; i++) {
        Asset* ass = NULL;
        ResTable* sharedRes = NULL;
        bool shared = true;
        const asset_path& ap = mAssetPaths.itemAt(i);
        Asset* idmap = openIdmapLocked(ap);
        ALOGV("Looking for resource asset in '%s'\n", ap.path.string());
        if (ap.type != kFileTypeDirectory) {
            if (i == 0) {
                // The first item is typically the framework resources,
                // which we want to avoid parsing every time.
                sharedRes = const_cast<AssetManager*>(this)->
                    mZipSet.getZipResourceTable(ap.path);
            }
            if (sharedRes == NULL) {
                ass = const_cast<AssetManager*>(this)->
                    mZipSet.getZipResourceTableAsset(ap.path);
                if (ass == NULL) {
                    ALOGV("loading resource table %s\n", ap.path.string());
                    ass = const_cast<AssetManager*>(this)->
                        openNonAssetInPathLocked("resources.arsc",
                                                 Asset::ACCESS_BUFFER,
                                                 ap);
                    if (ass != NULL && ass != kExcludedAsset) {
                        ass = const_cast<AssetManager*>(this)->
                            mZipSet.setZipResourceTableAsset(ap.path, ass);
                    }
                }
                
                if (i == 0 && ass != NULL) {
                    // If this is the first resource table in the asset
                    // manager, then we are going to cache it so that we
                    // can quickly copy it out for others.
                    ALOGV("Creating shared resources for %s", ap.path.string());
                    sharedRes = new ResTable();
                    sharedRes->add(ass, (void*)(i+1), false, idmap);
                    sharedRes = const_cast<AssetManager*>(this)->
                        mZipSet.setZipResourceTable(ap.path, sharedRes);
                }
            }
        } else {
            ALOGV("loading resource table %s\n", ap.path.string());
            Asset* ass = const_cast<AssetManager*>(this)->
                openNonAssetInPathLocked("resources.arsc",
                                         Asset::ACCESS_BUFFER,
                                         ap);
            shared = false;
        }
        if ((ass != NULL || sharedRes != NULL) && ass != kExcludedAsset) {
            if (rt == NULL) {
                mResources = rt = new ResTable();
                updateResourceParamsLocked();
            }
            ALOGV("Installing resource asset %p in to table %p\n", ass, mResources);
            if (sharedRes != NULL) {
                ALOGV("Copying existing resources for %s", ap.path.string());
                rt->add(sharedRes);
            } else {
                ALOGV("Parsing resources for %s", ap.path.string());
                rt->add(ass, (void*)(i+1), !shared, idmap);
            }

            if (!shared) {
                delete ass;
            }
        }
        if (idmap != NULL) {
            delete idmap;
        }
    }

    if (required && !rt) ALOGW("Unable to find resources file resources.arsc");
    if (!rt) {
        mResources = rt = new ResTable();
    }
    return rt;
}

void AssetManager::updateResourceParamsLocked() const
{
    ResTable* res = mResources;
    if (!res) {
        return;
    }

    size_t llen = mLocale ? strlen(mLocale) : 0;
    mConfig->language[0] = 0;
    mConfig->language[1] = 0;
    mConfig->country[0] = 0;
    mConfig->country[1] = 0;
    if (llen >= 2) {
        mConfig->language[0] = mLocale[0];
        mConfig->language[1] = mLocale[1];
    }
    if (llen >= 5) {
        mConfig->country[0] = mLocale[3];
        mConfig->country[1] = mLocale[4];
    }
    mConfig->size = sizeof(*mConfig);

    res->setParameters(mConfig);
}

Asset* AssetManager::openIdmapLocked(const struct asset_path& ap) const
{
    Asset* ass = NULL;
    if (ap.idmap.size() != 0) {
        ass = const_cast<AssetManager*>(this)->
            openAssetFromFileLocked(ap.idmap, Asset::ACCESS_BUFFER);
        if (ass) {
            ALOGV("loading idmap %s\n", ap.idmap.string());
        } else {
            ALOGW("failed to load idmap %s\n", ap.idmap.string());
        }
    }
    return ass;
}

const ResTable& AssetManager::getResources(bool required) const
{
    const ResTable* rt = getResTable(required);
    return *rt;
}

bool AssetManager::isUpToDate()
{
    AutoMutex _l(mLock);
    return mZipSet.isUpToDate();
}

void AssetManager::getLocales(Vector<String8>* locales) const
{
    ResTable* res = mResources;
    if (res != NULL) {
        res->getLocales(locales);
    }
}

/*
 * Open a non-asset file as if it were an asset, searching for it in the
 * specified app.
 *
 * Pass in a NULL values for "appName" if the common app directory should
 * be used.
 */
Asset* AssetManager::openNonAssetInPathLocked(const char* fileName, AccessMode mode,
    const asset_path& ap)
{
    Asset* pAsset = NULL;

    /* look at the filesystem on disk */
    if (ap.type == kFileTypeDirectory) {
        String8 path(ap.path);
        path.appendPath(fileName);

        pAsset = openAssetFromFileLocked(path, mode);

        if (pAsset == NULL) {
            /* try again, this time with ".gz" */
            path.append(".gz");
            pAsset = openAssetFromFileLocked(path, mode);
        }

        if (pAsset != NULL) {
            //printf("FOUND NA '%s' on disk\n", fileName);
            pAsset->setAssetSource(path);
        }

    /* look inside the zip file */
    } else {
        String8 path(fileName);

        /* check the appropriate Zip file */
        ZipFileRO* pZip;
        ZipEntryRO entry;

        pZip = getZipFileLocked(ap);
        if (pZip != NULL) {
            //printf("GOT zip, checking NA '%s'\n", (const char*) path);
            entry = pZip->findEntryByName(path.string());
            if (entry != NULL) {
                //printf("FOUND NA in Zip file for %s\n", appName ? appName : kAppCommon);
                pAsset = openAssetFromZipLocked(pZip, entry, mode, path);
            }
        }

        if (pAsset != NULL) {
            /* create a "source" name, for debug/display */
            pAsset->setAssetSource(
                    createZipSourceNameLocked(ZipSet::getPathName(ap.path.string()), String8(""),
                                                String8(fileName)));
        }
    }

    return pAsset;
}

/*
 * Open an asset, searching for it in the directory hierarchy for the
 * specified app.
 *
 * Pass in a NULL values for "appName" if the common app directory should
 * be used.
 */
Asset* AssetManager::openInPathLocked(const char* fileName, AccessMode mode,
    const asset_path& ap)
{
    Asset* pAsset = NULL;

    /*
     * Try various combinations of locale and vendor.
     */
    if (mLocale != NULL && mVendor != NULL)
        pAsset = openInLocaleVendorLocked(fileName, mode, ap, mLocale, mVendor);
    if (pAsset == NULL && mVendor != NULL)
        pAsset = openInLocaleVendorLocked(fileName, mode, ap, NULL, mVendor);
    if (pAsset == NULL && mLocale != NULL)
        pAsset = openInLocaleVendorLocked(fileName, mode, ap, mLocale, NULL);
    if (pAsset == NULL)
        pAsset = openInLocaleVendorLocked(fileName, mode, ap, NULL, NULL);

    return pAsset;
}

/*
 * Open an asset, searching for it in the directory hierarchy for the
 * specified locale and vendor.
 *
 * We also search in "app.jar".
 *
 * Pass in NULL values for "appName", "locale", and "vendor" if the
 * defaults should be used.
 */
Asset* AssetManager::openInLocaleVendorLocked(const char* fileName, AccessMode mode,
    const asset_path& ap, const char* locale, const char* vendor)
{
    Asset* pAsset = NULL;

    if (ap.type == kFileTypeDirectory) {
        if (mCacheMode == CACHE_OFF) {
            /* look at the filesystem on disk */
            String8 path(createPathNameLocked(ap, locale, vendor));
            path.appendPath(fileName);
    
            String8 excludeName(path);
            excludeName.append(kExcludeExtension);
            if (::getFileType(excludeName.string()) != kFileTypeNonexistent) {
                /* say no more */
                //printf("+++ excluding '%s'\n", (const char*) excludeName);
                return kExcludedAsset;
            }
    
            pAsset = openAssetFromFileLocked(path, mode);
    
            if (pAsset == NULL) {
                /* try again, this time with ".gz" */
                path.append(".gz");
                pAsset = openAssetFromFileLocked(path, mode);
            }
    
            if (pAsset != NULL)
                pAsset->setAssetSource(path);
        } else {
            /* find in cache */
            String8 path(createPathNameLocked(ap, locale, vendor));
            path.appendPath(fileName);
    
            AssetDir::FileInfo tmpInfo;
            bool found = false;
    
            String8 excludeName(path);
            excludeName.append(kExcludeExtension);
    
            if (mCache.indexOf(excludeName) != NAME_NOT_FOUND) {
                /* go no farther */
                //printf("+++ Excluding '%s'\n", (const char*) excludeName);
                return kExcludedAsset;
            }

            /*
             * File compression extensions (".gz") don't get stored in the
             * name cache, so we have to try both here.
             */
            if (mCache.indexOf(path) != NAME_NOT_FOUND) {
                found = true;
                pAsset = openAssetFromFileLocked(path, mode);
                if (pAsset == NULL) {
                    /* try again, this time with ".gz" */
                    path.append(".gz");
                    pAsset = openAssetFromFileLocked(path, mode);
                }
            }

            if (pAsset != NULL)
                pAsset->setAssetSource(path);

            /*
             * Don't continue the search into the Zip files.  Our cached info
             * said it was a file on disk; to be consistent with openDir()
             * we want to return the loose asset.  If the cached file gets
             * removed, we fail.
             *
             * The alternative is to update our cache when files get deleted,
             * or make some sort of "best effort" promise, but for now I'm
             * taking the hard line.
             */
            if (found) {
                if (pAsset == NULL)
                    ALOGD("Expected file not found: '%s'\n", path.string());
                return pAsset;
            }
        }
    }

    /*
     * Either it wasn't found on disk or on the cached view of the disk.
     * Dig through the currently-opened set of Zip files.  If caching
     * is disabled, the Zip file may get reopened.
     */
    if (pAsset == NULL && ap.type == kFileTypeRegular) {
        String8 path;

        path.appendPath((locale != NULL) ? locale : kDefaultLocale);
        path.appendPath((vendor != NULL) ? vendor : kDefaultVendor);
        path.appendPath(fileName);

        /* check the appropriate Zip file */
        ZipFileRO* pZip;
        ZipEntryRO entry;

        pZip = getZipFileLocked(ap);
        if (pZip != NULL) {
            //printf("GOT zip, checking '%s'\n", (const char*) path);
            entry = pZip->findEntryByName(path.string());
            if (entry != NULL) {
                //printf("FOUND in Zip file for %s/%s-%s\n",
                //    appName, locale, vendor);
                pAsset = openAssetFromZipLocked(pZip, entry, mode, path);
            }
        }

        if (pAsset != NULL) {
            /* create a "source" name, for debug/display */
            pAsset->setAssetSource(createZipSourceNameLocked(ZipSet::getPathName(ap.path.string()),
                                                             String8(""), String8(fileName)));
        }
    }

    return pAsset;
}

/*
 * Create a "source name" for a file from a Zip archive.
 */
String8 AssetManager::createZipSourceNameLocked(const String8& zipFileName,
    const String8& dirName, const String8& fileName)
{
    String8 sourceName("zip:");
    sourceName.append(zipFileName);
    sourceName.append(":");
    if (dirName.length() > 0) {
        sourceName.appendPath(dirName);
    }
    sourceName.appendPath(fileName);
    return sourceName;
}

/*
 * Create a path to a loose asset (asset-base/app/locale/vendor).
 */
String8 AssetManager::createPathNameLocked(const asset_path& ap, const char* locale,
    const char* vendor)
{
    String8 path(ap.path);
    path.appendPath((locale != NULL) ? locale : kDefaultLocale);
    path.appendPath((vendor != NULL) ? vendor : kDefaultVendor);
    return path;
}

/*
 * Create a path to a loose asset (asset-base/app/rootDir).
 */
String8 AssetManager::createPathNameLocked(const asset_path& ap, const char* rootDir)
{
    String8 path(ap.path);
    if (rootDir != NULL) path.appendPath(rootDir);
    return path;
}

/*
 * Return a pointer to one of our open Zip archives.  Returns NULL if no
 * matching Zip file exists.
 *
 * Right now we have 2 possible Zip files (1 each in app/"common").
 *
 * If caching is set to CACHE_OFF, to get the expected behavior we
 * need to reopen the Zip file on every request.  That would be silly
 * and expensive, so instead we just check the file modification date.
 *
 * Pass in NULL values for "appName", "locale", and "vendor" if the
 * generics should be used.
 */
ZipFileRO* AssetManager::getZipFileLocked(const asset_path& ap)
{
    ALOGV("getZipFileLocked() in %p\n", this);

    return mZipSet.getZip(ap.path);
}

/*
 * Try to open an asset from a file on disk.
 *
 * If the file is compressed with gzip, we seek to the start of the
 * deflated data and pass that in (just like we would for a Zip archive).
 *
 * For uncompressed data, we may already have an mmap()ed version sitting
 * around.  If so, we want to hand that to the Asset instead.
 *
 * This returns NULL if the file doesn't exist, couldn't be opened, or
 * claims to be a ".gz" but isn't.
 */
Asset* AssetManager::openAssetFromFileLocked(const String8& pathName,
    AccessMode mode)
{
    Asset* pAsset = NULL;

    if (strcasecmp(pathName.getPathExtension().string(), ".gz") == 0) {
        //printf("TRYING '%s'\n", (const char*) pathName);
        pAsset = Asset::createFromCompressedFile(pathName.string(), mode);
    } else {
        //printf("TRYING '%s'\n", (const char*) pathName);
        pAsset = Asset::createFromFile(pathName.string(), mode);
    }

    return pAsset;
}

/*
 * Given an entry in a Zip archive, create a new Asset object.
 *
 * If the entry is uncompressed, we may want to create or share a
 * slice of shared memory.
 */
Asset* AssetManager::openAssetFromZipLocked(const ZipFileRO* pZipFile,
    const ZipEntryRO entry, AccessMode mode, const String8& entryName)
{
    Asset* pAsset = NULL;

    // TODO: look for previously-created shared memory slice?
    int method;
    size_t uncompressedLen;

    //printf("USING Zip '%s'\n", pEntry->getFileName());

    //pZipFile->getEntryInfo(entry, &method, &uncompressedLen, &compressedLen,
    //    &offset);
    if (!pZipFile->getEntryInfo(entry, &method, &uncompressedLen, NULL, NULL,
            NULL, NULL))
    {
        ALOGW("getEntryInfo failed\n");
        return NULL;
    }

    FileMap* dataMap = pZipFile->createEntryFileMap(entry);
    if (dataMap == NULL) {
        ALOGW("create map from entry failed\n");
        return NULL;
    }

    if (method == ZipFileRO::kCompressStored) {
        pAsset = Asset::createFromUncompressedMap(dataMap, mode);
        ALOGV("Opened uncompressed entry %s in zip %s mode %d: %p", entryName.string(),
                dataMap->getFileName(), mode, pAsset);
    } else {
        pAsset = Asset::createFromCompressedMap(dataMap, method,
            uncompressedLen, mode);
        ALOGV("Opened compressed entry %s in zip %s mode %d: %p", entryName.string(),
                dataMap->getFileName(), mode, pAsset);
    }
    if (pAsset == NULL) {
        /* unexpected */
        ALOGW("create from segment failed\n");
    }

    return pAsset;
}



/*
 * Open a directory in the asset namespace.
 *
 * An "asset directory" is simply the combination of all files in all
 * locations, with ".gz" stripped for loose files.  With app, locale, and
 * vendor defined, we have 8 directories and 2 Zip archives to scan.
 *
 * Pass in "" for the root dir.
 */
AssetDir* AssetManager::openDir(const char* dirName)
{
    AutoMutex _l(mLock);

    AssetDir* pDir = NULL;
    SortedVector<AssetDir::FileInfo>* pMergedInfo = NULL;

    LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");
    assert(dirName != NULL);

    //printf("+++ openDir(%s) in '%s'\n", dirName, (const char*) mAssetBase);

    if (mCacheMode != CACHE_OFF && !mCacheValid)
        loadFileNameCacheLocked();

    pDir = new AssetDir;

    /*
     * Scan the various directories, merging what we find into a single
     * vector.  We want to scan them in reverse priority order so that
     * the ".EXCLUDE" processing works correctly.  Also, if we decide we
     * want to remember where the file is coming from, we'll get the right
     * version.
     *
     * We start with Zip archives, then do loose files.
     */
    pMergedInfo = new SortedVector<AssetDir::FileInfo>;

    size_t i = mAssetPaths.size();
    while (i > 0) {
        i--;
        const asset_path& ap = mAssetPaths.itemAt(i);
        if (ap.type == kFileTypeRegular) {
            ALOGV("Adding directory %s from zip %s", dirName, ap.path.string());
            scanAndMergeZipLocked(pMergedInfo, ap, kAssetsRoot, dirName);
        } else {
            ALOGV("Adding directory %s from dir %s", dirName, ap.path.string());
            scanAndMergeDirLocked(pMergedInfo, ap, kAssetsRoot, dirName);
        }
    }

#if 0
    printf("FILE LIST:\n");
    for (i = 0; i < (size_t) pMergedInfo->size(); i++) {
        printf(" %d: (%d) '%s'\n", i,
            pMergedInfo->itemAt(i).getFileType(),
            (const char*) pMergedInfo->itemAt(i).getFileName());
    }
#endif

    pDir->setFileList(pMergedInfo);
    return pDir;
}

/*
 * Open a directory in the non-asset namespace.
 *
 * An "asset directory" is simply the combination of all files in all
 * locations, with ".gz" stripped for loose files.  With app, locale, and
 * vendor defined, we have 8 directories and 2 Zip archives to scan.
 *
 * Pass in "" for the root dir.
 */
AssetDir* AssetManager::openNonAssetDir(void* cookie, const char* dirName)
{
    AutoMutex _l(mLock);

    AssetDir* pDir = NULL;
    SortedVector<AssetDir::FileInfo>* pMergedInfo = NULL;

    LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");
    assert(dirName != NULL);

    //printf("+++ openDir(%s) in '%s'\n", dirName, (const char*) mAssetBase);

    if (mCacheMode != CACHE_OFF && !mCacheValid)
        loadFileNameCacheLocked();

    pDir = new AssetDir;

    pMergedInfo = new SortedVector<AssetDir::FileInfo>;

    const size_t which = ((size_t)cookie)-1;

    if (which < mAssetPaths.size()) {
        const asset_path& ap = mAssetPaths.itemAt(which);
        if (ap.type == kFileTypeRegular) {
            ALOGV("Adding directory %s from zip %s", dirName, ap.path.string());
            scanAndMergeZipLocked(pMergedInfo, ap, NULL, dirName);
        } else {
            ALOGV("Adding directory %s from dir %s", dirName, ap.path.string());
            scanAndMergeDirLocked(pMergedInfo, ap, NULL, dirName);
        }
    }

#if 0
    printf("FILE LIST:\n");
    for (i = 0; i < (size_t) pMergedInfo->size(); i++) {
        printf(" %d: (%d) '%s'\n", i,
            pMergedInfo->itemAt(i).getFileType(),
            (const char*) pMergedInfo->itemAt(i).getFileName());
    }
#endif

    pDir->setFileList(pMergedInfo);
    return pDir;
}

/*
 * Scan the contents of the specified directory and merge them into the
 * "pMergedInfo" vector, removing previous entries if we find "exclude"
 * directives.
 *
 * Returns "false" if we found nothing to contribute.
 */
bool AssetManager::scanAndMergeDirLocked(SortedVector<AssetDir::FileInfo>* pMergedInfo,
    const asset_path& ap, const char* rootDir, const char* dirName)
{
    SortedVector<AssetDir::FileInfo>* pContents;
    String8 path;

    assert(pMergedInfo != NULL);

    //printf("scanAndMergeDir: %s %s %s %s\n", appName, locale, vendor,dirName);

    if (mCacheValid) {
        int i, start, count;

        pContents = new SortedVector<AssetDir::FileInfo>;

        /*
         * Get the basic partial path and find it in the cache.  That's
         * the start point for the search.
         */
        path = createPathNameLocked(ap, rootDir);
        if (dirName[0] != '\0')
            path.appendPath(dirName);

        start = mCache.indexOf(path);
        if (start == NAME_NOT_FOUND) {
            //printf("+++ not found in cache: dir '%s'\n", (const char*) path);
            delete pContents;
            return false;
        }

        /*
         * The match string looks like "common/default/default/foo/bar/".
         * The '/' on the end ensures that we don't match on the directory
         * itself or on ".../foo/barfy/".
         */
        path.append("/");

        count = mCache.size();

        /*
         * Pick out the stuff in the current dir by examining the pathname.
         * It needs to match the partial pathname prefix, and not have a '/'
         * (fssep) anywhere after the prefix.
         */
        for (i = start+1; i < count; i++) {
            if (mCache[i].getFileName().length() > path.length() &&
                strncmp(mCache[i].getFileName().string(), path.string(), path.length()) == 0)
            {
                const char* name = mCache[i].getFileName().string();
                // XXX THIS IS BROKEN!  Looks like we need to store the full
                // path prefix separately from the file path.
                if (strchr(name + path.length(), '/') == NULL) {
                    /* grab it, reducing path to just the filename component */
                    AssetDir::FileInfo tmp = mCache[i];
                    tmp.setFileName(tmp.getFileName().getPathLeaf());
                    pContents->add(tmp);
                }
            } else {
                /* no longer in the dir or its subdirs */
                break;
            }

        }
    } else {
        path = createPathNameLocked(ap, rootDir);
        if (dirName[0] != '\0')
            path.appendPath(dirName);
        pContents = scanDirLocked(path);
        if (pContents == NULL)
            return false;
    }

    // if we wanted to do an incremental cache fill, we would do it here

    /*
     * Process "exclude" directives.  If we find a filename that ends with
     * ".EXCLUDE", we look for a matching entry in the "merged" set, and
     * remove it if we find it.  We also delete the "exclude" entry.
     */
    int i, count, exclExtLen;

    count = pContents->size();
    exclExtLen = strlen(kExcludeExtension);
    for (i = 0; i < count; i++) {
        const char* name;
        int nameLen;

        name = pContents->itemAt(i).getFileName().string();
        nameLen = strlen(name);
        if (nameLen > exclExtLen &&
            strcmp(name + (nameLen - exclExtLen), kExcludeExtension) == 0)
        {
            String8 match(name, nameLen - exclExtLen);
            int matchIdx;

            matchIdx = AssetDir::FileInfo::findEntry(pMergedInfo, match);
            if (matchIdx > 0) {
                ALOGV("Excluding '%s' [%s]\n",
                    pMergedInfo->itemAt(matchIdx).getFileName().string(),
                    pMergedInfo->itemAt(matchIdx).getSourceName().string());
                pMergedInfo->removeAt(matchIdx);
            } else {
                //printf("+++ no match on '%s'\n", (const char*) match);
            }

            ALOGD("HEY: size=%d removing %d\n", (int)pContents->size(), i);
            pContents->removeAt(i);
            i--;        // adjust "for" loop
            count--;    //  and loop limit
        }
    }

    mergeInfoLocked(pMergedInfo, pContents);

    delete pContents;

    return true;
}

/*
 * Scan the contents of the specified directory, and stuff what we find
 * into a newly-allocated vector.
 *
 * Files ending in ".gz" will have their extensions removed.
 *
 * We should probably think about skipping files with "illegal" names,
 * e.g. illegal characters (/\:) or excessive length.
 *
 * Returns NULL if the specified directory doesn't exist.
 */
SortedVector<AssetDir::FileInfo>* AssetManager::scanDirLocked(const String8& path)
{
    SortedVector<AssetDir::FileInfo>* pContents = NULL;
    DIR* dir;
    struct dirent* entry;
    FileType fileType;

    ALOGV("Scanning dir '%s'\n", path.string());

    dir = opendir(path.string());
    if (dir == NULL)
        return NULL;

    pContents = new SortedVector<AssetDir::FileInfo>;

    while (1) {
        entry = readdir(dir);
        if (entry == NULL)
            break;

        if (strcmp(entry->d_name, ".") == 0 ||
            strcmp(entry->d_name, "..") == 0)
            continue;

#ifdef _DIRENT_HAVE_D_TYPE
        if (entry->d_type == DT_REG)
            fileType = kFileTypeRegular;
        else if (entry->d_type == DT_DIR)
            fileType = kFileTypeDirectory;
        else
            fileType = kFileTypeUnknown;
#else
        // stat the file
        fileType = ::getFileType(path.appendPathCopy(entry->d_name).string());
#endif

        if (fileType != kFileTypeRegular && fileType != kFileTypeDirectory)
            continue;

        AssetDir::FileInfo info;
        info.set(String8(entry->d_name), fileType);
        if (strcasecmp(info.getFileName().getPathExtension().string(), ".gz") == 0)
            info.setFileName(info.getFileName().getBasePath());
        info.setSourceName(path.appendPathCopy(info.getFileName()));
        pContents->add(info);
    }

    closedir(dir);
    return pContents;
}

/*
 * Scan the contents out of the specified Zip archive, and merge what we
 * find into "pMergedInfo".  If the Zip archive in question doesn't exist,
 * we return immediately.
 *
 * Returns "false" if we found nothing to contribute.
 */
bool AssetManager::scanAndMergeZipLocked(SortedVector<AssetDir::FileInfo>* pMergedInfo,
    const asset_path& ap, const char* rootDir, const char* baseDirName)
{
    ZipFileRO* pZip;
    Vector<String8> dirs;
    AssetDir::FileInfo info;
    SortedVector<AssetDir::FileInfo> contents;
    String8 sourceName, zipName, dirName;

    pZip = mZipSet.getZip(ap.path);
    if (pZip == NULL) {
        ALOGW("Failure opening zip %s\n", ap.path.string());
        return false;
    }

    zipName = ZipSet::getPathName(ap.path.string());

    /* convert "sounds" to "rootDir/sounds" */
    if (rootDir != NULL) dirName = rootDir;
    dirName.appendPath(baseDirName);

    /*
     * Scan through the list of files, looking for a match.  The files in
     * the Zip table of contents are not in sorted order, so we have to
     * process the entire list.  We're looking for a string that begins
     * with the characters in "dirName", is followed by a '/', and has no
     * subsequent '/' in the stuff that follows.
     *
     * What makes this especially fun is that directories are not stored
     * explicitly in Zip archives, so we have to infer them from context.
     * When we see "sounds/foo.wav" we have to leave a note to ourselves
     * to insert a directory called "sounds" into the list.  We store
     * these in temporary vector so that we only return each one once.
     *
     * Name comparisons are case-sensitive to match UNIX filesystem
     * semantics.
     */
    int dirNameLen = dirName.length();
    for (int i = 0; i < pZip->getNumEntries(); i++) {
        ZipEntryRO entry;
        char nameBuf[256];

        entry = pZip->findEntryByIndex(i);
        if (pZip->getEntryFileName(entry, nameBuf, sizeof(nameBuf)) != 0) {
            // TODO: fix this if we expect to have long names
            ALOGE("ARGH: name too long?\n");
            continue;
        }
        //printf("Comparing %s in %s?\n", nameBuf, dirName.string());
        if (dirNameLen == 0 ||
            (strncmp(nameBuf, dirName.string(), dirNameLen) == 0 &&
             nameBuf[dirNameLen] == '/'))
        {
            const char* cp;
            const char* nextSlash;

            cp = nameBuf + dirNameLen;
            if (dirNameLen != 0)
                cp++;       // advance past the '/'

            nextSlash = strchr(cp, '/');
//xxx this may break if there are bare directory entries
            if (nextSlash == NULL) {
                /* this is a file in the requested directory */

                info.set(String8(nameBuf).getPathLeaf(), kFileTypeRegular);

                info.setSourceName(
                    createZipSourceNameLocked(zipName, dirName, info.getFileName()));

                contents.add(info);
                //printf("FOUND: file '%s'\n", info.getFileName().string());
            } else {
                /* this is a subdir; add it if we don't already have it*/
                String8 subdirName(cp, nextSlash - cp);
                size_t j;
                size_t N = dirs.size();

                for (j = 0; j < N; j++) {
                    if (subdirName == dirs[j]) {
                        break;
                    }
                }
                if (j == N) {
                    dirs.add(subdirName);
                }

                //printf("FOUND: dir '%s'\n", subdirName.string());
            }
        }
    }

    /*
     * Add the set of unique directories.
     */
    for (int i = 0; i < (int) dirs.size(); i++) {
        info.set(dirs[i], kFileTypeDirectory);
        info.setSourceName(
            createZipSourceNameLocked(zipName, dirName, info.getFileName()));
        contents.add(info);
    }

    mergeInfoLocked(pMergedInfo, &contents);

    return true;
}


/*
 * Merge two vectors of FileInfo.
 *
 * The merged contents will be stuffed into *pMergedInfo.
 *
 * If an entry for a file exists in both "pMergedInfo" and "pContents",
 * we use the newer "pContents" entry.
 */
void AssetManager::mergeInfoLocked(SortedVector<AssetDir::FileInfo>* pMergedInfo,
    const SortedVector<AssetDir::FileInfo>* pContents)
{
    /*
     * Merge what we found in this directory with what we found in
     * other places.
     *
     * Two basic approaches:
     * (1) Create a new array that holds the unique values of the two
     *     arrays.
     * (2) Take the elements from pContents and shove them into pMergedInfo.
     *
     * Because these are vectors of complex objects, moving elements around
     * inside the vector requires constructing new objects and allocating
     * storage for members.  With approach #1, we're always adding to the
     * end, whereas with #2 we could be inserting multiple elements at the
     * front of the vector.  Approach #1 requires a full copy of the
     * contents of pMergedInfo, but approach #2 requires the same copy for
     * every insertion at the front of pMergedInfo.
     *
     * (We should probably use a SortedVector interface that allows us to
     * just stuff items in, trusting us to maintain the sort order.)
     */
    SortedVector<AssetDir::FileInfo>* pNewSorted;
    int mergeMax, contMax;
    int mergeIdx, contIdx;

    pNewSorted = new SortedVector<AssetDir::FileInfo>;
    mergeMax = pMergedInfo->size();
    contMax = pContents->size();
    mergeIdx = contIdx = 0;

    while (mergeIdx < mergeMax || contIdx < contMax) {
        if (mergeIdx == mergeMax) {
            /* hit end of "merge" list, copy rest of "contents" */
            pNewSorted->add(pContents->itemAt(contIdx));
            contIdx++;
        } else if (contIdx == contMax) {
            /* hit end of "cont" list, copy rest of "merge" */
            pNewSorted->add(pMergedInfo->itemAt(mergeIdx));
            mergeIdx++;
        } else if (pMergedInfo->itemAt(mergeIdx) == pContents->itemAt(contIdx))
        {
            /* items are identical, add newer and advance both indices */
            pNewSorted->add(pContents->itemAt(contIdx));
            mergeIdx++;
            contIdx++;
        } else if (pMergedInfo->itemAt(mergeIdx) < pContents->itemAt(contIdx))
        {
            /* "merge" is lower, add that one */
            pNewSorted->add(pMergedInfo->itemAt(mergeIdx));
            mergeIdx++;
        } else {
            /* "cont" is lower, add that one */
            assert(pContents->itemAt(contIdx) < pMergedInfo->itemAt(mergeIdx));
            pNewSorted->add(pContents->itemAt(contIdx));
            contIdx++;
        }
    }

    /*
     * Overwrite the "merged" list with the new stuff.
     */
    *pMergedInfo = *pNewSorted;
    delete pNewSorted;

#if 0       // for Vector, rather than SortedVector
    int i, j;
    for (i = pContents->size() -1; i >= 0; i--) {
        bool add = true;

        for (j = pMergedInfo->size() -1; j >= 0; j--) {
            /* case-sensitive comparisons, to behave like UNIX fs */
            if (strcmp(pContents->itemAt(i).mFileName,
                       pMergedInfo->itemAt(j).mFileName) == 0)
            {
                /* match, don't add this entry */
                add = false;
                break;
            }
        }

        if (add)
            pMergedInfo->add(pContents->itemAt(i));
    }
#endif
}


/*
 * Load all files into the file name cache.  We want to do this across
 * all combinations of { appname, locale, vendor }, performing a recursive
 * directory traversal.
 *
 * This is not the most efficient data structure.  Also, gathering the
 * information as we needed it (file-by-file or directory-by-directory)
 * would be faster.  However, on the actual device, 99% of the files will
 * live in Zip archives, so this list will be very small.  The trouble
 * is that we have to check the "loose" files first, so it's important
 * that we don't beat the filesystem silly looking for files that aren't
 * there.
 *
 * Note on thread safety: this is the only function that causes updates
 * to mCache, and anybody who tries to use it will call here if !mCacheValid,
 * so we need to employ a mutex here.
 */
void AssetManager::loadFileNameCacheLocked(void)
{
    assert(!mCacheValid);
    assert(mCache.size() == 0);

#ifdef DO_TIMINGS   // need to link against -lrt for this now
    DurationTimer timer;
    timer.start();
#endif

    fncScanLocked(&mCache, "");

#ifdef DO_TIMINGS
    timer.stop();
    ALOGD("Cache scan took %.3fms\n",
        timer.durationUsecs() / 1000.0);
#endif

#if 0
    int i;
    printf("CACHED FILE LIST (%d entries):\n", mCache.size());
    for (i = 0; i < (int) mCache.size(); i++) {
        printf(" %d: (%d) '%s'\n", i,
            mCache.itemAt(i).getFileType(),
            (const char*) mCache.itemAt(i).getFileName());
    }
#endif

    mCacheValid = true;
}

/*
 * Scan up to 8 versions of the specified directory.
 */
void AssetManager::fncScanLocked(SortedVector<AssetDir::FileInfo>* pMergedInfo,
    const char* dirName)
{
    size_t i = mAssetPaths.size();
    while (i > 0) {
        i--;
        const asset_path& ap = mAssetPaths.itemAt(i);
        fncScanAndMergeDirLocked(pMergedInfo, ap, NULL, NULL, dirName);
        if (mLocale != NULL)
            fncScanAndMergeDirLocked(pMergedInfo, ap, mLocale, NULL, dirName);
        if (mVendor != NULL)
            fncScanAndMergeDirLocked(pMergedInfo, ap, NULL, mVendor, dirName);
        if (mLocale != NULL && mVendor != NULL)
            fncScanAndMergeDirLocked(pMergedInfo, ap, mLocale, mVendor, dirName);
    }
}

/*
 * Recursively scan this directory and all subdirs.
 *
 * This is similar to scanAndMergeDir, but we don't remove the .EXCLUDE
 * files, and we prepend the extended partial path to the filenames.
 */
bool AssetManager::fncScanAndMergeDirLocked(
    SortedVector<AssetDir::FileInfo>* pMergedInfo,
    const asset_path& ap, const char* locale, const char* vendor,
    const char* dirName)
{
    SortedVector<AssetDir::FileInfo>* pContents;
    String8 partialPath;
    String8 fullPath;

    // XXX This is broken -- the filename cache needs to hold the base
    // asset path separately from its filename.
    
    partialPath = createPathNameLocked(ap, locale, vendor);
    if (dirName[0] != '\0') {
        partialPath.appendPath(dirName);
    }

    fullPath = partialPath;
    pContents = scanDirLocked(fullPath);
    if (pContents == NULL) {
        return false;       // directory did not exist
    }

    /*
     * Scan all subdirectories of the current dir, merging what we find
     * into "pMergedInfo".
     */
    for (int i = 0; i < (int) pContents->size(); i++) {
        if (pContents->itemAt(i).getFileType() == kFileTypeDirectory) {
            String8 subdir(dirName);
            subdir.appendPath(pContents->itemAt(i).getFileName());

            fncScanAndMergeDirLocked(pMergedInfo, ap, locale, vendor, subdir.string());
        }
    }

    /*
     * To be consistent, we want entries for the root directory.  If
     * we're the root, add one now.
     */
    if (dirName[0] == '\0') {
        AssetDir::FileInfo tmpInfo;

        tmpInfo.set(String8(""), kFileTypeDirectory);
        tmpInfo.setSourceName(createPathNameLocked(ap, locale, vendor));
        pContents->add(tmpInfo);
    }

    /*
     * We want to prepend the extended partial path to every entry in
     * "pContents".  It's the same value for each entry, so this will
     * not change the sorting order of the vector contents.
     */
    for (int i = 0; i < (int) pContents->size(); i++) {
        const AssetDir::FileInfo& info = pContents->itemAt(i);
        pContents->editItemAt(i).setFileName(partialPath.appendPathCopy(info.getFileName()));
    }

    mergeInfoLocked(pMergedInfo, pContents);
    return true;
}

/*
 * Trash the cache.
 */
void AssetManager::purgeFileNameCacheLocked(void)
{
    mCacheValid = false;
    mCache.clear();
}

/*
 * ===========================================================================
 *      AssetManager::SharedZip
 * ===========================================================================
 */


Mutex AssetManager::SharedZip::gLock;
DefaultKeyedVector<String8, wp<AssetManager::SharedZip> > AssetManager::SharedZip::gOpen;

AssetManager::SharedZip::SharedZip(const String8& path, time_t modWhen)
    : mPath(path), mZipFile(NULL), mModWhen(modWhen),
      mResourceTableAsset(NULL), mResourceTable(NULL)
{
    //ALOGI("Creating SharedZip %p %s\n", this, (const char*)mPath);
    mZipFile = new ZipFileRO;
    ALOGV("+++ opening zip '%s'\n", mPath.string());
    if (mZipFile->open(mPath.string()) != NO_ERROR) {
        ALOGD("failed to open Zip archive '%s'\n", mPath.string());
        delete mZipFile;
        mZipFile = NULL;
    }
}

sp<AssetManager::SharedZip> AssetManager::SharedZip::get(const String8& path)
{
    AutoMutex _l(gLock);
    time_t modWhen = getFileModDate(path);
    sp<SharedZip> zip = gOpen.valueFor(path).promote();
    if (zip != NULL && zip->mModWhen == modWhen) {
        return zip;
    }
    zip = new SharedZip(path, modWhen);
    gOpen.add(path, zip);
    return zip;

}

ZipFileRO* AssetManager::SharedZip::getZip()
{
    return mZipFile;
}

Asset* AssetManager::SharedZip::getResourceTableAsset()
{
    ALOGV("Getting from SharedZip %p resource asset %p\n", this, mResourceTableAsset);
    return mResourceTableAsset;
}

Asset* AssetManager::SharedZip::setResourceTableAsset(Asset* asset)
{
    {
        AutoMutex _l(gLock);
        if (mResourceTableAsset == NULL) {
            mResourceTableAsset = asset;
            // This is not thread safe the first time it is called, so
            // do it here with the global lock held.
            asset->getBuffer(true);
            return asset;
        }
    }
    delete asset;
    return mResourceTableAsset;
}

ResTable* AssetManager::SharedZip::getResourceTable()
{
    ALOGV("Getting from SharedZip %p resource table %p\n", this, mResourceTable);
    return mResourceTable;
}

ResTable* AssetManager::SharedZip::setResourceTable(ResTable* res)
{
    {
        AutoMutex _l(gLock);
        if (mResourceTable == NULL) {
            mResourceTable = res;
            return res;
        }
    }
    delete res;
    return mResourceTable;
}

bool AssetManager::SharedZip::isUpToDate()
{
    time_t modWhen = getFileModDate(mPath.string());
    return mModWhen == modWhen;
}

AssetManager::SharedZip::~SharedZip()
{
    //ALOGI("Destroying SharedZip %p %s\n", this, (const char*)mPath);
    if (mResourceTable != NULL) {
        delete mResourceTable;
    }
    if (mResourceTableAsset != NULL) {
        delete mResourceTableAsset;
    }
    if (mZipFile != NULL) {
        delete mZipFile;
        ALOGV("Closed '%s'\n", mPath.string());
    }
}

/*
 * ===========================================================================
 *      AssetManager::ZipSet
 * ===========================================================================
 */

/*
 * Constructor.
 */
AssetManager::ZipSet::ZipSet(void)
{
}

/*
 * Destructor.  Close any open archives.
 */
AssetManager::ZipSet::~ZipSet(void)
{
    size_t N = mZipFile.size();
    for (size_t i = 0; i < N; i++)
        closeZip(i);
}

/*
 * Close a Zip file and reset the entry.
 */
void AssetManager::ZipSet::closeZip(int idx)
{
    mZipFile.editItemAt(idx) = NULL;
}


/*
 * Retrieve the appropriate Zip file from the set.
 */
ZipFileRO* AssetManager::ZipSet::getZip(const String8& path)
{
    int idx = getIndex(path);
    sp<SharedZip> zip = mZipFile[idx];
    if (zip == NULL) {
        zip = SharedZip::get(path);
        mZipFile.editItemAt(idx) = zip;
    }
    return zip->getZip();
}

Asset* AssetManager::ZipSet::getZipResourceTableAsset(const String8& path)
{
    int idx = getIndex(path);
    sp<SharedZip> zip = mZipFile[idx];
    if (zip == NULL) {
        zip = SharedZip::get(path);
        mZipFile.editItemAt(idx) = zip;
    }
    return zip->getResourceTableAsset();
}

Asset* AssetManager::ZipSet::setZipResourceTableAsset(const String8& path,
                                                 Asset* asset)
{
    int idx = getIndex(path);
    sp<SharedZip> zip = mZipFile[idx];
    // doesn't make sense to call before previously accessing.
    return zip->setResourceTableAsset(asset);
}

ResTable* AssetManager::ZipSet::getZipResourceTable(const String8& path)
{
    int idx = getIndex(path);
    sp<SharedZip> zip = mZipFile[idx];
    if (zip == NULL) {
        zip = SharedZip::get(path);
        mZipFile.editItemAt(idx) = zip;
    }
    return zip->getResourceTable();
}

ResTable* AssetManager::ZipSet::setZipResourceTable(const String8& path,
                                                    ResTable* res)
{
    int idx = getIndex(path);
    sp<SharedZip> zip = mZipFile[idx];
    // doesn't make sense to call before previously accessing.
    return zip->setResourceTable(res);
}

/*
 * Generate the partial pathname for the specified archive.  The caller
 * gets to prepend the asset root directory.
 *
 * Returns something like "common/en-US-noogle.jar".
 */
/*static*/ String8 AssetManager::ZipSet::getPathName(const char* zipPath)
{
    return String8(zipPath);
}

bool AssetManager::ZipSet::isUpToDate()
{
    const size_t N = mZipFile.size();
    for (size_t i=0; i<N; i++) {
        if (mZipFile[i] != NULL && !mZipFile[i]->isUpToDate()) {
            return false;
        }
    }
    return true;
}

/*
 * Compute the zip file's index.
 *
 * "appName", "locale", and "vendor" should be set to NULL to indicate the
 * default directory.
 */
int AssetManager::ZipSet::getIndex(const String8& zip) const
{
    const size_t N = mZipPath.size();
    for (size_t i=0; i<N; i++) {
        if (mZipPath[i] == zip) {
            return i;
        }
    }

    mZipPath.add(zip);
    mZipFile.add(NULL);

    return mZipPath.size()-1;
}
