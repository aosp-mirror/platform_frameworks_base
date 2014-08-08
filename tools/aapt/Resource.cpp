//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//
#include "Main.h"
#include "AaptAssets.h"
#include "StringPool.h"
#include "XMLNode.h"
#include "ResourceTable.h"
#include "Images.h"

#include "CrunchCache.h"
#include "FileFinder.h"
#include "CacheUpdater.h"

#include "WorkQueue.h"

#if HAVE_PRINTF_ZD
#  define ZD "%zd"
#  define ZD_TYPE ssize_t
#else
#  define ZD "%ld"
#  define ZD_TYPE long
#endif

#define NOISY(x) // x

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

static String8 parseResourceName(const String8& leaf)
{
    const char* firstDot = strchr(leaf.string(), '.');
    const char* str = leaf.string();

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
            NOISY(printf("Dir %s: mcc=%d mnc=%d lang=%c%c cnt=%c%c orient=%d ui=%d density=%d touch=%d key=%d inp=%d nav=%d\n",
                   group->getPath().string(), mParams.mcc, mParams.mnc,
                   mParams.language[0] ? mParams.language[0] : '-',
                   mParams.language[1] ? mParams.language[1] : '-',
                   mParams.country[0] ? mParams.country[0] : '-',
                   mParams.country[1] ? mParams.country[1] : '-',
                   mParams.orientation, mParams.uiMode,
                   mParams.density, mParams.touchscreen, mParams.keyboard,
                   mParams.inputFlags, mParams.navigation));
            mPath = "res";
            mPath.appendPath(file->getGroupEntry().toDirName(mResType));
            mPath.appendPath(leaf);
            mBaseName = parseResourceName(leaf);
            if (mBaseName == "") {
                fprintf(stderr, "Error: malformed resource filename %s\n",
                        file->getPrintableSource().string());
                return UNKNOWN_ERROR;
            }

            NOISY(printf("file name=%s\n", mBaseName.string()));

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
        || type == "transition"
        || type == "drawable" || type == "layout"
        || type == "values" || type == "xml" || type == "raw"
        || type == "color" || type == "menu" || type == "mipmap";
}

static status_t parsePackage(Bundle* bundle, const sp<AaptAssets>& assets,
    const sp<AaptGroup>& grp)
{
    if (grp->getFiles().size() != 1) {
        fprintf(stderr, "warning: Multiple AndroidManifest.xml files found, using %s\n",
                grp->getFiles().valueAt(0)->getPrintableSource().string());
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
                file->getPrintableSource().string(), block.getLineNumber());
        return UNKNOWN_ERROR;
    }
    if (strcmp16(block.getElementName(&len), String16("manifest").string()) != 0) {
        fprintf(stderr, "%s:%d: Invalid start tag %s, expected <manifest>\n",
                file->getPrintableSource().string(), block.getLineNumber(),
                String8(block.getElementName(&len)).string());
        return UNKNOWN_ERROR;
    }

    ssize_t nameIndex = block.indexOfAttribute(NULL, "package");
    if (nameIndex < 0) {
        fprintf(stderr, "%s:%d: <manifest> does not have package attribute.\n",
                file->getPrintableSource().string(), block.getLineNumber());
        return UNKNOWN_ERROR;
    }

    assets->setPackage(String8(block.getAttributeStringValue(nameIndex, &len)));

    String16 uses_sdk16("uses-sdk");
    while ((code=block.next()) != ResXMLTree::END_DOCUMENT
           && code != ResXMLTree::BAD_DOCUMENT) {
        if (code == ResXMLTree::START_TAG) {
            if (strcmp16(block.getElementName(&len), uses_sdk16.string()) == 0) {
                ssize_t minSdkIndex = block.indexOfAttribute(RESOURCES_ANDROID_NAMESPACE,
                                                             "minSdkVersion");
                if (minSdkIndex >= 0) {
                    const uint16_t* minSdk16 = block.getAttributeStringValue(minSdkIndex, &len);
                    const char* minSdk8 = strdup(String8(minSdk16).string());
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
                   it.getBaseName().string(), it.getFile()->getPrintableSource().string());
        }
        String16 baseName(it.getBaseName());
        const char16_t* str = baseName.string();
        const char16_t* const end = str + baseName.size();
        while (str < end) {
            if (!((*str >= 'a' && *str <= 'z')
                    || (*str >= '0' && *str <= '9')
                    || *str == '_' || *str == '.')) {
                fprintf(stderr, "%s: Invalid file name: must contain only [a-z0-9_.]\n",
                        it.getPath().string());
                hasErrors = true;
            }
            str++;
        }
        String8 resPath = it.getPath();
        resPath.convertToResPath();
        table->addEntry(SourcePos(it.getPath(), 0), String16(assets->getPackage()),
                        type16,
                        baseName,
                        String16(resPath),
                        NULL,
                        &it.getParams());
        assets->addResource(it.getLeafName(), resPath, it.getFile(), type8);
    }

    return hasErrors ? UNKNOWN_ERROR : NO_ERROR;
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
    return (hasErrors || (res < NO_ERROR)) ? UNKNOWN_ERROR : NO_ERROR;
}

