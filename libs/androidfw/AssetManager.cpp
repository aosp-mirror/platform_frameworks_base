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
#define ATRACE_TAG ATRACE_TAG_RESOURCES
//#define LOG_NDEBUG 0

#include <androidfw/Asset.h>
#include <androidfw/AssetDir.h>
#include <androidfw/AssetManager.h>
#include <androidfw/misc.h>
#include <androidfw/PathUtils.h>
#include <androidfw/ResourceTypes.h>
#include <androidfw/ZipFileRO.h>
#include <cutils/atomic.h>
#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/String8.h>
#include <utils/threads.h>
#include <utils/Timers.h>
#include <utils/Trace.h>
#ifndef _WIN32
#include <sys/file.h>
#endif

#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <string.h> // strerror
#include <strings.h>

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

static const bool kIsDebug = false;

static const char* kAssetsRoot = "assets";
static const char* kAppZipName = NULL; //"classes.jar";
static const char* kSystemAssets = "framework/framework-res.apk";
static const char* kResourceCache = "resource-cache";

static const char* kExcludeExtension = ".EXCLUDE";

static Asset* const kExcludedAsset = (Asset*) 0xd000000d;

static volatile int32_t gCount = 0;

const char* AssetManager::RESOURCES_FILENAME = "resources.arsc";
const char* AssetManager::IDMAP_BIN = "/system/bin/idmap";
const char* AssetManager::VENDOR_OVERLAY_DIR = "/vendor/overlay";
const char* AssetManager::PRODUCT_OVERLAY_DIR = "/product/overlay";
const char* AssetManager::SYSTEM_EXT_OVERLAY_DIR = "/system_ext/overlay";
const char* AssetManager::ODM_OVERLAY_DIR = "/odm/overlay";
const char* AssetManager::OEM_OVERLAY_DIR = "/oem/overlay";
const char* AssetManager::OVERLAY_THEME_DIR_PROPERTY = "ro.boot.vendor.overlay.theme";
const char* AssetManager::TARGET_PACKAGE_NAME = "android";
const char* AssetManager::TARGET_APK_PATH = "/system/framework/framework-res.apk";
const char* AssetManager::IDMAP_DIR = "/data/resource-cache";

namespace {

String8 idmapPathForPackagePath(const String8& pkgPath) {
    const char* root = getenv("ANDROID_DATA");
    LOG_ALWAYS_FATAL_IF(root == NULL, "ANDROID_DATA not set");
    String8 path(root);
    appendPath(path, kResourceCache);

    char buf[256]; // 256 chars should be enough for anyone...
    strncpy(buf, pkgPath.c_str(), 255);
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
    appendPath(path, filename);
    path.append("@idmap");

    return path;
}

/*
 * Like strdup(), but uses C++ "new" operator instead of malloc.
 */
static char* strdupNew(const char* str) {
    char* newStr;
    int len;

    if (str == NULL)
        return NULL;

    len = strlen(str);
    newStr = new char[len+1];
    memcpy(newStr, str, len+1);

    return newStr;
}

} // namespace

/*
 * ===========================================================================
 *      AssetManager
 * ===========================================================================
 */

int32_t AssetManager::getGlobalCount() {
    return gCount;
}

AssetManager::AssetManager() :
        mLocale(NULL), mResources(NULL), mConfig(new ResTable_config) {
    int count = android_atomic_inc(&gCount) + 1;
    if (kIsDebug) {
        ALOGI("Creating AssetManager %p #%d\n", this, count);
    }
    memset(mConfig, 0, sizeof(ResTable_config));
}

AssetManager::~AssetManager() {
    int count = android_atomic_dec(&gCount);
    if (kIsDebug) {
        ALOGI("Destroying AssetManager in %p #%d\n", this, count);
    } else {
        ALOGV("Destroying AssetManager in %p #%d\n", this, count);
    }

    // Manually close any fd paths for which we have not yet opened their zip (which
    // will take ownership of the fd and close it when done).
    for (size_t i=0; i<mAssetPaths.size(); i++) {
        ALOGV("Cleaning path #%d: fd=%d, zip=%p", (int)i, mAssetPaths[i].rawFd,
                mAssetPaths[i].zip.get());
        if (mAssetPaths[i].rawFd >= 0 && mAssetPaths[i].zip == NULL) {
            close(mAssetPaths[i].rawFd);
        }
    }

    delete mConfig;
    delete mResources;

    // don't have a String class yet, so make sure we clean up
    delete[] mLocale;
}

