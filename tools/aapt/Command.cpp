//
// Copyright 2006 The Android Open Source Project
//
// Android Asset Packaging Tool main entry point.
//
#include "ApkBuilder.h"
#include "Bundle.h"
#include "Images.h"
#include "Main.h"
#include "ResourceFilter.h"
#include "ResourceTable.h"
#include "XMLNode.h"

#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/List.h>
#include <utils/Log.h>
#include <utils/SortedVector.h>
#include <utils/threads.h>
#include <utils/Vector.h>

#include <errno.h>
#include <fcntl.h>

using namespace android;

#ifndef AAPT_VERSION
    #define AAPT_VERSION ""
#endif

/*
 * Show version info.  All the cool kids do it.
 */
int doVersion(Bundle* bundle)
{
    if (bundle->getFileSpecCount() != 0) {
        printf("(ignoring extra arguments)\n");
    }
    printf("Android Asset Packaging Tool, v0.2-" AAPT_VERSION "\n");

    return 0;
}


/*
 * Open the file read only.  The call fails if the file doesn't exist.
 *
 * Returns NULL on failure.
 */
ZipFile* openReadOnly(const char* fileName)
{
    ZipFile* zip;
    status_t result;

    zip = new ZipFile;
    result = zip->open(fileName, ZipFile::kOpenReadOnly);
    if (result != NO_ERROR) {
        if (result == NAME_NOT_FOUND) {
            fprintf(stderr, "ERROR: '%s' not found\n", fileName);
        } else if (result == PERMISSION_DENIED) {
            fprintf(stderr, "ERROR: '%s' access denied\n", fileName);
        } else {
            fprintf(stderr, "ERROR: failed opening '%s' as Zip file\n",
                fileName);
        }
        delete zip;
        return NULL;
    }

    return zip;
}

/*
 * Open the file read-write.  The file will be created if it doesn't
 * already exist and "okayToCreate" is set.
 *
 * Returns NULL on failure.
 */
ZipFile* openReadWrite(const char* fileName, bool okayToCreate)
{
    ZipFile* zip = NULL;
    status_t result;
    int flags;

    flags = ZipFile::kOpenReadWrite;
    if (okayToCreate) {
        flags |= ZipFile::kOpenCreate;
    }

    zip = new ZipFile;
    result = zip->open(fileName, flags);
    if (result != NO_ERROR) {
        delete zip;
        zip = NULL;
        goto bail;
    }

bail:
    return zip;
}


/*
 * Return a short string describing the compression method.
 */
const char* compressionName(int method)
{
    if (method == ZipEntry::kCompressStored) {
        return "Stored";
    } else if (method == ZipEntry::kCompressDeflated) {
        return "Deflated";
    } else {
        return "Unknown";
    }
}

/*
 * Return the percent reduction in size (0% == no compression).
 */
int calcPercent(long uncompressedLen, long compressedLen)
{
    if (!uncompressedLen) {
        return 0;
    } else {
        return (int) (100.0 - (compressedLen * 100.0) / uncompressedLen + 0.5);
    }
}

/*
 * Handle the "list" command, which can be a simple file dump or
 * a verbose listing.
 *
 * The verbose listing closely matches the output of the Info-ZIP "unzip"
 * command.
 */
int doList(Bundle* bundle)
{
    int result = 1;
    ZipFile* zip = NULL;
    const ZipEntry* entry;
    long totalUncLen, totalCompLen;
    const char* zipFileName;

    if (bundle->getFileSpecCount() != 1) {
        fprintf(stderr, "ERROR: specify zip file name (only)\n");
        goto bail;
    }
    zipFileName = bundle->getFileSpecEntry(0);

    zip = openReadOnly(zipFileName);
    if (zip == NULL) {
        goto bail;
    }

    int count, i;

    if (bundle->getVerbose()) {
        printf("Archive:  %s\n", zipFileName);
        printf(
            " Length   Method    Size  Ratio   Offset      Date  Time  CRC-32    Name\n");
        printf(
            "--------  ------  ------- -----  -------      ----  ----  ------    ----\n");
    }

    totalUncLen = totalCompLen = 0;

    count = zip->getNumEntries();
    for (i = 0; i < count; i++) {
        entry = zip->getEntryByIndex(i);
        if (bundle->getVerbose()) {
            char dateBuf[32];
            time_t when;

            when = entry->getModWhen();
            strftime(dateBuf, sizeof(dateBuf), "%m-%d-%y %H:%M",
                localtime(&when));

            printf("%8ld  %-7.7s %7ld %3d%%  %8zd  %s  %08lx  %s\n",
                (long) entry->getUncompressedLen(),
                compressionName(entry->getCompressionMethod()),
                (long) entry->getCompressedLen(),
                calcPercent(entry->getUncompressedLen(),
                            entry->getCompressedLen()),
                (size_t) entry->getLFHOffset(),
                dateBuf,
                entry->getCRC32(),
                entry->getFileName());
        } else {
            printf("%s\n", entry->getFileName());
        }

        totalUncLen += entry->getUncompressedLen();
        totalCompLen += entry->getCompressedLen();
    }

    if (bundle->getVerbose()) {
        printf(
        "--------          -------  ---                            -------\n");
        printf("%8ld          %7ld  %2d%%                            %d files\n",
            totalUncLen,
            totalCompLen,
            calcPercent(totalUncLen, totalCompLen),
            zip->getNumEntries());
    }

    if (bundle->getAndroidList()) {
        AssetManager assets;
        if (!assets.addAssetPath(String8(zipFileName), NULL)) {
            fprintf(stderr, "ERROR: list -a failed because assets could not be loaded\n");
            goto bail;
        }

        const ResTable& res = assets.getResources(false);
        if (&res == NULL) {
            printf("\nNo resource table found.\n");
        } else {
#ifndef HAVE_ANDROID_OS
            printf("\nResource table:\n");
            res.print(false);
#endif
        }

        Asset* manifestAsset = assets.openNonAsset("AndroidManifest.xml",
                                                   Asset::ACCESS_BUFFER);
        if (manifestAsset == NULL) {
            printf("\nNo AndroidManifest.xml found.\n");
        } else {
            printf("\nAndroid manifest:\n");
            ResXMLTree tree;
            tree.setTo(manifestAsset->getBuffer(true),
                       manifestAsset->getLength());
            printXMLBlock(&tree);
        }
        delete manifestAsset;
    }

    result = 0;

bail:
    delete zip;
    return result;
}

static ssize_t indexOfAttribute(const ResXMLTree& tree, uint32_t attrRes)
{
    size_t N = tree.getAttributeCount();
    for (size_t i=0; i<N; i++) {
        if (tree.getAttributeNameResID(i) == attrRes) {
            return (ssize_t)i;
        }
    }
    return -1;
}

String8 getAttribute(const ResXMLTree& tree, const char* ns,
                            const char* attr, String8* outError)
{
    ssize_t idx = tree.indexOfAttribute(ns, attr);
    if (idx < 0) {
        return String8();
    }
    Res_value value;
    if (tree.getAttributeValue(idx, &value) != NO_ERROR) {
        if (value.dataType != Res_value::TYPE_STRING) {
            if (outError != NULL) {
                *outError = "attribute is not a string value";
            }
            return String8();
        }
    }
    size_t len;
    const uint16_t* str = tree.getAttributeStringValue(idx, &len);
    return str ? String8(str, len) : String8();
}

static String8 getAttribute(const ResXMLTree& tree, uint32_t attrRes, String8* outError)
{
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        return String8();
    }
    Res_value value;
    if (tree.getAttributeValue(idx, &value) != NO_ERROR) {
        if (value.dataType != Res_value::TYPE_STRING) {
            if (outError != NULL) {
                *outError = "attribute is not a string value";
            }
            return String8();
        }
    }
    size_t len;
    const uint16_t* str = tree.getAttributeStringValue(idx, &len);
    return str ? String8(str, len) : String8();
}

static int32_t getIntegerAttribute(const ResXMLTree& tree, uint32_t attrRes,
        String8* outError, int32_t defValue = -1)
{
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        return defValue;
    }
    Res_value value;
    if (tree.getAttributeValue(idx, &value) != NO_ERROR) {
        if (value.dataType < Res_value::TYPE_FIRST_INT
                || value.dataType > Res_value::TYPE_LAST_INT) {
            if (outError != NULL) {
                *outError = "attribute is not an integer value";
            }
            return defValue;
        }
    }
    return value.data;
}

static int32_t getResolvedIntegerAttribute(const ResTable* resTable, const ResXMLTree& tree,
        uint32_t attrRes, String8* outError, int32_t defValue = -1)
{
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        return defValue;
    }
    Res_value value;
    if (tree.getAttributeValue(idx, &value) != NO_ERROR) {
        if (value.dataType == Res_value::TYPE_REFERENCE) {
            resTable->resolveReference(&value, 0);
        }
        if (value.dataType < Res_value::TYPE_FIRST_INT
                || value.dataType > Res_value::TYPE_LAST_INT) {
            if (outError != NULL) {
                *outError = "attribute is not an integer value";
            }
            return defValue;
        }
    }
    return value.data;
}

static String8 getResolvedAttribute(const ResTable* resTable, const ResXMLTree& tree,
        uint32_t attrRes, String8* outError)
{
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        return String8();
    }
    Res_value value;
    if (tree.getAttributeValue(idx, &value) != NO_ERROR) {
        if (value.dataType == Res_value::TYPE_STRING) {
            size_t len;
            const uint16_t* str = tree.getAttributeStringValue(idx, &len);
            return str ? String8(str, len) : String8();
        }
        resTable->resolveReference(&value, 0);
        if (value.dataType != Res_value::TYPE_STRING) {
            if (outError != NULL) {
                *outError = "attribute is not a string value";
            }
            return String8();
        }
    }
    size_t len;
    const Res_value* value2 = &value;
    const char16_t* str = const_cast<ResTable*>(resTable)->valueToString(value2, 0, NULL, &len);
    return str ? String8(str, len) : String8();
}

static void getResolvedResourceAttribute(Res_value* value, const ResTable* resTable,
        const ResXMLTree& tree, uint32_t attrRes, String8* outError)
{
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        if (outError != NULL) {
            *outError = "attribute could not be found";
        }
        return;
    }
    if (tree.getAttributeValue(idx, value) != NO_ERROR) {
        if (value->dataType == Res_value::TYPE_REFERENCE) {
            resTable->resolveReference(value, 0);
        }
        // The attribute was found and was resolved if need be.
        return;
    }
    if (outError != NULL) {
        *outError = "error getting resolved resource attribute";
    }
}

static void printResolvedResourceAttribute(const ResTable* resTable, const ResXMLTree& tree,
        uint32_t attrRes, String8 attrLabel, String8* outError)
{
    Res_value value;
    getResolvedResourceAttribute(&value, resTable, tree, attrRes, outError);
    if (*outError != "") {
        *outError = "error print resolved resource attribute";
        return;
    }
    if (value.dataType == Res_value::TYPE_STRING) {
        String8 result = getResolvedAttribute(resTable, tree, attrRes, outError);
        printf("%s='%s'", attrLabel.string(),
                ResTable::normalizeForOutput(result.string()).string());
    } else if (Res_value::TYPE_FIRST_INT <= value.dataType &&
            value.dataType <= Res_value::TYPE_LAST_INT) {
        printf("%s='%d'", attrLabel.string(), value.data);
    } else {
        printf("%s='0x%x'", attrLabel.string(), (int)value.data);
    }
}

