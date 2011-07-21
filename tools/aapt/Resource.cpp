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

#define NOISY(x) // x

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

// ==========================================================================
// ==========================================================================
// ==========================================================================

bool isValidResourceType(const String8& type)
{
    return type == "anim" || type == "animator" || type == "interpolator"
        || type == "drawable" || type == "layout"
        || type == "values" || type == "xml" || type == "raw"
        || type == "color" || type == "menu" || type == "mipmap";
}

static sp<AaptFile> getResourceFile(const sp<AaptAssets>& assets, bool makeIfNecessary=true)
{
    sp<AaptGroup> group = assets->getFiles().valueFor(String8("resources.arsc"));
    sp<AaptFile> file;
    if (group != NULL) {
        file = group->getFiles().valueFor(AaptGroupEntry());
        if (file != NULL) {
            return file;
        }
    }

    if (!makeIfNecessary) {
        return NULL;
    }
    return assets->addFile(String8("resources.arsc"), AaptGroupEntry(), String8(),
                            NULL, String8());
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

static status_t preProcessImages(Bundle* bundle, const sp<AaptAssets>& assets,
                          const sp<ResourceTypeSet>& set, const char* type)
{
    bool hasErrors = false;
    ssize_t res = NO_ERROR;
    if (bundle->getUseCrunchCache() == false) {
        ResourceDirIterator it(set, String8(type));
        Vector<sp<AaptFile> > newNameFiles;
        Vector<String8> newNamePaths;
        while ((res=it.next()) == NO_ERROR) {
            res = preProcessImage(bundle, assets, it.getFile(), NULL);
            if (res < NO_ERROR) {
                hasErrors = true;
            }
        }
    }
    return (hasErrors || (res < NO_ERROR)) ? UNKNOWN_ERROR : NO_ERROR;
}

status_t postProcessImages(const sp<AaptAssets>& assets,
                           ResourceTable* table,
                           const sp<ResourceTypeSet>& set)
{
    ResourceDirIterator it(set, String8("drawable"));
    bool hasErrors = false;
    ssize_t res;
    while ((res=it.next()) == NO_ERROR) {
        res = postProcessImage(assets, table, it.getFile());
        if (res < NO_ERROR) {
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
            set->add(leafName, group);
            resources->add(resType, set);
        } else {
            sp<ResourceTypeSet> set = resources->valueAt(index);
            index = set->indexOfKey(leafName);
            if (index < 0) {
                set->add(leafName, group);
            } else {
                sp<AaptGroup> existingGroup = set->valueAt(index);
                int M = files.size();
                for (int j=0; j<M; j++) {
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
        sp<AaptDir> d = dirs.itemAt(i);
        collect_files(d, resources);

        // don't try to include the res dir
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
                size_t baseIndex = UNKNOWN_ERROR;
                if (baseSet->get() != NULL) {
                    baseIndex = (*baseSet)->indexOfKey(overlaySet->keyAt(overlayIndex));
                }
                if (baseIndex < UNKNOWN_ERROR) {
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
                            printf("baseFile %zd has flavor %s\n", i,
                                    baseFiles.keyAt(i).toString().string());
                        }
                        for (size_t i=0; i < overlayFiles.size(); i++) {
                            printf("overlayFile %zd has flavor %s\n", i,
                                    overlayFiles.keyAt(i).toString().string());
                        }
                    }

                    size_t overlayGroupSize = overlayFiles.size();
                    for (size_t overlayGroupIndex = 0;
                            overlayGroupIndex<overlayGroupSize;
                            overlayGroupIndex++) {
                        size_t baseFileIndex =
                                baseGroup->getFiles().indexOfKey(overlayFiles.
                                keyAt(overlayGroupIndex));
                        if(baseFileIndex < UNKNOWN_ERROR) {
                            if (bundle->getVerbose()) {
                                printf("found a match (%zd) for overlay file %s, for flavor %s\n",
                                        baseFileIndex,
                                        overlayGroup->getLeaf().string(),
                                        overlayFiles.keyAt(overlayGroupIndex).toString().string());
                            }
                            baseGroup->removeFile(baseFileIndex);
                        } else {
                            // didn't find a match fall through and add it..
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

void addTagAttribute(const sp<XMLNode>& node, const char* ns8,
        const char* attr8, const char* value)
{
    if (value == NULL) {
        return;
    }
    
    const String16 ns(ns8);
    const String16 attr(attr8);
    
    if (node->getAttribute(ns, attr) != NULL) {
        fprintf(stderr, "Warning: AndroidManifest.xml already defines %s (in %s);"
                        " using existing value in manifest.\n",
                String8(attr).string(), String8(ns).string());
        return;
    }
    
    node->addAttribute(ns, attr, String16(value));
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
    
    addTagAttribute(root, RESOURCES_ANDROID_NAMESPACE, "versionCode",
            bundle->getVersionCode());
    addTagAttribute(root, RESOURCES_ANDROID_NAMESPACE, "versionName",
            bundle->getVersionName());
    
    if (bundle->getMinSdkVersion() != NULL
            || bundle->getTargetSdkVersion() != NULL
            || bundle->getMaxSdkVersion() != NULL) {
        sp<XMLNode> vers = root->getChildElement(String16(), String16("uses-sdk"));
        if (vers == NULL) {
            vers = XMLNode::newElement(root->getFilename(), String16(), String16("uses-sdk"));
            root->insertChildAt(vers, 0);
        }
        
        addTagAttribute(vers, RESOURCES_ANDROID_NAMESPACE, "minSdkVersion",
                bundle->getMinSdkVersion());
        addTagAttribute(vers, RESOURCES_ANDROID_NAMESPACE, "targetSdkVersion",
                bundle->getTargetSdkVersion());
        addTagAttribute(vers, RESOURCES_ANDROID_NAMESPACE, "maxSdkVersion",
                bundle->getMaxSdkVersion());
    }

    if (bundle->getDebugMode()) {
        sp<XMLNode> application = root->getChildElement(String16(), String16("application"));
        if (application != NULL) {
            addTagAttribute(application, RESOURCES_ANDROID_NAMESPACE, "debuggable", "true");
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

status_t buildResources(Bundle* bundle, const sp<AaptAssets>& assets)
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
    if (!bundle->getWantUTF16()
            && bundle->isMinSdkAtLeast(SDK_FROYO)) {
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
        sp<AaptFile> resFile(getResourceFile(assets));
        if (resFile == NULL) {
            fprintf(stderr, "Error: unable to generate entry for resource data\n");
            return UNKNOWN_ERROR;
        }

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
        err = postProcessImages(assets, &table, drawables);
        if (err != NO_ERROR) {
            hasErrors = true;
        }
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
            if (err != NO_ERROR) {
                hasErrors = true;
            }
            ResXMLTree block;
            block.setTo(it.getFile()->getData(), it.getFile()->getSize(), true);
            checkForIds(src, block);
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

        resFile = getResourceFile(assets);
        if (resFile == NULL) {
            fprintf(stderr, "Error: unable to generate entry for resource data\n");
            return UNKNOWN_ERROR;
        }

        err = table.flatten(bundle, resFile);
        if (err < NO_ERROR) {
            return err;
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
        
        // Read resources back in,
        finalResTable.add(resFile->getData(), resFile->getSize(), NULL);
        
#if 0
        NOISY(
              printf("Generated resources:\n");
              finalResTable.print();
        )
#endif
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

static status_t fixupSymbol(String16* inoutSymbol)
{
    inoutSymbol->replaceAll('.', '_');
    inoutSymbol->replaceAll(':', '_');
    return NO_ERROR;
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
    const sp<AaptSymbols>& symbols, int indent, bool includePrivate)
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
        String16 nclassName16(symbols->getNestedSymbols().keyAt(i));
        String8 realClassName(nclassName16);
        if (fixupSymbol(&nclassName16) != NO_ERROR) {
            hasErrors = true;
        }
        String8 nclassName(nclassName16);

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

        bool deprecated = false;
        
        String16 comment = symbols->getComment(realClassName);
        fprintf(fp, "%s/** ", indentStr);
        if (comment.size() > 0) {
            String8 cmt(comment);
            fprintf(fp, "%s\n", cmt.string());
            if (strstr(cmt.string(), "@deprecated") != NULL) {
                deprecated = true;
            }
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
                String16 name(name8);
                fixupSymbol(&name);
                fprintf(fp, "%s   <tr><td><code>{@link #%s_%s %s:%s}</code></td><td>%s</td></tr>\n",
                        indentStr, nclassName.string(),
                        String8(name).string(),
                        assets->getPackage().string(),
                        String8(name).string(),
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
                String16 name(sym.name);
                fixupSymbol(&name);
                fprintf(fp, "%s   @see #%s_%s\n",
                        indentStr, nclassName.string(),
                        String8(name).string());
            }
        }
        fprintf(fp, "%s */\n", getIndentSpace(indent));

        if (deprecated) {
            fprintf(fp, "%s@Deprecated\n", indentStr);
        }
        
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
                String16 name(name8);
                if (fixupSymbol(&name) != NO_ERROR) {
                    hasErrors = true;
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
                
                bool deprecated = false;
                
                fprintf(fp, "%s/**\n", indentStr);
                if (comment.size() > 0) {
                    String8 cmt(comment);
                    fprintf(fp, "%s  <p>\n%s  @attr description\n", indentStr, indentStr);
                    fprintf(fp, "%s  %s\n", indentStr, cmt.string());
                    if (strstr(cmt.string(), "@deprecated") != NULL) {
                        deprecated = true;
                    }
                } else {
                    fprintf(fp,
                            "%s  <p>This symbol is the offset where the {@link %s.R.attr#%s}\n"
                            "%s  attribute's value can be found in the {@link #%s} array.\n",
                            indentStr,
                            pub ? assets->getPackage().string()
                                : assets->getSymbolsPrivatePackage().string(),
                            String8(name).string(),
                            indentStr, nclassName.string());
                }
                if (typeComment.size() > 0) {
                    String8 cmt(typeComment);
                    fprintf(fp, "\n\n%s  %s\n", indentStr, cmt.string());
                    if (strstr(cmt.string(), "@deprecated") != NULL) {
                        deprecated = true;
                    }
                }
                if (comment.size() > 0) {
                    if (pub) {
                        fprintf(fp,
                                "%s  <p>This corresponds to the global attribute"
                                "%s  resource symbol {@link %s.R.attr#%s}.\n",
                                indentStr, indentStr,
                                assets->getPackage().string(),
                                String8(name).string());
                    } else {
                        fprintf(fp,
                                "%s  <p>This is a private symbol.\n", indentStr);
                    }
                }
                fprintf(fp, "%s  @attr name %s:%s\n", indentStr,
                        "android", String8(name).string());
                fprintf(fp, "%s*/\n", indentStr);
                if (deprecated) {
                    fprintf(fp, "%s@Deprecated\n", indentStr);
                }
                fprintf(fp,
                        "%spublic static final int %s_%s = %d;\n",
                        indentStr, nclassName.string(),
                        String8(name).string(), (int)pos);
            }
        }
    }

    indent--;
    fprintf(fp, "%s};\n", getIndentSpace(indent));
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
        if (!includePrivate && !sym.isPublic) {
            continue;
        }
        String16 name(sym.name);
        String8 realName(name);
        if (fixupSymbol(&name) != NO_ERROR) {
            return UNKNOWN_ERROR;
        }
        String16 comment(sym.comment);
        bool haveComment = false;
        bool deprecated = false;
        if (comment.size() > 0) {
            haveComment = true;
            String8 cmt(comment);
            fprintf(fp,
                    "%s/** %s\n",
                    getIndentSpace(indent), cmt.string());
            if (strstr(cmt.string(), "@deprecated") != NULL) {
                deprecated = true;
            }
        } else if (sym.isPublic && !includePrivate) {
            sym.sourcePos.warning("No comment for public symbol %s:%s/%s",
                assets->getPackage().string(), className.string(),
                String8(sym.name).string());
        }
        String16 typeComment(sym.typeComment);
        if (typeComment.size() > 0) {
            String8 cmt(typeComment);
            if (!haveComment) {
                haveComment = true;
                fprintf(fp,
                        "%s/** %s\n", getIndentSpace(indent), cmt.string());
            } else {
                fprintf(fp,
                        "%s %s\n", getIndentSpace(indent), cmt.string());
            }
            if (strstr(cmt.string(), "@deprecated") != NULL) {
                deprecated = true;
            }
        }
        if (haveComment) {
            fprintf(fp,"%s */\n", getIndentSpace(indent));
        }
        if (deprecated) {
            fprintf(fp, "%s@Deprecated\n", getIndentSpace(indent));
        }
        fprintf(fp, id_format,
                getIndentSpace(indent),
                String8(name).string(), (int)sym.int32Val);
    }

    for (i=0; i<N; i++) {
        const AaptSymbolEntry& sym = symbols->getSymbols().valueAt(i);
        if (sym.typeCode != AaptSymbolEntry::TYPE_STRING) {
            continue;
        }
        if (!includePrivate && !sym.isPublic) {
            continue;
        }
        String16 name(sym.name);
        if (fixupSymbol(&name) != NO_ERROR) {
            return UNKNOWN_ERROR;
        }
        String16 comment(sym.comment);
        bool deprecated = false;
        if (comment.size() > 0) {
            String8 cmt(comment);
            fprintf(fp,
                    "%s/** %s\n"
                     "%s */\n",
                    getIndentSpace(indent), cmt.string(),
                    getIndentSpace(indent));
            if (strstr(cmt.string(), "@deprecated") != NULL) {
                deprecated = true;
            }
        } else if (sym.isPublic && !includePrivate) {
            sym.sourcePos.warning("No comment for public symbol %s:%s/%s",
                assets->getPackage().string(), className.string(),
                String8(sym.name).string());
        }
        if (deprecated) {
            fprintf(fp, "%s@Deprecated\n", getIndentSpace(indent));
        }
        fprintf(fp, "%spublic static final String %s=\"%s\";\n",
                getIndentSpace(indent),
                String8(name).string(), sym.stringVal.string());
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
        err = writeLayoutClasses(fp, assets, styleableSymbols, indent, includePrivate);
        if (err != NO_ERROR) {
            return err;
        }
    }

    indent--;
    fprintf(fp, "%s}\n", getIndentSpace(indent));
    return NO_ERROR;
}

status_t writeResourceSymbols(Bundle* bundle, const sp<AaptAssets>& assets,
    const String8& package, bool includePrivate)
{
    if (!bundle->getRClassDir()) {
        return NO_ERROR;
    }

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

        status_t err = writeSymbolClass(fp, assets, includePrivate, symbols, className, 0, bundle->getNonConstantId());
        if (err != NO_ERROR) {
            return err;
        }
        fclose(fp);

        if (bundle->getGenDependencies()) {
            // Add this R.java to the dependency file
            String8 dependencyFile(bundle->getRClassDir());
            dependencyFile.appendPath("R.d");

            fp = fopen(dependencyFile.string(), "a");
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
        const char* startTag, const KeyedVector<String8, NamespaceAttributePair>* tagAttrPairs)
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

    if (startTag != NULL) {
        bool haveStart = false;
        while ((code=tree.next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
            if (code != ResXMLTree::START_TAG) {
                continue;
            }
            String8 tag(tree.getElementName(&len));
            if (tag == startTag) {
                haveStart = true;
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
                const NamespaceAttributePair& nsAttr = tagAttrPairs->valueAt(tagIndex);
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

    return NO_ERROR;
}

static void addTagAttrPair(KeyedVector<String8, NamespaceAttributePair>* dest,
        const char* tag, const char* ns, const char* attr) {
    dest->add(String8(tag), NamespaceAttributePair(ns, attr));
}

status_t
writeProguardForLayouts(ProguardKeepSet* keep, const sp<AaptAssets>& assets)
{
    status_t err;

    // tag:attribute pairs that should be checked in layout files.
    KeyedVector<String8, NamespaceAttributePair> kLayoutTagAttrPairs;
    addTagAttrPair(&kLayoutTagAttrPairs, "view", NULL, "class");
    addTagAttrPair(&kLayoutTagAttrPairs, "fragment", NULL, "class");
    addTagAttrPair(&kLayoutTagAttrPairs, "fragment", RESOURCES_ANDROID_NAMESPACE, "name");

    // tag:attribute pairs that should be checked in xml files.
    KeyedVector<String8, NamespaceAttributePair> kXmlTagAttrPairs;
    addTagAttrPair(&kXmlTagAttrPairs, "PreferenceScreen", RESOURCES_ANDROID_NAMESPACE, "fragment");
    addTagAttrPair(&kXmlTagAttrPairs, "header", RESOURCES_ANDROID_NAMESPACE, "fragment");

    const Vector<sp<AaptDir> >& dirs = assets->resDirs();
    const size_t K = dirs.size();
    for (size_t k=0; k<K; k++) {
        const sp<AaptDir>& d = dirs.itemAt(k);
        const String8& dirName = d->getLeaf();
        const char* startTag = NULL;
        const KeyedVector<String8, NamespaceAttributePair>* tagAttrPairs = NULL;
        if ((dirName == String8("layout")) || (strncmp(dirName.string(), "layout-", 7) == 0)) {
            tagAttrPairs = &kLayoutTagAttrPairs;
        } else if ((dirName == String8("xml")) || (strncmp(dirName.string(), "xml-", 4) == 0)) {
            startTag = "PreferenceScreen";
            tagAttrPairs = &kXmlTagAttrPairs;
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
                err = writeProguardForXml(keep, files.valueAt(j), startTag, tagAttrPairs);
                if (err < 0) {
                    return err;
                }
            }
        }
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