bool AssetManager::addAssetPath(
        const String8& path, int32_t* cookie, bool appAsLib, bool isSystemAsset) {
    AutoMutex _l(mLock);

    asset_path ap;

    String8 realPath(path);
    if (kAppZipName) {
        appendPath(realPath, kAppZipName);
    }
    ap.type = ::getFileType(realPath.c_str());
    if (ap.type == kFileTypeRegular) {
        ap.path = realPath;
    } else {
        ap.path = path;
        ap.type = ::getFileType(path.c_str());
        if (ap.type != kFileTypeDirectory && ap.type != kFileTypeRegular) {
            ALOGW("Asset path %s is neither a directory nor file (type=%d).",
                 path.c_str(), (int)ap.type);
            return false;
        }
    }

    // Skip if we have it already.
    for (size_t i=0; i<mAssetPaths.size(); i++) {
        if (mAssetPaths[i].path == ap.path) {
            if (cookie) {
                *cookie = static_cast<int32_t>(i+1);
            }
            return true;
        }
    }

    ALOGV("In %p Asset %s path: %s", this,
         ap.type == kFileTypeDirectory ? "dir" : "zip", ap.path.c_str());

    ap.isSystemAsset = isSystemAsset;
    ssize_t apPos = mAssetPaths.add(ap);

    // new paths are always added at the end
    if (cookie) {
        *cookie = static_cast<int32_t>(mAssetPaths.size());
    }

#ifdef __ANDROID__
    // Load overlays, if any
    asset_path oap;
    for (size_t idx = 0; mZipSet.getOverlay(ap.path, idx, &oap); idx++) {
        oap.isSystemAsset = isSystemAsset;
        mAssetPaths.add(oap);
    }
#endif

    if (mResources != NULL) {
        appendPathToResTable(mAssetPaths.editItemAt(apPos), appAsLib);
    }

    return true;
}

bool AssetManager::addOverlayPath(const String8& packagePath, int32_t* cookie)
{
    const String8 idmapPath = idmapPathForPackagePath(packagePath);

    AutoMutex _l(mLock);

    for (size_t i = 0; i < mAssetPaths.size(); ++i) {
        if (mAssetPaths[i].idmap == idmapPath) {
           *cookie = static_cast<int32_t>(i + 1);
            return true;
         }
     }

    Asset* idmap = NULL;
    if ((idmap = openAssetFromFileLocked(idmapPath, Asset::ACCESS_BUFFER)) == NULL) {
        ALOGW("failed to open idmap file %s\n", idmapPath.c_str());
        return false;
    }

    String8 targetPath;
    String8 overlayPath;
    if (!ResTable::getIdmapInfo(idmap->getBuffer(false), idmap->getLength(),
                NULL, NULL, NULL, &targetPath, &overlayPath)) {
        ALOGW("failed to read idmap file %s\n", idmapPath.c_str());
        delete idmap;
        return false;
    }
    delete idmap;

    if (overlayPath != packagePath) {
        ALOGW("idmap file %s inconcistent: expected path %s does not match actual path %s\n",
                idmapPath.c_str(), packagePath.c_str(), overlayPath.c_str());
        return false;
    }
    if (access(targetPath.c_str(), R_OK) != 0) {
        ALOGW("failed to access file %s: %s\n", targetPath.c_str(), strerror(errno));
        return false;
    }
    if (access(idmapPath.c_str(), R_OK) != 0) {
        ALOGW("failed to access file %s: %s\n", idmapPath.c_str(), strerror(errno));
        return false;
    }
    if (access(overlayPath.c_str(), R_OK) != 0) {
        ALOGW("failed to access file %s: %s\n", overlayPath.c_str(), strerror(errno));
        return false;
    }

    asset_path oap;
    oap.path = overlayPath;
    oap.type = ::getFileType(overlayPath.c_str());
    oap.idmap = idmapPath;
#if 0
    ALOGD("Overlay added: targetPath=%s overlayPath=%s idmapPath=%s\n",
            targetPath.c_str(), overlayPath.c_str(), idmapPath.c_str());
#endif
    mAssetPaths.add(oap);
    *cookie = static_cast<int32_t>(mAssetPaths.size());

    if (mResources != NULL) {
        appendPathToResTable(oap);
    }

    return true;
}