// These are attribute resource constants for the platform, as found
// in android.R.attr
enum {
    LABEL_ATTR = 0x01010001,
    ICON_ATTR = 0x01010002,
    NAME_ATTR = 0x01010003,
    PERMISSION_ATTR = 0x01010006,
    EXPORTED_ATTR = 0x01010010,
    GRANT_URI_PERMISSIONS_ATTR = 0x0101001b,
    RESOURCE_ATTR = 0x01010025,
    DEBUGGABLE_ATTR = 0x0101000f,
    VALUE_ATTR = 0x01010024,
    VERSION_CODE_ATTR = 0x0101021b,
    VERSION_NAME_ATTR = 0x0101021c,
    SCREEN_ORIENTATION_ATTR = 0x0101001e,
    MIN_SDK_VERSION_ATTR = 0x0101020c,
    MAX_SDK_VERSION_ATTR = 0x01010271,
    REQ_TOUCH_SCREEN_ATTR = 0x01010227,
    REQ_KEYBOARD_TYPE_ATTR = 0x01010228,
    REQ_HARD_KEYBOARD_ATTR = 0x01010229,
    REQ_NAVIGATION_ATTR = 0x0101022a,
    REQ_FIVE_WAY_NAV_ATTR = 0x01010232,
    TARGET_SDK_VERSION_ATTR = 0x01010270,
    TEST_ONLY_ATTR = 0x01010272,
    ANY_DENSITY_ATTR = 0x0101026c,
    GL_ES_VERSION_ATTR = 0x01010281,
    SMALL_SCREEN_ATTR = 0x01010284,
    NORMAL_SCREEN_ATTR = 0x01010285,
    LARGE_SCREEN_ATTR = 0x01010286,
    XLARGE_SCREEN_ATTR = 0x010102bf,
    REQUIRED_ATTR = 0x0101028e,
    INSTALL_LOCATION_ATTR = 0x010102b7,
    SCREEN_SIZE_ATTR = 0x010102ca,
    SCREEN_DENSITY_ATTR = 0x010102cb,
    REQUIRES_SMALLEST_WIDTH_DP_ATTR = 0x01010364,
    COMPATIBLE_WIDTH_LIMIT_DP_ATTR = 0x01010365,
    LARGEST_WIDTH_LIMIT_DP_ATTR = 0x01010366,
    PUBLIC_KEY_ATTR = 0x010103a6,
    CATEGORY_ATTR = 0x010103e8,
    BANNER_ATTR = 0x10103f2,
};

String8 getComponentName(String8 &pkgName, String8 &componentName) {
    ssize_t idx = componentName.find(".");
    String8 retStr(pkgName);
    if (idx == 0) {
        retStr += componentName;
    } else if (idx < 0) {
        retStr += ".";
        retStr += componentName;
    } else {
        return componentName;
    }
    return retStr;
}

