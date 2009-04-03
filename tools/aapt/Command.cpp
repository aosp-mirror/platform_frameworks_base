//
// Copyright 2006 The Android Open Source Project
//
// Android Asset Packaging Tool main entry point.
//
#include "Main.h"
#include "Bundle.h"
#include "ResourceTable.h"
#include "XMLNode.h"

#include <utils.h>
#include <utils/ZipFile.h>

#include <fcntl.h>
#include <errno.h>

using namespace android;

/*
 * Show version info.  All the cool kids do it.
 */
int doVersion(Bundle* bundle)
{
    if (bundle->getFileSpecCount() != 0)
        printf("(ignoring extra arguments)\n");
    printf("Android Asset Packaging Tool, v0.2\n");

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
        if (result == NAME_NOT_FOUND)
            fprintf(stderr, "ERROR: '%s' not found\n", fileName);
        else if (result == PERMISSION_DENIED)
            fprintf(stderr, "ERROR: '%s' access denied\n", fileName);
        else
            fprintf(stderr, "ERROR: failed opening '%s' as Zip file\n",
                fileName);
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
    if (okayToCreate)
        flags |= ZipFile::kOpenCreate;

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
    if (method == ZipEntry::kCompressStored)
        return "Stored";
    else if (method == ZipEntry::kCompressDeflated)
        return "Deflated";
    else
        return "Unknown";
}

/*
 * Return the percent reduction in size (0% == no compression).
 */