static void collect_files(const sp<AaptDir>& dir,
        KeyedVector<String8, sp<ResourceTypeSet> >* resources)
{
    const DefaultKeyedVector<String8, sp<AaptGroup> >& groups = dir->getFiles();
    int N = groups.size();
    for (int i=0; i<N; i++) {
        String8 leafName = groups.keyAt(i);
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
            NOISY(printf("Creating new resource type set for leaf %s with group %s (%p)\n",
                    leafName.string(), group->getPath().string(), group.get()));
            set->add(leafName, group);
            resources->add(resType, set);
        } else {
            sp<ResourceTypeSet> set = resources->valueAt(index);
            index = set->indexOfKey(leafName);
            if (index < 0) {
                NOISY(printf("Adding to resource type set for leaf %s group %s (%p)\n",
                        leafName.string(), group->getPath().string(), group.get()));
                set->add(leafName, group);
            } else {
                sp<AaptGroup> existingGroup = set->valueAt(index);
                NOISY(printf("Extending to resource type set for leaf %s group %s (%p)\n",
                        leafName.string(), group->getPath().string(), group.get()));
                for (size_t j=0; j<files.size(); j++) {
                    NOISY(printf("Adding file %s in group %s resType %s\n",
                        files.valueAt(j)->getSourceFile().string(),
                        files.keyAt(j).toDirName(String8()).string(),
                        resType.string()));
                    status_t err = existingGroup->addFile(files.valueAt(j));
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
        sp<AaptDir> d = dirs.itemAt(i);
        NOISY(printf("Collecting dir #%d %p: %s, leaf %s\n", i, d.get(), d->getPath().string(),
                d->getLeaf().string()));
        collect_files(d, resources);

        // don't try to include the res dir
        NOISY(printf("Removing dir leaf %s\n", d->getLeaf().string()));
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
    const uint16_t* str;
    Res_value value;
    if (index >= 0 && parser.getAttributeValue(index, &value) >= 0) {
        const ResStringPool* pool = &parser.getStrings();
        if (value.dataType == Res_value::TYPE_REFERENCE) {
            uint32_t specFlags = 0;
            int strIdx;
            if ((strIdx=table.resolveReference(&value, 0x10000000, NULL, &specFlags)) < 0) {
                fprintf(stderr, "%s:%d: Tag <%s> attribute %s references unknown resid 0x%08x.\n",
                        path.string(), parser.getLineNumber(),
                        String8(parser.getElementName(&len)).string(), attr,
                        value.data);
                return ATTR_NOT_FOUND;
            }
            
            pool = table.getTableStringBlock(strIdx);
            #if 0
            if (pool != NULL) {
                str = pool->stringAt(value.data, &len);
            }
            printf("***** RES ATTR: %s specFlags=0x%x strIdx=%d: %s\n", attr,
                    specFlags, strIdx, str != NULL ? String8(str).string() : "???");
            #endif
            if ((specFlags&~ResTable_typeSpec::SPEC_PUBLIC) != 0 && false) {
                fprintf(stderr, "%s:%d: Tag <%s> attribute %s varies by configurations 0x%x.\n",
                        path.string(), parser.getLineNumber(),
                        String8(parser.getElementName(&len)).string(), attr,
                        specFlags);
                return ATTR_NOT_FOUND;
            }
        }
        if (value.dataType == Res_value::TYPE_STRING) {
            if (pool == NULL) {
                fprintf(stderr, "%s:%d: Tag <%s> attribute %s has no string block.\n",
                        path.string(), parser.getLineNumber(),
                        String8(parser.getElementName(&len)).string(), attr);
                return ATTR_NOT_FOUND;
            }
            if ((str=pool->stringAt(value.data, &len)) == NULL) {
                fprintf(stderr, "%s:%d: Tag <%s> attribute %s has corrupt string value.\n",
                        path.string(), parser.getLineNumber(),
                        String8(parser.getElementName(&len)).string(), attr);
                return ATTR_NOT_FOUND;
            }
        } else {
            fprintf(stderr, "%s:%d: Tag <%s> attribute %s has invalid type %d.\n",
                    path.string(), parser.getLineNumber(),
                    String8(parser.getElementName(&len)).string(), attr,
                    value.dataType);
            return ATTR_NOT_FOUND;
        }
        if (validChars) {
            for (size_t i=0; i<len; i++) {
                uint16_t c = str[i];
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
                            path.string(), parser.getLineNumber(),
                            String8(parser.getElementName(&len)).string(), attr, (char)str[i]);
                    return (int)i;
                }
            }
        }
        if (*str == ' ') {
            fprintf(stderr, "%s:%d: Tag <%s> attribute %s can not start with a space.\n",
                    path.string(), parser.getLineNumber(),
                    String8(parser.getElementName(&len)).string(), attr);
            return ATTR_LEADING_SPACES;
        }
        if (str[len-1] == ' ') {
            fprintf(stderr, "%s:%d: Tag <%s> attribute %s can not end with a space.\n",
                    path.string(), parser.getLineNumber(),
                    String8(parser.getElementName(&len)).string(), attr);
            return ATTR_TRAILING_SPACES;
        }
        return ATTR_OKAY;
    }
    if (required) {
        fprintf(stderr, "%s:%d: Tag <%s> missing required attribute %s.\n",
                path.string(), parser.getLineNumber(),
                String8(parser.getElementName(&len)).string(), attr);
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
                        path.string(), parser.getLineNumber());
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
            sp<ResourceTypeSet> overlaySet = overlayRes->valueAt(index);

            // for each of the resources, check for a match in the previously built
            // non-overlay "baseset".
            size_t overlayCount = overlaySet->size();
            for (size_t overlayIndex=0; overlayIndex<overlayCount; overlayIndex++) {
                if (bundle->getVerbose()) {
                    printf("trying overlaySet Key=%s\n",overlaySet->keyAt(overlayIndex).string());
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
                                    baseFiles.keyAt(i).toString().string());
                        }
                        for (size_t i=0; i < overlayFiles.size(); i++) {
                            printf("overlayFile " ZD " has flavor %s\n", (ZD_TYPE) i,
                                    overlayFiles.keyAt(i).toString().string());
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
                                        overlayGroup->getLeaf().string(),
                                        overlayFiles.keyAt(overlayGroupIndex).toString().string());
                            }
                            baseGroup->removeFile(baseFileIndex);
                        } else {
                            // didn't find a match fall through and add it..
                            if (true || bundle->getVerbose()) {
                                printf("nothing matches overlay file %s, for flavor %s\n",
                                        overlayGroup->getLeaf().string(),
                                        overlayFiles.keyAt(overlayGroupIndex).toString().string());
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
            NOISY(printf("Info: AndroidManifest.xml already defines %s (in %s);"
                         " overwriting existing value from manifest.\n",
                         String8(attr).string(), String8(ns).string()));
            existingEntry->string = String16(value);
            return true;
        }

        if (errorOnFailedInsert) {
            fprintf(stderr, "Error: AndroidManifest.xml already defines %s (in %s);"
                            " cannot insert new value %s.\n",
                    String8(attr).string(), String8(ns).string(), value);
            return false;
        }

        fprintf(stderr, "Warning: AndroidManifest.xml already defines %s (in %s);"
                        " using existing value in manifest.\n",
                String8(attr).string(), String8(ns).string());

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

static void fullyQualifyClassName(const String8& package, sp<XMLNode> node,
        const String16& attrName) {
    XMLNode::attribute_entry* attr = node->editAttribute(
            String16("http://schemas.android.com/apk/res/android"), attrName);
    if (attr != NULL) {
        String8 name(attr->string);

        // asdf     --> package.asdf
        // .asdf  .a.b  --> package.asdf package.a.b
        // asdf.adsf --> asdf.asdf
        String8 className;
        const char* p = name.string();
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
        NOISY(printf("Qualifying class '%s' to '%s'", name.string(), className.string()));
        attr->string.setTo(String16(className));
    }
}

status_t massageManifest(Bundle* bundle, sp<XMLNode> root)
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
    }
    if (!addTagAttribute(root, RESOURCES_ANDROID_NAMESPACE, "versionName",
            bundle->getVersionName(), errorOnFailedInsert, replaceVersion)) {
        return UNKNOWN_ERROR;
    }
    
    if (bundle->getMinSdkVersion() != NULL
            || bundle->getTargetSdkVersion() != NULL
            || bundle->getMaxSdkVersion() != NULL) {
        sp<XMLNode> vers = root->getChildElement(String16(), String16("uses-sdk"));
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
        attr->string.setTo(String16(manifestPackageNameOverride));
        NOISY(printf("Overriding package '%s' to be '%s'\n", origPackage.string(), manifestPackageNameOverride));

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
                        String16("http://schemas.android.com/apk/res/android"), String16("targetPackage"));
                if (attr != NULL) {
                    attr->string.setTo(String16(instrumentationPackageNameOverride));
                }
            }
        }
    }
    
    return NO_ERROR;
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
    const char* packageName = assets->getPackage();
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

    // Add the 'split' attribute which describes the configurations included.
    String8 splitName("config_");
    splitName.append(split->getDirectorySafeName());
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

    int err = compileXmlFile(assets, root, outFile, table);
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

    NOISY(printf("Creating resources for package %s\n",
                 assets->getPackage().string()));

    ResourceTable table(bundle, String16(assets->getPackage()));
    err = table.addIncludedResources(bundle, assets);
    if (err != NO_ERROR) {
        return err;
    }

    NOISY(printf("Found %d included resource packages\n", (int)table.size()));

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
                sp<AaptFile> file = it.getFile();
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
            err = compileXmlFile(assets, it.getFile(), &table, xmlFlags);
            if (err == NO_ERROR) {
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
            err = compileXmlFile(assets, it.getFile(), &table, xmlFlags);
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
            err = compileXmlFile(assets, it.getFile(), &table, xmlFlags);
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
            err = compileXmlFile(assets, it.getFile(), &table, xmlFlags);
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
            err = compileXmlFile(assets, it.getFile(), &table, xmlFlags);
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
            err = compileXmlFile(assets, it.getFile(), &table, xmlFlags);
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
            err = postProcessImage(assets, &table, it.getFile());
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
            err = compileXmlFile(assets, it.getFile(), &table, xmlFlags);
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
            err = compileXmlFile(assets, it.getFile(), &table, xmlFlags);
            if (err == NO_ERROR) {
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

    if (table.validateLocalizations()) {
        hasErrors = true;
    }
    
    if (hasErrors) {
        return UNKNOWN_ERROR;
    }

    const sp<AaptFile> manifestFile(androidManifestFile->getFiles().valueAt(0));
    String8 manifestPath(manifestFile->getPrintableSource());

    // Generate final compiled manifest file.
    manifestFile->clearData();
    sp<XMLNode> manifestTree = XMLNode::parse(manifestFile);
    if (manifestTree == NULL) {
        return UNKNOWN_ERROR;
    }
    err = massageManifest(bundle, manifestTree);
    if (err < NO_ERROR) {
        return err;
    }
    err = compileXmlFile(assets, manifestTree, manifestFile, &table);
    if (err < NO_ERROR) {
        return err;
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
        err = table.addSymbols(symbols);
        if (err < NO_ERROR) {
            return err;
        }

        Vector<sp<ApkSplit> >& splits = builder->getSplits();
        const size_t numSplits = splits.size();
        for (size_t i = 0; i < numSplits; i++) {
            sp<ApkSplit>& split = splits.editItemAt(i);
            sp<AaptFile> flattenedTable = new AaptFile(String8("resources.arsc"),
                    AaptGroupEntry(), String8());
            err = table.flatten(bundle, split->getResourceFilter(), flattenedTable);
            if (err != NO_ERROR) {
                fprintf(stderr, "Failed to generate resource table for split '%s'\n",
                        split->getPrintableName().string());
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
                sp<AaptFile> generatedManifest = new AaptFile(String8("AndroidManifest.xml"),
                        AaptGroupEntry(), String8());
                err = generateAndroidManifestForSplit(bundle, assets, split,
                        generatedManifest, &table);
                if (err != NO_ERROR) {
                    fprintf(stderr, "Failed to generate AndroidManifest.xml for split '%s'\n",
                            split->getPrintableName().string());
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
    err = compileXmlFile(assets, manifestFile,
            outManifestFile, &table,
            XML_COMPILE_ASSIGN_ATTRIBUTE_IDS
            | XML_COMPILE_STRIP_WHITESPACE | XML_COMPILE_STRIP_RAW_VALUES);
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
            if (strcmp16(block.getElementName(&len), manifest16.string()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block, NULL, "package",
                                 packageIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "sharedUserId", packageIdentChars, false) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), permission16.string()) == 0
                    || strcmp16(block.getElementName(&len), permission_group16.string()) == 0) {
                const bool isGroup = strcmp16(block.getElementName(&len),
                        permission_group16.string()) == 0;
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
                const uint16_t* id = block.getAttributeStringValue(index, &len);
                if (id == NULL) {
                    fprintf(stderr, "%s:%d: missing name attribute in element <%s>.\n", 
                            manifestPath.string(), block.getLineNumber(),
                            String8(block.getElementName(&len)).string());
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
                          manifestPath.string(), block.getLineNumber(), idStr.string());
                  hasErrors = true;
                }
                syms->addStringSymbol(String8(e), idStr, srcPos);
                const uint16_t* cmt = block.getComment(&len);
                if (cmt != NULL && *cmt != 0) {
                    //printf("Comment of %s: %s\n", String8(e).string(),
                    //        String8(cmt).string());
                    syms->appendComment(String8(e), String16(cmt), srcPos);
                } else {
                    //printf("No comment for %s\n", String8(e).string());
                }
                syms->makeSymbolPublic(String8(e), srcPos);
            } else if (strcmp16(block.getElementName(&len), uses_permission16.string()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "name", packageIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), instrumentation16.string()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block, RESOURCES_ANDROID_NAMESPACE,
                                 "name", classIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "targetPackage",
                                 packageIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), application16.string()) == 0) {
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
            } else if (strcmp16(block.getElementName(&len), provider16.string()) == 0) {
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
            } else if (strcmp16(block.getElementName(&len), service16.string()) == 0
                       || strcmp16(block.getElementName(&len), receiver16.string()) == 0
                       || strcmp16(block.getElementName(&len), activity16.string()) == 0) {
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
            } else if (strcmp16(block.getElementName(&len), action16.string()) == 0
                       || strcmp16(block.getElementName(&len), category16.string()) == 0) {
                if (validateAttr(manifestPath, finalResTable, block,
                                 RESOURCES_ANDROID_NAMESPACE, "name",
                                 packageIdentChars, true) != ATTR_OKAY) {
                    hasErrors = true;
                }
            } else if (strcmp16(block.getElementName(&len), data16.string()) == 0) {
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
            }
        }
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
        return String8(symbol.string(), colon);
    }
    return pub ? assets->getPackage() : assets->getSymbolsPrivatePackage();
}