static void printCompatibleScreens(ResXMLTree& tree, String8* outError) {
    size_t len;
    ResXMLTree::event_code_t code;
    int depth = 0;
    bool first = true;
    printf("compatible-screens:");
    while ((code=tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
        if (code == ResXMLTree::END_TAG) {
            depth--;
            if (depth < 0) {
                break;
            }
            continue;
        }
        if (code != ResXMLTree::START_TAG) {
            continue;
        }
        depth++;
        const char16_t* ctag16 = tree.getElementName(&len);
        if (ctag16 == NULL) {
            *outError = "failed to get XML element name (bad string pool)";
            return;
        }
        String8 tag(ctag16);
        if (tag == "screen") {
            int32_t screenSize = getIntegerAttribute(tree,
                    SCREEN_SIZE_ATTR, NULL, -1);
            int32_t screenDensity = getIntegerAttribute(tree,
                    SCREEN_DENSITY_ATTR, NULL, -1);
            if (screenSize > 0 && screenDensity > 0) {
                if (!first) {
                    printf(",");
                }
                first = false;
                printf("'%d/%d'", screenSize, screenDensity);
            }
        }
    }
    printf("\n");
}

static void printUsesPermission(const String8& name, bool optional=false, int maxSdkVersion=-1) {
    printf("uses-permission: name='%s'", ResTable::normalizeForOutput(name.string()).string());
    if (maxSdkVersion != -1) {
         printf(" maxSdkVersion='%d'", maxSdkVersion);
    }
    printf("\n");

    if (optional) {
        printf("optional-permission: name='%s'",
                ResTable::normalizeForOutput(name.string()).string());
        if (maxSdkVersion != -1) {
            printf(" maxSdkVersion='%d'", maxSdkVersion);
        }
        printf("\n");
    }
}

static void printUsesImpliedPermission(const String8& name, const String8& reason) {
    printf("uses-implied-permission: name='%s' reason='%s'\n",
            ResTable::normalizeForOutput(name.string()).string(),
            ResTable::normalizeForOutput(reason.string()).string());
}

Vector<String8> getNfcAidCategories(AssetManager& assets, String8 xmlPath, bool offHost,
        String8 *outError = NULL)
{
    Asset* aidAsset = assets.openNonAsset(xmlPath, Asset::ACCESS_BUFFER);
    if (aidAsset == NULL) {
        if (outError != NULL) *outError = "xml resource does not exist";
        return Vector<String8>();
    }

    const String8 serviceTagName(offHost ? "offhost-apdu-service" : "host-apdu-service");

    bool withinApduService = false;
    Vector<String8> categories;

    String8 error;
    ResXMLTree tree;
    tree.setTo(aidAsset->getBuffer(true), aidAsset->getLength());

    size_t len;
    int depth = 0;
    ResXMLTree::event_code_t code;
    while ((code=tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
        if (code == ResXMLTree::END_TAG) {
            depth--;
            const char16_t* ctag16 = tree.getElementName(&len);
            if (ctag16 == NULL) {
                *outError = "failed to get XML element name (bad string pool)";
                return Vector<String8>();
            }
            String8 tag(ctag16);

            if (depth == 0 && tag == serviceTagName) {
                withinApduService = false;
            }

        } else if (code == ResXMLTree::START_TAG) {
            depth++;
            const char16_t* ctag16 = tree.getElementName(&len);
            if (ctag16 == NULL) {
                *outError = "failed to get XML element name (bad string pool)";
                return Vector<String8>();
            }
            String8 tag(ctag16);

            if (depth == 1) {
                if (tag == serviceTagName) {
                    withinApduService = true;
                }
            } else if (depth == 2 && withinApduService) {
                if (tag == "aid-group") {
                    String8 category = getAttribute(tree, CATEGORY_ATTR, &error);
                    if (error != "") {
                        if (outError != NULL) *outError = error;
                        return Vector<String8>();
                    }

                    categories.add(category);
                }
            }
        }
    }
    aidAsset->close();
    return categories;
}

static void printComponentPresence(const char* componentName) {
    printf("provides-component:'%s'\n", componentName);
}

/**
 * Represents a feature that has been automatically added due to
 * a pre-requisite or some other reason.
 */
struct ImpliedFeature {
    /**
     * Name of the implied feature.
     */
    String8 name;

    /**
     * List of human-readable reasons for why this feature was implied.
     */
    SortedVector<String8> reasons;
};

/**
 * Represents a <feature-group> tag in the AndroidManifest.xml
 */
struct FeatureGroup {
    FeatureGroup() : openGLESVersion(-1) {}

    /**
     * Human readable label
     */
    String8 label;

    /**
     * Explicit features defined in the group
     */
    KeyedVector<String8, bool> features;

    /**
     * OpenGL ES version required
     */
    int openGLESVersion;
};

static void addImpliedFeature(KeyedVector<String8, ImpliedFeature>* impliedFeatures,
        const char* name, const char* reason) {
    String8 name8(name);
    ssize_t idx = impliedFeatures->indexOfKey(name8);
    if (idx < 0) {
        idx = impliedFeatures->add(name8, ImpliedFeature());
        impliedFeatures->editValueAt(idx).name = name8;
    }
    impliedFeatures->editValueAt(idx).reasons.add(String8(reason));
}

static void printFeatureGroup(const FeatureGroup& grp,
        const KeyedVector<String8, ImpliedFeature>* impliedFeatures = NULL) {
    printf("feature-group: label='%s'\n", grp.label.string());

    if (grp.openGLESVersion > 0) {
        printf("  uses-gl-es: '0x%x'\n", grp.openGLESVersion);
    }

    const size_t numFeatures = grp.features.size();
    for (size_t i = 0; i < numFeatures; i++) {
        if (!grp.features[i]) {
            continue;
        }

        const String8& featureName = grp.features.keyAt(i);
        printf("  uses-feature: name='%s'\n",
                ResTable::normalizeForOutput(featureName.string()).string());
    }

    const size_t numImpliedFeatures =
        (impliedFeatures != NULL) ? impliedFeatures->size() : 0;
    for (size_t i = 0; i < numImpliedFeatures; i++) {
        const ImpliedFeature& impliedFeature = impliedFeatures->valueAt(i);
        if (grp.features.indexOfKey(impliedFeature.name) >= 0) {
            // The feature is explicitly set, no need to use implied
            // definition.
            continue;
        }

        String8 printableFeatureName(ResTable::normalizeForOutput(
                    impliedFeature.name.string()));
        printf("  uses-feature: name='%s'\n", printableFeatureName.string());
        printf("  uses-implied-feature: name='%s' reason='",
                printableFeatureName.string());
        const size_t numReasons = impliedFeature.reasons.size();
        for (size_t j = 0; j < numReasons; j++) {
            printf("%s", impliedFeature.reasons[j].string());
            if (j + 2 < numReasons) {
                printf(", ");
            } else if (j + 1 < numReasons) {
                printf(", and ");
            }
        }
        printf("'\n");
    }
}

static void addParentFeatures(FeatureGroup* grp, const String8& name) {
    if (name == "android.hardware.camera.autofocus" ||
            name == "android.hardware.camera.flash") {
        grp->features.add(String8("android.hardware.camera"), true);
    } else if (name == "android.hardware.location.gps" ||
            name == "android.hardware.location.network") {
        grp->features.add(String8("android.hardware.location"), true);
    } else if (name == "android.hardware.touchscreen.multitouch") {
        grp->features.add(String8("android.hardware.touchscreen"), true);
    } else if (name == "android.hardware.touchscreen.multitouch.distinct") {
        grp->features.add(String8("android.hardware.touchscreen.multitouch"), true);
        grp->features.add(String8("android.hardware.touchscreen"), true);
    } else if (name == "android.hardware.opengles.aep") {
        const int openGLESVersion31 = 0x00030001;
        if (openGLESVersion31 > grp->openGLESVersion) {
            grp->openGLESVersion = openGLESVersion31;
        }
    }
}

/*
 * Handle the "dump" command, to extract select data from an archive.
 */
extern char CONSOLE_DATA[2925]; // see EOF
int doDump(Bundle* bundle)
{
    status_t result = UNKNOWN_ERROR;

    if (bundle->getFileSpecCount() < 1) {
        fprintf(stderr, "ERROR: no dump option specified\n");
        return 1;
    }

    if (bundle->getFileSpecCount() < 2) {
        fprintf(stderr, "ERROR: no dump file specified\n");
        return 1;
    }

    const char* option = bundle->getFileSpecEntry(0);
    const char* filename = bundle->getFileSpecEntry(1);

    AssetManager assets;
    int32_t assetsCookie;
    if (!assets.addAssetPath(String8(filename), &assetsCookie)) {
        fprintf(stderr, "ERROR: dump failed because assets could not be loaded\n");
        return 1;
    }

    // Make a dummy config for retrieving resources...  we need to supply
    // non-default values for some configs so that we can retrieve resources
    // in the app that don't have a default.  The most important of these is
    // the API version because key resources like icons will have an implicit
    // version if they are using newer config types like density.
    ResTable_config config;
    memset(&config, 0, sizeof(ResTable_config));
    config.language[0] = 'e';
    config.language[1] = 'n';
    config.country[0] = 'U';
    config.country[1] = 'S';
    config.orientation = ResTable_config::ORIENTATION_PORT;
    config.density = ResTable_config::DENSITY_MEDIUM;
    config.sdkVersion = 10000; // Very high.
    config.screenWidthDp = 320;
    config.screenHeightDp = 480;
    config.smallestScreenWidthDp = 320;
    config.screenLayout |= ResTable_config::SCREENSIZE_NORMAL;
    assets.setConfiguration(config);

    const ResTable& res = assets.getResources(false);
    if (&res == NULL) {
        fprintf(stderr, "ERROR: dump failed because no resource table was found\n");
        return 1;
    } else if (res.getError() != NO_ERROR) {
        fprintf(stderr, "ERROR: dump failed because the resource table is invalid/corrupt.\n");
        return 1;
    }

    // The dynamicRefTable can be null if there are no resources for this asset cookie.
    // This fine.
    const DynamicRefTable* dynamicRefTable = res.getDynamicRefTableForCookie(assetsCookie);

    Asset* asset = NULL;

    if (strcmp("resources", option) == 0) {
#ifndef HAVE_ANDROID_OS
        res.print(bundle->getValues());
#endif

    } else if (strcmp("strings", option) == 0) {
        const ResStringPool* pool = res.getTableStringBlock(0);
        printStringPool(pool);

    } else if (strcmp("xmltree", option) == 0) {
        if (bundle->getFileSpecCount() < 3) {
            fprintf(stderr, "ERROR: no dump xmltree resource file specified\n");
            goto bail;
        }

        for (int i=2; i<bundle->getFileSpecCount(); i++) {
            const char* resname = bundle->getFileSpecEntry(i);
            ResXMLTree tree(dynamicRefTable);
            asset = assets.openNonAsset(assetsCookie, resname, Asset::ACCESS_BUFFER);
            if (asset == NULL) {
                fprintf(stderr, "ERROR: dump failed because resource %s found\n", resname);
                goto bail;
            }

            if (tree.setTo(asset->getBuffer(true),
                           asset->getLength()) != NO_ERROR) {
                fprintf(stderr, "ERROR: Resource %s is corrupt\n", resname);
                goto bail;
            }
            tree.restart();
            printXMLBlock(&tree);
            tree.uninit();
            delete asset;
            asset = NULL;
        }

    } else if (strcmp("xmlstrings", option) == 0) {
        if (bundle->getFileSpecCount() < 3) {
            fprintf(stderr, "ERROR: no dump xmltree resource file specified\n");
            goto bail;
        }

        for (int i=2; i<bundle->getFileSpecCount(); i++) {
            const char* resname = bundle->getFileSpecEntry(i);
            asset = assets.openNonAsset(assetsCookie, resname, Asset::ACCESS_BUFFER);
            if (asset == NULL) {
                fprintf(stderr, "ERROR: dump failed because resource %s found\n", resname);
                goto bail;
            }

            ResXMLTree tree(dynamicRefTable);
            if (tree.setTo(asset->getBuffer(true),
                           asset->getLength()) != NO_ERROR) {
                fprintf(stderr, "ERROR: Resource %s is corrupt\n", resname);
                goto bail;
            }
            printStringPool(&tree.getStrings());
            delete asset;
            asset = NULL;
        }

    } else {
        asset = assets.openNonAsset(assetsCookie, "AndroidManifest.xml", Asset::ACCESS_BUFFER);
        if (asset == NULL) {
            fprintf(stderr, "ERROR: dump failed because no AndroidManifest.xml found\n");
            goto bail;
        }

        ResXMLTree tree(dynamicRefTable);
        if (tree.setTo(asset->getBuffer(true),
                       asset->getLength()) != NO_ERROR) {
            fprintf(stderr, "ERROR: AndroidManifest.xml is corrupt\n");
            goto bail;
        }
        tree.restart();

        if (strcmp("permissions", option) == 0) {
            size_t len;
            ResXMLTree::event_code_t code;
            int depth = 0;
            while ((code=tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
                if (code == ResXMLTree::END_TAG) {
                    depth--;
                    continue;
                }
                if (code != ResXMLTree::START_TAG) {
                    continue;
                }
                depth++;
                const char16_t* ctag16 = tree.getElementName(&len);
                if (ctag16 == NULL) {
                    fprintf(stderr, "ERROR: failed to get XML element name (bad string pool)\n");
                    goto bail;
                }
                String8 tag(ctag16);
                //printf("Depth %d tag %s\n", depth, tag.string());
                if (depth == 1) {
                    if (tag != "manifest") {
                        fprintf(stderr, "ERROR: manifest does not start with <manifest> tag\n");
                        goto bail;
                    }
                    String8 pkg = getAttribute(tree, NULL, "package", NULL);
                    printf("package: %s\n", ResTable::normalizeForOutput(pkg.string()).string());
                } else if (depth == 2 && tag == "permission") {
                    String8 error;
                    String8 name = getAttribute(tree, NAME_ATTR, &error);
                    if (error != "") {
                        fprintf(stderr, "ERROR: %s\n", error.string());
                        goto bail;
                    }
                    printf("permission: %s\n",
                            ResTable::normalizeForOutput(name.string()).string());
                } else if (depth == 2 && tag == "uses-permission") {
                    String8 error;
                    String8 name = getAttribute(tree, NAME_ATTR, &error);
                    if (error != "") {
                        fprintf(stderr, "ERROR: %s\n", error.string());
                        goto bail;
                    }
                    printUsesPermission(name,
                            getIntegerAttribute(tree, REQUIRED_ATTR, NULL, 1) == 0,
                            getIntegerAttribute(tree, MAX_SDK_VERSION_ATTR, NULL, -1));
                }
            }
        } else if (strcmp("badging", option) == 0) {
            Vector<String8> locales;
            res.getLocales(&locales);

            Vector<ResTable_config> configs;
            res.getConfigurations(&configs);
            SortedVector<int> densities;
            const size_t NC = configs.size();
            for (size_t i=0; i<NC; i++) {
                int dens = configs[i].density;
                if (dens == 0) {
                    dens = 160;
                }
                densities.add(dens);
            }

            size_t len;
            ResXMLTree::event_code_t code;
            int depth = 0;
            String8 error;
            bool withinActivity = false;
            bool isMainActivity = false;
            bool isLauncherActivity = false;
            bool isLeanbackLauncherActivity = false;
            bool isSearchable = false;
            bool withinApplication = false;
            bool withinSupportsInput = false;
            bool withinFeatureGroup = false;
            bool withinReceiver = false;
            bool withinService = false;
            bool withinProvider = false;
            bool withinIntentFilter = false;
            bool hasMainActivity = false;
            bool hasOtherActivities = false;
            bool hasOtherReceivers = false;
            bool hasOtherServices = false;
            bool hasIntentFilter = false;

            bool hasWallpaperService = false;
            bool hasImeService = false;
            bool hasAccessibilityService = false;
            bool hasPrintService = false;
            bool hasWidgetReceivers = false;
            bool hasDeviceAdminReceiver = false;
            bool hasPaymentService = false;
            bool hasDocumentsProvider = false;
            bool hasCameraActivity = false;
            bool hasCameraSecureActivity = false;
            bool hasLauncher = false;
            bool hasNotificationListenerService = false;
            bool hasDreamService = false;

            bool actMainActivity = false;
            bool actWidgetReceivers = false;
            bool actDeviceAdminEnabled = false;
            bool actImeService = false;
            bool actWallpaperService = false;
            bool actAccessibilityService = false;
            bool actPrintService = false;
            bool actHostApduService = false;
            bool actOffHostApduService = false;
            bool actDocumentsProvider = false;
            bool actNotificationListenerService = false;
            bool actDreamService = false;
            bool actCamera = false;
            bool actCameraSecure = false;
            bool catLauncher = false;
            bool hasMetaHostPaymentCategory = false;
            bool hasMetaOffHostPaymentCategory = false;

            // These permissions are required by services implementing services
            // the system binds to (IME, Accessibility, PrintServices, etc.)
            bool hasBindDeviceAdminPermission = false;
            bool hasBindInputMethodPermission = false;
            bool hasBindAccessibilityServicePermission = false;
            bool hasBindPrintServicePermission = false;
            bool hasBindNfcServicePermission = false;
            bool hasRequiredSafAttributes = false;
            bool hasBindNotificationListenerServicePermission = false;
            bool hasBindDreamServicePermission = false;

            // These two implement the implicit permissions that are granted
            // to pre-1.6 applications.
            bool hasWriteExternalStoragePermission = false;
            bool hasReadPhoneStatePermission = false;

            // If an app requests write storage, they will also get read storage.
            bool hasReadExternalStoragePermission = false;

            // Implement transition to read and write call log.
            bool hasReadContactsPermission = false;
            bool hasWriteContactsPermission = false;
            bool hasReadCallLogPermission = false;
            bool hasWriteCallLogPermission = false;

            // If an app declares itself as multiArch, we report the
            // native libraries differently.
            bool hasMultiArch = false;

            // This next group of variables is used to implement a group of
            // backward-compatibility heuristics necessitated by the addition of
            // some new uses-feature constants in 2.1 and 2.2. In most cases, the
            // heuristic is "if an app requests a permission but doesn't explicitly
            // request the corresponding <uses-feature>, presume it's there anyway".

            // 2.2 also added some other features that apps can request, but that
            // have no corresponding permission, so we cannot implement any
            // back-compatibility heuristic for them. The below are thus unnecessary
            // (but are retained here for documentary purposes.)
            //bool specCompassFeature = false;
            //bool specAccelerometerFeature = false;
            //bool specProximityFeature = false;
            //bool specAmbientLightFeature = false;
            //bool specLiveWallpaperFeature = false;

            int targetSdk = 0;
            int smallScreen = 1;
            int normalScreen = 1;
            int largeScreen = 1;
            int xlargeScreen = 1;
            int anyDensity = 1;
            int requiresSmallestWidthDp = 0;
            int compatibleWidthLimitDp = 0;
            int largestWidthLimitDp = 0;
            String8 pkg;
            String8 activityName;
            String8 activityLabel;
            String8 activityIcon;
            String8 activityBanner;
            String8 receiverName;
            String8 serviceName;
            Vector<String8> supportedInput;

            FeatureGroup commonFeatures;
            Vector<FeatureGroup> featureGroups;
            KeyedVector<String8, ImpliedFeature> impliedFeatures;

            while ((code=tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
                if (code == ResXMLTree::END_TAG) {
                    depth--;
                    if (depth < 2) {
                        if (withinSupportsInput && !supportedInput.isEmpty()) {
                            printf("supports-input: '");
                            const size_t N = supportedInput.size();
                            for (size_t i=0; i<N; i++) {
                                printf("%s", ResTable::normalizeForOutput(
                                        supportedInput[i].string()).string());
                                if (i != N - 1) {
                                    printf("' '");
                                } else {
                                    printf("'\n");
                                }
                            }
                            supportedInput.clear();
                        }
                        withinApplication = false;
                        withinSupportsInput = false;
                        withinFeatureGroup = false;
                    } else if (depth < 3) {
                        if (withinActivity && isMainActivity) {
                            String8 aName(getComponentName(pkg, activityName));
                            if (isLauncherActivity) {
                                printf("launchable-activity:");
                                if (aName.length() > 0) {
                                    printf(" name='%s' ",
                                            ResTable::normalizeForOutput(aName.string()).string());
                                }
                                printf(" label='%s' icon='%s'\n",
                                        ResTable::normalizeForOutput(activityLabel.string()).string(),
                                        ResTable::normalizeForOutput(activityIcon.string()).string());
                            }
                            if (isLeanbackLauncherActivity) {
                                printf("leanback-launchable-activity:");
                                if (aName.length() > 0) {
                                    printf(" name='%s' ",
                                            ResTable::normalizeForOutput(aName.string()).string());
                                }
                                printf(" label='%s' icon='%s' banner='%s'\n",
                                        ResTable::normalizeForOutput(activityLabel.string()).string(),
                                        ResTable::normalizeForOutput(activityIcon.string()).string(),
                                        ResTable::normalizeForOutput(activityBanner.string()).string());
                            }
                        }
                        if (!hasIntentFilter) {
                            hasOtherActivities |= withinActivity;
                            hasOtherReceivers |= withinReceiver;
                            hasOtherServices |= withinService;
                        } else {
                            if (withinService) {
                                hasPaymentService |= (actHostApduService && hasMetaHostPaymentCategory &&
                                        hasBindNfcServicePermission);
                                hasPaymentService |= (actOffHostApduService && hasMetaOffHostPaymentCategory &&
                                        hasBindNfcServicePermission);
                            }
                        }
                        withinActivity = false;
                        withinService = false;
                        withinReceiver = false;
                        withinProvider = false;
                        hasIntentFilter = false;
                        isMainActivity = isLauncherActivity = isLeanbackLauncherActivity = false;
                    } else if (depth < 4) {
                        if (withinIntentFilter) {
                            if (withinActivity) {
                                hasMainActivity |= actMainActivity;
                                hasLauncher |= catLauncher;
                                hasCameraActivity |= actCamera;
                                hasCameraSecureActivity |= actCameraSecure;
                                hasOtherActivities |= !actMainActivity && !actCamera && !actCameraSecure;
                            } else if (withinReceiver) {
                                hasWidgetReceivers |= actWidgetReceivers;
                                hasDeviceAdminReceiver |= (actDeviceAdminEnabled &&
                                        hasBindDeviceAdminPermission);
                                hasOtherReceivers |= (!actWidgetReceivers && !actDeviceAdminEnabled);
                            } else if (withinService) {
                                hasImeService |= actImeService;
                                hasWallpaperService |= actWallpaperService;
                                hasAccessibilityService |= (actAccessibilityService &&
                                        hasBindAccessibilityServicePermission);
                                hasPrintService |= (actPrintService && hasBindPrintServicePermission);
                                hasNotificationListenerService |= actNotificationListenerService &&
                                        hasBindNotificationListenerServicePermission;
                                hasDreamService |= actDreamService && hasBindDreamServicePermission;
                                hasOtherServices |= (!actImeService && !actWallpaperService &&
                                        !actAccessibilityService && !actPrintService &&
                                        !actHostApduService && !actOffHostApduService &&
                                        !actNotificationListenerService);
                            } else if (withinProvider) {
                                hasDocumentsProvider |= actDocumentsProvider && hasRequiredSafAttributes;
                            }
                        }
                        withinIntentFilter = false;
                    }
                    continue;
                }
                if (code != ResXMLTree::START_TAG) {
                    continue;
                }
                depth++;

                const char16_t* ctag16 = tree.getElementName(&len);
                if (ctag16 == NULL) {
                    fprintf(stderr, "ERROR: failed to get XML element name (bad string pool)\n");
                    goto bail;
                }
                String8 tag(ctag16);
                //printf("Depth %d,  %s\n", depth, tag.string());
                if (depth == 1) {
                    if (tag != "manifest") {
                        fprintf(stderr, "ERROR: manifest does not start with <manifest> tag\n");
                        goto bail;
                    }
                    pkg = getAttribute(tree, NULL, "package", NULL);
                    printf("package: name='%s' ",
                            ResTable::normalizeForOutput(pkg.string()).string());
                    int32_t versionCode = getIntegerAttribute(tree, VERSION_CODE_ATTR, &error);
                    if (error != "") {
                        fprintf(stderr, "ERROR getting 'android:versionCode' attribute: %s\n", error.string());
                        goto bail;
                    }
                    if (versionCode > 0) {
                        printf("versionCode='%d' ", versionCode);
                    } else {
                        printf("versionCode='' ");
                    }
                    String8 versionName = getResolvedAttribute(&res, tree, VERSION_NAME_ATTR, &error);
                    if (error != "") {
                        fprintf(stderr, "ERROR getting 'android:versionName' attribute: %s\n", error.string());
                        goto bail;
                    }
                    printf("versionName='%s'",
                            ResTable::normalizeForOutput(versionName.string()).string());

                    String8 splitName = getAttribute(tree, NULL, "split", NULL);
                    if (!splitName.isEmpty()) {
                        printf(" split='%s'", ResTable::normalizeForOutput(
                                    splitName.string()).string());
                    }
                    printf("\n");

                    int32_t installLocation = getResolvedIntegerAttribute(&res, tree,
                            INSTALL_LOCATION_ATTR, &error, -1);
                    if (error != "") {
                        fprintf(stderr, "ERROR getting 'android:installLocation' attribute: %s\n",
                                error.string());
                        goto bail;
                    }

                    if (installLocation >= 0) {
                        printf("install-location:'");
                        switch (installLocation) {
                            case 0:
                                printf("auto");
                                break;
                            case 1:
                                printf("internalOnly");
                                break;
                            case 2:
                                printf("preferExternal");
                                break;
                            default:
                                fprintf(stderr, "Invalid installLocation %d\n", installLocation);
                                goto bail;
                        }
                        printf("'\n");
                    }
                } else if (depth == 2) {
                    withinApplication = false;
                    if (tag == "application") {
                        withinApplication = true;

                        String8 label;
                        const size_t NL = locales.size();
                        for (size_t i=0; i<NL; i++) {
                            const char* localeStr =  locales[i].string();
                            assets.setLocale(localeStr != NULL ? localeStr : "");
                            String8 llabel = getResolvedAttribute(&res, tree, LABEL_ATTR, &error);
                            if (llabel != "") {
                                if (localeStr == NULL || strlen(localeStr) == 0) {
                                    label = llabel;
                                    printf("application-label:'%s'\n",
                                            ResTable::normalizeForOutput(llabel.string()).string());
                                } else {
                                    if (label == "") {
                                        label = llabel;
                                    }
                                    printf("application-label-%s:'%s'\n", localeStr,
                                           ResTable::normalizeForOutput(llabel.string()).string());
                                }
                            }
                        }

                        ResTable_config tmpConfig = config;
                        const size_t ND = densities.size();
                        for (size_t i=0; i<ND; i++) {
                            tmpConfig.density = densities[i];
                            assets.setConfiguration(tmpConfig);
                            String8 icon = getResolvedAttribute(&res, tree, ICON_ATTR, &error);
                            if (icon != "") {
                                printf("application-icon-%d:'%s'\n", densities[i],
                                        ResTable::normalizeForOutput(icon.string()).string());
                            }
                        }
                        assets.setConfiguration(config);

                        String8 icon = getResolvedAttribute(&res, tree, ICON_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:icon' attribute: %s\n", error.string());
                            goto bail;
                        }
                        int32_t testOnly = getIntegerAttribute(tree, TEST_ONLY_ATTR, &error, 0);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:testOnly' attribute: %s\n", error.string());
                            goto bail;
                        }
                        printf("application: label='%s' ",
                                ResTable::normalizeForOutput(label.string()).string());
                        printf("icon='%s'\n", ResTable::normalizeForOutput(icon.string()).string());
                        if (testOnly != 0) {
                            printf("testOnly='%d'\n", testOnly);
                        }

                        int32_t debuggable = getResolvedIntegerAttribute(&res, tree, DEBUGGABLE_ATTR, &error, 0);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:debuggable' attribute: %s\n", error.string());
                            goto bail;
                        }
                        if (debuggable != 0) {
                            printf("application-debuggable\n");
                        }

                        // We must search by name because the multiArch flag hasn't been API
                        // frozen yet.
                        int32_t multiArchIndex = tree.indexOfAttribute(RESOURCES_ANDROID_NAMESPACE,
                                "multiArch");
                        if (multiArchIndex >= 0) {
                            Res_value value;
                            if (tree.getAttributeValue(multiArchIndex, &value) != NO_ERROR) {
                                if (value.dataType >= Res_value::TYPE_FIRST_INT &&
                                        value.dataType <= Res_value::TYPE_LAST_INT) {
                                    hasMultiArch = value.data;
                                }
                            }
                        }
                    } else if (tag == "uses-sdk") {
                        int32_t code = getIntegerAttribute(tree, MIN_SDK_VERSION_ATTR, &error);
                        if (error != "") {
                            error = "";
                            String8 name = getResolvedAttribute(&res, tree, MIN_SDK_VERSION_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:minSdkVersion' attribute: %s\n",
                                        error.string());
                                goto bail;
                            }
                            if (name == "Donut") targetSdk = 4;
                            printf("sdkVersion:'%s'\n",
                                    ResTable::normalizeForOutput(name.string()).string());
                        } else if (code != -1) {
                            targetSdk = code;
                            printf("sdkVersion:'%d'\n", code);
                        }
                        code = getIntegerAttribute(tree, MAX_SDK_VERSION_ATTR, NULL, -1);
                        if (code != -1) {
                            printf("maxSdkVersion:'%d'\n", code);
                        }
                        code = getIntegerAttribute(tree, TARGET_SDK_VERSION_ATTR, &error);
                        if (error != "") {
                            error = "";
                            String8 name = getResolvedAttribute(&res, tree, TARGET_SDK_VERSION_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:targetSdkVersion' attribute: %s\n",
                                        error.string());
                                goto bail;
                            }
                            if (name == "Donut" && targetSdk < 4) targetSdk = 4;
                            printf("targetSdkVersion:'%s'\n",
                                    ResTable::normalizeForOutput(name.string()).string());
                        } else if (code != -1) {
                            if (targetSdk < code) {
                                targetSdk = code;
                            }
                            printf("targetSdkVersion:'%d'\n", code);
                        }
                    } else if (tag == "uses-configuration") {
                        int32_t reqTouchScreen = getIntegerAttribute(tree,
                                REQ_TOUCH_SCREEN_ATTR, NULL, 0);
                        int32_t reqKeyboardType = getIntegerAttribute(tree,
                                REQ_KEYBOARD_TYPE_ATTR, NULL, 0);
                        int32_t reqHardKeyboard = getIntegerAttribute(tree,
                                REQ_HARD_KEYBOARD_ATTR, NULL, 0);
                        int32_t reqNavigation = getIntegerAttribute(tree,
                                REQ_NAVIGATION_ATTR, NULL, 0);
                        int32_t reqFiveWayNav = getIntegerAttribute(tree,
                                REQ_FIVE_WAY_NAV_ATTR, NULL, 0);
                        printf("uses-configuration:");
                        if (reqTouchScreen != 0) {
                            printf(" reqTouchScreen='%d'", reqTouchScreen);
                        }
                        if (reqKeyboardType != 0) {
                            printf(" reqKeyboardType='%d'", reqKeyboardType);
                        }
                        if (reqHardKeyboard != 0) {
                            printf(" reqHardKeyboard='%d'", reqHardKeyboard);
                        }
                        if (reqNavigation != 0) {
                            printf(" reqNavigation='%d'", reqNavigation);
                        }
                        if (reqFiveWayNav != 0) {
                            printf(" reqFiveWayNav='%d'", reqFiveWayNav);
                        }
                        printf("\n");
                    } else if (tag == "supports-input") {
                        withinSupportsInput = true;
                    } else if (tag == "supports-screens") {
                        smallScreen = getIntegerAttribute(tree,
                                SMALL_SCREEN_ATTR, NULL, 1);
                        normalScreen = getIntegerAttribute(tree,
                                NORMAL_SCREEN_ATTR, NULL, 1);
                        largeScreen = getIntegerAttribute(tree,
                                LARGE_SCREEN_ATTR, NULL, 1);
                        xlargeScreen = getIntegerAttribute(tree,
                                XLARGE_SCREEN_ATTR, NULL, 1);
                        anyDensity = getIntegerAttribute(tree,
                                ANY_DENSITY_ATTR, NULL, 1);
                        requiresSmallestWidthDp = getIntegerAttribute(tree,
                                REQUIRES_SMALLEST_WIDTH_DP_ATTR, NULL, 0);
                        compatibleWidthLimitDp = getIntegerAttribute(tree,
                                COMPATIBLE_WIDTH_LIMIT_DP_ATTR, NULL, 0);
                        largestWidthLimitDp = getIntegerAttribute(tree,
                                LARGEST_WIDTH_LIMIT_DP_ATTR, NULL, 0);
                    } else if (tag == "feature-group") {
                        withinFeatureGroup = true;
                        FeatureGroup group;
                        group.label = getResolvedAttribute(&res, tree, LABEL_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:label' attribute:"
                                    " %s\n", error.string());
                            goto bail;
                        }
                        featureGroups.add(group);

                    } else if (tag == "uses-feature") {
                        String8 name = getAttribute(tree, NAME_ATTR, &error);
                        if (name != "" && error == "") {
                            int req = getIntegerAttribute(tree,
                                    REQUIRED_ATTR, NULL, 1);

                            commonFeatures.features.add(name, req);
                            if (req) {
                                addParentFeatures(&commonFeatures, name);
                            }
                        } else {
                            int vers = getIntegerAttribute(tree,
                                    GL_ES_VERSION_ATTR, &error);
                            if (error == "") {
                                if (vers > commonFeatures.openGLESVersion) {
                                    commonFeatures.openGLESVersion = vers;
                                }
                            }
                        }
                    } else if (tag == "uses-permission") {
                        String8 name = getAttribute(tree, NAME_ATTR, &error);
                        if (name != "" && error == "") {
                            if (name == "android.permission.CAMERA") {
                                addImpliedFeature(&impliedFeatures, "android.hardware.camera",
                                        String8::format("requested %s permission", name.string())
                                        .string());
                            } else if (name == "android.permission.ACCESS_FINE_LOCATION") {
                                addImpliedFeature(&impliedFeatures, "android.hardware.location.gps",
                                        String8::format("requested %s permission", name.string())
                                        .string());
                                addImpliedFeature(&impliedFeatures, "android.hardware.location",
                                        String8::format("requested %s permission", name.string())
                                        .string());
                            } else if (name == "android.permission.ACCESS_MOCK_LOCATION") {
                                addImpliedFeature(&impliedFeatures, "android.hardware.location",
                                        String8::format("requested %s permission", name.string())
                                        .string());
                            } else if (name == "android.permission.ACCESS_COARSE_LOCATION") {
                                addImpliedFeature(&impliedFeatures, "android.hardware.location.network",
                                        String8::format("requested %s permission", name.string())
                                        .string());
                                addImpliedFeature(&impliedFeatures, "android.hardware.location",
                                        String8::format("requested %s permission", name.string())
                                        .string());
                            } else if (name == "android.permission.ACCESS_LOCATION_EXTRA_COMMANDS" ||
                                       name == "android.permission.INSTALL_LOCATION_PROVIDER") {
                                addImpliedFeature(&impliedFeatures, "android.hardware.location",
                                        String8::format("requested %s permission", name.string())
                                        .string());
                            } else if (name == "android.permission.BLUETOOTH" ||
                                       name == "android.permission.BLUETOOTH_ADMIN") {
                                if (targetSdk > 4) {
                                    addImpliedFeature(&impliedFeatures, "android.hardware.bluetooth",
                                            String8::format("requested %s permission", name.string())
                                            .string());
                                    addImpliedFeature(&impliedFeatures, "android.hardware.bluetooth",
                                            "targetSdkVersion > 4");
                                }
                            } else if (name == "android.permission.RECORD_AUDIO") {
                                addImpliedFeature(&impliedFeatures, "android.hardware.microphone",
                                        String8::format("requested %s permission", name.string())
                                        .string());
                            } else if (name == "android.permission.ACCESS_WIFI_STATE" ||
                                       name == "android.permission.CHANGE_WIFI_STATE" ||
                                       name == "android.permission.CHANGE_WIFI_MULTICAST_STATE") {
                                addImpliedFeature(&impliedFeatures, "android.hardware.wifi",
                                        String8::format("requested %s permission", name.string())
                                        .string());
                            } else if (name == "android.permission.CALL_PHONE" ||
                                       name == "android.permission.CALL_PRIVILEGED" ||
                                       name == "android.permission.MODIFY_PHONE_STATE" ||
                                       name == "android.permission.PROCESS_OUTGOING_CALLS" ||
                                       name == "android.permission.READ_SMS" ||
                                       name == "android.permission.RECEIVE_SMS" ||
                                       name == "android.permission.RECEIVE_MMS" ||
                                       name == "android.permission.RECEIVE_WAP_PUSH" ||
                                       name == "android.permission.SEND_SMS" ||
                                       name == "android.permission.WRITE_APN_SETTINGS" ||
                                       name == "android.permission.WRITE_SMS") {
                                addImpliedFeature(&impliedFeatures, "android.hardware.telephony",
                                        String8("requested a telephony permission").string());
                            } else if (name == "android.permission.WRITE_EXTERNAL_STORAGE") {
                                hasWriteExternalStoragePermission = true;
                            } else if (name == "android.permission.READ_EXTERNAL_STORAGE") {
                                hasReadExternalStoragePermission = true;
                            } else if (name == "android.permission.READ_PHONE_STATE") {
                                hasReadPhoneStatePermission = true;
                            } else if (name == "android.permission.READ_CONTACTS") {
                                hasReadContactsPermission = true;
                            } else if (name == "android.permission.WRITE_CONTACTS") {
                                hasWriteContactsPermission = true;
                            } else if (name == "android.permission.READ_CALL_LOG") {
                                hasReadCallLogPermission = true;
                            } else if (name == "android.permission.WRITE_CALL_LOG") {
                                hasWriteCallLogPermission = true;
                            }

                            printUsesPermission(name,
                                    getIntegerAttribute(tree, REQUIRED_ATTR, NULL, 1) == 0,
                                    getIntegerAttribute(tree, MAX_SDK_VERSION_ATTR, NULL, -1));
                       } else {
                            fprintf(stderr, "ERROR getting 'android:name' attribute: %s\n",
                                    error.string());
                            goto bail;
                        }
                    } else if (tag == "uses-package") {
                        String8 name = getAttribute(tree, NAME_ATTR, &error);
                        if (name != "" && error == "") {
                            printf("uses-package:'%s'\n",
                                    ResTable::normalizeForOutput(name.string()).string());
                        } else {
                            fprintf(stderr, "ERROR getting 'android:name' attribute: %s\n",
                                    error.string());
                                goto bail;
                        }
                    } else if (tag == "original-package") {
                        String8 name = getAttribute(tree, NAME_ATTR, &error);
                        if (name != "" && error == "") {
                            printf("original-package:'%s'\n",
                                    ResTable::normalizeForOutput(name.string()).string());
                        } else {
                            fprintf(stderr, "ERROR getting 'android:name' attribute: %s\n",
                                    error.string());
                                goto bail;
                        }
                    } else if (tag == "supports-gl-texture") {
                        String8 name = getAttribute(tree, NAME_ATTR, &error);
                        if (name != "" && error == "") {
                            printf("supports-gl-texture:'%s'\n",
                                    ResTable::normalizeForOutput(name.string()).string());
                        } else {
                            fprintf(stderr, "ERROR getting 'android:name' attribute: %s\n",
                                    error.string());
                                goto bail;
                        }
                    } else if (tag == "compatible-screens") {
                        printCompatibleScreens(tree, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting compatible screens: %s\n",
                                    error.string());
                            goto bail;
                        }
                        depth--;
                    } else if (tag == "package-verifier") {
                        String8 name = getAttribute(tree, NAME_ATTR, &error);
                        if (name != "" && error == "") {
                            String8 publicKey = getAttribute(tree, PUBLIC_KEY_ATTR, &error);
                            if (publicKey != "" && error == "") {
                                printf("package-verifier: name='%s' publicKey='%s'\n",
                                        ResTable::normalizeForOutput(name.string()).string(),
                                        ResTable::normalizeForOutput(publicKey.string()).string());
                            }
                        }
                    }
                } else if (depth == 3) {
                    withinActivity = false;
                    withinReceiver = false;
                    withinService = false;
                    withinProvider = false;
                    hasIntentFilter = false;
                    hasMetaHostPaymentCategory = false;
                    hasMetaOffHostPaymentCategory = false;
                    hasBindDeviceAdminPermission = false;
                    hasBindInputMethodPermission = false;
                    hasBindAccessibilityServicePermission = false;
                    hasBindPrintServicePermission = false;
                    hasBindNfcServicePermission = false;
                    hasRequiredSafAttributes = false;
                    hasBindNotificationListenerServicePermission = false;
                    hasBindDreamServicePermission = false;
                    if (withinApplication) {
                        if(tag == "activity") {
                            withinActivity = true;
                            activityName = getAttribute(tree, NAME_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:name' attribute: %s\n",
                                        error.string());
                                goto bail;
                            }

                            activityLabel = getResolvedAttribute(&res, tree, LABEL_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:label' attribute: %s\n",
                                        error.string());
                                goto bail;
                            }

                            activityIcon = getResolvedAttribute(&res, tree, ICON_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:icon' attribute: %s\n",
                                        error.string());
                                goto bail;
                            }

                            activityBanner = getResolvedAttribute(&res, tree, BANNER_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:banner' attribute: %s\n",
                                        error.string());
                                goto bail;
                            }

                            int32_t orien = getResolvedIntegerAttribute(&res, tree,
                                    SCREEN_ORIENTATION_ATTR, &error);
                            if (error == "") {
                                if (orien == 0 || orien == 6 || orien == 8) {
                                    // Requests landscape, sensorLandscape, or reverseLandscape.
                                    addImpliedFeature(&impliedFeatures, "android.hardware.screen.landscape",
                                            "one or more activities have specified a landscape orientation");
                                } else if (orien == 1 || orien == 7 || orien == 9) {
                                    // Requests portrait, sensorPortrait, or reversePortrait.
                                    addImpliedFeature(&impliedFeatures, "android.hardware.screen.portrait",
                                            "one or more activities have specified a portrait orientation");
                                }
                            }
                        } else if (tag == "uses-library") {
                            String8 libraryName = getAttribute(tree, NAME_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr,
                                        "ERROR getting 'android:name' attribute for uses-library"
                                        " %s\n", error.string());
                                goto bail;
                            }
                            int req = getIntegerAttribute(tree,
                                    REQUIRED_ATTR, NULL, 1);
                            printf("uses-library%s:'%s'\n",
                                    req ? "" : "-not-required", ResTable::normalizeForOutput(
                                            libraryName.string()).string());
                        } else if (tag == "receiver") {
                            withinReceiver = true;
                            receiverName = getAttribute(tree, NAME_ATTR, &error);

                            if (error != "") {
                                fprintf(stderr,
                                        "ERROR getting 'android:name' attribute for receiver:"
                                        " %s\n", error.string());
                                goto bail;
                            }

                            String8 permission = getAttribute(tree, PERMISSION_ATTR, &error);
                            if (error == "") {
                                if (permission == "android.permission.BIND_DEVICE_ADMIN") {
                                    hasBindDeviceAdminPermission = true;
                                }
                            } else {
                                fprintf(stderr, "ERROR getting 'android:permission' attribute for"
                                        " receiver '%s': %s\n", receiverName.string(), error.string());
                            }
                        } else if (tag == "service") {
                            withinService = true;
                            serviceName = getAttribute(tree, NAME_ATTR, &error);

                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:name' attribute for "
                                        "service:%s\n", error.string());
                                goto bail;
                            }

                            String8 permission = getAttribute(tree, PERMISSION_ATTR, &error);
                            if (error == "") {
                                if (permission == "android.permission.BIND_INPUT_METHOD") {
                                    hasBindInputMethodPermission = true;
                                } else if (permission == "android.permission.BIND_ACCESSIBILITY_SERVICE") {
                                    hasBindAccessibilityServicePermission = true;
                                } else if (permission == "android.permission.BIND_PRINT_SERVICE") {
                                    hasBindPrintServicePermission = true;
                                } else if (permission == "android.permission.BIND_NFC_SERVICE") {
                                    hasBindNfcServicePermission = true;
                                } else if (permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE") {
                                    hasBindNotificationListenerServicePermission = true;
                                } else if (permission == "android.permission.BIND_DREAM_SERVICE") {
                                    hasBindDreamServicePermission = true;
                                }
                            } else {
                                fprintf(stderr, "ERROR getting 'android:permission' attribute for"
                                        " service '%s': %s\n", serviceName.string(), error.string());
                            }
                        } else if (tag == "provider") {
                            withinProvider = true;

                            bool exported = getResolvedIntegerAttribute(&res, tree, EXPORTED_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:exported' attribute for provider:"
                                        " %s\n", error.string());
                                goto bail;
                            }

                            bool grantUriPermissions = getResolvedIntegerAttribute(&res, tree,
                                    GRANT_URI_PERMISSIONS_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:grantUriPermissions' attribute for provider:"
                                        " %s\n", error.string());
                                goto bail;
                            }

                            String8 permission = getResolvedAttribute(&res, tree, PERMISSION_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:permission' attribute for provider:"
                                        " %s\n", error.string());
                                goto bail;
                            }

                            hasRequiredSafAttributes |= exported && grantUriPermissions &&
                                permission == "android.permission.MANAGE_DOCUMENTS";

                        } else if (bundle->getIncludeMetaData() && tag == "meta-data") {
                            String8 metaDataName = getResolvedAttribute(&res, tree, NAME_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:name' attribute for "
                                        "meta-data:%s\n", error.string());
                                goto bail;
                            }
                            printf("meta-data: name='%s' ",
                                    ResTable::normalizeForOutput(metaDataName.string()).string());
                            printResolvedResourceAttribute(&res, tree, VALUE_ATTR, String8("value"),
                                    &error);
                            if (error != "") {
                                // Try looking for a RESOURCE_ATTR
                                error = "";
                                printResolvedResourceAttribute(&res, tree, RESOURCE_ATTR,
                                        String8("resource"), &error);
                                if (error != "") {
                                    fprintf(stderr, "ERROR getting 'android:value' or "
                                            "'android:resource' attribute for "
                                            "meta-data:%s\n", error.string());
                                    goto bail;
                                }
                            }
                            printf("\n");
                        } else if (withinSupportsInput && tag == "input-type") {
                            String8 name = getAttribute(tree, NAME_ATTR, &error);
                            if (name != "" && error == "") {
                                supportedInput.add(name);
                            } else {
                                fprintf(stderr, "ERROR getting 'android:name' attribute: %s\n",
                                        error.string());
                                goto bail;
                            }
                        }
                    } else if (withinFeatureGroup && tag == "uses-feature") {
                        FeatureGroup& top = featureGroups.editTop();

                        String8 name = getResolvedAttribute(&res, tree, NAME_ATTR, &error);
                        if (name != "" && error == "") {
                            top.features.add(name, true);
                            addParentFeatures(&top, name);
                        } else {
                            int vers = getIntegerAttribute(tree, GL_ES_VERSION_ATTR, &error);
                            if (error == "") {
                                if (vers > top.openGLESVersion) {
                                    top.openGLESVersion = vers;
                                }
                            }
                        }
                    }
                } else if (depth == 4) {
                    if (tag == "intent-filter") {
                        hasIntentFilter = true;
                        withinIntentFilter = true;
                        actMainActivity = false;
                        actWidgetReceivers = false;
                        actImeService = false;
                        actWallpaperService = false;
                        actAccessibilityService = false;
                        actPrintService = false;
                        actDeviceAdminEnabled = false;
                        actHostApduService = false;
                        actOffHostApduService = false;
                        actDocumentsProvider = false;
                        actNotificationListenerService = false;
                        actDreamService = false;
                        actCamera = false;
                        actCameraSecure = false;
                        catLauncher = false;
                    } else if (withinService && tag == "meta-data") {
                        String8 name = getAttribute(tree, NAME_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:name' attribute for"
                                    " meta-data tag in service '%s': %s\n", serviceName.string(), error.string());
                            goto bail;
                        }

                        if (name == "android.nfc.cardemulation.host_apdu_service" ||
                                name == "android.nfc.cardemulation.off_host_apdu_service") {
                            bool offHost = true;
                            if (name == "android.nfc.cardemulation.host_apdu_service") {
                                offHost = false;
                            }

                            String8 xmlPath = getResolvedAttribute(&res, tree, RESOURCE_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:resource' attribute for"
                                        " meta-data tag in service '%s': %s\n", serviceName.string(), error.string());
                                goto bail;
                            }

                            Vector<String8> categories = getNfcAidCategories(assets, xmlPath,
                                    offHost, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting AID category for service '%s'\n",
                                        serviceName.string());
                                goto bail;
                            }

                            const size_t catLen = categories.size();
                            for (size_t i = 0; i < catLen; i++) {
                                bool paymentCategory = (categories[i] == "payment");
                                if (offHost) {
                                    hasMetaOffHostPaymentCategory |= paymentCategory;
                                } else {
                                    hasMetaHostPaymentCategory |= paymentCategory;
                                }
                            }
                        }
                    }
                } else if ((depth == 5) && withinIntentFilter) {
                    String8 action;
                    if (tag == "action") {
                        action = getAttribute(tree, NAME_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:name' attribute: %s\n",
                                    error.string());
                            goto bail;
                        }

                        if (withinActivity) {
                            if (action == "android.intent.action.MAIN") {
                                isMainActivity = true;
                                actMainActivity = true;
                            } else if (action == "android.media.action.STILL_IMAGE_CAMERA" ||
                                    action == "android.media.action.VIDEO_CAMERA") {
                                actCamera = true;
                            } else if (action == "android.media.action.STILL_IMAGE_CAMERA_SECURE") {
                                actCameraSecure = true;
                            }
                        } else if (withinReceiver) {
                            if (action == "android.appwidget.action.APPWIDGET_UPDATE") {
                                actWidgetReceivers = true;
                            } else if (action == "android.app.action.DEVICE_ADMIN_ENABLED") {
                                actDeviceAdminEnabled = true;
                            }
                        } else if (withinService) {
                            if (action == "android.view.InputMethod") {
                                actImeService = true;
                            } else if (action == "android.service.wallpaper.WallpaperService") {
                                actWallpaperService = true;
                            } else if (action == "android.accessibilityservice.AccessibilityService") {
                                actAccessibilityService = true;
                            } else if (action == "android.printservice.PrintService") {
                                actPrintService = true;
                            } else if (action == "android.nfc.cardemulation.action.HOST_APDU_SERVICE") {
                                actHostApduService = true;
                            } else if (action == "android.nfc.cardemulation.action.OFF_HOST_APDU_SERVICE") {
                                actOffHostApduService = true;
                            } else if (action == "android.service.notification.NotificationListenerService") {
                                actNotificationListenerService = true;
                            } else if (action == "android.service.dreams.DreamService") {
                                actDreamService = true;
                            }
                        } else if (withinProvider) {
                            if (action == "android.content.action.DOCUMENTS_PROVIDER") {
                                actDocumentsProvider = true;
                            }
                        }
                        if (action == "android.intent.action.SEARCH") {
                            isSearchable = true;
                        }
                    }

                    if (tag == "category") {
                        String8 category = getAttribute(tree, NAME_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'name' attribute: %s\n",
                                    error.string());
                            goto bail;
                        }
                        if (withinActivity) {
                            if (category == "android.intent.category.LAUNCHER") {
                                isLauncherActivity = true;
                            } else if (category == "android.intent.category.LEANBACK_LAUNCHER") {
                                isLeanbackLauncherActivity = true;
                            } else if (category == "android.intent.category.HOME") {
                                catLauncher = true;
                            }
                        }
                    }
                }
            }

            // Pre-1.6 implicitly granted permission compatibility logic
            if (targetSdk < 4) {
                if (!hasWriteExternalStoragePermission) {
                    printUsesPermission(String8("android.permission.WRITE_EXTERNAL_STORAGE"));
                    printUsesImpliedPermission(String8("android.permission.WRITE_EXTERNAL_STORAGE"),
                            String8("targetSdkVersion < 4"));
                    hasWriteExternalStoragePermission = true;
                }
                if (!hasReadPhoneStatePermission) {
                    printUsesPermission(String8("android.permission.READ_PHONE_STATE"));
                    printUsesImpliedPermission(String8("android.permission.READ_PHONE_STATE"),
                            String8("targetSdkVersion < 4"));
                }
            }

            // If the application has requested WRITE_EXTERNAL_STORAGE, we will
            // force them to always take READ_EXTERNAL_STORAGE as well.  We always
            // do this (regardless of target API version) because we can't have
            // an app with write permission but not read permission.
            if (!hasReadExternalStoragePermission && hasWriteExternalStoragePermission) {
                printUsesPermission(String8("android.permission.READ_EXTERNAL_STORAGE"));
                printUsesImpliedPermission(String8("android.permission.READ_EXTERNAL_STORAGE"),
                        String8("requested WRITE_EXTERNAL_STORAGE"));
            }

            // Pre-JellyBean call log permission compatibility.
            if (targetSdk < 16) {
                if (!hasReadCallLogPermission && hasReadContactsPermission) {
                    printUsesPermission(String8("android.permission.READ_CALL_LOG"));
                    printUsesImpliedPermission(String8("android.permission.READ_CALL_LOG"),
                            String8("targetSdkVersion < 16 and requested READ_CONTACTS"));
                }
                if (!hasWriteCallLogPermission && hasWriteContactsPermission) {
                    printUsesPermission(String8("android.permission.WRITE_CALL_LOG"));
                    printUsesImpliedPermission(String8("android.permission.WRITE_CALL_LOG"),
                            String8("targetSdkVersion < 16 and requested WRITE_CONTACTS"));
                }
            }

            addImpliedFeature(&impliedFeatures, "android.hardware.touchscreen",
                    "default feature for all apps");

            const size_t numFeatureGroups = featureGroups.size();
            if (numFeatureGroups == 0) {
                // If no <feature-group> tags were defined, apply auto-implied features.
                printFeatureGroup(commonFeatures, &impliedFeatures);

            } else {
                // <feature-group> tags are defined, so we ignore implied features and
                for (size_t i = 0; i < numFeatureGroups; i++) {
                    FeatureGroup& grp = featureGroups.editItemAt(i);

                    if (commonFeatures.openGLESVersion > grp.openGLESVersion) {
                        grp.openGLESVersion = commonFeatures.openGLESVersion;
                    }

                    // Merge the features defined in the top level (not inside a <feature-group>)
                    // with this feature group.
                    const size_t numCommonFeatures = commonFeatures.features.size();
                    for (size_t j = 0; j < numCommonFeatures; j++) {
                        if (grp.features.indexOfKey(commonFeatures.features.keyAt(j)) < 0) {
                            grp.features.add(commonFeatures.features.keyAt(j),
                                    commonFeatures.features[j]);
                        }
                    }

                   if (!grp.features.isEmpty()) {
                        printFeatureGroup(grp);
                    }
                }
            }


            if (hasWidgetReceivers) {
                printComponentPresence("app-widget");
            }
            if (hasDeviceAdminReceiver) {
                printComponentPresence("device-admin");
            }
            if (hasImeService) {
                printComponentPresence("ime");
            }
            if (hasWallpaperService) {
                printComponentPresence("wallpaper");
            }
            if (hasAccessibilityService) {
                printComponentPresence("accessibility");
            }
            if (hasPrintService) {
                printComponentPresence("print-service");
            }
            if (hasPaymentService) {
                printComponentPresence("payment");
            }
            if (isSearchable) {
                printComponentPresence("search");
            }
            if (hasDocumentsProvider) {
                printComponentPresence("document-provider");
            }
            if (hasLauncher) {
                printComponentPresence("launcher");
            }
            if (hasNotificationListenerService) {
                printComponentPresence("notification-listener");
            }
            if (hasDreamService) {
                printComponentPresence("dream");
            }
            if (hasCameraActivity) {
                printComponentPresence("camera");
            }
            if (hasCameraSecureActivity) {
                printComponentPresence("camera-secure");
            }

            if (hasMainActivity) {
                printf("main\n");
            }
            if (hasOtherActivities) {
                printf("other-activities\n");
            }
             if (hasOtherReceivers) {
                printf("other-receivers\n");
            }
            if (hasOtherServices) {
                printf("other-services\n");
            }

            // For modern apps, if screen size buckets haven't been specified
            // but the new width ranges have, then infer the buckets from them.
            if (smallScreen > 0 && normalScreen > 0 && largeScreen > 0 && xlargeScreen > 0
                    && requiresSmallestWidthDp > 0) {
                int compatWidth = compatibleWidthLimitDp;
                if (compatWidth <= 0) {
                    compatWidth = requiresSmallestWidthDp;
                }
                if (requiresSmallestWidthDp <= 240 && compatWidth >= 240) {
                    smallScreen = -1;
                } else {
                    smallScreen = 0;
                }
                if (requiresSmallestWidthDp <= 320 && compatWidth >= 320) {
                    normalScreen = -1;
                } else {
                    normalScreen = 0;
                }
                if (requiresSmallestWidthDp <= 480 && compatWidth >= 480) {
                    largeScreen = -1;
                } else {
                    largeScreen = 0;
                }
                if (requiresSmallestWidthDp <= 720 && compatWidth >= 720) {
                    xlargeScreen = -1;
                } else {
                    xlargeScreen = 0;
                }
            }

            // Determine default values for any unspecified screen sizes,
            // based on the target SDK of the package.  As of 4 (donut)
            // the screen size support was introduced, so all default to
            // enabled.
            if (smallScreen > 0) {
                smallScreen = targetSdk >= 4 ? -1 : 0;
            }
            if (normalScreen > 0) {
                normalScreen = -1;
            }
            if (largeScreen > 0) {
                largeScreen = targetSdk >= 4 ? -1 : 0;
            }
            if (xlargeScreen > 0) {
                // Introduced in Gingerbread.
                xlargeScreen = targetSdk >= 9 ? -1 : 0;
            }
            if (anyDensity > 0) {
                anyDensity = (targetSdk >= 4 || requiresSmallestWidthDp > 0
                        || compatibleWidthLimitDp > 0) ? -1 : 0;
            }
            printf("supports-screens:");
            if (smallScreen != 0) {
                printf(" 'small'");
            }
            if (normalScreen != 0) {
                printf(" 'normal'");
            }
            if (largeScreen != 0) {
                printf(" 'large'");
            }
            if (xlargeScreen != 0) {
                printf(" 'xlarge'");
            }
            printf("\n");
            printf("supports-any-density: '%s'\n", anyDensity ? "true" : "false");
            if (requiresSmallestWidthDp > 0) {
                printf("requires-smallest-width:'%d'\n", requiresSmallestWidthDp);
            }
            if (compatibleWidthLimitDp > 0) {
                printf("compatible-width-limit:'%d'\n", compatibleWidthLimitDp);
            }
            if (largestWidthLimitDp > 0) {
                printf("largest-width-limit:'%d'\n", largestWidthLimitDp);
            }

            printf("locales:");
            const size_t NL = locales.size();
            for (size_t i=0; i<NL; i++) {
                const char* localeStr =  locales[i].string();
                if (localeStr == NULL || strlen(localeStr) == 0) {
                    localeStr = "--_--";
                }
                printf(" '%s'", localeStr);
            }
            printf("\n");

            printf("densities:");
            const size_t ND = densities.size();
            for (size_t i=0; i<ND; i++) {
                printf(" '%d'", densities[i]);
            }
            printf("\n");

            AssetDir* dir = assets.openNonAssetDir(assetsCookie, "lib");
            if (dir != NULL) {
                if (dir->getFileCount() > 0) {
                    SortedVector<String8> architectures;
                    for (size_t i=0; i<dir->getFileCount(); i++) {
                        architectures.add(ResTable::normalizeForOutput(
                                dir->getFileName(i).string()));
                    }

                    bool outputAltNativeCode = false;
                    // A multiArch package is one that contains 64-bit and
                    // 32-bit versions of native code and expects 3rd-party
                    // apps to load these native code libraries. Since most
                    // 64-bit systems also support 32-bit apps, the apps
                    // loading this multiArch package's code may be either
                    // 32-bit or 64-bit.
                    if (hasMultiArch) {
                        // If this is a multiArch package, report the 64-bit
                        // version only. Then as a separate entry, report the
                        // rest.
                        //
                        // If we report the 32-bit architecture, this APK will
                        // be installed on a 32-bit device, causing a large waste
                        // of bandwidth and disk space. This assumes that
                        // the developer of the multiArch package has also
                        // made a version that is 32-bit only.
                        String8 intel64("x86_64");
                        String8 arm64("arm64-v8a");
                        ssize_t index = architectures.indexOf(intel64);
                        if (index < 0) {
                            index = architectures.indexOf(arm64);
                        }

                        if (index >= 0) {
                            printf("native-code: '%s'\n", architectures[index].string());
                            architectures.removeAt(index);
                            outputAltNativeCode = true;
                        }
                    }

                    const size_t archCount = architectures.size();
                    if (archCount > 0) {
                        if (outputAltNativeCode) {
                            printf("alt-");
                        }
                        printf("native-code:");
                        for (size_t i = 0; i < archCount; i++) {
                            printf(" '%s'", architectures[i].string());
                        }
                        printf("\n");
                    }
                }
                delete dir;
            }
        } else if (strcmp("badger", option) == 0) {
            printf("%s", CONSOLE_DATA);
        } else if (strcmp("configurations", option) == 0) {
            Vector<ResTable_config> configs;
            res.getConfigurations(&configs);
            const size_t N = configs.size();
            for (size_t i=0; i<N; i++) {
                printf("%s\n", configs[i].toString().string());
            }
        } else {
            fprintf(stderr, "ERROR: unknown dump option '%s'\n", option);
            goto bail;
        }
    }

    result = NO_ERROR;