bool AssetManager::addAssetFd(
        int fd, const String8& debugPathName, int32_t* cookie, bool appAsLib,
        bool assume_ownership) {
    AutoMutex _l(mLock);

    asset_path ap;

    ap.path = debugPathName;
    ap.rawFd = fd;
    ap.type = kFileTypeRegular;
    ap.assumeOwnership = assume_ownership;

    ALOGV("In %p Asset fd %d name: %s", this, fd, ap.path.c_str());

    ssize_t apPos = mAssetPaths.add(ap);

    // new paths are always added at the end
    if (cookie) {
        *cookie = static_cast<int32_t>(mAssetPaths.size());
    }

    if (mResources != NULL) {
        appendPathToResTable(mAssetPaths.editItemAt(apPos), appAsLib);
    }

    return true;
}

bool AssetManager::createIdmap(const char* targetApkPath, const char* overlayApkPath,
        uint32_t targetCrc, uint32_t overlayCrc, uint32_t** outData, size_t* outSize)
{
    AutoMutex _l(mLock);
    const String8 paths[2] = { String8(targetApkPath), String8(overlayApkPath) };
    Asset* assets[2] = {NULL, NULL};
    bool ret = false;
    {
        ResTable tables[2];

        for (int i = 0; i < 2; ++i) {
            asset_path ap;
            ap.type = kFileTypeRegular;
            ap.path = paths[i];
            assets[i] = openNonAssetInPathLocked("resources.arsc",
                    Asset::ACCESS_BUFFER, ap);
            if (assets[i] == NULL) {
                ALOGW("failed to find resources.arsc in %s\n", ap.path.c_str());
                goto exit;
            }
            if (tables[i].add(assets[i]) != NO_ERROR) {
                ALOGW("failed to add %s to resource table", paths[i].c_str());
                goto exit;
            }
        }
        ret = tables[1].createIdmap(tables[0], targetCrc, overlayCrc,
                targetApkPath, overlayApkPath, (void**)outData, outSize) == NO_ERROR;
    }

exit:
    delete assets[0];
    delete assets[1];
    return ret;
}

bool AssetManager::addDefaultAssets()
{
    const char* root = getenv("ANDROID_ROOT");
    LOG_ALWAYS_FATAL_IF(root == NULL, "ANDROID_ROOT not set");

    String8 path(root);
    appendPath(path, kSystemAssets);

    return addAssetPath(path, NULL, false /* appAsLib */, true /* isSystemAsset */);
}

int32_t AssetManager::nextAssetPath(const int32_t cookie) const
{
    AutoMutex _l(mLock);
    const size_t next = static_cast<size_t>(cookie) + 1;
    return next > mAssetPaths.size() ? -1 : next;
}

String8 AssetManager::getAssetPath(const int32_t cookie) const
{
    AutoMutex _l(mLock);
    const size_t which = static_cast<size_t>(cookie) - 1;
    if (which < mAssetPaths.size()) {
        return mAssetPaths[which].path;
    }
    return String8();
}

void AssetManager::setLocaleLocked(const char* locale)
{
    if (mLocale != NULL) {
        delete[] mLocale;
    }

    mLocale = strdupNew(locale);
    updateResourceParamsLocked();
}

