//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//
#include "AaptAssets.h"
#include "AaptUtil.h"
#include "AaptXml.h"
#include "CacheUpdater.h"
#include "CrunchCache.h"
#include "FileFinder.h"
#include "Images.h"
#include "IndentPrinter.h"
#include "Main.h"
#include "ResourceTable.h"
#include "StringPool.h"
#include "Symbol.h"
#include "Utils.h"
#include "WorkQueue.h"
#include "XMLNode.h"

#include <androidfw/PathUtils.h>

#include <algorithm>

// STATUST: mingw does seem to redefine UNKNOWN_ERROR from our enum value, so a cast is necessary.

#if !defined(_WIN32)
#  define STATUST(x) x
#else
#  define STATUST(x) (status_t)x
#endif

// Set to true for noisy debug output.
static const bool kIsDebug = false;

// Number of threads to use for preprocessing images.
static const size_t MAX_THREADS = 4;

// ==========================================================================
// ==========================================================================
// ==========================================================================

class PackageInfo
{
public:
    PackageInfo()
    {
    }
    ~PackageInfo()
    {
    }

    status_t parsePackage(const sp<AaptGroup>& grp);
};

// ==========================================================================
// ==========================================================================
// ==========================================================================

String8 parseResourceName(const String8& leaf)
{
    const char* firstDot = strchr(leaf.c_str(), '.');
    const char* str = leaf.c_str();

    if (firstDot) {
        return String8(str, firstDot-str);
    } else {
        return String8(str);
    }
}

ResourceTypeSet::ResourceTypeSet()
    :RefBase(),
     KeyedVector<String8,sp<AaptGroup> >()
{
}

FilePathStore::FilePathStore()
    :RefBase(),
     Vector<String8>()
{
}

class ResourceDirIterator
{
public:
    ResourceDirIterator(const sp<ResourceTypeSet>& set, const String8& resType)
        : mResType(resType), mSet(set), mSetPos(0), mGroupPos(0)
    {
        memset(&mParams, 0, sizeof(ResTable_config));
    }

    inline const sp<AaptGroup>& getGroup() const { return mGroup; }
    inline const sp<AaptFile>& getFile() const { return mFile; }

    inline const String8& getBaseName() const { return mBaseName; }
    inline const String8& getLeafName() const { return mLeafName; }
    inline String8 getPath() const { return mPath; }
    inline const ResTable_config& getParams() const { return mParams; }

    enum {
        EOD = 1
    };

    ssize_t next()
    {
        while (true) {
            sp<AaptGroup> group;
            sp<AaptFile> file;

            // Try to get next file in this current group.
            if (mGroup != NULL && mGroupPos < mGroup->getFiles().size()) {
                group = mGroup;
                file = group->getFiles().valueAt(mGroupPos++);

            // Try to get the next group/file in this directory
            } else if (mSetPos < mSet->size()) {
                mGroup = group = mSet->valueAt(mSetPos++);
                if (group->getFiles().size() < 1) {
                    continue;
                }
                file = group->getFiles().valueAt(0);
                mGroupPos = 1;

            // All done!
            } else {
                return EOD;
            }

            mFile = file;

            String8 leaf(group->getLeaf());
            mLeafName = String8(leaf);
            mParams = file->getGroupEntry().toParams();
            if (kIsDebug) {
                printf("Dir %s: mcc=%d mnc=%d lang=%c%c cnt=%c%c orient=%d ui=%d density=%d touch=%d key=%d inp=%d nav=%d\n",
                        group->getPath().c_str(), mParams.mcc, mParams.mnc,
                        mParams.language[0] ? mParams.language[0] : '-',
                        mParams.language[1] ? mParams.language[1] : '-',
                        mParams.country[0] ? mParams.country[0] : '-',
                        mParams.country[1] ? mParams.country[1] : '-',
                        mParams.orientation, mParams.uiMode,
                        mParams.density, mParams.touchscreen, mParams.keyboard,
                        mParams.inputFlags, mParams.navigation);
            }
            mPath = "res";
            appendPath(mPath, file->getGroupEntry().toDirName(mResType));
            appendPath(mPath, leaf);
            mBaseName = parseResourceName(leaf);
            if (mBaseName == "") {
                fprintf(stderr, "Error: malformed resource filename %s\n",
                        file->getPrintableSource().c_str());
                return UNKNOWN_ERROR;
            }

            if (kIsDebug) {
                printf("file name=%s\n", mBaseName.c_str());
            }

            return NO_ERROR;
        }
    }

private:
    String8 mResType;

    const sp<ResourceTypeSet> mSet;
    size_t mSetPos;

    sp<AaptGroup> mGroup;
    size_t mGroupPos;

    sp<AaptFile> mFile;
    String8 mBaseName;
    String8 mLeafName;
    String8 mPath;
    ResTable_config mParams;
};

class AnnotationProcessor {
public:
    AnnotationProcessor() : mDeprecated(false), mSystemApi(false) { }

    void preprocessComment(String8& comment) {
        if (comment.size() > 0) {
            if (comment.contains("@deprecated")) {
                mDeprecated = true;
            }
            if (comment.removeAll("@SystemApi")) {
                mSystemApi = true;
            }
        }
    }

    void printAnnotations(FILE* fp, const char* indentStr) {
        if (mDeprecated) {
            fprintf(fp, "%s@Deprecated\n", indentStr);
        }
        if (mSystemApi) {
            fprintf(fp, "%s@android.annotation.SystemApi\n", indentStr);
        }
    }

private:
    bool mDeprecated;
    bool mSystemApi;
};

// ==========================================================================
// ==========================================================================
// ==========================================================================

bool isValidResourceType(const String8& type)
{
    return type == "anim" || type == "animator" || type == "interpolator"
        || type == "transition" || type == "font"
        || type == "drawable" || type == "layout"
        || type == "values" || type == "xml" || type == "raw"
        || type == "color" || type == "menu" || type == "mipmap";
}

static status_t parsePackage(Bundle* bundle, const sp<AaptAssets>& assets,
    const sp<AaptGroup>& grp)
{
    if (grp->getFiles().size() != 1) {
        fprintf(stderr, "warning: Multiple AndroidManifest.xml files found, using %s\n",
                grp->getFiles().valueAt(0)->getPrintableSource().c_str());
    }

    sp<AaptFile> file = grp->getFiles().valueAt(0);

    ResXMLTree block;
    status_t err = parseXMLResource(file, &block);
    if (err != NO_ERROR) {
        return err;
    }
    //printXMLBlock(&block);

    ResXMLTree::event_code_t code;
    while ((code=block.next()) != ResXMLTree::START_TAG
           && code != ResXMLTree::END_DOCUMENT
           && code != ResXMLTree::BAD_DOCUMENT) {
    }

    size_t len;
    if (code != ResXMLTree::START_TAG) {
        fprintf(stderr, "%s:%d: No start tag found\n",
                file->getPrintableSource().c_str(), block.getLineNumber());
        return UNKNOWN_ERROR;
    }
    if (strcmp16(block.getElementName(&len), String16("manifest").c_str()) != 0) {
        fprintf(stderr, "%s:%d: Invalid start tag %s, expected <manifest>\n",
                file->getPrintableSource().c_str(), block.getLineNumber(),
                String8(block.getElementName(&len)).c_str());
        return UNKNOWN_ERROR;
    }

    ssize_t nameIndex = block.indexOfAttribute(NULL, "package");
    if (nameIndex < 0) {
        fprintf(stderr, "%s:%d: <manifest> does not have package attribute.\n",
                file->getPrintableSource().c_str(), block.getLineNumber());
        return UNKNOWN_ERROR;
    }

    assets->setPackage(String8(block.getAttributeStringValue(nameIndex, &len)));

    ssize_t revisionCodeIndex = block.indexOfAttribute(RESOURCES_ANDROID_NAMESPACE, "revisionCode");
    if (revisionCodeIndex >= 0) {
        bundle->setRevisionCode(String8(block.getAttributeStringValue(revisionCodeIndex, &len)).c_str());
    }

    String16 uses_sdk16("uses-sdk");
    while ((code=block.next()) != ResXMLTree::END_DOCUMENT
           && code != ResXMLTree::BAD_DOCUMENT) {
        if (code == ResXMLTree::START_TAG) {
            if (strcmp16(block.getElementName(&len), uses_sdk16.c_str()) == 0) {
                ssize_t minSdkIndex = block.indexOfAttribute(RESOURCES_ANDROID_NAMESPACE,
                                                             "minSdkVersion");
                if (minSdkIndex >= 0) {
                    const char16_t* minSdk16 = block.getAttributeStringValue(minSdkIndex, &len);
                    const char* minSdk8 = strdup(String8(minSdk16).c_str());
                    bundle->setManifestMinSdkVersion(minSdk8);
                }
            }
        }
    }

    return NO_ERROR;
}

// ==========================================================================
// ==========================================================================
// ==========================================================================

static status_t makeFileResources(Bundle* bundle, const sp<AaptAssets>& assets,
                                  ResourceTable* table,
                                  const sp<ResourceTypeSet>& set,
                                  const char* resType)
{
    String8 type8(resType);
    String16 type16(resType);

    bool hasErrors = false;

    ResourceDirIterator it(set, String8(resType));
    ssize_t res;
    while ((res=it.next()) == NO_ERROR) {
        if (bundle->getVerbose()) {
            printf("    (new resource id %s from %s)\n",
                   it.getBaseName().c_str(), it.getFile()->getPrintableSource().c_str());
        }
        String16 baseName(it.getBaseName());
        const char16_t* str = baseName.c_str();
        const char16_t* const end = str + baseName.size();
        while (str < end) {
            if (!((*str >= 'a' && *str <= 'z')
                    || (*str >= '0' && *str <= '9')
                    || *str == '_' || *str == '.')) {
                fprintf(stderr, "%s: Invalid file name: must contain only [a-z0-9_.]\n",
                        it.getPath().c_str());
                hasErrors = true;
            }
            str++;
        }
        String8 resPath = it.getPath();
        convertToResPath(resPath);
        status_t result = table->addEntry(SourcePos(it.getPath(), 0),
                        String16(assets->getPackage()),
                        type16,
                        baseName,
                        String16(resPath),
                        NULL,
                        &it.getParams());
        if (result != NO_ERROR) {
            hasErrors = true;
        } else {
            assets->addResource(it.getLeafName(), resPath, it.getFile(), type8);
        }
    }

    return hasErrors ? STATUST(UNKNOWN_ERROR) : NO_ERROR;
}

class PreProcessImageWorkUnit : public WorkQueue::WorkUnit {
public:
    PreProcessImageWorkUnit(const Bundle* bundle, const sp<AaptAssets>& assets,
            const sp<AaptFile>& file, volatile bool* hasErrors) :
            mBundle(bundle), mAssets(assets), mFile(file), mHasErrors(hasErrors) {
    }

    virtual bool run() {
        status_t status = preProcessImage(mBundle, mAssets, mFile, NULL);
        if (status) {
            *mHasErrors = true;
        }
        return true; // continue even if there are errors
    }

private:
    const Bundle* mBundle;
    sp<AaptAssets> mAssets;
    sp<AaptFile> mFile;
    volatile bool* mHasErrors;
};

static status_t preProcessImages(const Bundle* bundle, const sp<AaptAssets>& assets,
                          const sp<ResourceTypeSet>& set, const char* type)
{
    volatile bool hasErrors = false;
    ssize_t res = NO_ERROR;
    if (bundle->getUseCrunchCache() == false) {
        WorkQueue wq(MAX_THREADS, false);
        ResourceDirIterator it(set, String8(type));
        while ((res=it.next()) == NO_ERROR) {
            PreProcessImageWorkUnit* w = new PreProcessImageWorkUnit(
                    bundle, assets, it.getFile(), &hasErrors);
            status_t status = wq.schedule(w);
            if (status) {
                fprintf(stderr, "preProcessImages failed: schedule() returned %d\n", status);
                hasErrors = true;
                delete w;
                break;
            }
        }
        status_t status = wq.finish();
        if (status) {
            fprintf(stderr, "preProcessImages failed: finish() returned %d\n", status);
            hasErrors = true;
        }
    }
    return (hasErrors || (res < NO_ERROR)) ? STATUST(UNKNOWN_ERROR) : NO_ERROR;
}

static void collect_files(const sp<AaptDir>& dir,
        KeyedVector<String8, sp<ResourceTypeSet> >* resources)
{
    const DefaultKeyedVector<String8, sp<AaptGroup> >& groups = dir->getFiles();
    int N = groups.size();
    for (int i=0; i<N; i++) {
        const String8& leafName = groups.keyAt(i);
        const sp<AaptGroup>& group = groups.valueAt(i);

        const DefaultKeyedVector<AaptGroupEntry, sp<AaptFile> >& files
                = group->getFiles();

        if (files.size() == 0) {
            continue;
        }

        String8 resType = files.valueAt(0)->getResourceType();

        ssize_t index = resources->indexOfKey(resType);

        if (index < 0) {
            sp<ResourceTypeSet> set = new ResourceTypeSet();
            if (kIsDebug) {
                printf("Creating new resource type set for leaf %s with group %s (%p)\n",
                        leafName.c_str(), group->getPath().c_str(), group.get());
            }
            set->add(leafName, group);
            resources->add(resType, set);
        } else {
            const sp<ResourceTypeSet>& set = resources->valueAt(index);
            index = set->indexOfKey(leafName);
            if (index < 0) {
                if (kIsDebug) {
                    printf("Adding to resource type set for leaf %s group %s (%p)\n",
                            leafName.c_str(), group->getPath().c_str(), group.get());
                }
                set->add(leafName, group);
            } else {
                sp<AaptGroup> existingGroup = set->valueAt(index);
                if (kIsDebug) {
                    printf("Extending to resource type set for leaf %s group %s (%p)\n",
                            leafName.c_str(), group->getPath().c_str(), group.get());
                }
                for (size_t j=0; j<files.size(); j++) {
                    if (kIsDebug) {
                        printf("Adding file %s in group %s resType %s\n",
                                files.valueAt(j)->getSourceFile().c_str(),
                                files.keyAt(j).toDirName(String8()).c_str(),
                                resType.c_str());
                    }
                    existingGroup->addFile(files.valueAt(j));
                }
            }
        }
    }
}