bail:
    if (asset) {
        delete asset;
    }
    return (result != NO_ERROR);
}


/*
 * Handle the "add" command, which wants to add files to a new or
 * pre-existing archive.
 */
int doAdd(Bundle* bundle)
{
    ZipFile* zip = NULL;
    status_t result = UNKNOWN_ERROR;
    const char* zipFileName;

    if (bundle->getUpdate()) {
        /* avoid confusion */
        fprintf(stderr, "ERROR: can't use '-u' with add\n");
        goto bail;
    }

    if (bundle->getFileSpecCount() < 1) {
        fprintf(stderr, "ERROR: must specify zip file name\n");
        goto bail;
    }
    zipFileName = bundle->getFileSpecEntry(0);

    if (bundle->getFileSpecCount() < 2) {
        fprintf(stderr, "NOTE: nothing to do\n");
        goto bail;
    }

    zip = openReadWrite(zipFileName, true);
    if (zip == NULL) {
        fprintf(stderr, "ERROR: failed opening/creating '%s' as Zip file\n", zipFileName);
        goto bail;
    }

    for (int i = 1; i < bundle->getFileSpecCount(); i++) {
        const char* fileName = bundle->getFileSpecEntry(i);

        if (strcasecmp(String8(fileName).getPathExtension().string(), ".gz") == 0) {
            printf(" '%s'... (from gzip)\n", fileName);
            result = zip->addGzip(fileName, String8(fileName).getBasePath().string(), NULL);
        } else {
            if (bundle->getJunkPath()) {
                String8 storageName = String8(fileName).getPathLeaf();
                printf(" '%s' as '%s'...\n", fileName,
                        ResTable::normalizeForOutput(storageName.string()).string());
                result = zip->add(fileName, storageName.string(),
                                  bundle->getCompressionMethod(), NULL);
            } else {
                printf(" '%s'...\n", fileName);
                result = zip->add(fileName, bundle->getCompressionMethod(), NULL);
            }
        }
        if (result != NO_ERROR) {
            fprintf(stderr, "Unable to add '%s' to '%s'", bundle->getFileSpecEntry(i), zipFileName);
            if (result == NAME_NOT_FOUND) {
                fprintf(stderr, ": file not found\n");
            } else if (result == ALREADY_EXISTS) {
                fprintf(stderr, ": already exists in archive\n");
            } else {
                fprintf(stderr, "\n");
            }
            goto bail;
        }
    }

    result = NO_ERROR;

bail:
    delete zip;
    return (result != NO_ERROR);
}