void AssetManager::setConfiguration(const ResTable_config& config, const char* locale)
{
    AutoMutex _l(mLock);
    *mConfig = config;
    if (locale) {
        setLocaleLocked(locale);
    } else if (config.language[0] != 0) {
        char spec[RESTABLE_MAX_LOCALE_LEN];
        config.getBcp47Locale(spec);
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
 * The data could be in any asset path. Each asset path could be:
 *  - A directory on disk.
 *  - A Zip archive, uncompressed or compressed.
 *
 * If the file is in a directory, it could have a .gz suffix, meaning it is compressed.
 *
 * We should probably reject requests for "illegal" filenames, e.g. those
 * with illegal characters or "../" backward relative paths.
 */
Asset* AssetManager::open(const char* fileName, AccessMode mode)
{
    AutoMutex _l(mLock);

    LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");

    String8 assetName(kAssetsRoot);
    appendPath(assetName, fileName);

    /*
     * For each top-level asset path, search for the asset.
     */

    size_t i = mAssetPaths.size();
    while (i > 0) {
        i--;
        ALOGV("Looking for asset '%s' in '%s'\n",
                assetName.c_str(), mAssetPaths.itemAt(i).path.c_str());
        Asset* pAsset = openNonAssetInPathLocked(assetName.c_str(), mode,
                mAssetPaths.editItemAt(i));
        if (pAsset != NULL) {
            return pAsset != kExcludedAsset ? pAsset : NULL;
        }
    }

    return NULL;
}

/*
 * Open a non-asset file as if it were an asset.
 *
 * The "fileName" is the partial path starting from the application name.
 */
Asset* AssetManager::openNonAsset(const char* fileName, AccessMode mode, int32_t* outCookie)
{
    AutoMutex _l(mLock);

    LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");

    /*
     * For each top-level asset path, search for the asset.
     */

    size_t i = mAssetPaths.size();
    while (i > 0) {
        i--;
        ALOGV("Looking for non-asset '%s' in '%s'\n", fileName, mAssetPaths.itemAt(i).path.c_str());
        Asset* pAsset = openNonAssetInPathLocked(
            fileName, mode, mAssetPaths.editItemAt(i));
        if (pAsset != NULL) {
            if (outCookie != NULL) *outCookie = static_cast<int32_t>(i + 1);
            return pAsset != kExcludedAsset ? pAsset : NULL;
        }
    }

    return NULL;
}

Asset* AssetManager::openNonAsset(const int32_t cookie, const char* fileName, AccessMode mode)
{
    const size_t which = static_cast<size_t>(cookie) - 1;

    AutoMutex _l(mLock);

    LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");

    if (which < mAssetPaths.size()) {
        ALOGV("Looking for non-asset '%s' in '%s'\n", fileName,
                mAssetPaths.itemAt(which).path.c_str());
        Asset* pAsset = openNonAssetInPathLocked(
            fileName, mode, mAssetPaths.editItemAt(which));
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

    if (pAsset == NULL) {
        return kFileTypeNonexistent;
    } else {
        return kFileTypeRegular;
    }
}

bool AssetManager::appendPathToResTable(asset_path& ap, bool appAsLib) const {
    // skip those ap's that correspond to system overlays
    if (ap.isSystemOverlay) {
        return true;
    }

    Asset* ass = NULL;
    ResTable* sharedRes = NULL;
    bool shared = true;
    bool onlyEmptyResources = true;
    ATRACE_NAME(ap.path.c_str());
    Asset* idmap = openIdmapLocked(ap);
    size_t nextEntryIdx = mResources->getTableCount();
    ALOGV("Looking for resource asset in '%s'\n", ap.path.c_str());
    if (ap.type != kFileTypeDirectory && ap.rawFd < 0) {
        if (nextEntryIdx == 0) {
            // The first item is typically the framework resources,
            // which we want to avoid parsing every time.
            sharedRes = const_cast<AssetManager*>(this)->
                mZipSet.getZipResourceTable(ap.path);
            if (sharedRes != NULL) {
                // skip ahead the number of system overlay packages preloaded
                nextEntryIdx = sharedRes->getTableCount();
            }
        }
        if (sharedRes == NULL) {
            ass = const_cast<AssetManager*>(this)->
                mZipSet.getZipResourceTableAsset(ap.path);
            if (ass == NULL) {
                ALOGV("loading resource table %s\n", ap.path.c_str());
                ass = const_cast<AssetManager*>(this)->
                    openNonAssetInPathLocked("resources.arsc",
                                             Asset::ACCESS_BUFFER,
                                             ap);
                if (ass != NULL && ass != kExcludedAsset) {
                    ass = const_cast<AssetManager*>(this)->
                        mZipSet.setZipResourceTableAsset(ap.path, ass);
                }
            }

            if (nextEntryIdx == 0 && ass != NULL) {
                // If this is the first resource table in the asset
                // manager, then we are going to cache it so that we
                // can quickly copy it out for others.
                ALOGV("Creating shared resources for %s", ap.path.c_str());
                sharedRes = new ResTable();
                sharedRes->add(ass, idmap, nextEntryIdx + 1, false);
#ifdef __ANDROID__
                const char* data = getenv("ANDROID_DATA");
                LOG_ALWAYS_FATAL_IF(data == NULL, "ANDROID_DATA not set");
                String8 overlaysListPath(data);
                appendPath(overlaysListPath, kResourceCache);
                appendPath(overlaysListPath, "overlays.list");
                addSystemOverlays(overlaysListPath.c_str(), ap.path, sharedRes, nextEntryIdx);
#endif
                sharedRes = const_cast<AssetManager*>(this)->
                    mZipSet.setZipResourceTable(ap.path, sharedRes);
            }
        }
    } else {
        ALOGV("loading resource table %s\n", ap.path.c_str());
        ass = const_cast<AssetManager*>(this)->
            openNonAssetInPathLocked("resources.arsc",
                                     Asset::ACCESS_BUFFER,
                                     ap);
        shared = false;
    }

    if ((ass != NULL || sharedRes != NULL) && ass != kExcludedAsset) {
        ALOGV("Installing resource asset %p in to table %p\n", ass, mResources);
        if (sharedRes != NULL) {
            ALOGV("Copying existing resources for %s", ap.path.c_str());
            mResources->add(sharedRes, ap.isSystemAsset);
        } else {
            ALOGV("Parsing resources for %s", ap.path.c_str());
            mResources->add(ass, idmap, nextEntryIdx + 1, !shared, appAsLib, ap.isSystemAsset);
        }
        onlyEmptyResources = false;

        if (!shared) {
            delete ass;
        }
    } else {
        ALOGV("Installing empty resources in to table %p\n", mResources);
        mResources->addEmpty(nextEntryIdx + 1);
    }

    if (idmap != NULL) {
        delete idmap;
    }
    return onlyEmptyResources;
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

    mResources = new ResTable();
    updateResourceParamsLocked();

    bool onlyEmptyResources = true;
    const size_t N = mAssetPaths.size();
    for (size_t i=0; i<N; i++) {
        bool empty = appendPathToResTable(
                const_cast<AssetManager*>(this)->mAssetPaths.editItemAt(i));
        onlyEmptyResources = onlyEmptyResources && empty;
    }

    if (required && onlyEmptyResources) {
        ALOGW("Unable to find resources file resources.arsc");
        delete mResources;
        mResources = NULL;
    }

    return mResources;
}

void AssetManager::updateResourceParamsLocked() const
{
    ATRACE_CALL();
    ResTable* res = mResources;
    if (!res) {
        return;
    }

    if (mLocale) {
        mConfig->setBcp47Locale(mLocale);
    } else {
        mConfig->clearLocale();
    }

    res->setParameters(mConfig);
}

Asset* AssetManager::openIdmapLocked(const struct asset_path& ap) const
{
    Asset* ass = NULL;
    if (ap.idmap.size() != 0) {
        ass = const_cast<AssetManager*>(this)->
            openAssetFromFileLocked(ap.idmap, Asset::ACCESS_BUFFER);
        if (ass) {
            ALOGV("loading idmap %s\n", ap.idmap.c_str());
        } else {
            ALOGW("failed to load idmap %s\n", ap.idmap.c_str());
        }
    }
    return ass;
}

void AssetManager::addSystemOverlays(const char* pathOverlaysList,
        const String8& targetPackagePath, ResTable* sharedRes, size_t offset) const
{
    FILE* fin = fopen(pathOverlaysList, "r");
    if (fin == NULL) {
        return;
    }

#ifndef _WIN32
    if (TEMP_FAILURE_RETRY(flock(fileno(fin), LOCK_SH)) != 0) {
        fclose(fin);
        return;
    }
#endif
    char buf[1024];
    while (fgets(buf, sizeof(buf), fin)) {
        // format of each line:
        //   <path to apk><space><path to idmap><newline>
        char* space = strchr(buf, ' ');
        char* newline = strchr(buf, '\n');
        asset_path oap;

        if (space == NULL || newline == NULL || newline < space) {
            continue;
        }

        oap.path = String8(buf, space - buf);
        oap.type = kFileTypeRegular;
        oap.idmap = String8(space + 1, newline - space - 1);
        oap.isSystemOverlay = true;

        Asset* oass = const_cast<AssetManager*>(this)->
            openNonAssetInPathLocked("resources.arsc",
                    Asset::ACCESS_BUFFER,
                    oap);

        if (oass != NULL) {
            Asset* oidmap = openIdmapLocked(oap);
            offset++;
            sharedRes->add(oass, oidmap, offset + 1, false);
            const_cast<AssetManager*>(this)->mAssetPaths.add(oap);
            const_cast<AssetManager*>(this)->mZipSet.addOverlay(targetPackagePath, oap);
            delete oidmap;
        }
    }

#ifndef _WIN32
    TEMP_FAILURE_RETRY(flock(fileno(fin), LOCK_UN));
#endif
    fclose(fin);
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

void AssetManager::getLocales(Vector<String8>* locales, bool includeSystemLocales) const
{
    ResTable* res = mResources;
    if (res != NULL) {
        res->getLocales(locales, includeSystemLocales, true /* mergeEquivalentLangs */);
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
    asset_path& ap)
{
    Asset* pAsset = NULL;

    ALOGV("openNonAssetInPath: name=%s type=%d fd=%d", fileName, ap.type, ap.rawFd);

    /* look at the filesystem on disk */
    if (ap.type == kFileTypeDirectory) {
        String8 path(ap.path);
        appendPath(path, fileName);

        pAsset = openAssetFromFileLocked(path, mode);

        if (pAsset == NULL) {
            /* try again, this time with ".gz" */
            path.append(".gz");
            pAsset = openAssetFromFileLocked(path, mode);
        }

        if (pAsset != NULL) {
            ALOGV("FOUND NA '%s' on disk", fileName);
            pAsset->setAssetSource(path);
        }

    /* look inside the zip file */
    } else {
        String8 path(fileName);

        /* check the appropriate Zip file */
        ZipFileRO* pZip = getZipFileLocked(ap);
        if (pZip != NULL) {
            ALOGV("GOT zip, checking NA '%s'", path.c_str());
            ZipEntryRO entry = pZip->findEntryByName(path.c_str());
            if (entry != NULL) {
                ALOGV("FOUND NA in Zip file for %s", path.c_str());
                pAsset = openAssetFromZipLocked(pZip, entry, mode, path);
                pZip->releaseEntry(entry);
            }
        }

        if (pAsset != NULL) {
            /* create a "source" name, for debug/display */
            pAsset->setAssetSource(
                    createZipSourceNameLocked(ZipSet::getPathName(ap.path.c_str()), String8(""),
                                                String8(fileName)));
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
        appendPath(sourceName, dirName);
    }
    appendPath(sourceName, fileName);
    return sourceName;
}

/*
 * Create a path to a loose asset (asset-base/app/rootDir).
 */
String8 AssetManager::createPathNameLocked(const asset_path& ap, const char* rootDir)
{
    String8 path(ap.path);
    if (rootDir != NULL) appendPath(path, rootDir);
    return path;
}

/*
 * Return a pointer to one of our open Zip archives.  Returns NULL if no
 * matching Zip file exists.
 */
ZipFileRO* AssetManager::getZipFileLocked(asset_path& ap)
{
    ALOGV("getZipFileLocked() in %p: ap=%p zip=%p", this, &ap, ap.zip.get());

    if (ap.zip != NULL) {
        return ap.zip->getZip();
    }

    if (ap.rawFd < 0) {
        ALOGV("getZipFileLocked: Creating new zip from path %s", ap.path.c_str());
        ap.zip = mZipSet.getSharedZip(ap.path);
    } else {
        ALOGV("getZipFileLocked: Creating new zip from fd %d", ap.rawFd);
        ap.zip = SharedZip::create(ap.rawFd, ap.path);

    }
    return ap.zip != NULL ? ap.zip->getZip() : NULL;
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

    if (strcasecmp(getPathExtension(pathName).c_str(), ".gz") == 0) {
        //printf("TRYING '%s'\n", (const char*) pathName);
        pAsset = Asset::createFromCompressedFile(pathName.c_str(), mode);
    } else {
        //printf("TRYING '%s'\n", (const char*) pathName);
        pAsset = Asset::createFromFile(pathName.c_str(), mode);
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
    std::unique_ptr<Asset> pAsset;

    // TODO: look for previously-created shared memory slice?
    uint16_t method;
    uint32_t uncompressedLen;

    //printf("USING Zip '%s'\n", pEntry->getFileName());

    if (!pZipFile->getEntryInfo(entry, &method, &uncompressedLen, nullptr, nullptr,
            nullptr, nullptr, nullptr))
    {
        ALOGW("getEntryInfo failed\n");
        return NULL;
    }

    std::optional<incfs::IncFsFileMap> dataMap = pZipFile->createEntryIncFsFileMap(entry);
    if (!dataMap.has_value()) {
        ALOGW("create map from entry failed\n");
        return NULL;
    }

    if (method == ZipFileRO::kCompressStored) {
        pAsset = Asset::createFromUncompressedMap(std::move(*dataMap), mode);
        ALOGV("Opened uncompressed entry %s in zip %s mode %d: %p", entryName.c_str(),
                dataMap->file_name(), mode, pAsset.get());
    } else {
        pAsset = Asset::createFromCompressedMap(std::move(*dataMap),
            static_cast<size_t>(uncompressedLen), mode);
        ALOGV("Opened compressed entry %s in zip %s mode %d: %p", entryName.c_str(),
                dataMap->file_name(), mode, pAsset.get());
    }
    if (pAsset == NULL) {
        /* unexpected */
        ALOGW("create from segment failed\n");
    }

    return pAsset.release();
}

/*
 * Open a directory in the asset namespace.
 *
 * An "asset directory" is simply the combination of all asset paths' "assets/" directories.
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
            ALOGV("Adding directory %s from zip %s", dirName, ap.path.c_str());
            scanAndMergeZipLocked(pMergedInfo, ap, kAssetsRoot, dirName);
        } else {
            ALOGV("Adding directory %s from dir %s", dirName, ap.path.c_str());
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
 * An "asset directory" is simply the combination of all asset paths' "assets/" directories.
 *
 * Pass in "" for the root dir.
 */
AssetDir* AssetManager::openNonAssetDir(const int32_t cookie, const char* dirName)
{
    AutoMutex _l(mLock);

    AssetDir* pDir = NULL;
    SortedVector<AssetDir::FileInfo>* pMergedInfo = NULL;

    LOG_FATAL_IF(mAssetPaths.size() == 0, "No assets added to AssetManager");
    assert(dirName != NULL);

    //printf("+++ openDir(%s) in '%s'\n", dirName, (const char*) mAssetBase);

    pDir = new AssetDir;

    pMergedInfo = new SortedVector<AssetDir::FileInfo>;

    const size_t which = static_cast<size_t>(cookie) - 1;

    if (which < mAssetPaths.size()) {
        const asset_path& ap = mAssetPaths.itemAt(which);
        if (ap.type == kFileTypeRegular) {
            ALOGV("Adding directory %s from zip %s", dirName, ap.path.c_str());
            scanAndMergeZipLocked(pMergedInfo, ap, NULL, dirName);
        } else {
            ALOGV("Adding directory %s from dir %s", dirName, ap.path.c_str());
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
    assert(pMergedInfo != NULL);

    //printf("scanAndMergeDir: %s %s %s\n", ap.path.c_str(), rootDir, dirName);

    String8 path = createPathNameLocked(ap, rootDir);
    if (dirName[0] != '\0') appendPath(path, dirName);

    SortedVector<AssetDir::FileInfo>* pContents = scanDirLocked(path);
    if (pContents == NULL)
        return false;

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

        name = pContents->itemAt(i).getFileName().c_str();
        nameLen = strlen(name);
        if (nameLen > exclExtLen &&
            strcmp(name + (nameLen - exclExtLen), kExcludeExtension) == 0)
        {
            String8 match(name, nameLen - exclExtLen);
            int matchIdx;

            matchIdx = AssetDir::FileInfo::findEntry(pMergedInfo, match);
            if (matchIdx > 0) {
                ALOGV("Excluding '%s' [%s]\n",
                    pMergedInfo->itemAt(matchIdx).getFileName().c_str(),
                    pMergedInfo->itemAt(matchIdx).getSourceName().c_str());
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

    ALOGV("Scanning dir '%s'\n", path.c_str());

    dir = opendir(path.c_str());
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
        fileType = ::getFileType(appendPathCopy(path, entry->d_name).c_str());
#endif

        if (fileType != kFileTypeRegular && fileType != kFileTypeDirectory)
            continue;

        AssetDir::FileInfo info;
        info.set(String8(entry->d_name), fileType);
        if (strcasecmp(getPathExtension(info.getFileName()).c_str(), ".gz") == 0)
            info.setFileName(getBasePath(info.getFileName()));
        info.setSourceName(appendPathCopy(path, info.getFileName()));
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
        ALOGW("Failure opening zip %s\n", ap.path.c_str());
        return false;
    }

    zipName = ZipSet::getPathName(ap.path.c_str());

    /* convert "sounds" to "rootDir/sounds" */
    if (rootDir != NULL) dirName = rootDir;
    appendPath(dirName, baseDirName);

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
    void *iterationCookie;
    if (!pZip->startIteration(&iterationCookie, dirName.c_str(), NULL)) {
        ALOGW("ZipFileRO::startIteration returned false");
        return false;
    }

    ZipEntryRO entry;
    while ((entry = pZip->nextEntry(iterationCookie)) != NULL) {
        char nameBuf[256];

        if (pZip->getEntryFileName(entry, nameBuf, sizeof(nameBuf)) != 0) {
            // TODO: fix this if we expect to have long names
            ALOGE("ARGH: name too long?\n");
            continue;
        }
        //printf("Comparing %s in %s?\n", nameBuf, dirName.c_str());
        if (dirNameLen == 0 || nameBuf[dirNameLen] == '/')
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

                info.set(getPathLeaf(String8(nameBuf)), kFileTypeRegular);

                info.setSourceName(
                    createZipSourceNameLocked(zipName, dirName, info.getFileName()));

                contents.add(info);
                //printf("FOUND: file '%s'\n", info.getFileName().c_str());
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

                //printf("FOUND: dir '%s'\n", subdirName.c_str());
            }
        }
    }

    pZip->endIteration(iterationCookie);

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
    if (kIsDebug) {
        ALOGI("Creating SharedZip %p %s\n", this, mPath.c_str());
    }
    ALOGV("+++ opening zip '%s'\n", mPath.c_str());
    mZipFile = ZipFileRO::open(mPath.c_str());
    if (mZipFile == NULL) {
        ALOGD("failed to open Zip archive '%s'\n", mPath.c_str());
    }
}

AssetManager::SharedZip::SharedZip(int fd, const String8& path)
    : mPath(path), mZipFile(NULL), mModWhen(0),
      mResourceTableAsset(NULL), mResourceTable(NULL)
{
    if (kIsDebug) {
        ALOGI("Creating SharedZip %p fd=%d %s\n", this, fd, mPath.c_str());
    }
    ALOGV("+++ opening zip fd=%d '%s'\n", fd, mPath.c_str());
    mZipFile = ZipFileRO::openFd(fd, mPath.c_str());
    if (mZipFile == NULL) {
        ::close(fd);
        ALOGD("failed to open Zip archive fd=%d '%s'\n", fd, mPath.c_str());
    }
}

sp<AssetManager::SharedZip> AssetManager::SharedZip::get(const String8& path,
        bool createIfNotPresent)
{
    AutoMutex _l(gLock);
    time_t modWhen = getFileModDate(path.c_str());
    sp<SharedZip> zip = gOpen.valueFor(path).promote();
    if (zip != NULL && zip->mModWhen == modWhen) {
        return zip;
    }
    if (zip == NULL && !createIfNotPresent) {
        return NULL;
    }
    zip = new SharedZip(path, modWhen);
    gOpen.add(path, zip);
    return zip;
}

sp<AssetManager::SharedZip> AssetManager::SharedZip::create(int fd, const String8& path)
{
    return new SharedZip(fd, path);
}

ZipFileRO* AssetManager::SharedZip::getZip()
{
    return mZipFile;
}

Asset* AssetManager::SharedZip::getResourceTableAsset()
{
    AutoMutex _l(gLock);
    ALOGV("Getting from SharedZip %p resource asset %p\n", this, mResourceTableAsset);
    return mResourceTableAsset;
}

Asset* AssetManager::SharedZip::setResourceTableAsset(Asset* asset)
{
    {
        AutoMutex _l(gLock);
        if (mResourceTableAsset == NULL) {
            // This is not thread safe the first time it is called, so
            // do it here with the global lock held.
            asset->getBuffer(true);
            mResourceTableAsset = asset;
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
    time_t modWhen = getFileModDate(mPath.c_str());
    return mModWhen == modWhen;
}

void AssetManager::SharedZip::addOverlay(const asset_path& ap)
{
    mOverlays.add(ap);
}

bool AssetManager::SharedZip::getOverlay(size_t idx, asset_path* out) const
{
    if (idx >= mOverlays.size()) {
        return false;
    }
    *out = mOverlays[idx];
    return true;
}

AssetManager::SharedZip::~SharedZip()
{
    if (kIsDebug) {
        ALOGI("Destroying SharedZip %p %s\n", this, mPath.c_str());
    }
    if (mResourceTable != NULL) {
        delete mResourceTable;
    }
    if (mResourceTableAsset != NULL) {
        delete mResourceTableAsset;
    }
    if (mZipFile != NULL) {
        delete mZipFile;
        ALOGV("Closed '%s'\n", mPath.c_str());
    }
}

/*
 * ===========================================================================
 *      AssetManager::ZipSet
 * ===========================================================================
 */

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
    return getSharedZip(path)->getZip();
}

const sp<AssetManager::SharedZip> AssetManager::ZipSet::getSharedZip(const String8& path)
{
    int idx = getIndex(path);
    sp<SharedZip> zip = mZipFile[idx];
    if (zip == NULL) {
        zip = SharedZip::get(path);
        mZipFile.editItemAt(idx) = zip;
    }
    return zip;
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

void AssetManager::ZipSet::addOverlay(const String8& path, const asset_path& overlay)
{
    int idx = getIndex(path);
    sp<SharedZip> zip = mZipFile[idx];
    zip->addOverlay(overlay);
}

bool AssetManager::ZipSet::getOverlay(const String8& path, size_t idx, asset_path* out) const
{
    sp<SharedZip> zip = SharedZip::get(path, false);
    if (zip == NULL) {
        return false;
    }
    return zip->getOverlay(idx, out);
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