static void collect_files(const sp<AaptAssets>& ass,
        KeyedVector<String8, sp<ResourceTypeSet> >* resources)
{
    const Vector<sp<AaptDir> >& dirs = ass->resDirs();
    int N = dirs.size();

    for (int i=0; i<N; i++) {
        const sp<AaptDir>& d = dirs.itemAt(i);
        if (kIsDebug) {
            printf("Collecting dir #%d %p: %s, leaf %s\n", i, d.get(), d->getPath().c_str(),
                    d->getLeaf().c_str());
        }
        collect_files(d, resources);

        // don't try to include the res dir
        if (kIsDebug) {
            printf("Removing dir leaf %s\n", d->getLeaf().c_str());
        }
        ass->removeDir(d->getLeaf());
    }
}

enum {
    ATTR_OKAY = -1,
    ATTR_NOT_FOUND = -2,
    ATTR_LEADING_SPACES = -3,
    ATTR_TRAILING_SPACES = -4
};
static int validateAttr(const String8& path, const ResTable& table,
        const ResXMLParser& parser,
        const char* ns, const char* attr, const char* validChars, bool required)
{
    size_t len;

    ssize_t index = parser.indexOfAttribute(ns, attr);
    const char16_t* str;
    Res_value value;
    if (index >= 0 && parser.getAttributeValue(index, &value) >= 0) {
        const ResStringPool* pool = &parser.getStrings();
        if (value.dataType == Res_value::TYPE_REFERENCE) {
            uint32_t specFlags = 0;
            int strIdx;
            if ((strIdx=table.resolveReference(&value, 0x10000000, NULL, &specFlags)) < 0) {
                fprintf(stderr, "%s:%d: Tag <%s> attribute %s references unknown resid 0x%08x.\n",
                        path.c_str(), parser.getLineNumber(),
                        String8(parser.getElementName(&len)).c_str(), attr,
                        value.data);
                return ATTR_NOT_FOUND;
            }
            
            pool = table.getTableStringBlock(strIdx);
            #if 0
            if (pool != NULL) {
                str = pool->stringAt(value.data, &len);
            }
            printf("***** RES ATTR: %s specFlags=0x%x strIdx=%d: %s\n", attr,
                    specFlags, strIdx, str != NULL ? String8(str).c_str() : "???");
            #endif
            if ((specFlags&~ResTable_typeSpec::SPEC_PUBLIC) != 0 && false) {
                fprintf(stderr, "%s:%d: Tag <%s> attribute %s varies by configurations 0x%x.\n",
                        path.c_str(), parser.getLineNumber(),
                        String8(parser.getElementName(&len)).c_str(), attr,
                        specFlags);
                return ATTR_NOT_FOUND;
            }
        }
        if (value.dataType == Res_value::TYPE_STRING) {
            if (pool == NULL) {
                fprintf(stderr, "%s:%d: Tag <%s> attribute %s has no string block.\n",
                        path.c_str(), parser.getLineNumber(),
                        String8(parser.getElementName(&len)).c_str(), attr);
                return ATTR_NOT_FOUND;
            }
            if ((str = UnpackOptionalString(pool->stringAt(value.data), &len)) == NULL) {
                fprintf(stderr, "%s:%d: Tag <%s> attribute %s has corrupt string value.\n",
                        path.c_str(), parser.getLineNumber(),
                        String8(parser.getElementName(&len)).c_str(), attr);
                return ATTR_NOT_FOUND;
            }
        } else {
            fprintf(stderr, "%s:%d: Tag <%s> attribute %s has invalid type %d.\n",
                    path.c_str(), parser.getLineNumber(),
                    String8(parser.getElementName(&len)).c_str(), attr,
                    value.dataType);
            return ATTR_NOT_FOUND;
        }
        if (validChars) {
            for (size_t i=0; i<len; i++) {
                char16_t c = str[i];
                const char* p = validChars;
                bool okay = false;
                while (*p) {
                    if (c == *p) {
                        okay = true;
                        break;
                    }
                    p++;
                }
                if (!okay) {
                    fprintf(stderr, "%s:%d: Tag <%s> attribute %s has invalid character '%c'.\n",
                            path.c_str(), parser.getLineNumber(),
                            String8(parser.getElementName(&len)).c_str(), attr, (char)str[i]);
                    return (int)i;
                }
            }
        }
        if (*str == ' ') {
            fprintf(stderr, "%s:%d: Tag <%s> attribute %s can not start with a space.\n",
                    path.c_str(), parser.getLineNumber(),
                    String8(parser.getElementName(&len)).c_str(), attr);
            return ATTR_LEADING_SPACES;
        }
        if (len != 0 && str[len-1] == ' ') {
            fprintf(stderr, "%s:%d: Tag <%s> attribute %s can not end with a space.\n",
                    path.c_str(), parser.getLineNumber(),
                    String8(parser.getElementName(&len)).c_str(), attr);
            return ATTR_TRAILING_SPACES;
        }
        return ATTR_OKAY;
    }
    if (required) {
        fprintf(stderr, "%s:%d: Tag <%s> missing required attribute %s.\n",
                path.c_str(), parser.getLineNumber(),
                String8(parser.getElementName(&len)).c_str(), attr);
        return ATTR_NOT_FOUND;
    }
    return ATTR_OKAY;
}

static void checkForIds(const String8& path, ResXMLParser& parser)
{
    ResXMLTree::event_code_t code;
    while ((code=parser.next()) != ResXMLTree::END_DOCUMENT
           && code > ResXMLTree::BAD_DOCUMENT) {
        if (code == ResXMLTree::START_TAG) {
            ssize_t index = parser.indexOfAttribute(NULL, "id");
            if (index >= 0) {
                fprintf(stderr, "%s:%d: warning: found plain 'id' attribute; did you mean the new 'android:id' name?\n",
                        path.c_str(), parser.getLineNumber());
            }
        }
    }
}

static bool applyFileOverlay(Bundle *bundle,
                             const sp<AaptAssets>& assets,
                             sp<ResourceTypeSet> *baseSet,
                             const char *resType)
{
    if (bundle->getVerbose()) {
        printf("applyFileOverlay for %s\n", resType);
    }

    // Replace any base level files in this category with any found from the overlay
    // Also add any found only in the overlay.
    sp<AaptAssets> overlay = assets->getOverlay();
    String8 resTypeString(resType);

    // work through the linked list of overlays
    while (overlay.get()) {
        KeyedVector<String8, sp<ResourceTypeSet> >* overlayRes = overlay->getResources();

        // get the overlay resources of the requested type
        ssize_t index = overlayRes->indexOfKey(resTypeString);
        if (index >= 0) {
            const sp<ResourceTypeSet>& overlaySet = overlayRes->valueAt(index);

            // for each of the resources, check for a match in the previously built
            // non-overlay "baseset".
            size_t overlayCount = overlaySet->size();
            for (size_t overlayIndex=0; overlayIndex<overlayCount; overlayIndex++) {
                if (bundle->getVerbose()) {
                    printf("trying overlaySet Key=%s\n",overlaySet->keyAt(overlayIndex).c_str());
                }
                ssize_t baseIndex = -1;
                if (baseSet->get() != NULL) {
                    baseIndex = (*baseSet)->indexOfKey(overlaySet->keyAt(overlayIndex));
                }
                if (baseIndex >= 0) {
                    // look for same flavor.  For a given file (strings.xml, for example)
                    // there may be a locale specific or other flavors - we want to match
                    // the same flavor.
                    sp<AaptGroup> overlayGroup = overlaySet->valueAt(overlayIndex);
                    sp<AaptGroup> baseGroup = (*baseSet)->valueAt(baseIndex);

                    DefaultKeyedVector<AaptGroupEntry, sp<AaptFile> > overlayFiles =
                            overlayGroup->getFiles();
                    if (bundle->getVerbose()) {
                        DefaultKeyedVector<AaptGroupEntry, sp<AaptFile> > baseFiles =
                                baseGroup->getFiles();
                        for (size_t i=0; i < baseFiles.size(); i++) {
                            printf("baseFile " ZD " has flavor %s\n", (ZD_TYPE) i,
                                    baseFiles.keyAt(i).toString().c_str());
                        }
                        for (size_t i=0; i < overlayFiles.size(); i++) {
                            printf("overlayFile " ZD " has flavor %s\n", (ZD_TYPE) i,
                                    overlayFiles.keyAt(i).toString().c_str());
                        }
                    }

                    size_t overlayGroupSize = overlayFiles.size();
                    for (size_t overlayGroupIndex = 0;
                            overlayGroupIndex<overlayGroupSize;
                            overlayGroupIndex++) {
                        ssize_t baseFileIndex =
                                baseGroup->getFiles().indexOfKey(overlayFiles.
                                keyAt(overlayGroupIndex));
                        if (baseFileIndex >= 0) {
                            if (bundle->getVerbose()) {
                                printf("found a match (" ZD ") for overlay file %s, for flavor %s\n",
                                        (ZD_TYPE) baseFileIndex,
                                        overlayGroup->getLeaf().c_str(),
                                        overlayFiles.keyAt(overlayGroupIndex).toString().c_str());
                            }
                            baseGroup->removeFile(baseFileIndex);
                        } else {
                            // didn't find a match fall through and add it..
                            if (true || bundle->getVerbose()) {
                                printf("nothing matches overlay file %s, for flavor %s\n",
                                        overlayGroup->getLeaf().c_str(),
                                        overlayFiles.keyAt(overlayGroupIndex).toString().c_str());
                            }
                        }
                        baseGroup->addFile(overlayFiles.valueAt(overlayGroupIndex));
                        assets->addGroupEntry(overlayFiles.keyAt(overlayGroupIndex));
                    }
                } else {
                    if (baseSet->get() == NULL) {
                        *baseSet = new ResourceTypeSet();
                        assets->getResources()->add(String8(resType), *baseSet);
                    }
                    // this group doesn't exist (a file that's only in the overlay)
                    (*baseSet)->add(overlaySet->keyAt(overlayIndex),
                            overlaySet->valueAt(overlayIndex));
                    // make sure all flavors are defined in the resources.
                    sp<AaptGroup> overlayGroup = overlaySet->valueAt(overlayIndex);
                    DefaultKeyedVector<AaptGroupEntry, sp<AaptFile> > overlayFiles =
                            overlayGroup->getFiles();
                    size_t overlayGroupSize = overlayFiles.size();
                    for (size_t overlayGroupIndex = 0;
                            overlayGroupIndex<overlayGroupSize;
                            overlayGroupIndex++) {
                        assets->addGroupEntry(overlayFiles.keyAt(overlayGroupIndex));
                    }
                }
            }
            // this overlay didn't have resources for this type
        }
        // try next overlay
        overlay = overlay->getOverlay();
    }
    return true;
}

/*
 * Inserts an attribute in a given node.
 * If errorOnFailedInsert is true, and the attribute already exists, returns false.
 * If replaceExisting is true, the attribute will be updated if it already exists.
 * Returns true otherwise, even if the attribute already exists, and does not modify
 * the existing attribute's value.
 */
bool addTagAttribute(const sp<XMLNode>& node, const char* ns8,
        const char* attr8, const char* value, bool errorOnFailedInsert,
        bool replaceExisting)
{
    if (value == NULL) {
        return true;
    }

    const String16 ns(ns8);
    const String16 attr(attr8);

    XMLNode::attribute_entry* existingEntry = node->editAttribute(ns, attr);
    if (existingEntry != NULL) {
        if (replaceExisting) {
            existingEntry->string = String16(value);
            return true;
        }

        if (errorOnFailedInsert) {
            fprintf(stderr, "Error: AndroidManifest.xml already defines %s (in %s);"
                            " cannot insert new value %s.\n",
                    String8(attr).c_str(), String8(ns).c_str(), value);
            return false;
        }

        // don't stop the build.
        return true;
    }
    
    node->addAttribute(ns, attr, String16(value));
    return true;
}

/*
 * Inserts an attribute in a given node, only if the attribute does not
 * exist.
 * If errorOnFailedInsert is true, and the attribute already exists, returns false.
 * Returns true otherwise, even if the attribute already exists.
 */
bool addTagAttribute(const sp<XMLNode>& node, const char* ns8,
        const char* attr8, const char* value, bool errorOnFailedInsert)
{
    return addTagAttribute(node, ns8, attr8, value, errorOnFailedInsert, false);
}

static void fullyQualifyClassName(const String8& package, const sp<XMLNode>& node,
        const String16& attrName) {
    XMLNode::attribute_entry* attr = node->editAttribute(
            String16("http://schemas.android.com/apk/res/android"), attrName);
    if (attr != NULL) {
        String8 name(attr->string);

        // asdf     --> package.asdf
        // .asdf  .a.b  --> package.asdf package.a.b
        // asdf.adsf --> asdf.asdf
        String8 className;
        const char* p = name.c_str();
        const char* q = strchr(p, '.');
        if (p == q) {
            className += package;
            className += name;
        } else if (q == NULL) {
            className += package;
            className += ".";
            className += name;
        } else {
            className += name;
        }
        if (kIsDebug) {
            printf("Qualifying class '%s' to '%s'", name.c_str(), className.c_str());
        }
        attr->string = String16(className);
    }
}

static sp<ResourceTable::ConfigList> findEntry(const String16& packageStr, const String16& typeStr,
                                               const String16& nameStr, ResourceTable* table) {
  sp<ResourceTable::Package> pkg = table->getPackage(packageStr);
  if (pkg != NULL) {
      sp<ResourceTable::Type> type = pkg->getTypes().valueFor(typeStr);
      if (type != NULL) {
          return type->getConfigs().valueFor(nameStr);
      }
  }
  return NULL;
}

static uint16_t getMaxSdkVersion(const sp<ResourceTable::ConfigList>& configList) {
  const DefaultKeyedVector<ConfigDescription, sp<ResourceTable::Entry>>& entries =
          configList->getEntries();
  uint16_t maxSdkVersion = 0u;
  for (size_t i = 0; i < entries.size(); i++) {
    maxSdkVersion = std::max(maxSdkVersion, entries.keyAt(i).sdkVersion);
  }
  return maxSdkVersion;
}