/*
 * Delete files from an existing archive.
 */
int doRemove(Bundle* bundle)
{
    ZipFile* zip = NULL;
    status_t result = UNKNOWN_ERROR;
    const char* zipFileName;

    if (bundle->getFileSpecCount() < 1) {
        fprintf(stderr, "ERROR: must specify zip file name\n");
        goto bail;
    }
    zipFileName = bundle->getFileSpecEntry(0);

    if (bundle->getFileSpecCount() < 2) {
        fprintf(stderr, "NOTE: nothing to do\n");
        goto bail;
    }

    zip = openReadWrite(zipFileName, false);
    if (zip == NULL) {
        fprintf(stderr, "ERROR: failed opening Zip archive '%s'\n",
            zipFileName);
        goto bail;
    }

    for (int i = 1; i < bundle->getFileSpecCount(); i++) {
        const char* fileName = bundle->getFileSpecEntry(i);
        ZipEntry* entry;

        entry = zip->getEntryByName(fileName);
        if (entry == NULL) {
            printf(" '%s' NOT FOUND\n", fileName);
            continue;
        }

        result = zip->remove(entry);

        if (result != NO_ERROR) {
            fprintf(stderr, "Unable to delete '%s' from '%s'\n",
                bundle->getFileSpecEntry(i), zipFileName);
            goto bail;
        }
    }

    /* update the archive */
    zip->flush();

bail:
    delete zip;
    return (result != NO_ERROR);
}