static String8 getSymbolName(const String8& symbol) {
    ssize_t colon = symbol.find(":", 0);
    if (colon >= 0) {
        return String8(symbol.string() + colon + 1);
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
            //     name.string(), String8(asym->getComment(name)).string());
            if (outTypeComment != NULL) {
                *outTypeComment = asym->getTypeComment(name);
            }
            return asym->getComment(name);
        }
    }
    return String16();
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
                    name16.string(), name16.size(),
                    attr16.string(), attr16.size(),
                    package16.string(), package16.size(), &typeSpecFlags);
                if (code == 0) {
                    fprintf(stderr, "ERROR: In <declare-styleable> %s, unable to find attribute %s\n",
                            nclassName.string(), sym.name.string());
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
            fprintf(fp, "%s\n", cmt.string());
        } else {
            fprintf(fp, "Attributes that can be used with a %s.\n", nclassName.string());
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
                if (comment.size() > 0) {
                    const char16_t* p = comment.string();
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
                    comment = String16(comment.string(), p-comment.string());
                }
                fprintf(fp, "%s   <tr><td><code>{@link #%s_%s %s:%s}</code></td><td>%s</td></tr>\n",
                        indentStr, nclassName.string(),
                        flattenSymbol(name8).string(),
                        getSymbolPackage(name8, assets, true).string(),
                        getSymbolName(name8).string(),
                        String8(comment).string());
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
                        indentStr, nclassName.string(),
                        flattenSymbol(sym.name).string());
            }
        }
        fprintf(fp, "%s */\n", getIndentSpace(indent));

        ann.printAnnotations(fp, indentStr);
        
        fprintf(fp,
                "%spublic static final int[] %s = {\n"
                "%s",
                indentStr, nclassName.string(),
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
                    name16.string(), name16.size(),
                    attr16.string(), attr16.size(),
                    package16.string(), package16.size(), &typeSpecFlags);
                //printf("%s:%s/%s: 0x%08x\n", String8(package16).string(),
                //    String8(attr16).string(), String8(name16).string(), typeSpecFlags);
                const bool pub = (typeSpecFlags&ResTable_typeSpec::SPEC_PUBLIC) != 0;

                AnnotationProcessor ann;
                fprintf(fp, "%s/**\n", indentStr);
                if (comment.size() > 0) {
                    String8 cmt(comment);
                    ann.preprocessComment(cmt);
                    fprintf(fp, "%s  <p>\n%s  @attr description\n", indentStr, indentStr);
                    fprintf(fp, "%s  %s\n", indentStr, cmt.string());
                } else {
                    fprintf(fp,
                            "%s  <p>This symbol is the offset where the {@link %s.R.attr#%s}\n"
                            "%s  attribute's value can be found in the {@link #%s} array.\n",
                            indentStr,
                            getSymbolPackage(name8, assets, pub).string(),
                            getSymbolName(name8).string(),
                            indentStr, nclassName.string());
                }
                if (typeComment.size() > 0) {
                    String8 cmt(typeComment);
                    ann.preprocessComment(cmt);
                    fprintf(fp, "\n\n%s  %s\n", indentStr, cmt.string());
                }
                if (comment.size() > 0) {
                    if (pub) {
                        fprintf(fp,
                                "%s  <p>This corresponds to the global attribute\n"
                                "%s  resource symbol {@link %s.R.attr#%s}.\n",
                                indentStr, indentStr,
                                getSymbolPackage(name8, assets, true).string(),
                                getSymbolName(name8).string());
                    } else {
                        fprintf(fp,
                                "%s  <p>This is a private symbol.\n", indentStr);
                    }
                }
                fprintf(fp, "%s  @attr name %s:%s\n", indentStr,
                        getSymbolPackage(name8, assets, pub).string(),
                        getSymbolName(name8).string());
                fprintf(fp, "%s*/\n", indentStr);
                ann.printAnnotations(fp, indentStr);

                const char * id_format = nonConstantId ?
                        "%spublic static int %s_%s = %d;\n" :
                        "%spublic static final int %s_%s = %d;\n";

                fprintf(fp,
                        id_format,
                        indentStr, nclassName.string(),
                        flattenSymbol(name8).string(), (int)pos);
            }
        }
    }

    indent--;
    fprintf(fp, "%s};\n", getIndentSpace(indent));
    return hasErrors ? UNKNOWN_ERROR : NO_ERROR;
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
                    name16.string(), name16.size(),
                    attr16.string(), attr16.size(),
                    package16.string(), package16.size(), &typeSpecFlags);
                if (code == 0) {
                    fprintf(stderr, "ERROR: In <declare-styleable> %s, unable to find attribute %s\n",
                            nclassName.string(), sym.name.string());
                    hasErrors = true;
                }
                isPublic = (typeSpecFlags&ResTable_typeSpec::SPEC_PUBLIC) != 0;
            }
            idents.add(code);
            origOrder.add(code);
            publicFlags.add(isPublic);
        }

        NA = idents.size();

        fprintf(fp, "int[] styleable %s {", nclassName.string());

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
                    name16.string(), name16.size(),
                    attr16.string(), attr16.size(),
                    package16.string(), package16.size(), &typeSpecFlags);
                //printf("%s:%s/%s: 0x%08x\n", String8(package16).string(),
                //    String8(attr16).string(), String8(name16).string(), typeSpecFlags);
                const bool pub = (typeSpecFlags&ResTable_typeSpec::SPEC_PUBLIC) != 0;

                fprintf(fp,
                        "int styleable %s_%s %d\n",
                        nclassName.string(),
                        flattenSymbol(name8).string(), (int)pos);
            }
        }
    }

    return hasErrors ? UNKNOWN_ERROR : NO_ERROR;
}