static void massageRoundIconSupport(const String16& iconRef, const String16& roundIconRef,
                                    ResourceTable* table) {
  bool publicOnly = false;
  const char* err;

  String16 iconPackage, iconType, iconName;
  if (!ResTable::expandResourceRef(iconRef.c_str(), iconRef.size(), &iconPackage, &iconType,
                                   &iconName, NULL, &table->getAssetsPackage(), &err,
                                   &publicOnly)) {
      // Errors will be raised in later XML compilation.
      return;
  }

  sp<ResourceTable::ConfigList> iconEntry = findEntry(iconPackage, iconType, iconName, table);
  if (iconEntry == NULL || getMaxSdkVersion(iconEntry) < SDK_O) {
      // The icon is not adaptive, so nothing to massage.
      return;
  }

  String16 roundIconPackage, roundIconType, roundIconName;
  if (!ResTable::expandResourceRef(roundIconRef.c_str(), roundIconRef.size(), &roundIconPackage,
                                   &roundIconType, &roundIconName, NULL, &table->getAssetsPackage(),
                                   &err, &publicOnly)) {
      // Errors will be raised in later XML compilation.
      return;
  }

  sp<ResourceTable::ConfigList> roundIconEntry = findEntry(roundIconPackage, roundIconType,
                                                           roundIconName, table);
  if (roundIconEntry == NULL || getMaxSdkVersion(roundIconEntry) >= SDK_O) {
      // The developer explicitly used a v26 compatible drawable as the roundIcon, meaning we should
      // not generate an alias to the icon drawable.
      return;
  }

  String16 aliasValue = String16(String8::format("@%s:%s/%s", String8(iconPackage).c_str(),
                                                 String8(iconType).c_str(),
                                                 String8(iconName).c_str()));

  // Add an equivalent v26 entry to the roundIcon for each v26 variant of the regular icon.
  const DefaultKeyedVector<ConfigDescription, sp<ResourceTable::Entry>>& configList =
          iconEntry->getEntries();
  for (size_t i = 0; i < configList.size(); i++) {
      if (configList.keyAt(i).sdkVersion >= SDK_O) {
          table->addEntry(SourcePos(), roundIconPackage, roundIconType, roundIconName, aliasValue,
                          NULL, &configList.keyAt(i));
      }
  }
}

status_t massageManifest(Bundle* bundle, ResourceTable* table, sp<XMLNode> root)
{
    root = root->searchElement(String16(), String16("manifest"));
    if (root == NULL) {
        fprintf(stderr, "No <manifest> tag.\n");
        return UNKNOWN_ERROR;
    }

    bool errorOnFailedInsert = bundle->getErrorOnFailedInsert();
    bool replaceVersion = bundle->getReplaceVersion();

    if (!addTagAttribute(root, RESOURCES_ANDROID_NAMESPACE, "versionCode",
            bundle->getVersionCode(), errorOnFailedInsert, replaceVersion)) {
        return UNKNOWN_ERROR;
    } else {
        const XMLNode::attribute_entry* attr = root->getAttribute(
                String16(RESOURCES_ANDROID_NAMESPACE), String16("versionCode"));
        if (attr != NULL) {
            bundle->setVersionCode(strdup(String8(attr->string).c_str()));
        }
    }

    if (!addTagAttribute(root, RESOURCES_ANDROID_NAMESPACE, "versionName",
            bundle->getVersionName(), errorOnFailedInsert, replaceVersion)) {
        return UNKNOWN_ERROR;
    } else {
        const XMLNode::attribute_entry* attr = root->getAttribute(
                String16(RESOURCES_ANDROID_NAMESPACE), String16("versionName"));
        if (attr != NULL) {
            bundle->setVersionName(strdup(String8(attr->string).c_str()));
        }
    }
    
    sp<XMLNode> vers = root->getChildElement(String16(), String16("uses-sdk"));
    if (bundle->getMinSdkVersion() != NULL
            || bundle->getTargetSdkVersion() != NULL
            || bundle->getMaxSdkVersion() != NULL) {
        if (vers == NULL) {
            vers = XMLNode::newElement(root->getFilename(), String16(), String16("uses-sdk"));
            root->insertChildAt(vers, 0);
        }
        
        if (!addTagAttribute(vers, RESOURCES_ANDROID_NAMESPACE, "minSdkVersion",
                bundle->getMinSdkVersion(), errorOnFailedInsert)) {
            return UNKNOWN_ERROR;
        }
        if (!addTagAttribute(vers, RESOURCES_ANDROID_NAMESPACE, "targetSdkVersion",
                bundle->getTargetSdkVersion(), errorOnFailedInsert)) {
            return UNKNOWN_ERROR;
        }
        if (!addTagAttribute(vers, RESOURCES_ANDROID_NAMESPACE, "maxSdkVersion",
                bundle->getMaxSdkVersion(), errorOnFailedInsert)) {
            return UNKNOWN_ERROR;
        }
    }

    if (vers != NULL) {
        const XMLNode::attribute_entry* attr = vers->getAttribute(
                String16(RESOURCES_ANDROID_NAMESPACE), String16("minSdkVersion"));
        if (attr != NULL) {
            bundle->setMinSdkVersion(strdup(String8(attr->string).c_str()));
        }
    }


    if (bundle->getCompileSdkVersion() != 0) {
        if (!addTagAttribute(root, RESOURCES_ANDROID_NAMESPACE, "compileSdkVersion",
                    String8::format("%d", bundle->getCompileSdkVersion()).c_str(),
                    errorOnFailedInsert, true)) {
            return UNKNOWN_ERROR;
        }
    }

    if (bundle->getCompileSdkVersionCodename() != "") {
        if (!addTagAttribute(root, RESOURCES_ANDROID_NAMESPACE, "compileSdkVersionCodename",
                    bundle->getCompileSdkVersionCodename().c_str(), errorOnFailedInsert, true)) {
            return UNKNOWN_ERROR;
        }
    }

    if (bundle->getPlatformBuildVersionCode() != "") {
        if (!addTagAttribute(root, "", "platformBuildVersionCode",
                    bundle->getPlatformBuildVersionCode().c_str(), errorOnFailedInsert, true)) {
            return UNKNOWN_ERROR;
        }
    }

    if (bundle->getPlatformBuildVersionName() != "") {
        if (!addTagAttribute(root, "", "platformBuildVersionName",
                    bundle->getPlatformBuildVersionName().c_str(), errorOnFailedInsert, true)) {
            return UNKNOWN_ERROR;
        }
    }

    if (bundle->getDebugMode()) {
        sp<XMLNode> application = root->getChildElement(String16(), String16("application"));
        if (application != NULL) {
            if (!addTagAttribute(application, RESOURCES_ANDROID_NAMESPACE, "debuggable", "true",
                    errorOnFailedInsert)) {
                return UNKNOWN_ERROR;
            }
        }
    }

    // Deal with manifest package name overrides
    const char* manifestPackageNameOverride = bundle->getManifestPackageNameOverride();
    if (manifestPackageNameOverride != NULL) {
        // Update the actual package name
        XMLNode::attribute_entry* attr = root->editAttribute(String16(), String16("package"));
        if (attr == NULL) {
            fprintf(stderr, "package name is required with --rename-manifest-package.\n");
            return UNKNOWN_ERROR;
        }
        String8 origPackage(attr->string);
        attr->string = String16(manifestPackageNameOverride);
        if (kIsDebug) {
            printf("Overriding package '%s' to be '%s'\n", origPackage.c_str(),
                    manifestPackageNameOverride);
        }

        // Make class names fully qualified
        sp<XMLNode> application = root->getChildElement(String16(), String16("application"));
        if (application != NULL) {
            fullyQualifyClassName(origPackage, application, String16("name"));
            fullyQualifyClassName(origPackage, application, String16("backupAgent"));

            Vector<sp<XMLNode> >& children = const_cast<Vector<sp<XMLNode> >&>(application->getChildren());
            for (size_t i = 0; i < children.size(); i++) {
                sp<XMLNode> child = children.editItemAt(i);
                String8 tag(child->getElementName());
                if (tag == "activity" || tag == "service" || tag == "receiver" || tag == "provider") {
                    fullyQualifyClassName(origPackage, child, String16("name"));
                } else if (tag == "activity-alias") {
                    fullyQualifyClassName(origPackage, child, String16("name"));
                    fullyQualifyClassName(origPackage, child, String16("targetActivity"));
                }
            }
        }
    }

    // Deal with manifest package name overrides
    const char* instrumentationPackageNameOverride = bundle->getInstrumentationPackageNameOverride();
    if (instrumentationPackageNameOverride != NULL) {
        // Fix up instrumentation targets.
        Vector<sp<XMLNode> >& children = const_cast<Vector<sp<XMLNode> >&>(root->getChildren());
        for (size_t i = 0; i < children.size(); i++) {
            sp<XMLNode> child = children.editItemAt(i);
            String8 tag(child->getElementName());
            if (tag == "instrumentation") {
                XMLNode::attribute_entry* attr = child->editAttribute(
                        String16(RESOURCES_ANDROID_NAMESPACE), String16("targetPackage"));
                if (attr != NULL) {
                    attr->string = String16(instrumentationPackageNameOverride);
                }
            }
        }
    }
    
    sp<XMLNode> application = root->getChildElement(String16(), String16("application"));
    if (application != NULL) {
        XMLNode::attribute_entry* icon_attr = application->editAttribute(
                String16(RESOURCES_ANDROID_NAMESPACE), String16("icon"));
        if (icon_attr != NULL) {
            XMLNode::attribute_entry* round_icon_attr = application->editAttribute(
                    String16(RESOURCES_ANDROID_NAMESPACE), String16("roundIcon"));
            if (round_icon_attr != NULL) {
                massageRoundIconSupport(icon_attr->string, round_icon_attr->string, table);
            }
        }
    }

    // Generate split name if feature is present.
    const XMLNode::attribute_entry* attr = root->getAttribute(String16(), String16("featureName"));
    if (attr != NULL) {
        String16 splitName("feature_");
        splitName.append(attr->string);
        status_t err = root->addAttribute(String16(), String16("split"), splitName);
        if (err != NO_ERROR) {
            ALOGE("Failed to insert split name into AndroidManifest.xml");
            return err;
        }
    }

    return NO_ERROR;
}

static int32_t getPlatformAssetCookie(const AssetManager& assets) {
    // Find the system package (0x01). AAPT always generates attributes
    // with the type 0x01, so we're looking for the first attribute
    // resource in the system package.
    const ResTable& table = assets.getResources(true);
    Res_value val;
    ssize_t idx = table.getResource(0x01010000, &val, true);
    if (idx != NO_ERROR) {
        // Try as a bag.
        const ResTable::bag_entry* entry;
        ssize_t cnt = table.lockBag(0x01010000, &entry);
        if (cnt >= 0) {
            idx = entry->stringBlock;
        }
        table.unlockBag(entry);
    }

    if (idx < 0) {
        return 0;
    }
    return table.getTableCookie(idx);
}

enum {
    VERSION_CODE_ATTR = 0x0101021b,
    VERSION_NAME_ATTR = 0x0101021c,
};