static status_t addResourcesToBuilder(const sp<AaptDir>& dir, const sp<ApkBuilder>& builder, bool ignoreConfig=false) {
    const size_t numDirs = dir->getDirs().size();
    for (size_t i = 0; i < numDirs; i++) {
        bool ignore = ignoreConfig;
        const sp<AaptDir>& subDir = dir->getDirs().valueAt(i);
        const char* dirStr = subDir->getLeaf().string();
        if (!ignore && strstr(dirStr, "mipmap") == dirStr) {
            ignore = true;
        }
        status_t err = addResourcesToBuilder(subDir, builder, ignore);
        if (err != NO_ERROR) {
            return err;
        }
    }

    const size_t numFiles = dir->getFiles().size();
    for (size_t i = 0; i < numFiles; i++) {
        sp<AaptGroup> gp = dir->getFiles().valueAt(i);
        const size_t numConfigs = gp->getFiles().size();
        for (size_t j = 0; j < numConfigs; j++) {
            status_t err = NO_ERROR;
            if (ignoreConfig) {
                err = builder->getBaseSplit()->addEntry(gp->getPath(), gp->getFiles().valueAt(j));
            } else {
                err = builder->addEntry(gp->getPath(), gp->getFiles().valueAt(j));
            }
            if (err != NO_ERROR) {
                fprintf(stderr, "Failed to add %s (%s) to builder.\n",
                        gp->getPath().string(), gp->getFiles()[j]->getPrintableSource().string());
                return err;
            }
        }
    }
    return NO_ERROR;
}