static status_t writeSymbolClass(
    FILE* fp, const sp<AaptAssets>& assets, bool includePrivate,
    const sp<AaptSymbols>& symbols, const String8& className, int indent,
    bool nonConstantId)
{
    fprintf(fp, "%spublic %sfinal class %s {\n",
            getIndentSpace(indent),
            indent != 0 ? "static " : "", className.string());
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
                    getIndentSpace(indent), cmt.string());
        } else if (sym.isPublic && !includePrivate) {
            sym.sourcePos.warning("No comment for public symbol %s:%s/%s",
                assets->getPackage().string(), className.string(),
                String8(sym.name).string());
        }
        String16 typeComment(sym.typeComment);
        if (typeComment.size() > 0) {
            String8 cmt(typeComment);
            ann.preprocessComment(cmt);
            if (!haveComment) {
                haveComment = true;
                fprintf(fp,
                        "%s/** %s\n", getIndentSpace(indent), cmt.string());
            } else {
                fprintf(fp,
                        "%s %s\n", getIndentSpace(indent), cmt.string());
            }
        }
        if (haveComment) {
            fprintf(fp,"%s */\n", getIndentSpace(indent));
        }
        ann.printAnnotations(fp, getIndentSpace(indent));
        fprintf(fp, id_format,
                getIndentSpace(indent),
                flattenSymbol(name8).string(), (int)sym.int32Val);
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
                    getIndentSpace(indent), cmt.string(),
                    getIndentSpace(indent));
        } else if (sym.isPublic && !includePrivate) {
            sym.sourcePos.warning("No comment for public symbol %s:%s/%s",
                assets->getPackage().string(), className.string(),
                String8(sym.name).string());
        }
        ann.printAnnotations(fp, getIndentSpace(indent));
        fprintf(fp, "%spublic static final String %s=\"%s\";\n",
                getIndentSpace(indent),
                flattenSymbol(name8).string(), sym.stringVal.string());
    }

    sp<AaptSymbols> styleableSymbols;

    N = symbols->getNestedSymbols().size();
    for (i=0; i<N; i++) {
        sp<AaptSymbols> nsymbols = symbols->getNestedSymbols().valueAt(i);
        String8 nclassName(symbols->getNestedSymbols().keyAt(i));
        if (nclassName == "styleable") {
            styleableSymbols = nsymbols;
        } else {
            err = writeSymbolClass(fp, assets, includePrivate, nsymbols, nclassName, indent, nonConstantId);
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
                className.string(),
                flattenSymbol(name8).string(), (int)sym.int32Val);
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
    const String8& package, bool includePrivate)
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
            String8 pkg(package);
            const char* last = pkg.string();
            const char* s = last-1;
            do {
                s++;
                if (s > last && (*s == '.' || *s == 0)) {
                    String8 part(last, s-last);
                    dest.appendPath(part);
#ifdef HAVE_MS_C_RUNTIME
                    _mkdir(dest.string());
#else
                    mkdir(dest.string(), S_IRUSR|S_IWUSR|S_IXUSR|S_IRGRP|S_IXGRP);
#endif
                    last = s+1;
                }
            } while (*s);
        }
        dest.appendPath(className);
        dest.append(".java");
        FILE* fp = fopen(dest.string(), "w+");
        if (fp == NULL) {
            fprintf(stderr, "ERROR: Unable to open class file %s: %s\n",
                    dest.string(), strerror(errno));
            return UNKNOWN_ERROR;
        }
        if (bundle->getVerbose()) {
            printf("  Writing symbols for class %s.\n", className.string());
        }

        fprintf(fp,
            "/* AUTO-GENERATED FILE.  DO NOT MODIFY.\n"
            " *\n"
            " * This class was automatically generated by the\n"
            " * aapt tool from the resource data it found.  It\n"
            " * should not be modified by hand.\n"
            " */\n"
            "\n"
            "package %s;\n\n", package.string());

        status_t err = writeSymbolClass(fp, assets, includePrivate, symbols,
                className, 0, bundle->getNonConstantId());
        fclose(fp);
        if (err != NO_ERROR) {
            return err;
        }

        if (textSymbolsDest != NULL && R == className) {
            String8 textDest(textSymbolsDest);
            textDest.appendPath(className);
            textDest.append(".txt");

            FILE* fp = fopen(textDest.string(), "w+");
            if (fp == NULL) {
                fprintf(stderr, "ERROR: Unable to open text symbol file %s: %s\n",
                        textDest.string(), strerror(errno));
                return UNKNOWN_ERROR;
            }
            if (bundle->getVerbose()) {
                printf("  Writing text symbols for class %s.\n", className.string());
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
            dependencyFile.appendPath("R.java.d");

            FILE *fp = fopen(dependencyFile.string(), "a");
            fprintf(fp,"%s \\\n", dest.string());
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
        const char* p = className.string();
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
        const char* pkg, const String8& srcName, int line)
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
writeProguardForAndroidManifest(ProguardKeepSet* keep, const sp<AaptAssets>& assets)
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

    // First, look for a package file to parse.  This is required to
    // be able to generate the resource information.
    assGroup = assets->getFiles().valueFor(String8("AndroidManifest.xml"));
    if (assGroup == NULL) {
        fprintf(stderr, "ERROR: No AndroidManifest.xml file found.\n");
        return -1;
    }

    if (assGroup->getFiles().size() != 1) {
        fprintf(stderr, "warning: Multiple AndroidManifest.xml files found, using %s\n",
                assGroup->getFiles().valueAt(0)->getPrintableSource().string());
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
        // printf("Depth %d tag %s\n", depth, tag.string());
        bool keepTag = false;
        if (depth == 1) {
            if (tag != "manifest") {
                fprintf(stderr, "ERROR: manifest does not start with <manifest> tag\n");
                return -1;
            }
            pkg = getAttribute(tree, NULL, "package", NULL);
        } else if (depth == 2) {
            if (tag == "application") {
                inApplication = true;
                keepTag = true;

                String8 agent = getAttribute(tree, "http://schemas.android.com/apk/res/android",
                        "backupAgent", &error);
                if (agent.length() > 0) {
                    addProguardKeepRule(keep, agent, pkg.string(),
                            assFile->getPrintableSource(), tree.getLineNumber());
                }
            } else if (tag == "instrumentation") {
                keepTag = true;
            }
        }
        if (!keepTag && inApplication && depth == 3) {
            if (tag == "activity" || tag == "service" || tag == "receiver" || tag == "provider") {
                keepTag = true;
            }
        }
        if (keepTag) {
            String8 name = getAttribute(tree, "http://schemas.android.com/apk/res/android",
                    "name", &error);
            if (error != "") {
                fprintf(stderr, "ERROR: %s\n", error.string());
                return -1;
            }
            if (name.length() > 0) {
                addProguardKeepRule(keep, name, pkg.string(),
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

    if (!startTags.isEmpty()) {
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
        if (strchr(tag.string(), '.')) {
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
                        //        layoutFile->getPrintableSource().string(), tree.getLineNumber(),
                        //        tag.string(), nsAttr.ns, nsAttr.attr);
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

    // tag:attribute pairs that should be checked in layout files.
    KeyedVector<String8, Vector<NamespaceAttributePair> > kLayoutTagAttrPairs;
    addTagAttrPair(&kLayoutTagAttrPairs, "view", NULL, "class");
    addTagAttrPair(&kLayoutTagAttrPairs, "fragment", NULL, "class");
    addTagAttrPair(&kLayoutTagAttrPairs, "fragment", RESOURCES_ANDROID_NAMESPACE, "name");

    // tag:attribute pairs that should be checked in xml files.
    KeyedVector<String8, Vector<NamespaceAttributePair> > kXmlTagAttrPairs;
    addTagAttrPair(&kXmlTagAttrPairs, "PreferenceScreen", RESOURCES_ANDROID_NAMESPACE, "fragment");
    addTagAttrPair(&kXmlTagAttrPairs, "header", RESOURCES_ANDROID_NAMESPACE, "fragment");

    const Vector<sp<AaptDir> >& dirs = assets->resDirs();
    const size_t K = dirs.size();
    for (size_t k=0; k<K; k++) {
        const sp<AaptDir>& d = dirs.itemAt(k);
        const String8& dirName = d->getLeaf();
        Vector<String8> startTags;
        const char* startTag = NULL;
        const KeyedVector<String8, Vector<NamespaceAttributePair> >* tagAttrPairs = NULL;
        if ((dirName == String8("layout")) || (strncmp(dirName.string(), "layout-", 7) == 0)) {
            tagAttrPairs = &kLayoutTagAttrPairs;
        } else if ((dirName == String8("xml")) || (strncmp(dirName.string(), "xml-", 4) == 0)) {
            startTags.add(String8("PreferenceScreen"));
            startTags.add(String8("preference-headers"));
            tagAttrPairs = &kXmlTagAttrPairs;
        } else if ((dirName == String8("menu")) || (strncmp(dirName.string(), "menu-", 5) == 0)) {
            startTags.add(String8("menu"));
            tagAttrPairs = NULL;
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
writeProguardFile(Bundle* bundle, const sp<AaptAssets>& assets)
{
    status_t err = -1;

    if (!bundle->getProguardFile()) {
        return NO_ERROR;
    }

    ProguardKeepSet keep;

    err = writeProguardForAndroidManifest(&keep, assets);
    if (err < 0) {
        return err;
    }

    err = writeProguardForLayouts(&keep, assets);
    if (err < 0) {
        return err;
    }

    FILE* fp = fopen(bundle->getProguardFile(), "w+");
    if (fp == NULL) {
        fprintf(stderr, "ERROR: Unable to open class file %s: %s\n",
                bundle->getProguardFile(), strerror(errno));
        return UNKNOWN_ERROR;
    }

    const KeyedVector<String8, SortedVector<String8> >& rules = keep.rules;
    const size_t N = rules.size();
    for (size_t i=0; i<N; i++) {
        const SortedVector<String8>& locations = rules.valueAt(i);
        const size_t M = locations.size();
        for (size_t j=0; j<M; j++) {
            fprintf(fp, "# %s\n", locations.itemAt(j).string());
        }
        fprintf(fp, "%s\n\n", rules.keyAt(i).string());
    }
    fclose(fp);

    return err;
}

// Loops through the string paths and writes them to the file pointer
// Each file path is written on its own line with a terminating backslash.
status_t writePathsToFile(const sp<FilePathStore>& files, FILE* fp)
{
    status_t deps = -1;
    for (size_t file_i = 0; file_i < files->size(); ++file_i) {
        // Add the full file path to the dependency file
        fprintf(fp, "%s \\\n", files->itemAt(file_i).string());
        deps++;
    }
    return deps;
}

status_t
writeDependencyPreReqs(Bundle* bundle, const sp<AaptAssets>& assets, FILE* fp, bool includeRaw)
{
    status_t deps = -1;
    deps += writePathsToFile(assets->getFullResPaths(), fp);
    if (includeRaw) {
        deps += writePathsToFile(assets->getFullAssetPaths(), fp);
    }
    return deps;
}