int calcPercent(long uncompressedLen, long compressedLen)
{
    if (!uncompressedLen)
        return 0;
    else
        return (int) (100.0 - (compressedLen * 100.0) / uncompressedLen + 0.5);
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
    if (zip == NULL)
        goto bail;

    int count, i;

    if (bundle->getVerbose()) {
        printf("Archive:  %s\n", zipFileName);
        printf(
            " Length   Method    Size  Ratio   Date   Time   CRC-32    Name\n");
        printf(
            "--------  ------  ------- -----   ----   ----   ------    ----\n");
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

            printf("%8ld  %-7.7s %7ld %3d%%  %s  %08lx  %s\n",
                (long) entry->getUncompressedLen(),
                compressionName(entry->getCompressionMethod()),
                (long) entry->getCompressedLen(),
                calcPercent(entry->getUncompressedLen(),
                            entry->getCompressedLen()),
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
            printf("\nResource table:\n");
            res.print();
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

static String8 getAttribute(const ResXMLTree& tree, const char* ns,
                            const char* attr, String8* outError)
{
    ssize_t idx = tree.indexOfAttribute(ns, attr);
    if (idx < 0) {
        return String8();
    }
    Res_value value;
    if (tree.getAttributeValue(idx, &value) != NO_ERROR) {
        if (value.dataType != Res_value::TYPE_STRING) {
            if (outError != NULL) *outError = "attribute is not a string value";
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
            if (outError != NULL) *outError = "attribute is not a string value";
            return String8();
        }
    }
    size_t len;
    const uint16_t* str = tree.getAttributeStringValue(idx, &len);
    return str ? String8(str, len) : String8();
}

static int32_t getIntegerAttribute(const ResXMLTree& tree, uint32_t attrRes, String8* outError)
{
    ssize_t idx = indexOfAttribute(tree, attrRes);
    if (idx < 0) {
        return -1;
    }
    Res_value value;
    if (tree.getAttributeValue(idx, &value) != NO_ERROR) {
        if (value.dataType != Res_value::TYPE_INT_DEC) {
            if (outError != NULL) *outError = "attribute is not an integer value";
            return -1;
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
            if (outError != NULL) *outError = "attribute is not a string value";
            return String8();
        }
    }
    size_t len;
    const Res_value* value2 = &value;
    const char16_t* str = const_cast<ResTable*>(resTable)->valueToString(value2, 0, NULL, &len);
    return str ? String8(str, len) : String8();
}

// These are attribute resource constants for the platform, as found
// in android.R.attr
enum {
    NAME_ATTR = 0x01010003,
    VERSION_CODE_ATTR = 0x0101021b,
    VERSION_NAME_ATTR = 0x0101021c,
    LABEL_ATTR = 0x01010001,
    ICON_ATTR = 0x01010002,
    MIN_SDK_VERSION_ATTR = 0x0101020c
};

const char *getComponentName(String8 &pkgName, String8 &componentName) {
    ssize_t idx = componentName.find(".");
    String8 retStr(pkgName);
    if (idx == 0) {
        retStr += componentName;
    } else if (idx < 0) {
        retStr += ".";
        retStr += componentName;
    } else {
        return componentName.string();
    }
    return retStr.string();
}

/*
 * Handle the "dump" command, to extract select data from an archive.
 */
int doDump(Bundle* bundle)
{
    status_t result = UNKNOWN_ERROR;
    Asset* asset = NULL;

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
    if (!assets.addAssetPath(String8(filename), NULL)) {
        fprintf(stderr, "ERROR: dump failed because assets could not be loaded\n");
        return 1;
    }

    const ResTable& res = assets.getResources(false);
    if (&res == NULL) {
        fprintf(stderr, "ERROR: dump failed because no resource table was found\n");
        goto bail;
    }

    if (strcmp("resources", option) == 0) {
        res.print();

    } else if (strcmp("xmltree", option) == 0) {
        if (bundle->getFileSpecCount() < 3) {
            fprintf(stderr, "ERROR: no dump xmltree resource file specified\n");
            goto bail;
        }

        for (int i=2; i<bundle->getFileSpecCount(); i++) {
            const char* resname = bundle->getFileSpecEntry(i);
            ResXMLTree tree;
            asset = assets.openNonAsset(resname, Asset::ACCESS_BUFFER);
            if (asset == NULL) {
                fprintf(stderr, "ERROR: dump failed because resource %p found\n", resname);
                goto bail;
            }

            if (tree.setTo(asset->getBuffer(true),
                           asset->getLength()) != NO_ERROR) {
                fprintf(stderr, "ERROR: Resource %s is corrupt\n", resname);
                goto bail;
            }
            tree.restart();
            printXMLBlock(&tree);
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
            ResXMLTree tree;
            asset = assets.openNonAsset(resname, Asset::ACCESS_BUFFER);
            if (asset == NULL) {
                fprintf(stderr, "ERROR: dump failed because resource %p found\n", resname);
                goto bail;
            }

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
        ResXMLTree tree;
        asset = assets.openNonAsset("AndroidManifest.xml",
                                            Asset::ACCESS_BUFFER);
        if (asset == NULL) {
            fprintf(stderr, "ERROR: dump failed because no AndroidManifest.xml found\n");
            goto bail;
        }

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
                String8 tag(tree.getElementName(&len));
                //printf("Depth %d tag %s\n", depth, tag.string());
                if (depth == 1) {
                    if (tag != "manifest") {
                        fprintf(stderr, "ERROR: manifest does not start with <manifest> tag\n");
                        goto bail;
                    }
                    String8 pkg = getAttribute(tree, NULL, "package", NULL);
                    printf("package: %s\n", pkg.string());
                } else if (depth == 2 && tag == "permission") {
                    String8 error;
                    String8 name = getAttribute(tree, NAME_ATTR, &error);
                    if (error != "") {
                        fprintf(stderr, "ERROR: %s\n", error.string());
                        goto bail;
                    }
                    printf("permission: %s\n", name.string());
                } else if (depth == 2 && tag == "uses-permission") {
                    String8 error;
                    String8 name = getAttribute(tree, NAME_ATTR, &error);
                    if (error != "") {
                        fprintf(stderr, "ERROR: %s\n", error.string());
                        goto bail;
                    }
                    printf("uses-permission: %s\n", name.string());
                }
            }
        } else if (strcmp("badging", option) == 0) {
            size_t len;
            ResXMLTree::event_code_t code;
            int depth = 0;
            String8 error;
            bool withinActivity = false;
            bool isMainActivity = false;
            bool isLauncherActivity = false;
            bool withinApplication = false;
            bool withinReceiver = false;
            String8 pkg;
            String8 activityName;
            String8 activityLabel;
            String8 activityIcon;
            String8 receiverName;
            while ((code=tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
                if (code == ResXMLTree::END_TAG) {
                    depth--;
                    continue;
                }
                if (code != ResXMLTree::START_TAG) {
                    continue;
                }
                depth++;
                String8 tag(tree.getElementName(&len));
                //printf("Depth %d tag %s\n", depth, tag.string());
                if (depth == 1) {
                    if (tag != "manifest") {
                        fprintf(stderr, "ERROR: manifest does not start with <manifest> tag\n");
                        goto bail;
                    }
                    pkg = getAttribute(tree, NULL, "package", NULL);
                    printf("package: name='%s' ", pkg.string());
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
                    String8 versionName = getAttribute(tree, VERSION_NAME_ATTR, &error);
                    if (error != "") {
                        fprintf(stderr, "ERROR getting 'android:versionName' attribute: %s\n", error.string());
                        goto bail;
                    }
                    printf("versionName='%s'\n", versionName.string());
                } else if (depth == 2) {
                    withinApplication = false;
                    if (tag == "application") {
                        withinApplication = true;
                        String8 label = getResolvedAttribute(&res, tree, LABEL_ATTR, &error);
                         if (error != "") {
                             fprintf(stderr, "ERROR getting 'android:label' attribute: %s\n", error.string());
                             goto bail;
                        }
                        printf("application: label='%s' ", label.string());
                        String8 icon = getResolvedAttribute(&res, tree, ICON_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:icon' attribute: %s\n", error.string());
                            goto bail;
                        }
                        printf("icon='%s'\n", icon.string());
                    } else if (tag == "uses-sdk") {
                        int32_t sdkVersion = getIntegerAttribute(tree, MIN_SDK_VERSION_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:minSdkVersion' attribute: %s\n", error.string());
                            goto bail;
                        }
                        if (sdkVersion != -1) {
                            printf("sdkVersion:'%d'\n", sdkVersion);
                        }
                    }
                } else if (depth == 3 && withinApplication) {
                    withinActivity = false;
                    withinReceiver = false;
                    if(tag == "activity") {
                        withinActivity = true;
                        activityName = getAttribute(tree, NAME_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:name' attribute: %s\n", error.string());
                            goto bail;
                        }

                        activityLabel = getResolvedAttribute(&res, tree, LABEL_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:label' attribute: %s\n", error.string());
                            goto bail;
                        }

                        activityIcon = getResolvedAttribute(&res, tree, ICON_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:icon' attribute: %s\n", error.string());
                            goto bail;
                        }
                    } else if (tag == "uses-library") {
                        String8 libraryName = getAttribute(tree, NAME_ATTR, &error);
                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:name' attribute for uses-library: %s\n", error.string());
                            goto bail;
                        }
                        printf("uses-library:'%s'\n", libraryName.string());
                    } else if (tag == "receiver") {
                        withinReceiver = true;
                        receiverName = getAttribute(tree, NAME_ATTR, &error);

                        if (error != "") {
                            fprintf(stderr, "ERROR getting 'android:name' attribute for receiver: %s\n", error.string());
                            goto bail;
                        }
                    }
                } else if (depth == 5) {
                        if (withinActivity) {
                            if (tag == "action") {
                                //printf("LOG: action tag\n");
                                String8 action = getAttribute(tree, NAME_ATTR, &error);
                                if (error != "") {
                                    fprintf(stderr, "ERROR getting 'android:name' attribute: %s\n", error.string());
                                    goto bail;
                                }
                                if (action == "android.intent.action.MAIN") {
                                    isMainActivity = true;
                                    //printf("LOG: isMainActivity==true\n");
                                }
                        } else if (tag == "category") {
                            String8 category = getAttribute(tree, NAME_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'name' attribute: %s\n", error.string());
                                goto bail;
                            }
                            if (category == "android.intent.category.LAUNCHER") {
                                isLauncherActivity = true;
                                //printf("LOG: isLauncherActivity==true\n");
                            }
                        }
                    } else if (withinReceiver) {
                        if (tag == "action") {
                            String8 action = getAttribute(tree, NAME_ATTR, &error);
                            if (error != "") {
                                fprintf(stderr, "ERROR getting 'android:name' attribute for receiver: %s\n", error.string());
                                goto bail;
                            }
                            if (action == "android.appwidget.action.APPWIDGET_UPDATE") {
                                const char *rName = getComponentName(pkg, receiverName);
                                if (rName != NULL) {
                                    printf("gadget-receiver:'%s/%s'\n", pkg.string(), rName);
                                }
                            }
                        }
                    }
                }

                if (depth < 2) {
                    withinApplication = false;
                }
                if (depth < 3) {
                    //if (withinActivity) printf("LOG: withinActivity==false\n");
                    withinActivity = false;
                    withinReceiver = false;
                }

                if (depth < 5) {
                    //if (isMainActivity) printf("LOG: isMainActivity==false\n");
                    //if (isLauncherActivity) printf("LOG: isLauncherActivity==false\n");
                    isMainActivity = false;
                    isLauncherActivity = false;
                }

                if (withinActivity && isMainActivity && isLauncherActivity) {
                    printf("launchable activity:");
                    const char *aName = getComponentName(pkg, activityName);
                    if (aName != NULL) {
                        printf(" name='%s'", aName);
                    }
                    printf("label='%s' icon='%s'\n",
                           activityLabel.string(),
                           activityIcon.string());
                }
            }
            printf("locales:");
            Vector<String8> locales;
            res.getLocales(&locales);
            const size_t N = locales.size();
            for (size_t i=0; i<N; i++) {
                const char* localeStr =  locales[i].string();
                if (localeStr == NULL || strlen(localeStr) == 0) {
                    localeStr = "--_--";
                }
                printf(" '%s'", localeStr);
            }
            printf("\n");
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
            printf(" '%s'...\n", fileName);
            result = zip->add(fileName, bundle->getCompressionMethod(), NULL);
        }
        if (result != NO_ERROR) {
            fprintf(stderr, "Unable to add '%s' to '%s'", bundle->getFileSpecEntry(i), zipFileName);
            if (result == NAME_NOT_FOUND)
                fprintf(stderr, ": file not found\n");
            else if (result == ALREADY_EXISTS)
                fprintf(stderr, ": already exists in archive\n");
            else
                fprintf(stderr, "\n");
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

    // -c zz_ZZ means do pseudolocalization
    ResourceFilter filter;
    err = filter.parse(bundle->getConfigurations());
    if (err != NO_ERROR) {
        goto bail;
    }
    if (filter.containsPseudo()) {
        bundle->setPseudolocalize(true);
    }

    N = bundle->getFileSpecCount();
    if (N < 1 && bundle->getResourceSourceDirs().size() == 0 && bundle->getJarFiles().size() == 0
            && bundle->getAndroidManifestFile() == NULL && bundle->getAssetSourceDir() == NULL) {
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
    err = assets->slurpFromArgs(bundle);
    if (err < 0) {
        goto bail;
    }

    if (bundle->getVerbose()) {
        assets->print();
    }

    // If they asked for any files that need to be compiled, do so.
    if (bundle->getResourceSourceDirs().size() || bundle->getAndroidManifestFile()) {
        err = buildResources(bundle, assets);
        if (err != 0) {
            goto bail;
        }
    }

    // At this point we've read everything and processed everything.  From here
    // on out it's just writing output files.
    if (SourcePos::hasErrors()) {
        goto bail;
    }

    // Write out R.java constants
    if (assets->getPackage() == assets->getSymbolsPrivatePackage()) {
        err = writeResourceSymbols(bundle, assets, assets->getPackage(), true);
        if (err < 0) {
            goto bail;
        }
    } else {
        err = writeResourceSymbols(bundle, assets, assets->getPackage(), false);
        if (err < 0) {
            goto bail;
        }
        err = writeResourceSymbols(bundle, assets, assets->getSymbolsPrivatePackage(), true);
        if (err < 0) {
            goto bail;
        }
    }

    // Write the apk
    if (outputAPKFile) {
        err = writeAPK(bundle, assets, String8(outputAPKFile));
        if (err != NO_ERROR) {
            fprintf(stderr, "ERROR: packaging of '%s' failed\n", outputAPKFile);
            goto bail;
        }
    }

    retVal = 0;
bail:
    if (SourcePos::hasErrors()) {
        SourcePos::printErrors(stderr);
    }
    return retVal;
}