static String8 buildApkName(const String8& original, const sp<ApkSplit>& split) {
    if (split->isBase()) {
        return original;
    }

    String8 ext(original.getPathExtension());
    if (ext == String8(".apk")) {
        return String8::format("%s_%s%s",
                original.getBasePath().string(),
                split->getDirectorySafeName().string(),
                ext.string());
    }

    return String8::format("%s_%s", original.string(),
            split->getDirectorySafeName().string());
}

/*
 * Package up an asset directory and associated application files.
 */
int doPackage(Bundle* bundle)
{
    const char* outputAPKFile;
    int retVal = 1;
    status_t err;
    sp<AaptAssets> assets;
    int N;
    FILE* fp;
    String8 dependencyFile;
    sp<ApkBuilder> builder;

    // -c en_XA or/and ar_XB means do pseudolocalization
    sp<WeakResourceFilter> configFilter = new WeakResourceFilter();
    err = configFilter->parse(bundle->getConfigurations());
    if (err != NO_ERROR) {
        goto bail;
    }
    if (configFilter->containsPseudo()) {
        bundle->setPseudolocalize(bundle->getPseudolocalize() | PSEUDO_ACCENTED);
    }
    if (configFilter->containsPseudoBidi()) {
        bundle->setPseudolocalize(bundle->getPseudolocalize() | PSEUDO_BIDI);
    }

    N = bundle->getFileSpecCount();
    if (N < 1 && bundle->getResourceSourceDirs().size() == 0 && bundle->getJarFiles().size() == 0
            && bundle->getAndroidManifestFile() == NULL && bundle->getAssetSourceDirs().size() == 0) {
        fprintf(stderr, "ERROR: no input files\n");
        goto bail;
    }

    outputAPKFile = bundle->getOutputAPKFile();

    // Make sure the filenames provided exist and are of the appropriate type.
    if (outputAPKFile) {
        FileType type;
        type = getFileType(outputAPKFile);
        if (type != kFileTypeNonexistent && type != kFileTypeRegular) {
            fprintf(stderr,
                "ERROR: output file '%s' exists but is not regular file\n",
                outputAPKFile);
            goto bail;
        }
    }

    // Load the assets.
    assets = new AaptAssets();

    // Set up the resource gathering in assets if we're going to generate
    // dependency files. Every time we encounter a resource while slurping
    // the tree, we'll add it to these stores so we have full resource paths
    // to write to a dependency file.
    if (bundle->getGenDependencies()) {
        sp<FilePathStore> resPathStore = new FilePathStore;
        assets->setFullResPaths(resPathStore);
        sp<FilePathStore> assetPathStore = new FilePathStore;
        assets->setFullAssetPaths(assetPathStore);
    }

    err = assets->slurpFromArgs(bundle);
    if (err < 0) {
        goto bail;
    }

    if (bundle->getVerbose()) {
        assets->print(String8());
    }

    // Create the ApkBuilder, which will collect the compiled files
    // to write to the final APK (or sets of APKs if we are building
    // a Split APK.
    builder = new ApkBuilder(configFilter);

    // If we are generating a Split APK, find out which configurations to split on.
    if (bundle->getSplitConfigurations().size() > 0) {
        const Vector<String8>& splitStrs = bundle->getSplitConfigurations();
        const size_t numSplits = splitStrs.size();
        for (size_t i = 0; i < numSplits; i++) {
            std::set<ConfigDescription> configs;
            if (!AaptConfig::parseCommaSeparatedList(splitStrs[i], &configs)) {
                fprintf(stderr, "ERROR: failed to parse split configuration '%s'\n", splitStrs[i].string());
                goto bail;
            }

            err = builder->createSplitForConfigs(configs);
            if (err != NO_ERROR) {
                goto bail;
            }
        }
    }

    // If they asked for any fileAs that need to be compiled, do so.
    if (bundle->getResourceSourceDirs().size() || bundle->getAndroidManifestFile()) {
        err = buildResources(bundle, assets, builder);
        if (err != 0) {
            goto bail;
        }
    }

    // At this point we've read everything and processed everything.  From here
    // on out it's just writing output files.
    if (SourcePos::hasErrors()) {
        goto bail;
    }

    // Update symbols with information about which ones are needed as Java symbols.
    assets->applyJavaSymbols();
    if (SourcePos::hasErrors()) {
        goto bail;
    }

    // If we've been asked to generate a dependency file, do that here
    if (bundle->getGenDependencies()) {
        // If this is the packaging step, generate the dependency file next to
        // the output apk (e.g. bin/resources.ap_.d)
        if (outputAPKFile) {
            dependencyFile = String8(outputAPKFile);
            // Add the .d extension to the dependency file.
            dependencyFile.append(".d");
        } else {
            // Else if this is the R.java dependency generation step,
            // generate the dependency file in the R.java package subdirectory
            // e.g. gen/com/foo/app/R.java.d
            dependencyFile = String8(bundle->getRClassDir());
            dependencyFile.appendPath("R.java.d");
        }
        // Make sure we have a clean dependency file to start with
        fp = fopen(dependencyFile, "w");
        fclose(fp);
    }

    // Write out R.java constants
    if (!assets->havePrivateSymbols()) {
        if (bundle->getCustomPackage() == NULL) {
            // Write the R.java file into the appropriate class directory
            // e.g. gen/com/foo/app/R.java
            err = writeResourceSymbols(bundle, assets, assets->getPackage(), true,
                    bundle->getBuildSharedLibrary());
        } else {
            const String8 customPkg(bundle->getCustomPackage());
            err = writeResourceSymbols(bundle, assets, customPkg, true,
                    bundle->getBuildSharedLibrary());
        }
        if (err < 0) {
            goto bail;
        }
        // If we have library files, we're going to write our R.java file into
        // the appropriate class directory for those libraries as well.
        // e.g. gen/com/foo/app/lib/R.java
        if (bundle->getExtraPackages() != NULL) {
            // Split on colon
            String8 libs(bundle->getExtraPackages());
            char* packageString = strtok(libs.lockBuffer(libs.length()), ":");
            while (packageString != NULL) {
                // Write the R.java file out with the correct package name
                err = writeResourceSymbols(bundle, assets, String8(packageString), true, false);
                if (err < 0) {
                    goto bail;
                }
                packageString = strtok(NULL, ":");
            }
            libs.unlockBuffer();
        }
    } else {
        err = writeResourceSymbols(bundle, assets, assets->getPackage(), false, false);
        if (err < 0) {
            goto bail;
        }
        err = writeResourceSymbols(bundle, assets, assets->getSymbolsPrivatePackage(), true, false);
        if (err < 0) {
            goto bail;
        }
    }

    // Write out the ProGuard file
    err = writeProguardFile(bundle, assets);
    if (err < 0) {
        goto bail;
    }

    // Write the apk
    if (outputAPKFile) {
        // Gather all resources and add them to the APK Builder. The builder will then
        // figure out which Split they belong in.
        err = addResourcesToBuilder(assets, builder);
        if (err != NO_ERROR) {
            goto bail;
        }

        const Vector<sp<ApkSplit> >& splits = builder->getSplits();
        const size_t numSplits = splits.size();
        for (size_t i = 0; i < numSplits; i++) {
            const sp<ApkSplit>& split = splits[i];
            String8 outputPath = buildApkName(String8(outputAPKFile), split);
            err = writeAPK(bundle, outputPath, split);
            if (err != NO_ERROR) {
                fprintf(stderr, "ERROR: packaging of '%s' failed\n", outputPath.string());
                goto bail;
            }
        }
    }

    // If we've been asked to generate a dependency file, we need to finish up here.
    // the writeResourceSymbols and writeAPK functions have already written the target
    // half of the dependency file, now we need to write the prerequisites. (files that
    // the R.java file or .ap_ file depend on)
    if (bundle->getGenDependencies()) {
        // Now that writeResourceSymbols or writeAPK has taken care of writing
        // the targets to our dependency file, we'll write the prereqs
        fp = fopen(dependencyFile, "a+");
        fprintf(fp, " : ");
        bool includeRaw = (outputAPKFile != NULL);
        err = writeDependencyPreReqs(bundle, assets, fp, includeRaw);
        // Also manually add the AndroidManifeset since it's not under res/ or assets/
        // and therefore was not added to our pathstores during slurping
        fprintf(fp, "%s \\\n", bundle->getAndroidManifestFile());
        fclose(fp);
    }

    retVal = 0;
bail:
    if (SourcePos::hasErrors()) {
        SourcePos::printErrors(stderr);
    }
    return retVal;
}