static ssize_t extractPlatformBuildVersion(const ResTable& table, ResXMLTree& tree, Bundle* bundle) {
    // First check if we should be recording the compileSdkVersion* attributes.
    static const String16 compileSdkVersionName("android:attr/compileSdkVersion");
    const bool useCompileSdkVersion = table.identifierForName(compileSdkVersionName.c_str(),
                                                              compileSdkVersionName.size()) != 0u;

    size_t len;
    ResXMLTree::event_code_t code;
    while ((code = tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
        if (code != ResXMLTree::START_TAG) {
            continue;
        }

        const char16_t* ctag16 = tree.getElementName(&len);
        if (ctag16 == NULL) {
            fprintf(stderr, "ERROR: failed to get XML element name (bad string pool)\n");
            return UNKNOWN_ERROR;
        }

        String8 tag(ctag16, len);
        if (tag != "manifest") {
            continue;
        }

        String8 error;
        int32_t versionCode = AaptXml::getIntegerAttribute(tree, VERSION_CODE_ATTR, &error);
        if (error != "") {
            fprintf(stderr, "ERROR: failed to get platform version code\n");
            return UNKNOWN_ERROR;
        }

        if (versionCode >= 0 && bundle->getPlatformBuildVersionCode() == "") {
            bundle->setPlatformBuildVersionCode(String8::format("%d", versionCode));
        }

        if (useCompileSdkVersion && versionCode >= 0 && bundle->getCompileSdkVersion() == 0) {
            bundle->setCompileSdkVersion(versionCode);
        }

        String8 versionName = AaptXml::getAttribute(tree, VERSION_NAME_ATTR, &error);
        if (error != "") {
            fprintf(stderr, "ERROR: failed to get platform version name\n");
            return UNKNOWN_ERROR;
        }

        if (versionName != "" && bundle->getPlatformBuildVersionName() == "") {
            bundle->setPlatformBuildVersionName(versionName);
        }

        if (useCompileSdkVersion && versionName != ""
                && bundle->getCompileSdkVersionCodename() == "") {
            bundle->setCompileSdkVersionCodename(versionName);
        }
        return NO_ERROR;
    }

    fprintf(stderr, "ERROR: no <manifest> tag found in platform AndroidManifest.xml\n");
    return UNKNOWN_ERROR;
}

static ssize_t extractPlatformBuildVersion(AssetManager& assets, Bundle* bundle) {
    int32_t cookie = getPlatformAssetCookie(assets);
    if (cookie == 0) {
        // No platform was loaded.
        return NO_ERROR;
    }

    Asset* asset = assets.openNonAsset(cookie, "AndroidManifest.xml", Asset::ACCESS_STREAMING);
    if (asset == NULL) {
        fprintf(stderr, "ERROR: Platform AndroidManifest.xml not found\n");
        return UNKNOWN_ERROR;
    }

    ssize_t result = NO_ERROR;

    // Create a new scope so that ResXMLTree is destroyed before we delete the memory over
    // which it iterates (asset).
    {
        ResXMLTree tree;
        if (tree.setTo(asset->getBuffer(true), asset->getLength()) != NO_ERROR) {
            fprintf(stderr, "ERROR: Platform AndroidManifest.xml is corrupt\n");
            result = UNKNOWN_ERROR;
        } else {
            result = extractPlatformBuildVersion(assets.getResources(true), tree, bundle);
        }
    }

    delete asset;
    return result;
}

#define ASSIGN_IT(n) \
        do { \
            ssize_t index = resources->indexOfKey(String8(#n)); \
            if (index >= 0) { \
                n ## s = resources->valueAt(index); \
            } \
        } while (0)

status_t updatePreProcessedCache(Bundle* bundle)
{
    #if BENCHMARK
    fprintf(stdout, "BENCHMARK: Starting PNG PreProcessing \n");
    long startPNGTime = clock();
    #endif /* BENCHMARK */

    String8 source(bundle->getResourceSourceDirs()[0]);
    String8 dest(bundle->getCrunchedOutputDir());

    FileFinder* ff = new SystemFileFinder();
    CrunchCache cc(source,dest,ff);

    CacheUpdater* cu = new SystemCacheUpdater(bundle);
    size_t numFiles = cc.crunch(cu);

    if (bundle->getVerbose())
        fprintf(stdout, "Crunched %d PNG files to update cache\n", (int)numFiles);

    delete ff;
    delete cu;

    #if BENCHMARK
    fprintf(stdout, "BENCHMARK: End PNG PreProcessing. Time Elapsed: %f ms \n"
            ,(clock() - startPNGTime)/1000.0);
    #endif /* BENCHMARK */
    return 0;
}

status_t generateAndroidManifestForSplit(Bundle* bundle, const sp<AaptAssets>& assets,
        const sp<ApkSplit>& split, sp<AaptFile>& outFile, ResourceTable* table) {
    const String8 filename("AndroidManifest.xml");
    const String16 androidPrefix("android");
    const String16 androidNSUri("http://schemas.android.com/apk/res/android");
    sp<XMLNode> root = XMLNode::newNamespace(filename, androidPrefix, androidNSUri);

    // Build the <manifest> tag
    sp<XMLNode> manifest = XMLNode::newElement(filename, String16(), String16("manifest"));

    // Add the 'package' attribute which is set to the package name.
    const char* packageName = assets->getPackage().c_str();
    const char* manifestPackageNameOverride = bundle->getManifestPackageNameOverride();
    if (manifestPackageNameOverride != NULL) {
        packageName = manifestPackageNameOverride;
    }
    manifest->addAttribute(String16(), String16("package"), String16(packageName));

    // Add the 'versionCode' attribute which is set to the original version code.
    if (!addTagAttribute(manifest, RESOURCES_ANDROID_NAMESPACE, "versionCode",
            bundle->getVersionCode(), true, true)) {
        return UNKNOWN_ERROR;
    }

    // Add the 'revisionCode' attribute, which is set to the original revisionCode.
    if (bundle->getRevisionCode().size() > 0) {
        if (!addTagAttribute(manifest, RESOURCES_ANDROID_NAMESPACE, "revisionCode",
                    bundle->getRevisionCode().c_str(), true, true)) {
            return UNKNOWN_ERROR;
        }
    }

    // Add the 'split' attribute which describes the configurations included.
    String8 splitName("config.");
    splitName.append(split->getPackageSafeName());
    manifest->addAttribute(String16(), String16("split"), String16(splitName));

    // Build an empty <application> tag (required).
    sp<XMLNode> app = XMLNode::newElement(filename, String16(), String16("application"));

    // Add the 'hasCode' attribute which is never true for resource splits.
    if (!addTagAttribute(app, RESOURCES_ANDROID_NAMESPACE, "hasCode",
            "false", true, true)) {
        return UNKNOWN_ERROR;
    }

    manifest->addChild(app);
    root->addChild(manifest);

    int err = compileXmlFile(bundle, assets, String16(), root, outFile, table);
    if (err < NO_ERROR) {
        return err;
    }
    outFile->setCompressionMethod(ZipEntry::kCompressDeflated);
    return NO_ERROR;
}

status_t buildResources(Bundle* bundle, const sp<AaptAssets>& assets, sp<ApkBuilder>& builder)
{
    // First, look for a package file to parse.  This is required to
    // be able to generate the resource information.
    sp<AaptGroup> androidManifestFile =
            assets->getFiles().valueFor(String8("AndroidManifest.xml"));
    if (androidManifestFile == NULL) {
        fprintf(stderr, "ERROR: No AndroidManifest.xml file found.\n");
        return UNKNOWN_ERROR;
    }

    status_t err = parsePackage(bundle, assets, androidManifestFile);
    if (err != NO_ERROR) {
        return err;
    }

    if (kIsDebug) {
        printf("Creating resources for package %s\n", assets->getPackage().c_str());
    }

    // Set the private symbols package if it was declared.
    // This can also be declared in XML as <private-symbols name="package" />
    if (bundle->getPrivateSymbolsPackage().size() != 0) {
        assets->setSymbolsPrivatePackage(bundle->getPrivateSymbolsPackage());
    }

    ResourceTable::PackageType packageType = ResourceTable::App;
    if (bundle->getBuildSharedLibrary()) {
        packageType = ResourceTable::SharedLibrary;
    } else if (bundle->getExtending()) {
        packageType = ResourceTable::System;
    } else if (!bundle->getFeatureOfPackage().empty()) {
        packageType = ResourceTable::AppFeature;
    }

    ResourceTable table(bundle, String16(assets->getPackage()), packageType);
    err = table.addIncludedResources(bundle, assets);
    if (err != NO_ERROR) {
        return err;
    }

    if (kIsDebug) {
        printf("Found %d included resource packages\n", (int)table.size());
    }

    // Standard flags for compiled XML and optional UTF-8 encoding
    int xmlFlags = XML_COMPILE_STANDARD_RESOURCE;

    /* Only enable UTF-8 if the caller of aapt didn't specifically
     * request UTF-16 encoding and the parameters of this package
     * allow UTF-8 to be used.
     */
    if (!bundle->getUTF16StringsOption()) {
        xmlFlags |= XML_COMPILE_UTF8;
    }

    // --------------------------------------------------------------
    // First, gather all resource information.
    // --------------------------------------------------------------

    // resType -> leafName -> group
    KeyedVector<String8, sp<ResourceTypeSet> > *resources = 
            new KeyedVector<String8, sp<ResourceTypeSet> >;
    collect_files(assets, resources);

    sp<ResourceTypeSet> drawables;
    sp<ResourceTypeSet> layouts;
    sp<ResourceTypeSet> anims;
    sp<ResourceTypeSet> animators;
    sp<ResourceTypeSet> interpolators;
    sp<ResourceTypeSet> transitions;
    sp<ResourceTypeSet> xmls;
    sp<ResourceTypeSet> raws;
    sp<ResourceTypeSet> colors;
    sp<ResourceTypeSet> menus;
    sp<ResourceTypeSet> mipmaps;
    sp<ResourceTypeSet> fonts;

    ASSIGN_IT(drawable);
    ASSIGN_IT(layout);
    ASSIGN_IT(anim);
    ASSIGN_IT(animator);
    ASSIGN_IT(interpolator);
    ASSIGN_IT(transition);
    ASSIGN_IT(xml);
    ASSIGN_IT(raw);
    ASSIGN_IT(color);
    ASSIGN_IT(menu);
    ASSIGN_IT(mipmap);
    ASSIGN_IT(font);

    assets->setResources(resources);
    // now go through any resource overlays and collect their files
    sp<AaptAssets> current = assets->getOverlay();
    while(current.get()) {
        KeyedVector<String8, sp<ResourceTypeSet> > *resources = 
                new KeyedVector<String8, sp<ResourceTypeSet> >;
        current->setResources(resources);
        collect_files(current, resources);
        current = current->getOverlay();
    }
    // apply the overlay files to the base set
    if (!applyFileOverlay(bundle, assets, &drawables, "drawable") ||
            !applyFileOverlay(bundle, assets, &layouts, "layout") ||
            !applyFileOverlay(bundle, assets, &anims, "anim") ||
            !applyFileOverlay(bundle, assets, &animators, "animator") ||
            !applyFileOverlay(bundle, assets, &interpolators, "interpolator") ||
            !applyFileOverlay(bundle, assets, &transitions, "transition") ||
            !applyFileOverlay(bundle, assets, &xmls, "xml") ||
            !applyFileOverlay(bundle, assets, &raws, "raw") ||
            !applyFileOverlay(bundle, assets, &colors, "color") ||
            !applyFileOverlay(bundle, assets, &menus, "menu") ||
            !applyFileOverlay(bundle, assets, &fonts, "font") ||
            !applyFileOverlay(bundle, assets, &mipmaps, "mipmap")) {
        return UNKNOWN_ERROR;
    }

    bool hasErrors = false;

    if (drawables != NULL) {
        if (bundle->getOutputAPKFile() != NULL) {
            err = preProcessImages(bundle, assets, drawables, "drawable");
        }
        if (err == NO_ERROR) {
            err = makeFileResources(bundle, assets, &table, drawables, "drawable");
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        } else {
            hasErrors = true;
        }
    }

    if (mipmaps != NULL) {
        if (bundle->getOutputAPKFile() != NULL) {
            err = preProcessImages(bundle, assets, mipmaps, "mipmap");
        }
        if (err == NO_ERROR) {
            err = makeFileResources(bundle, assets, &table, mipmaps, "mipmap");
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        } else {
            hasErrors = true;
        }
    }

    if (fonts != NULL) {
        err = makeFileResources(bundle, assets, &table, fonts, "font");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    if (layouts != NULL) {
        err = makeFileResources(bundle, assets, &table, layouts, "layout");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    if (anims != NULL) {
        err = makeFileResources(bundle, assets, &table, anims, "anim");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    if (animators != NULL) {
        err = makeFileResources(bundle, assets, &table, animators, "animator");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    if (transitions != NULL) {
        err = makeFileResources(bundle, assets, &table, transitions, "transition");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    if (interpolators != NULL) {
        err = makeFileResources(bundle, assets, &table, interpolators, "interpolator");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    if (xmls != NULL) {
        err = makeFileResources(bundle, assets, &table, xmls, "xml");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    if (raws != NULL) {
        err = makeFileResources(bundle, assets, &table, raws, "raw");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    // compile resources
    current = assets;
    while(current.get()) {
        KeyedVector<String8, sp<ResourceTypeSet> > *resources = 
                current->getResources();

        ssize_t index = resources->indexOfKey(String8("values"));
        if (index >= 0) {
            ResourceDirIterator it(resources->valueAt(index), String8("values"));
            ssize_t res;
            while ((res=it.next()) == NO_ERROR) {
                const sp<AaptFile>& file = it.getFile();
                res = compileResourceFile(bundle, assets, file, it.getParams(), 
                                          (current!=assets), &table);
                if (res != NO_ERROR) {
                    hasErrors = true;
                }
            }
        }
        current = current->getOverlay();
    }

    if (colors != NULL) {
        err = makeFileResources(bundle, assets, &table, colors, "color");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    if (menus != NULL) {
        err = makeFileResources(bundle, assets, &table, menus, "menu");
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }

    if (hasErrors) {
        return UNKNOWN_ERROR;
    }

    // --------------------------------------------------------------------
    // Assignment of resource IDs and initial generation of resource table.
    // --------------------------------------------------------------------

    if (table.hasResources()) {
        err = table.assignResourceIds();
        if (err < NO_ERROR) {
            return err;
        }
    }

    // --------------------------------------------------------------
    // Finally, we can now we can compile XML files, which may reference
    // resources.
    // --------------------------------------------------------------

    if (layouts != NULL) {
        ResourceDirIterator it(layouts, String8("layout"));
        while ((err=it.next()) == NO_ERROR) {
            String8 src = it.getFile()->getPrintableSource();
            err = compileXmlFile(bundle, assets, String16(it.getBaseName()),
                    it.getFile(), &table, xmlFlags);
            // Only verify IDs if there was no error and the file is non-empty.
            if (err == NO_ERROR && it.getFile()->hasData()) {
                ResXMLTree block;
                block.setTo(it.getFile()->getData(), it.getFile()->getSize(), true);
                checkForIds(src, block);
            } else {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (anims != NULL) {
        ResourceDirIterator it(anims, String8("anim"));
        while ((err=it.next()) == NO_ERROR) {
            err = compileXmlFile(bundle, assets, String16(it.getBaseName()),
                    it.getFile(), &table, xmlFlags);
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (animators != NULL) {
        ResourceDirIterator it(animators, String8("animator"));
        while ((err=it.next()) == NO_ERROR) {
            err = compileXmlFile(bundle, assets, String16(it.getBaseName()),
                    it.getFile(), &table, xmlFlags);
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (interpolators != NULL) {
        ResourceDirIterator it(interpolators, String8("interpolator"));
        while ((err=it.next()) == NO_ERROR) {
            err = compileXmlFile(bundle, assets, String16(it.getBaseName()),
                    it.getFile(), &table, xmlFlags);
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (transitions != NULL) {
        ResourceDirIterator it(transitions, String8("transition"));
        while ((err=it.next()) == NO_ERROR) {
            err = compileXmlFile(bundle, assets, String16(it.getBaseName()),
                    it.getFile(), &table, xmlFlags);
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (xmls != NULL) {
        ResourceDirIterator it(xmls, String8("xml"));
        while ((err=it.next()) == NO_ERROR) {
            err = compileXmlFile(bundle, assets, String16(it.getBaseName()),
                    it.getFile(), &table, xmlFlags);
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (drawables != NULL) {
        ResourceDirIterator it(drawables, String8("drawable"));
        while ((err=it.next()) == NO_ERROR) {
            err = postProcessImage(bundle, assets, &table, it.getFile());
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (mipmaps != NULL) {
        ResourceDirIterator it(mipmaps, String8("mipmap"));
        while ((err=it.next()) == NO_ERROR) {
            err = postProcessImage(bundle, assets, &table, it.getFile());
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (colors != NULL) {
        ResourceDirIterator it(colors, String8("color"));
        while ((err=it.next()) == NO_ERROR) {
            err = compileXmlFile(bundle, assets, String16(it.getBaseName()),
                    it.getFile(), &table, xmlFlags);
            if (err != NO_ERROR) {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (menus != NULL) {
        ResourceDirIterator it(menus, String8("menu"));
        while ((err=it.next()) == NO_ERROR) {
            String8 src = it.getFile()->getPrintableSource();
            err = compileXmlFile(bundle, assets, String16(it.getBaseName()),
                    it.getFile(), &table, xmlFlags);
            if (err == NO_ERROR && it.getFile()->hasData()) {
                ResXMLTree block;
                block.setTo(it.getFile()->getData(), it.getFile()->getSize(), true);
                checkForIds(src, block);
            } else {
                hasErrors = true;
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    if (fonts != NULL) {
        ResourceDirIterator it(fonts, String8("font"));
        while ((err=it.next()) == NO_ERROR) {
            // fonts can be resources other than xml.
            if (getPathExtension(it.getFile()->getPath()) == ".xml") {
                String8 src = it.getFile()->getPrintableSource();
                err = compileXmlFile(bundle, assets, String16(it.getBaseName()),
                        it.getFile(), &table, xmlFlags);
                if (err != NO_ERROR) {
                    hasErrors = true;
                }
            }
        }

        if (err < NO_ERROR) {
            hasErrors = true;
        }
        err = NO_ERROR;
    }

    // Now compile any generated resources.
    std::queue<CompileResourceWorkItem>& workQueue = table.getWorkQueue();
    while (!workQueue.empty()) {
        CompileResourceWorkItem& workItem = workQueue.front();
        int xmlCompilationFlags = xmlFlags | XML_COMPILE_PARSE_VALUES
                | XML_COMPILE_ASSIGN_ATTRIBUTE_IDS;
        if (!workItem.needsCompiling) {
            xmlCompilationFlags &= ~XML_COMPILE_ASSIGN_ATTRIBUTE_IDS;
            xmlCompilationFlags &= ~XML_COMPILE_PARSE_VALUES;
        }
        err = compileXmlFile(bundle, assets, workItem.resourceName, workItem.xmlRoot,
                             workItem.file, &table, xmlCompilationFlags);

        if (err == NO_ERROR && workItem.file->hasData()) {
            assets->addResource(getPathLeaf(workItem.resPath),
                                workItem.resPath,
                                workItem.file,
                                workItem.file->getResourceType());
        } else {
            hasErrors = true;
        }
        workQueue.pop();
    }

    if (table.validateLocalizations()) {
        hasErrors = true;
    }
    
    if (hasErrors) {
        return UNKNOWN_ERROR;
    }

    // If we're not overriding the platform build versions,
    // extract them from the platform APK.
    if (packageType != ResourceTable::System &&
            (bundle->getPlatformBuildVersionCode() == "" ||
            bundle->getPlatformBuildVersionName() == "" ||
            bundle->getCompileSdkVersion() == 0 ||
            bundle->getCompileSdkVersionCodename() == "")) {
        err = extractPlatformBuildVersion(assets->getAssetManager(), bundle);
        if (err != NO_ERROR) {
            return UNKNOWN_ERROR;
        }
    }

    const sp<AaptFile> manifestFile(androidManifestFile->getFiles().valueAt(0));
    String8 manifestPath(manifestFile->getPrintableSource());

    // Generate final compiled manifest file.
    manifestFile->clearData();
    sp<XMLNode> manifestTree = XMLNode::parse(manifestFile);
    if (manifestTree == NULL) {
        return UNKNOWN_ERROR;
    }
    err = massageManifest(bundle, &table, manifestTree);
    if (err < NO_ERROR) {
        return err;
    }
    err = compileXmlFile(bundle, assets, String16(), manifestTree, manifestFile, &table);
    if (err < NO_ERROR) {
        return err;
    }

    if (table.modifyForCompat(bundle) != NO_ERROR) {
        return UNKNOWN_ERROR;
    }

    //block.restart();
    //printXMLBlock(&block);

    // --------------------------------------------------------------
    // Generate the final resource table.
    // Re-flatten because we may have added new resource IDs
    // --------------------------------------------------------------


    ResTable finalResTable;
    sp<AaptFile> resFile;
    
    if (table.hasResources()) {
        sp<AaptSymbols> symbols = assets->getSymbolsFor(String8("R"));
        err = table.addSymbols(symbols, bundle->getSkipSymbolsWithoutDefaultLocalization());
        if (err < NO_ERROR) {
            return err;
        }

        KeyedVector<Symbol, Vector<SymbolDefinition> > densityVaryingResources;
        if (builder->getSplits().size() > 1) {
            // Only look for density varying resources if we're generating
            // splits.
            table.getDensityVaryingResources(densityVaryingResources);
        }

        Vector<sp<ApkSplit> >& splits = builder->getSplits();
        const size_t numSplits = splits.size();
        for (size_t i = 0; i < numSplits; i++) {
            sp<ApkSplit>& split = splits.editItemAt(i);
            sp<AaptFile> flattenedTable = new AaptFile(String8("resources.arsc"),
                    AaptGroupEntry(), String8());
            err = table.flatten(bundle, split->getResourceFilter(),
                    flattenedTable, split->isBase());
            if (err != NO_ERROR) {
                fprintf(stderr, "Failed to generate resource table for split '%s'\n",
                        split->getPrintableName().c_str());
                return err;
            }
            split->addEntry(String8("resources.arsc"), flattenedTable);

            if (split->isBase()) {
                resFile = flattenedTable;
                err = finalResTable.add(flattenedTable->getData(), flattenedTable->getSize());
                if (err != NO_ERROR) {
                    fprintf(stderr, "Generated resource table is corrupt.\n");
                    return err;
                }
            } else {
                ResTable resTable;
                err = resTable.add(flattenedTable->getData(), flattenedTable->getSize());
                if (err != NO_ERROR) {
                    fprintf(stderr, "Generated resource table for split '%s' is corrupt.\n",
                            split->getPrintableName().c_str());
                    return err;
                }

                bool hasError = false;
                const std::set<ConfigDescription>& splitConfigs = split->getConfigs();
                for (std::set<ConfigDescription>::const_iterator iter = splitConfigs.begin();
                        iter != splitConfigs.end();
                        ++iter) {
                    const ConfigDescription& config = *iter;
                    if (AaptConfig::isDensityOnly(config)) {
                        // Each density only split must contain all
                        // density only resources.
                        Res_value val;
                        resTable.setParameters(&config);
                        const size_t densityVaryingResourceCount = densityVaryingResources.size();
                        for (size_t k = 0; k < densityVaryingResourceCount; k++) {
                            const Symbol& symbol = densityVaryingResources.keyAt(k);
                            ssize_t block = resTable.getResource(symbol.id, &val, true);
                            if (block < 0) {
                                // Maybe it's in the base?
                                finalResTable.setParameters(&config);
                                block = finalResTable.getResource(symbol.id, &val, true);
                            }

                            if (block < 0) {
                                hasError = true;
                                SourcePos().error("%s has no definition for density split '%s'",
                                        symbol.toString().c_str(), config.toString().c_str());

                                if (bundle->getVerbose()) {
                                    const Vector<SymbolDefinition>& defs = densityVaryingResources[k];
                                    const size_t defCount = std::min(size_t(5), defs.size());
                                    for (size_t d = 0; d < defCount; d++) {
                                        const SymbolDefinition& def = defs[d];
                                        def.source.error("%s has definition for %s",
                                                symbol.toString().c_str(), def.config.toString().c_str());
                                    }

                                    if (defCount < defs.size()) {
                                        SourcePos().error("and %d more ...", (int) (defs.size() - defCount));
                                    }
                                }
                            }
                        }
                    }
                }

                if (hasError) {
                    return UNKNOWN_ERROR;
                }

                // Generate the AndroidManifest for this split.
                sp<AaptFile> generatedManifest = new AaptFile(String8("AndroidManifest.xml"),
                        AaptGroupEntry(), String8());
                err = generateAndroidManifestForSplit(bundle, assets, split,
                        generatedManifest, &table);
                if (err != NO_ERROR) {
                    fprintf(stderr, "Failed to generate AndroidManifest.xml for split '%s'\n",
                            split->getPrintableName().c_str());
                    return err;
                }
                split->addEntry(String8("AndroidManifest.xml"), generatedManifest);
            }
        }

        if (bundle->getPublicOutputFile()) {
            FILE* fp = fopen(bundle->getPublicOutputFile(), "w+");
            if (fp == NULL) {
                fprintf(stderr, "ERROR: Unable to open public definitions output file %s: %s\n",
                        (const char*)bundle->getPublicOutputFile(), strerror(errno));
                return UNKNOWN_ERROR;
            }
            if (bundle->getVerbose()) {
                printf("  Writing public definitions to %s.\n", bundle->getPublicOutputFile());
            }
            table.writePublicDefinitions(String16(assets->getPackage()), fp);
            fclose(fp);
        }

        if (finalResTable.getTableCount() == 0 || resFile == NULL) {
            fprintf(stderr, "No resource table was generated.\n");
            return UNKNOWN_ERROR;
        }
    }

    // Perform a basic validation of the manifest file.  This time we
    // parse it with the comments intact, so that we can use them to
    // generate java docs...  so we are not going to write this one
    // back out to the final manifest data.
    sp<AaptFile> outManifestFile = new AaptFile(manifestFile->getSourceFile(),
            manifestFile->getGroupEntry(),
            manifestFile->getResourceType());
    err = compileXmlFile(bundle, assets, String16(), manifestFile,
            outManifestFile, &table, XML_COMPILE_STANDARD_RESOURCE & ~XML_COMPILE_STRIP_COMMENTS);
    if (err < NO_ERROR) {
        return err;
    }
    ResXMLTree block;
    block.setTo(outManifestFile->getData(), outManifestFile->getSize(), true);
    String16 manifest16("manifest");
    String16 permission16("permission");
    String16 permission_group16("permission-group");
    String16 uses_permission16("uses-permission");
    String16 instrumentation16("instrumentation");
    String16 application16("application");
    String16 provider16("provider");
    String16 service16("service");
    String16 receiver16("receiver");
    String16 activity16("activity");
    String16 action16("action");
    String16 category16("category");
    String16 data16("scheme");
    String16 feature_group16("feature-group");
    String16 uses_feature16("uses-feature");
    const char* packageIdentChars = "abcdefghijklmnopqrstuvwxyz"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ._0123456789";
    const char* packageIdentCharsWithTheStupid = "abcdefghijklmnopqrstuvwxyz"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ._0123456789-";
    const char* classIdentChars = "abcdefghijklmnopqrstuvwxyz"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ._0123456789$";
    const char* processIdentChars = "abcdefghijklmnopqrstuvwxyz"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ._0123456789:";
    const char* authoritiesIdentChars = "abcdefghijklmnopqrstuvwxyz"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ._0123456789-:;";
    const char* typeIdentChars = "abcdefghijklmnopqrstuvwxyz"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ._0123456789:-/*+";
    const char* schemeIdentChars = "abcdefghijklmnopqrstuvwxyz"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ._0123456789-";
    ResXMLTree::event_code_t code;
    sp<AaptSymbols> permissionSymbols;
    sp<AaptSymbols> permissionGroupSymbols;
    while ((code=block.next()) != ResXMLTree::END_DOCUMENT
           && code > ResXMLTree::BAD_DOCUMENT) {
        if (code == ResXMLTree::START_TAG) {
            size_t len;
            if (block.getElementNamespace(&len) != NULL) {
                continue;
            }
            if (strcmp16(block.getElementName(&len), manifest16.c_str()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block, NULL, "package",
                                 packageIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "sharedUserId", packageIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), permission16.c_str()) == 0
                    || strcmp16(block.getElementName(&len), permission_group16.c_str()) == 0) {
                const bool isGroup = strcmp16(block.getElementName(&len),
                        permission_group16.c_str()) == 0;
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "name", isGroup ? packageIdentCharsWithTheStupid
                                 : packageIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
                SourcePos srcPos(manifestPath, block.getLineNumber());
                sp<AaptSymbols> syms;
                if (!isGroup) {
                    syms = permissionSymbols;
                    if (syms == NULL) {
                        sp<AaptSymbols> symbols =
                                assets->getSymbolsFor(String8("Manifest"));
                        syms = permissionSymbols = symbols->addNestedSymbol(
                                String8("permission"), srcPos);
                    }
                } else {
                    syms = permissionGroupSymbols;
                    if (syms == NULL) {
                        sp<AaptSymbols> symbols =
                                assets->getSymbolsFor(String8("Manifest"));
                        syms = permissionGroupSymbols = symbols->addNestedSymbol(
                                String8("permission_group"), srcPos);
                    }
                }
                size_t len;
                ssize_t index = block.indexOfAttribute(RESOURCES_ANDROID_NAMESPACE, "name");
                const char16_t* id = block.getAttributeStringValue(index, &len);
                if (id == NULL) {
                    fprintf(stderr, "%s:%d: missing name attribute in element <%s>.\n", 
                            manifestPath.c_str(), block.getLineNumber(),
                            String8(block.getElementName(&len)).c_str());
                    hasErrors = true;
                    break;
                }
                String8 idStr(id);
                char* p = idStr.lockBuffer(idStr.size());
                char* e = p + idStr.size();
                bool begins_with_digit = true;  // init to true so an empty string fails
                while (e > p) {
                    e--;
                    if (*e >= '0' && *e <= '9') {
                      begins_with_digit = true;
                      continue;
                    }
                    if ((*e >= 'a' && *e <= 'z') ||
                        (*e >= 'A' && *e <= 'Z') ||
                        (*e == '_')) {
                      begins_with_digit = false;
                      continue;
                    }
                    if (isGroup && (*e == '-')) {
                        *e = '_';
                        begins_with_digit = false;
                        continue;
                    }
                    e++;
                    break;
                }
                idStr.unlockBuffer();
                // verify that we stopped because we hit a period or
                // the beginning of the string, and that the
                // identifier didn't begin with a digit.
                if (begins_with_digit || (e != p && *(e-1) != '.')) {
                  fprintf(stderr,
                          "%s:%d: Permission name <%s> is not a valid Java symbol\n",
                          manifestPath.c_str(), block.getLineNumber(), idStr.c_str());
                  hasErrors = true;
                }
                syms->addStringSymbol(String8(e), idStr, srcPos);
                const char16_t* cmt = block.getComment(&len);
                if (cmt != NULL && *cmt != 0) {
                    //printf("Comment of %s: %s\n", String8(e).c_str(),
                    //        String8(cmt).c_str());
                    syms->appendComment(String8(e), String16(cmt), srcPos);
                }
                syms->makeSymbolPublic(String8(e), srcPos);
            } else if (strcmp16(block.getElementName(&len), uses_permission16.c_str()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "name", packageIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), instrumentation16.c_str()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "name", classIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "targetPackage",
                                 packageIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), application16.c_str()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "name", classIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "permission",
                                 packageIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "process",
                                 processIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "taskAffinity",
                                 processIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), provider16.c_str()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "name", classIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "authorities",
                                 authoritiesIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "permission",
                                 packageIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "process",
                                 processIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), service16.c_str()) == 0
                       || strcmp16(block.getElementName(&len), receiver16.c_str()) == 0
                       || strcmp16(block.getElementName(&len), activity16.c_str()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "name", classIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "permission",
                                 packageIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "process",
                                 processIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "taskAffinity",
                                 processIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), action16.c_str()) == 0
                       || strcmp16(block.getElementName(&len), category16.c_str()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "name",
                                 packageIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), data16.c_str()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "mimeType",
                                 typeIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "scheme",
                                 schemeIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), feature_group16.c_str()) == 0) {
                int depth = 1;
                while ((code=block.next()) != ResXMLTree::END_DOCUMENT
                       && code > ResXMLTree::BAD_DOCUMENT) {
                    if (code == ResXMLTree::START_TAG) {
                        depth++;
                        if (strcmp16(block.getElementName(&len), uses_feature16.c_str()) == 0) {
                            ssize_t idx = block.indexOfAttribute(
                                    RESOURCES_ANDROID_NAMESPACE, "required");
                            if (idx < 0) {
                                continue;
                            }

                            int32_t data = block.getAttributeData(idx);
                            if (data == 0) {
                                fprintf(stderr, "%s:%d: Tag <uses-feature> can not have "
                                        "android:required=\"false\" when inside a "
                                        "<feature-group> tag.\n",
                                        manifestPath.c_str(), block.getLineNumber());
                                hasErrors = true;
                            }
                        }
                    } else if (code == ResXMLTree::END_TAG) {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                }
            }
        }
    }

    if (hasErrors) {
        return UNKNOWN_ERROR;
    }

    if (resFile != NULL) {
        // These resources are now considered to be a part of the included
        // resources, for others to reference.
        err = assets->addIncludedResources(resFile);
        if (err < NO_ERROR) {
            fprintf(stderr, "ERROR: Unable to parse generated resources, aborting.\n");
            return err;
        }
    }
    
    return err;
}

static const char* getIndentSpace(int indent)
{
static const char whitespace[] =
"                                                                                       ";

    return whitespace + sizeof(whitespace) - 1 - indent*4;
}

static String8 flattenSymbol(const String8& symbol) {
    String8 result(symbol);
    ssize_t first;
    if ((first = symbol.find(":", 0)) >= 0
            || (first = symbol.find(".", 0)) >= 0) {
        size_t size = symbol.size();
        char* buf = result.lockBuffer(size);
        for (size_t i = first; i < size; i++) {
            if (buf[i] == ':' || buf[i] == '.') {
                buf[i] = '_';
            }
        }
        result.unlockBuffer(size);
    }
    return result;
}

static String8 getSymbolPackage(const String8& symbol, const sp<AaptAssets>& assets, bool pub) {
    ssize_t colon = symbol.find(":", 0);
    if (colon >= 0) {
        return String8(symbol.c_str(), colon);
    }
    return pub ? assets->getPackage() : assets->getSymbolsPrivatePackage();
}

static String8 getSymbolName(const String8& symbol) {
    ssize_t colon = symbol.find(":", 0);
    if (colon >= 0) {
        return String8(symbol.c_str() + colon + 1);
    }
    return symbol;
}

static String16 getAttributeComment(const sp<AaptAssets>& assets,
                                    const String8& name,
                                    String16* outTypeComment = NULL)
{
    sp<AaptSymbols> asym = assets->getSymbolsFor(String8("R"));
    if (asym != NULL) {
        //printf("Got R symbols!\n");
        asym = asym->getNestedSymbols().valueFor(String8("attr"));
        if (asym != NULL) {
            //printf("Got attrs symbols! comment %s=%s\n",
            //     name.c_str(), String8(asym->getComment(name)).c_str());
            if (outTypeComment != NULL) {
                *outTypeComment = asym->getTypeComment(name);
            }
            return asym->getComment(name);
        }
    }
    return String16();
}

static status_t writeResourceLoadedCallbackForLayoutClasses(
    FILE* fp, const sp<AaptAssets>& assets,
    const sp<AaptSymbols>& symbols, int indent, bool /* includePrivate */)
{
    String16 attr16("attr");
    String16 package16(assets->getPackage());

    const char* indentStr = getIndentSpace(indent);
    bool hasErrors = false;

    size_t i;
    size_t N = symbols->getNestedSymbols().size();
    for (i=0; i<N; i++) {
        sp<AaptSymbols> nsymbols = symbols->getNestedSymbols().valueAt(i);
        String8 realClassName(symbols->getNestedSymbols().keyAt(i));
        String8 nclassName(flattenSymbol(realClassName));

        fprintf(fp,
                "%sfor(int i = 0; i < styleable.%s.length; ++i) {\n"
                "%sstyleable.%s[i] = (styleable.%s[i] & 0x00ffffff) | (packageId << 24);\n"
                "%s}\n",
                indentStr, nclassName.c_str(),
                getIndentSpace(indent+1), nclassName.c_str(), nclassName.c_str(),
                indentStr);
    }

    return hasErrors ? STATUST(UNKNOWN_ERROR) : NO_ERROR;
}

static status_t writeResourceLoadedCallback(
    FILE* fp, const sp<AaptAssets>& assets, bool includePrivate,
    const sp<AaptSymbols>& symbols, const String8& className, int indent)
{
    size_t i;
    status_t err = NO_ERROR;

    size_t N = symbols->getSymbols().size();
    for (i=0; i<N; i++) {
        const AaptSymbolEntry& sym = symbols->getSymbols().valueAt(i);
        if (sym.typeCode != AaptSymbolEntry::TYPE_INT32) {
            continue;
        }
        if (!assets->isJavaSymbol(sym, includePrivate)) {
            continue;
        }
        String8 flat_name(flattenSymbol(sym.name));
        fprintf(fp,
                "%s%s.%s = (%s.%s & 0x00ffffff) | (packageId << 24);\n",
                getIndentSpace(indent), className.c_str(), flat_name.c_str(),
                className.c_str(), flat_name.c_str());
    }

    N = symbols->getNestedSymbols().size();
    for (i=0; i<N; i++) {
        sp<AaptSymbols> nsymbols = symbols->getNestedSymbols().valueAt(i);
        String8 nclassName(symbols->getNestedSymbols().keyAt(i));
        if (nclassName == "styleable") {
            err = writeResourceLoadedCallbackForLayoutClasses(
                    fp, assets, nsymbols, indent, includePrivate);
        } else {
            err = writeResourceLoadedCallback(fp, assets, includePrivate, nsymbols,
                    nclassName, indent);
        }
        if (err != NO_ERROR) {
            return err;
        }
    }

    return NO_ERROR;
}

static status_t writeLayoutClasses(
    FILE* fp, const sp<AaptAssets>& assets,
    const sp<AaptSymbols>& symbols, int indent, bool includePrivate, bool nonConstantId)
{
    const char* indentStr = getIndentSpace(indent);
    if (!includePrivate) {
        fprintf(fp, "%s/** @doconly */\n", indentStr);
    }
    fprintf(fp, "%spublic static final class styleable {\n", indentStr);
    indent++;

    String16 attr16("attr");
    String16 package16(assets->getPackage());

    indentStr = getIndentSpace(indent);
    bool hasErrors = false;

    size_t i;
    size_t N = symbols->getNestedSymbols().size();
    for (i=0; i<N; i++) {
        sp<AaptSymbols> nsymbols = symbols->getNestedSymbols().valueAt(i);
        String8 realClassName(symbols->getNestedSymbols().keyAt(i));
        String8 nclassName(flattenSymbol(realClassName));

        SortedVector<uint32_t> idents;
        Vector<uint32_t> origOrder;
        Vector<bool> publicFlags;

        size_t a;
        size_t NA = nsymbols->getSymbols().size();
        for (a=0; a<NA; a++) {
            const AaptSymbolEntry& sym(nsymbols->getSymbols().valueAt(a));
            int32_t code = sym.typeCode == AaptSymbolEntry::TYPE_INT32
                    ? sym.int32Val : 0;
            bool isPublic = true;
            if (code == 0) {
                String16 name16(sym.name);
                uint32_t typeSpecFlags;
                code = assets->getIncludedResources().identifierForName(
                    name16.c_str(), name16.size(),
                    attr16.c_str(), attr16.size(),
                    package16.c_str(), package16.size(), &typeSpecFlags);
                if (code == 0) {
                    fprintf(stderr, "ERROR: In <declare-styleable> %s, unable to find attribute %s\n",
                            nclassName.c_str(), sym.name.c_str());
                    hasErrors = true;
                }
                isPublic = (typeSpecFlags&ResTable_typeSpec::SPEC_PUBLIC) != 0;
            }
            idents.add(code);
            origOrder.add(code);
            publicFlags.add(isPublic);
        }

        NA = idents.size();

        String16 comment = symbols->getComment(realClassName);
        AnnotationProcessor ann;
        fprintf(fp, "%s/** ", indentStr);
        if (comment.size() > 0) {
            String8 cmt(comment);
            ann.preprocessComment(cmt);
            fprintf(fp, "%s\n", cmt.c_str());
        } else {
            fprintf(fp, "Attributes that can be used with a %s.\n", nclassName.c_str());
        }
        bool hasTable = false;
        for (a=0; a<NA; a++) {
            ssize_t pos = idents.indexOf(origOrder.itemAt(a));
            if (pos >= 0) {
                if (!hasTable) {
                    hasTable = true;
                    fprintf(fp,
                            "%s   <p>Includes the following attributes:</p>\n"
                            "%s   <table>\n"
                            "%s   <colgroup align=\"left\" />\n"
                            "%s   <colgroup align=\"left\" />\n"
                            "%s   <tr><th>Attribute</th><th>Description</th></tr>\n",
                            indentStr,
                            indentStr,
                            indentStr,
                            indentStr,
                            indentStr);
                }
                const AaptSymbolEntry& sym = nsymbols->getSymbols().valueAt(a);
                if (!publicFlags.itemAt(a) && !includePrivate) {
                    continue;
                }
                String8 name8(sym.name);
                String16 comment(sym.comment);
                if (comment.size() <= 0) {
                    comment = getAttributeComment(assets, name8);
                }
                if (comment.contains(u"@removed")) {
                    continue;
                }
                if (comment.size() > 0) {
                    const char16_t* p = comment.c_str();
                    while (*p != 0 && *p != '.') {
                        if (*p == '{') {
                            while (*p != 0 && *p != '}') {
                                p++;
                            }
                        } else {
                            p++;
                        }
                    }
                    if (*p == '.') {
                        p++;
                    }
                    comment = String16(comment.c_str(), p-comment.c_str());
                }
                fprintf(fp, "%s   <tr><td><code>{@link #%s_%s %s:%s}</code></td><td>%s</td></tr>\n",
                        indentStr, nclassName.c_str(),
                        flattenSymbol(name8).c_str(),
                        getSymbolPackage(name8, assets, true).c_str(),
                        getSymbolName(name8).c_str(),
                        String8(comment).c_str());
            }
        }
        if (hasTable) {
            fprintf(fp, "%s   </table>\n", indentStr);
        }
        for (a=0; a<NA; a++) {
            ssize_t pos = idents.indexOf(origOrder.itemAt(a));
            if (pos >= 0) {
                const AaptSymbolEntry& sym = nsymbols->getSymbols().valueAt(a);
                if (!publicFlags.itemAt(a) && !includePrivate) {
                    continue;
                }
                fprintf(fp, "%s   @see #%s_%s\n",
                        indentStr, nclassName.c_str(),
                        flattenSymbol(sym.name).c_str());
            }
        }
        fprintf(fp, "%s */\n", getIndentSpace(indent));

        ann.printAnnotations(fp, indentStr);
        
        fprintf(fp,
                "%spublic static final int[] %s = {\n"
                "%s",
                indentStr, nclassName.c_str(),
                getIndentSpace(indent+1));

        for (a=0; a<NA; a++) {
            if (a != 0) {
                if ((a&3) == 0) {
                    fprintf(fp, ",\n%s", getIndentSpace(indent+1));
                } else {
                    fprintf(fp, ", ");
                }
            }
            fprintf(fp, "0x%08x", idents[a]);
        }

        fprintf(fp, "\n%s};\n", indentStr);

        for (a=0; a<NA; a++) {
            ssize_t pos = idents.indexOf(origOrder.itemAt(a));
            if (pos >= 0) {
                const AaptSymbolEntry& sym = nsymbols->getSymbols().valueAt(a);
                if (!publicFlags.itemAt(a) && !includePrivate) {
                    continue;
                }
                String8 name8(sym.name);
                String16 comment(sym.comment);
                String16 typeComment;
                if (comment.size() <= 0) {
                    comment = getAttributeComment(assets, name8, &typeComment);
                } else {
                    getAttributeComment(assets, name8, &typeComment);
                }

                uint32_t typeSpecFlags = 0;
                String16 name16(sym.name);
                assets->getIncludedResources().identifierForName(
                    name16.c_str(), name16.size(),
                    attr16.c_str(), attr16.size(),
                    package16.c_str(), package16.size(), &typeSpecFlags);
                //printf("%s:%s/%s: 0x%08x\n", String8(package16).c_str(),
                //    String8(attr16).c_str(), String8(name16).c_str(), typeSpecFlags);
                const bool pub = (typeSpecFlags&ResTable_typeSpec::SPEC_PUBLIC) != 0;

                AnnotationProcessor ann;
                fprintf(fp, "%s/**\n", indentStr);
                if (comment.size() > 0) {
                    String8 cmt(comment);
                    ann.preprocessComment(cmt);
                    fprintf(fp, "%s  <p>\n%s  @attr description\n", indentStr, indentStr);
                    fprintf(fp, "%s  %s\n", indentStr, cmt.c_str());
                } else {
                    fprintf(fp,
                            "%s  <p>This symbol is the offset where the {@link %s.R.attr#%s}\n"
                            "%s  attribute's value can be found in the {@link #%s} array.\n",
                            indentStr,
                            getSymbolPackage(name8, assets, pub).c_str(),
                            getSymbolName(name8).c_str(),
                            indentStr, nclassName.c_str());
                }
                if (typeComment.size() > 0) {
                    String8 cmt(typeComment);
                    ann.preprocessComment(cmt);
                    fprintf(fp, "\n\n%s  %s\n", indentStr, cmt.c_str());
                }
                if (comment.size() > 0) {
                    if (pub) {
                        fprintf(fp,
                                "%s  <p>This corresponds to the global attribute\n"
                                "%s  resource symbol {@link %s.R.attr#%s}.\n",
                                indentStr, indentStr,
                                getSymbolPackage(name8, assets, true).c_str(),
                                getSymbolName(name8).c_str());
                    } else {
                        fprintf(fp,
                                "%s  <p>This is a private symbol.\n", indentStr);
                    }
                }
                fprintf(fp, "%s  @attr name %s:%s\n", indentStr,
                        getSymbolPackage(name8, assets, pub).c_str(),
                        getSymbolName(name8).c_str());
                fprintf(fp, "%s*/\n", indentStr);
                ann.printAnnotations(fp, indentStr);

                const char * id_format = nonConstantId ?
                        "%spublic static int %s_%s = %d;\n" :
                        "%spublic static final int %s_%s = %d;\n";

                fprintf(fp,
                        id_format,
                        indentStr, nclassName.c_str(),
                        flattenSymbol(name8).c_str(), (int)pos);
            }
        }
    }

    indent--;
    fprintf(fp, "%s};\n", getIndentSpace(indent));
    return hasErrors ? STATUST(UNKNOWN_ERROR) : NO_ERROR;
}

static status_t writeTextLayoutClasses(
    FILE* fp, const sp<AaptAssets>& assets,
    const sp<AaptSymbols>& symbols, bool includePrivate)
{
    String16 attr16("attr");
    String16 package16(assets->getPackage());

    bool hasErrors = false;

    size_t i;
    size_t N = symbols->getNestedSymbols().size();
    for (i=0; i<N; i++) {
        sp<AaptSymbols> nsymbols = symbols->getNestedSymbols().valueAt(i);
        String8 realClassName(symbols->getNestedSymbols().keyAt(i));
        String8 nclassName(flattenSymbol(realClassName));

        SortedVector<uint32_t> idents;
        Vector<uint32_t> origOrder;
        Vector<bool> publicFlags;

        size_t a;
        size_t NA = nsymbols->getSymbols().size();
        for (a=0; a<NA; a++) {
            const AaptSymbolEntry& sym(nsymbols->getSymbols().valueAt(a));
            int32_t code = sym.typeCode == AaptSymbolEntry::TYPE_INT32
                    ? sym.int32Val : 0;
            bool isPublic = true;
            if (code == 0) {
                String16 name16(sym.name);
                uint32_t typeSpecFlags;
                code = assets->getIncludedResources().identifierForName(
                    name16.c_str(), name16.size(),
                    attr16.c_str(), attr16.size(),
                    package16.c_str(), package16.size(), &typeSpecFlags);
                if (code == 0) {
                    fprintf(stderr, "ERROR: In <declare-styleable> %s, unable to find attribute %s\n",
                            nclassName.c_str(), sym.name.c_str());
                    hasErrors = true;
                }
                isPublic = (typeSpecFlags&ResTable_typeSpec::SPEC_PUBLIC) != 0;
            }
            idents.add(code);
            origOrder.add(code);
            publicFlags.add(isPublic);
        }

        NA = idents.size();

        fprintf(fp, "int[] styleable %s {", nclassName.c_str());

        for (a=0; a<NA; a++) {
            if (a != 0) {
                fprintf(fp, ",");
            }
            fprintf(fp, " 0x%08x", idents[a]);
        }

        fprintf(fp, " }\n");

        for (a=0; a<NA; a++) {
            ssize_t pos = idents.indexOf(origOrder.itemAt(a));
            if (pos >= 0) {
                const AaptSymbolEntry& sym = nsymbols->getSymbols().valueAt(a);
                if (!publicFlags.itemAt(a) && !includePrivate) {
                    continue;
                }
                String8 name8(sym.name);
                String16 comment(sym.comment);
                String16 typeComment;
                if (comment.size() <= 0) {
                    comment = getAttributeComment(assets, name8, &typeComment);
                } else {
                    getAttributeComment(assets, name8, &typeComment);
                }

                uint32_t typeSpecFlags = 0;
                String16 name16(sym.name);
                assets->getIncludedResources().identifierForName(
                    name16.c_str(), name16.size(),
                    attr16.c_str(), attr16.size(),
                    package16.c_str(), package16.size(), &typeSpecFlags);
                //printf("%s:%s/%s: 0x%08x\n", String8(package16).c_str(),
                //    String8(attr16).c_str(), String8(name16).c_str(), typeSpecFlags);
                //const bool pub = (typeSpecFlags&ResTable_typeSpec::SPEC_PUBLIC) != 0;

                fprintf(fp,
                        "int styleable %s_%s %d\n",
                        nclassName.c_str(),
                        flattenSymbol(name8).c_str(), (int)pos);
            }
        }
    }

    return hasErrors ? STATUST(UNKNOWN_ERROR) : NO_ERROR;
}

static status_t writeSymbolClass(
    FILE* fp, const sp<AaptAssets>& assets, bool includePrivate,
    const sp<AaptSymbols>& symbols, const String8& className, int indent,
    bool nonConstantId, bool emitCallback)
{
    fprintf(fp, "%spublic %sfinal class %s {\n",
            getIndentSpace(indent),
            indent != 0 ? "static " : "", className.c_str());
    indent++;

    size_t i;
    status_t err = NO_ERROR;

    const char * id_format = nonConstantId ?
            "%spublic static int %s=0x%08x;\n" :
            "%spublic static final int %s=0x%08x;\n";

    size_t N = symbols->getSymbols().size();
    for (i=0; i<N; i++) {
        const AaptSymbolEntry& sym = symbols->getSymbols().valueAt(i);
        if (sym.typeCode != AaptSymbolEntry::TYPE_INT32) {
            continue;
        }
        if (!assets->isJavaSymbol(sym, includePrivate)) {
            continue;
        }
        String8 name8(sym.name);
        String16 comment(sym.comment);
        bool haveComment = false;
        AnnotationProcessor ann;
        if (comment.size() > 0) {
            haveComment = true;
            String8 cmt(comment);
            ann.preprocessComment(cmt);
            fprintf(fp,
                    "%s/** %s\n",
                    getIndentSpace(indent), cmt.c_str());
        }
        String16 typeComment(sym.typeComment);
        if (typeComment.size() > 0) {
            String8 cmt(typeComment);
            ann.preprocessComment(cmt);
            if (!haveComment) {
                haveComment = true;
                fprintf(fp,
                        "%s/** %s\n", getIndentSpace(indent), cmt.c_str());
            } else {
                fprintf(fp,
                        "%s %s\n", getIndentSpace(indent), cmt.c_str());
            }
        }
        if (haveComment) {
            fprintf(fp,"%s */\n", getIndentSpace(indent));
        }
        ann.printAnnotations(fp, getIndentSpace(indent));
        fprintf(fp, id_format,
                getIndentSpace(indent),
                flattenSymbol(name8).c_str(), (int)sym.int32Val);
    }

    for (i=0; i<N; i++) {
        const AaptSymbolEntry& sym = symbols->getSymbols().valueAt(i);
        if (sym.typeCode != AaptSymbolEntry::TYPE_STRING) {
            continue;
        }
        if (!assets->isJavaSymbol(sym, includePrivate)) {
            continue;
        }
        String8 name8(sym.name);
        String16 comment(sym.comment);
        AnnotationProcessor ann;
        if (comment.size() > 0) {
            String8 cmt(comment);
            ann.preprocessComment(cmt);
            fprintf(fp,
                    "%s/** %s\n"
                     "%s */\n",
                    getIndentSpace(indent), cmt.c_str(),
                    getIndentSpace(indent));
        }
        ann.printAnnotations(fp, getIndentSpace(indent));
        fprintf(fp, "%spublic static final String %s=\"%s\";\n",
                getIndentSpace(indent),
                flattenSymbol(name8).c_str(), sym.stringVal.c_str());
    }

    sp<AaptSymbols> styleableSymbols;

    N = symbols->getNestedSymbols().size();
    for (i=0; i<N; i++) {
        sp<AaptSymbols> nsymbols = symbols->getNestedSymbols().valueAt(i);
        String8 nclassName(symbols->getNestedSymbols().keyAt(i));
        if (nclassName == "styleable") {
            styleableSymbols = nsymbols;
        } else {
            err = writeSymbolClass(fp, assets, includePrivate, nsymbols, nclassName,
                    indent, nonConstantId, false);
        }
        if (err != NO_ERROR) {
            return err;
        }
    }

    if (styleableSymbols != NULL) {
        err = writeLayoutClasses(fp, assets, styleableSymbols, indent, includePrivate, nonConstantId);
        if (err != NO_ERROR) {
            return err;
        }
    }

    if (emitCallback) {
        fprintf(fp, "%spublic static void onResourcesLoaded(int packageId) {\n",
                getIndentSpace(indent));
        writeResourceLoadedCallback(fp, assets, includePrivate, symbols, className, indent + 1);
        fprintf(fp, "%s}\n", getIndentSpace(indent));
    }

    indent--;
    fprintf(fp, "%s}\n", getIndentSpace(indent));
    return NO_ERROR;
}

static status_t writeTextSymbolClass(
    FILE* fp, const sp<AaptAssets>& assets, bool includePrivate,
    const sp<AaptSymbols>& symbols, const String8& className)
{
    size_t i;
    status_t err = NO_ERROR;

    size_t N = symbols->getSymbols().size();
    for (i=0; i<N; i++) {
        const AaptSymbolEntry& sym = symbols->getSymbols().valueAt(i);
        if (sym.typeCode != AaptSymbolEntry::TYPE_INT32) {
            continue;
        }

        if (!assets->isJavaSymbol(sym, includePrivate)) {
            continue;
        }

        String8 name8(sym.name);
        fprintf(fp, "int %s %s 0x%08x\n",
                className.c_str(),
                flattenSymbol(name8).c_str(), (int)sym.int32Val);
    }

    N = symbols->getNestedSymbols().size();
    for (i=0; i<N; i++) {
        sp<AaptSymbols> nsymbols = symbols->getNestedSymbols().valueAt(i);
        String8 nclassName(symbols->getNestedSymbols().keyAt(i));
        if (nclassName == "styleable") {
            err = writeTextLayoutClasses(fp, assets, nsymbols, includePrivate);
        } else {
            err = writeTextSymbolClass(fp, assets, includePrivate, nsymbols, nclassName);
        }
        if (err != NO_ERROR) {
            return err;
        }
    }

    return NO_ERROR;
}

status_t writeResourceSymbols(Bundle* bundle, const sp<AaptAssets>& assets,
    const String8& package, bool includePrivate, bool emitCallback)
{
    if (!bundle->getRClassDir()) {
        return NO_ERROR;
    }

    const char* textSymbolsDest = bundle->getOutputTextSymbols();

    String8 R("R");
    const size_t N = assets->getSymbols().size();
    for (size_t i=0; i<N; i++) {
        sp<AaptSymbols> symbols = assets->getSymbols().valueAt(i);
        String8 className(assets->getSymbols().keyAt(i));
        String8 dest(bundle->getRClassDir());

        if (bundle->getMakePackageDirs()) {
            const String8& pkg(package);
            const char* last = pkg.c_str();
            const char* s = last-1;
            do {
                s++;
                if (s > last && (*s == '.' || *s == 0)) {
                    String8 part(last, s-last);
                    appendPath(dest, part);
#ifdef _WIN32
                    _mkdir(dest.c_str());
#else
                    mkdir(dest.c_str(), S_IRUSR|S_IWUSR|S_IXUSR|S_IRGRP|S_IXGRP);
#endif
                    last = s+1;
                }
            } while (*s);
        }
        appendPath(dest, className);
        dest.append(".java");
        FILE* fp = fopen(dest.c_str(), "w+");
        if (fp == NULL) {
            fprintf(stderr, "ERROR: Unable to open class file %s: %s\n",
                    dest.c_str(), strerror(errno));
            return UNKNOWN_ERROR;
        }
        if (bundle->getVerbose()) {
            printf("  Writing symbols for class %s.\n", className.c_str());
        }

        fprintf(fp,
            "/* AUTO-GENERATED FILE.  DO NOT MODIFY.\n"
            " *\n"
            " * This class was automatically generated by the\n"
            " * aapt tool from the resource data it found.  It\n"
            " * should not be modified by hand.\n"
            " */\n"
            "\n"
            "package %s;\n\n", package.c_str());

        status_t err = writeSymbolClass(fp, assets, includePrivate, symbols,
                className, 0, bundle->getNonConstantId(), emitCallback);
        fclose(fp);
        if (err != NO_ERROR) {
            return err;
        }

        if (textSymbolsDest != NULL && R == className) {
            String8 textDest(textSymbolsDest);
            appendPath(textDest, className);
            textDest.append(".txt");

            FILE* fp = fopen(textDest.c_str(), "w+");
            if (fp == NULL) {
                fprintf(stderr, "ERROR: Unable to open text symbol file %s: %s\n",
                        textDest.c_str(), strerror(errno));
                return UNKNOWN_ERROR;
            }
            if (bundle->getVerbose()) {
                printf("  Writing text symbols for class %s.\n", className.c_str());
            }

            status_t err = writeTextSymbolClass(fp, assets, includePrivate, symbols,
                    className);
            fclose(fp);
            if (err != NO_ERROR) {
                return err;
            }
        }

        // If we were asked to generate a dependency file, we'll go ahead and add this R.java
        // as a target in the dependency file right next to it.
        if (bundle->getGenDependencies() && R == className) {
            // Add this R.java to the dependency file
            String8 dependencyFile(bundle->getRClassDir());
            appendPath(dependencyFile, "R.java.d");

            FILE *fp = fopen(dependencyFile.c_str(), "a");
            fprintf(fp,"%s \\\n", dest.c_str());
            fclose(fp);
        }
    }

    return NO_ERROR;
}


class ProguardKeepSet
{
public:
    // { rule --> { file locations } }
    KeyedVector<String8, SortedVector<String8> > rules;

    void add(const String8& rule, const String8& where);
};

void ProguardKeepSet::add(const String8& rule, const String8& where)
{
    ssize_t index = rules.indexOfKey(rule);
    if (index < 0) {
        index = rules.add(rule, SortedVector<String8>());
    }
    rules.editValueAt(index).add(where);
}

void
addProguardKeepRule(ProguardKeepSet* keep, const String8& inClassName,
        const char* pkg, const String8& srcName, int line)
{
    String8 className(inClassName);
    if (pkg != NULL) {
        // asdf     --> package.asdf
        // .asdf  .a.b  --> package.asdf package.a.b
        // asdf.adsf --> asdf.asdf
        const char* p = className.c_str();
        const char* q = strchr(p, '.');
        if (p == q) {
            className = pkg;
            className.append(inClassName);
        } else if (q == NULL) {
            className = pkg;
            className.append(".");
            className.append(inClassName);
        }
    }

    String8 rule("-keep class ");
    rule += className;
    rule += " { <init>(...); }";

    String8 location("view ");
    location += srcName;
    char lineno[20];
    sprintf(lineno, ":%d", line);
    location += lineno;

    keep->add(rule, location);
}

void
addProguardKeepMethodRule(ProguardKeepSet* keep, const String8& memberName,
        const char* /* pkg */, const String8& srcName, int line)
{
    String8 rule("-keepclassmembers class * { *** ");
    rule += memberName;
    rule += "(...); }";

    String8 location("onClick ");
    location += srcName;
    char lineno[20];
    sprintf(lineno, ":%d", line);
    location += lineno;

    keep->add(rule, location);
}

status_t
writeProguardForAndroidManifest(ProguardKeepSet* keep, const sp<AaptAssets>& assets, bool mainDex)
{
    status_t err;
    ResXMLTree tree;
    size_t len;
    ResXMLTree::event_code_t code;
    int depth = 0;
    bool inApplication = false;
    String8 error;
    sp<AaptGroup> assGroup;
    sp<AaptFile> assFile;
    String8 pkg;
    String8 defaultProcess;

    // First, look for a package file to parse.  This is required to
    // be able to generate the resource information.
    assGroup = assets->getFiles().valueFor(String8("AndroidManifest.xml"));
    if (assGroup == NULL) {
        fprintf(stderr, "ERROR: No AndroidManifest.xml file found.\n");
        return -1;
    }

    if (assGroup->getFiles().size() != 1) {
        fprintf(stderr, "warning: Multiple AndroidManifest.xml files found, using %s\n",
                assGroup->getFiles().valueAt(0)->getPrintableSource().c_str());
    }

    assFile = assGroup->getFiles().valueAt(0);

    err = parseXMLResource(assFile, &tree);
    if (err != NO_ERROR) {
        return err;
    }

    tree.restart();

    while ((code=tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
        if (code == ResXMLTree::END_TAG) {
            if (/* name == "Application" && */ depth == 2) {
                inApplication = false;
            }
            depth--;
            continue;
        }
        if (code != ResXMLTree::START_TAG) {
            continue;
        }
        depth++;
        String8 tag(tree.getElementName(&len));
        // printf("Depth %d tag %s\n", depth, tag.c_str());
        bool keepTag = false;
        if (depth == 1) {
            if (tag != "manifest") {
                fprintf(stderr, "ERROR: manifest does not start with <manifest> tag\n");
                return -1;
            }
            pkg = AaptXml::getAttribute(tree, NULL, "package");
        } else if (depth == 2) {
            if (tag == "application") {
                inApplication = true;
                keepTag = true;

                String8 agent = AaptXml::getAttribute(tree,
                        "http://schemas.android.com/apk/res/android",
                        "backupAgent", &error);
                if (agent.length() > 0) {
                    addProguardKeepRule(keep, agent, pkg.c_str(),
                            assFile->getPrintableSource(), tree.getLineNumber());
                }

                if (mainDex) {
                    defaultProcess = AaptXml::getAttribute(tree,
                            "http://schemas.android.com/apk/res/android", "process", &error);
                    if (error != "") {
                        fprintf(stderr, "ERROR: %s\n", error.c_str());
                        return -1;
                    }
                }
            } else if (tag == "instrumentation") {
                keepTag = true;
            }
        }
        if (!keepTag && inApplication && depth == 3) {
            if (tag == "activity" || tag == "service" || tag == "receiver" || tag == "provider") {
                keepTag = true;

                if (mainDex) {
                    String8 componentProcess = AaptXml::getAttribute(tree,
                            "http://schemas.android.com/apk/res/android", "process", &error);
                    if (error != "") {
                        fprintf(stderr, "ERROR: %s\n", error.c_str());
                        return -1;
                    }

                    const String8& process =
                            componentProcess.length() > 0 ? componentProcess : defaultProcess;
                    keepTag = process.length() > 0 && process.find(":") != 0;
                }
            }
        }
        if (keepTag) {
            String8 name = AaptXml::getAttribute(tree,
                    "http://schemas.android.com/apk/res/android", "name", &error);
            if (error != "") {
                fprintf(stderr, "ERROR: %s\n", error.c_str());
                return -1;
            }

            keepTag = name.length() > 0;

            if (keepTag) {
                addProguardKeepRule(keep, name, pkg.c_str(),
                        assFile->getPrintableSource(), tree.getLineNumber());
            }
        }
    }

    return NO_ERROR;
}

struct NamespaceAttributePair {
    const char* ns;
    const char* attr;

    NamespaceAttributePair(const char* n, const char* a) : ns(n), attr(a) {}
    NamespaceAttributePair() : ns(NULL), attr(NULL) {}
};

status_t
writeProguardForXml(ProguardKeepSet* keep, const sp<AaptFile>& layoutFile,
        const Vector<String8>& startTags, const KeyedVector<String8, Vector<NamespaceAttributePair> >* tagAttrPairs)
{
    status_t err;
    ResXMLTree tree;
    size_t len;
    ResXMLTree::event_code_t code;

    err = parseXMLResource(layoutFile, &tree);
    if (err != NO_ERROR) {
        return err;
    }

    tree.restart();

    if (!startTags.empty()) {
        bool haveStart = false;
        while ((code=tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
            if (code != ResXMLTree::START_TAG) {
                continue;
            }
            String8 tag(tree.getElementName(&len));
            const size_t numStartTags = startTags.size();
            for (size_t i = 0; i < numStartTags; i++) {
                if (tag == startTags[i]) {
                    haveStart = true;
                }
            }
            break;
        }
        if (!haveStart) {
            return NO_ERROR;
        }
    }

    while ((code=tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
        if (code != ResXMLTree::START_TAG) {
            continue;
        }
        String8 tag(tree.getElementName(&len));

        // If there is no '.', we'll assume that it's one of the built in names.
        if (strchr(tag.c_str(), '.')) {
            addProguardKeepRule(keep, tag, NULL,
                    layoutFile->getPrintableSource(), tree.getLineNumber());
        } else if (tagAttrPairs != NULL) {
            ssize_t tagIndex = tagAttrPairs->indexOfKey(tag);
            if (tagIndex >= 0) {
                const Vector<NamespaceAttributePair>& nsAttrVector = tagAttrPairs->valueAt(tagIndex);
                for (size_t i = 0; i < nsAttrVector.size(); i++) {
                    const NamespaceAttributePair& nsAttr = nsAttrVector[i];

                    ssize_t attrIndex = tree.indexOfAttribute(nsAttr.ns, nsAttr.attr);
                    if (attrIndex < 0) {
                        // fprintf(stderr, "%s:%d: <%s> does not have attribute %s:%s.\n",
                        //        layoutFile->getPrintableSource().c_str(), tree.getLineNumber(),
                        //        tag.c_str(), nsAttr.ns, nsAttr.attr);
                    } else {
                        size_t len;
                        addProguardKeepRule(keep,
                                            String8(tree.getAttributeStringValue(attrIndex, &len)), NULL,
                                            layoutFile->getPrintableSource(), tree.getLineNumber());
                    }
                }
            }
        }
        ssize_t attrIndex = tree.indexOfAttribute(RESOURCES_ANDROID_NAMESPACE, "onClick");
        if (attrIndex >= 0) {
            size_t len;
            addProguardKeepMethodRule(keep,
                                String8(tree.getAttributeStringValue(attrIndex, &len)), NULL,
                                layoutFile->getPrintableSource(), tree.getLineNumber());
        }
    }

    return NO_ERROR;
}

static void addTagAttrPair(KeyedVector<String8, Vector<NamespaceAttributePair> >* dest,
        const char* tag, const char* ns, const char* attr) {
    String8 tagStr(tag);
    ssize_t index = dest->indexOfKey(tagStr);

    if (index < 0) {
        Vector<NamespaceAttributePair> vector;
        vector.add(NamespaceAttributePair(ns, attr));
        dest->add(tagStr, vector);
    } else {
        dest->editValueAt(index).add(NamespaceAttributePair(ns, attr));
    }
}

status_t
writeProguardForLayouts(ProguardKeepSet* keep, const sp<AaptAssets>& assets)
{
    status_t err;
    const char* kClass = "class";
    const char* kFragment = "fragment";
    const String8 kTransition("transition");
    const String8 kTransitionPrefix("transition-");

    // tag:attribute pairs that should be checked in layout files.
    KeyedVector<String8, Vector<NamespaceAttributePair> > kLayoutTagAttrPairs;
    addTagAttrPair(&kLayoutTagAttrPairs, "view", NULL, kClass);
    addTagAttrPair(&kLayoutTagAttrPairs, kFragment, NULL, kClass);
    addTagAttrPair(&kLayoutTagAttrPairs, kFragment, RESOURCES_ANDROID_NAMESPACE, "name");

    // tag:attribute pairs that should be checked in xml files.
    KeyedVector<String8, Vector<NamespaceAttributePair> > kXmlTagAttrPairs;
    addTagAttrPair(&kXmlTagAttrPairs, "PreferenceScreen", RESOURCES_ANDROID_NAMESPACE, kFragment);
    addTagAttrPair(&kXmlTagAttrPairs, "header", RESOURCES_ANDROID_NAMESPACE, kFragment);

    // tag:attribute pairs that should be checked in transition files.
    KeyedVector<String8, Vector<NamespaceAttributePair> > kTransitionTagAttrPairs;
    addTagAttrPair(&kTransitionTagAttrPairs, kTransition.c_str(), NULL, kClass);
    addTagAttrPair(&kTransitionTagAttrPairs, "pathMotion", NULL, kClass);

    const Vector<sp<AaptDir> >& dirs = assets->resDirs();
    const size_t K = dirs.size();
    for (size_t k=0; k<K; k++) {
        const sp<AaptDir>& d = dirs.itemAt(k);
        const String8& dirName = d->getLeaf();
        Vector<String8> startTags;
        const KeyedVector<String8, Vector<NamespaceAttributePair> >* tagAttrPairs = NULL;
        if ((dirName == String8("layout")) || (strncmp(dirName.c_str(), "layout-", 7) == 0)) {
            tagAttrPairs = &kLayoutTagAttrPairs;
        } else if ((dirName == String8("xml")) || (strncmp(dirName.c_str(), "xml-", 4) == 0)) {
            startTags.add(String8("PreferenceScreen"));
            startTags.add(String8("preference-headers"));
            tagAttrPairs = &kXmlTagAttrPairs;
        } else if ((dirName == String8("menu")) || (strncmp(dirName.c_str(), "menu-", 5) == 0)) {
            startTags.add(String8("menu"));
            tagAttrPairs = NULL;
        } else if (dirName == kTransition || (strncmp(dirName.c_str(), kTransitionPrefix.c_str(),
                        kTransitionPrefix.size()) == 0)) {
            tagAttrPairs = &kTransitionTagAttrPairs;
        } else {
            continue;
        }

        const KeyedVector<String8,sp<AaptGroup> > groups = d->getFiles();
        const size_t N = groups.size();
        for (size_t i=0; i<N; i++) {
            const sp<AaptGroup>& group = groups.valueAt(i);
            const DefaultKeyedVector<AaptGroupEntry, sp<AaptFile> >& files = group->getFiles();
            const size_t M = files.size();
            for (size_t j=0; j<M; j++) {
                err = writeProguardForXml(keep, files.valueAt(j), startTags, tagAttrPairs);
                if (err < 0) {
                    return err;
                }
            }
        }
    }
    // Handle the overlays
    sp<AaptAssets> overlay = assets->getOverlay();
    if (overlay.get()) {
        return writeProguardForLayouts(keep, overlay);
    }

    return NO_ERROR;
}

status_t
writeProguardSpec(const char* filename, const ProguardKeepSet& keep, status_t err)
{
    FILE* fp = fopen(filename, "w+");
    if (fp == NULL) {
        fprintf(stderr, "ERROR: Unable to open class file %s: %s\n",
                filename, strerror(errno));
        return UNKNOWN_ERROR;
    }

    const KeyedVector<String8, SortedVector<String8> >& rules = keep.rules;
    const size_t N = rules.size();
    for (size_t i=0; i<N; i++) {
        const SortedVector<String8>& locations = rules.valueAt(i);
        const size_t M = locations.size();
        for (size_t j=0; j<M; j++) {
            fprintf(fp, "# %s\n", locations.itemAt(j).c_str());
        }
        fprintf(fp, "%s\n\n", rules.keyAt(i).c_str());
    }
    fclose(fp);

    return err;
}

status_t
writeProguardFile(Bundle* bundle, const sp<AaptAssets>& assets)
{
    status_t err = -1;

    if (!bundle->getProguardFile()) {
        return NO_ERROR;
    }

    ProguardKeepSet keep;

    err = writeProguardForAndroidManifest(&keep, assets, false);
    if (err < 0) {
        return err;
    }

    err = writeProguardForLayouts(&keep, assets);
    if (err < 0) {
        return err;
    }

    return writeProguardSpec(bundle->getProguardFile(), keep, err);
}

status_t
writeMainDexProguardFile(Bundle* bundle, const sp<AaptAssets>& assets)
{
    status_t err = -1;

    if (!bundle->getMainDexProguardFile()) {
        return NO_ERROR;
    }

    ProguardKeepSet keep;

    err = writeProguardForAndroidManifest(&keep, assets, true);
    if (err < 0) {
        return err;
    }

    return writeProguardSpec(bundle->getMainDexProguardFile(), keep, err);
}

// Loops through the string paths and writes them to the file pointer
// Each file path is written on its own line with a terminating backslash.
status_t writePathsToFile(const sp<FilePathStore>& files, FILE* fp)
{
    status_t deps = -1;
    for (size_t file_i = 0; file_i < files->size(); ++file_i) {
        // Add the full file path to the dependency file
        fprintf(fp, "%s \\\n", files->itemAt(file_i).c_str());
        deps++;
    }
    return deps;
}

status_t
writeDependencyPreReqs(Bundle* /* bundle */, const sp<AaptAssets>& assets, FILE* fp, bool includeRaw)
{
    status_t deps = -1;
    deps += writePathsToFile(assets->getFullResPaths(), fp);
    if (includeRaw) {
        deps += writePathsToFile(assets->getFullAssetPaths(), fp);
    }
    return deps;
}