/*
 * Do PNG Crunching
 * PRECONDITIONS
 *  -S flag points to a source directory containing drawable* folders
 *  -C flag points to destination directory. The folder structure in the
 *     source directory will be mirrored to the destination (cache) directory
 *
 * POSTCONDITIONS
 *  Destination directory will be updated to match the PNG files in
 *  the source directory.
 */
int doCrunch(Bundle* bundle)
{
    fprintf(stdout, "Crunching PNG Files in ");
    fprintf(stdout, "source dir: %s\n", bundle->getResourceSourceDirs()[0]);
    fprintf(stdout, "To destination dir: %s\n", bundle->getCrunchedOutputDir());

    updatePreProcessedCache(bundle);

    return NO_ERROR;
}

/*
 * Do PNG Crunching on a single flag
 *  -i points to a single png file
 *  -o points to a single png output file
 */
int doSingleCrunch(Bundle* bundle)
{
    fprintf(stdout, "Crunching single PNG file: %s\n", bundle->getSingleCrunchInputFile());
    fprintf(stdout, "\tOutput file: %s\n", bundle->getSingleCrunchOutputFile());

    String8 input(bundle->getSingleCrunchInputFile());
    String8 output(bundle->getSingleCrunchOutputFile());

    if (preProcessImageToCache(bundle, input, output) != NO_ERROR) {
        // we can't return the status_t as it gets truncate to the lower 8 bits.
        return 42;
    }

    return NO_ERROR;
}

char CONSOLE_DATA[2925] = {
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 95, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 61, 63,
    86, 35, 40, 46, 46, 95, 95, 95, 95, 97, 97, 44, 32, 46, 124, 42, 33, 83,
    62, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 58, 46, 58, 59, 61, 59, 61, 81,
    81, 81, 81, 66, 96, 61, 61, 58, 46, 46, 46, 58, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 46, 61, 59, 59, 59, 58, 106, 81, 81, 81, 81, 102, 59, 61, 59,
    59, 61, 61, 61, 58, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 61, 59, 59,
    59, 58, 109, 81, 81, 81, 81, 61, 59, 59, 59, 59, 59, 58, 59, 59, 46, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 46, 61, 59, 59, 59, 60, 81, 81, 81, 81, 87,
    58, 59, 59, 59, 59, 59, 59, 61, 119, 44, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 46,
    47, 61, 59, 59, 58, 100, 81, 81, 81, 81, 35, 58, 59, 59, 59, 59, 59, 58,
    121, 81, 91, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 46, 109, 58, 59, 59, 61, 81, 81,
    81, 81, 81, 109, 58, 59, 59, 59, 59, 61, 109, 81, 81, 76, 46, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 41, 87, 59, 61, 59, 41, 81, 81, 81, 81, 81, 81, 59, 61, 59,
    59, 58, 109, 81, 81, 87, 39, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 60, 81, 91, 59,
    59, 61, 81, 81, 81, 81, 81, 87, 43, 59, 58, 59, 60, 81, 81, 81, 76, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 52, 91, 58, 45, 59, 87, 81, 81, 81, 81,
    70, 58, 58, 58, 59, 106, 81, 81, 81, 91, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 93, 40, 32, 46, 59, 100, 81, 81, 81, 81, 40, 58, 46, 46, 58, 100, 81,
    81, 68, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 46, 46, 46, 32, 46, 46, 46, 32, 46, 32, 46, 45, 91, 59, 61, 58, 109,
    81, 81, 81, 87, 46, 58, 61, 59, 60, 81, 81, 80, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32,
    32, 32, 32, 32, 32, 32, 32, 46, 46, 61, 59, 61, 61, 61, 59, 61, 61, 59,
    59, 59, 58, 58, 46, 46, 41, 58, 59, 58, 81, 81, 81, 81, 69, 58, 59, 59,
    60, 81, 81, 68, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 58, 59,
    61, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 61, 61, 46,
    61, 59, 93, 81, 81, 81, 81, 107, 58, 59, 58, 109, 87, 68, 96, 32, 32, 32,
    46, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 10, 32, 32, 32, 46, 60, 61, 61, 59, 59, 59, 59, 59, 59, 59, 59,
    59, 59, 59, 59, 59, 59, 59, 59, 59, 58, 58, 58, 115, 109, 68, 41, 36, 81,
    109, 46, 61, 61, 81, 69, 96, 46, 58, 58, 46, 58, 46, 46, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 46, 32, 95, 81,
    67, 61, 61, 58, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59,
    59, 59, 59, 59, 58, 68, 39, 61, 105, 61, 63, 81, 119, 58, 106, 80, 32, 58,
    61, 59, 59, 61, 59, 61, 59, 61, 46, 95, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 10, 32, 32, 36, 81, 109, 105, 59, 61, 59, 59, 59,
    59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 46, 58, 37,
    73, 108, 108, 62, 52, 81, 109, 34, 32, 61, 59, 59, 59, 59, 59, 59, 59, 59,
    59, 61, 59, 61, 61, 46, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10,
    32, 46, 45, 57, 101, 43, 43, 61, 61, 59, 59, 59, 59, 59, 59, 61, 59, 59,
    59, 59, 59, 59, 59, 59, 59, 58, 97, 46, 61, 108, 62, 126, 58, 106, 80, 96,
    46, 61, 61, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 61, 61,
    97, 103, 97, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 45, 46, 32,
    46, 32, 32, 32, 32, 32, 32, 32, 32, 45, 45, 45, 58, 59, 59, 59, 59, 61,
    119, 81, 97, 124, 105, 124, 124, 39, 126, 95, 119, 58, 61, 58, 59, 59, 59,
    59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 61, 119, 81, 81, 99, 32, 32,
    32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 58, 59, 59, 58, 106, 81, 81, 81, 109, 119,
    119, 119, 109, 109, 81, 81, 122, 58, 59, 59, 59, 59, 59, 59, 59, 59, 59,
    59, 59, 59, 59, 59, 58, 115, 81, 87, 81, 102, 32, 32, 32, 32, 32, 32, 10,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 61, 58, 59, 61, 81, 81, 81, 81, 81, 81, 87, 87, 81, 81, 81, 81,
    81, 58, 59, 59, 59, 59, 59, 59, 59, 59, 58, 45, 45, 45, 59, 59, 59, 41,
    87, 66, 33, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 58, 59, 59, 93, 81,
    81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 40, 58, 59, 59, 59, 58,
    45, 32, 46, 32, 32, 32, 32, 32, 46, 32, 126, 96, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 58, 61, 59, 58, 81, 81, 81, 81, 81, 81, 81, 81,
    81, 81, 81, 81, 81, 40, 58, 59, 59, 59, 58, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 58,
    59, 59, 58, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 40, 58,
    59, 59, 59, 46, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 58, 61, 59, 60, 81, 81, 81, 81,
    81, 81, 81, 81, 81, 81, 81, 81, 81, 59, 61, 59, 59, 61, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 58, 59, 59, 93, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81,
    81, 81, 40, 59, 59, 59, 59, 32, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 58, 61, 58, 106,
    81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 76, 58, 59, 59, 59,
    32, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 61, 58, 58, 81, 81, 81, 81, 81, 81, 81, 81,
    81, 81, 81, 81, 81, 87, 58, 59, 59, 59, 59, 32, 46, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    58, 59, 61, 41, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 81, 87, 59,
    61, 58, 59, 59, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 58, 61, 58, 61, 81, 81, 81,
    81, 81, 81, 81, 81, 81, 81, 81, 81, 107, 58, 59, 59, 59, 59, 58, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 58, 59, 59, 58, 51, 81, 81, 81, 81, 81, 81, 81, 81, 81,
    81, 102, 94, 59, 59, 59, 59, 59, 61, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 58, 61, 59,
    59, 59, 43, 63, 36, 81, 81, 81, 87, 64, 86, 102, 58, 59, 59, 59, 59, 59,
    59, 59, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 46, 61, 59, 59, 59, 59, 59, 59, 59, 43, 33,
    58, 126, 126, 58, 59, 59, 59, 59, 59, 59, 59, 59, 59, 59, 32, 46, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 46,
    61, 59, 59, 59, 58, 45, 58, 61, 59, 58, 58, 58, 61, 59, 59, 59, 59, 59,
    59, 59, 59, 59, 59, 59, 59, 58, 32, 46, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 46, 61, 59, 59, 59, 59, 59, 58, 95,
    32, 45, 61, 59, 61, 59, 59, 59, 59, 59, 59, 59, 45, 58, 59, 59, 59, 59,
    61, 58, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 58, 61, 59, 59, 59, 59, 59, 61, 59, 61, 46, 46, 32, 45, 45, 45,
    59, 58, 45, 45, 46, 58, 59, 59, 59, 59, 59, 59, 61, 46, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 46, 58, 59, 59, 59, 59,
    59, 59, 59, 59, 59, 61, 59, 46, 32, 32, 46, 32, 46, 32, 58, 61, 59, 59,
    59, 59, 59, 59, 59, 59, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 45, 59, 59, 59, 59, 59, 59, 59, 59, 58, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 61, 59, 59, 59, 59, 59, 59, 59, 58, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    46, 61, 59, 59, 59, 59, 59, 59, 59, 32, 46, 32, 32, 32, 32, 32, 32, 61,
    46, 61, 59, 59, 59, 59, 59, 59, 58, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 61, 59, 59, 59, 59, 59, 59,
    59, 59, 32, 46, 32, 32, 32, 32, 32, 32, 32, 46, 61, 58, 59, 59, 59, 59,
    59, 58, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 58, 59, 59, 59, 59, 59, 59, 59, 59, 46, 46, 32, 32, 32,
    32, 32, 32, 32, 61, 59, 59, 59, 59, 59, 59, 59, 45, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 46, 32, 45, 61,
    59, 59, 59, 59, 59, 58, 32, 46, 32, 32, 32, 32, 32, 32, 32, 58, 59, 59,
    59, 59, 59, 58, 45, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 45, 45, 45, 45, 32, 46, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 45, 61, 59, 58, 45, 45, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    10, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 46, 32, 32, 46, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
    32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 10
  };
