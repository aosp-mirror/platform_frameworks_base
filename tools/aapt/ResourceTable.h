//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#ifndef RESOURCE_TABLE_H
#define RESOURCE_TABLE_H

#include "ConfigDescription.h"
#include "StringPool.h"
#include "SourcePos.h"
#include "ResourceFilter.h"

#include <map>
#include <queue>
#include <set>

using namespace std;

class XMLNode;
class ResourceTable;

enum {
    XML_COMPILE_STRIP_COMMENTS = 1<<0,
    XML_COMPILE_ASSIGN_ATTRIBUTE_IDS = 1<<1,
    XML_COMPILE_COMPACT_WHITESPACE = 1<<2,
    XML_COMPILE_STRIP_WHITESPACE = 1<<3,
    XML_COMPILE_STRIP_RAW_VALUES = 1<<4,
    XML_COMPILE_UTF8 = 1<<5,
    
    XML_COMPILE_STANDARD_RESOURCE =
            XML_COMPILE_STRIP_COMMENTS | XML_COMPILE_ASSIGN_ATTRIBUTE_IDS
            | XML_COMPILE_STRIP_WHITESPACE | XML_COMPILE_STRIP_RAW_VALUES
};

status_t compileXmlFile(const Bundle* bundle,
                        const sp<AaptAssets>& assets,
                        const String16& resourceName,
                        const sp<AaptFile>& target,
                        ResourceTable* table,
                        int options = XML_COMPILE_STANDARD_RESOURCE);

status_t compileXmlFile(const Bundle* bundle,
                        const sp<AaptAssets>& assets,
                        const String16& resourceName,
                        const sp<AaptFile>& target,
                        const sp<AaptFile>& outTarget,
                        ResourceTable* table,
                        int options = XML_COMPILE_STANDARD_RESOURCE);

status_t compileXmlFile(const Bundle* bundle,
                        const sp<AaptAssets>& assets,
                        const String16& resourceName,
                        const sp<XMLNode>& xmlTree,
                        const sp<AaptFile>& target,
                        ResourceTable* table,
                        int options = XML_COMPILE_STANDARD_RESOURCE);

status_t compileResourceFile(Bundle* bundle,
                             const sp<AaptAssets>& assets,
                             const sp<AaptFile>& in,
                             const ResTable_config& defParams,
                             const bool overwrite,
                             ResourceTable* outTable);

struct AccessorCookie
{
    SourcePos sourcePos;
    String8 attr;
    String8 value;

    AccessorCookie(const SourcePos&p, const String8& a, const String8& v)
        :sourcePos(p),
         attr(a),
         value(v)
    {
    }
};

// Holds the necessary information to compile the
// resource.
struct CompileResourceWorkItem {
    String16 resourceName;
    String8 resPath;
    sp<AaptFile> file;
};

class ResourceTable : public ResTable::Accessor
{
public:
    // The type of package to build.
    enum PackageType {
        App,
        System,
        SharedLibrary,
        AppFeature
    };

    class Package;
    class Type;
    class Entry;

    ResourceTable(Bundle* bundle, const String16& assetsPackage, PackageType type);

    const String16& getAssetsPackage() const {
        return mAssetsPackage;
    }

    /**
     * Returns the queue of resources that need to be compiled.
     * This is only used for resources that have been generated
     * during the compilation phase. If they were just added
     * to the AaptAssets, then they may be skipped over
     * and would mess up iteration order for the existing
     * resources.
     */
    queue<CompileResourceWorkItem>& getWorkQueue() {
        return mWorkQueue;
    }

    status_t addIncludedResources(Bundle* bundle, const sp<AaptAssets>& assets);

    status_t addPublic(const SourcePos& pos,
                       const String16& package,
                       const String16& type,
                       const String16& name,
                       const uint32_t ident);

    status_t addEntry(const SourcePos& pos,
                      const String16& package,
                      const String16& type,
                      const String16& name,
                      const String16& value,
                      const Vector<StringPool::entry_style_span>* style = NULL,
                      const ResTable_config* params = NULL,
                      const bool doSetIndex = false,
                      const int32_t format = ResTable_map::TYPE_ANY,
                      const bool overwrite = false);

    status_t startBag(const SourcePos& pos,
                    const String16& package,
                    const String16& type,
                    const String16& name,
                    const String16& bagParent,
                    const ResTable_config* params = NULL,
                    bool overlay = false,
                    bool replace = false,
                    bool isId = false);
    
    status_t addBag(const SourcePos& pos,
                    const String16& package,
                    const String16& type,
                    const String16& name,
                    const String16& bagParent,
                    const String16& bagKey,
                    const String16& value,
                    const Vector<StringPool::entry_style_span>* style = NULL,
                    const ResTable_config* params = NULL,
                    bool replace = false,
                    bool isId = false,
                    const int32_t format = ResTable_map::TYPE_ANY);

    bool hasBagOrEntry(const String16& package,
                       const String16& type,
                       const String16& name) const;

    bool hasBagOrEntry(const String16& package,
                       const String16& type,
                       const String16& name,
                       const ResTable_config& config) const;

    bool hasBagOrEntry(const String16& ref,
                       const String16* defType = NULL,
                       const String16* defPackage = NULL);

    bool appendComment(const String16& package,
                       const String16& type,
                       const String16& name,
                       const String16& comment,
                       bool onlyIfEmpty = false);

    bool appendTypeComment(const String16& package,
                           const String16& type,
                           const String16& name,
                           const String16& comment);
    
    void canAddEntry(const SourcePos& pos,
        const String16& package, const String16& type, const String16& name);
        
    size_t size() const;
    size_t numLocalResources() const;
    bool hasResources() const;

    status_t modifyForCompat(const Bundle* bundle);
    status_t modifyForCompat(const Bundle* bundle,
                             const String16& resourceName,
                             const sp<AaptFile>& file,
                             const sp<XMLNode>& root);

    sp<AaptFile> flatten(Bundle* bundle, const sp<const ResourceFilter>& filter,
            const bool isBase);

    static inline uint32_t makeResId(uint32_t packageId,
                                     uint32_t typeId,
                                     uint32_t nameId)
    {
        return nameId | (typeId<<16) | (packageId<<24);
    }

    static inline uint32_t getResId(const sp<Package>& p,
                                    const sp<Type>& t,
                                    uint32_t nameId);

    uint32_t getResId(const String16& package,
                      const String16& type,
                      const String16& name,
                      bool onlyPublic = true) const;

    uint32_t getResId(const String16& ref,
                      const String16* defType = NULL,
                      const String16* defPackage = NULL,
                      const char** outErrorMsg = NULL,
                      bool onlyPublic = true) const;

    static bool isValidResourceName(const String16& s);
    
    bool stringToValue(Res_value* outValue, StringPool* pool,
                       const String16& str,
                       bool preserveSpaces, bool coerceType,
                       uint32_t attrID,
                       const Vector<StringPool::entry_style_span>* style = NULL,
                       String16* outStr = NULL, void* accessorCookie = NULL,
                       uint32_t attrType = ResTable_map::TYPE_ANY,
                       const String8* configTypeName = NULL,
                       const ConfigDescription* config = NULL);

    status_t assignResourceIds();
    status_t addSymbols(const sp<AaptSymbols>& outSymbols = NULL);
    void addLocalization(const String16& name, const String8& locale, const SourcePos& src);
    status_t validateLocalizations(void);

    status_t flatten(Bundle* bundle, const sp<const ResourceFilter>& filter,
            const sp<AaptFile>& dest, const bool isBase);
    status_t flattenLibraryTable(const sp<AaptFile>& dest, const Vector<sp<Package> >& libs);

    void writePublicDefinitions(const String16& package, FILE* fp);

    virtual uint32_t getCustomResource(const String16& package,
                                       const String16& type,
                                       const String16& name) const;
    virtual uint32_t getCustomResourceWithCreation(const String16& package,
                                                   const String16& type,
                                                   const String16& name,
                                                   const bool createIfNeeded);
    virtual uint32_t getRemappedPackage(uint32_t origPackage) const;
    virtual bool getAttributeType(uint32_t attrID, uint32_t* outType);
    virtual bool getAttributeMin(uint32_t attrID, uint32_t* outMin);
    virtual bool getAttributeMax(uint32_t attrID, uint32_t* outMax);
    virtual bool getAttributeKeys(uint32_t attrID, Vector<String16>* outKeys);
    virtual bool getAttributeEnum(uint32_t attrID,
                                  const char16_t* name, size_t nameLen,
                                  Res_value* outValue);
    virtual bool getAttributeFlags(uint32_t attrID,
                                   const char16_t* name, size_t nameLen,
                                   Res_value* outValue);
    virtual uint32_t getAttributeL10N(uint32_t attrID);

    virtual bool getLocalizationSetting();
    virtual void reportError(void* accessorCookie, const char* fmt, ...);

    void setCurrentXmlPos(const SourcePos& pos) { mCurrentXmlPos = pos; }

    class Item {
    public:
        Item() : isId(false), format(ResTable_map::TYPE_ANY), bagKeyId(0), evaluating(false)
            { memset(&parsedValue, 0, sizeof(parsedValue)); }
        Item(const SourcePos& pos,
             bool _isId,
             const String16& _value,
             const Vector<StringPool::entry_style_span>* _style = NULL,
             int32_t format = ResTable_map::TYPE_ANY);
        Item(const Item& o) : sourcePos(o.sourcePos),
            isId(o.isId), value(o.value), style(o.style),
            format(o.format), bagKeyId(o.bagKeyId), evaluating(false) {
            memset(&parsedValue, 0, sizeof(parsedValue));
        }
        ~Item() { }

        Item& operator=(const Item& o) {
            sourcePos = o.sourcePos;
            isId = o.isId;
            value = o.value;
            style = o.style;
            format = o.format;
            bagKeyId = o.bagKeyId;
            parsedValue = o.parsedValue;
            return *this;
        }

        SourcePos                               sourcePos;
        mutable bool                            isId;
        String16                                value;
        Vector<StringPool::entry_style_span>    style;
        int32_t                                 format;
        uint32_t                                bagKeyId;
        mutable bool                            evaluating;
        Res_value                               parsedValue;
    };

    class Entry : public RefBase {
    public:
        Entry(const String16& name, const SourcePos& pos)
            : mName(name), mType(TYPE_UNKNOWN),
              mItemFormat(ResTable_map::TYPE_ANY), mNameIndex(-1), mPos(pos)
        { }

        Entry(const Entry& entry);
        Entry& operator=(const Entry& entry);

        virtual ~Entry() { }

        enum type {
            TYPE_UNKNOWN = 0,
            TYPE_ITEM,
            TYPE_BAG
        };
        
        String16 getName() const { return mName; }
        type getType() const { return mType; }

        void setParent(const String16& parent) { mParent = parent; }
        String16 getParent() const { return mParent; }

        status_t makeItABag(const SourcePos& sourcePos);

        status_t emptyBag(const SourcePos& sourcePos);
 
        status_t setItem(const SourcePos& pos,
                         const String16& value,
                         const Vector<StringPool::entry_style_span>* style = NULL,
                         int32_t format = ResTable_map::TYPE_ANY,
                         const bool overwrite = false);

        status_t addToBag(const SourcePos& pos,
                          const String16& key, const String16& value,
                          const Vector<StringPool::entry_style_span>* style = NULL,
                          bool replace=false, bool isId = false,
                          int32_t format = ResTable_map::TYPE_ANY);

        status_t removeFromBag(const String16& key);

        // Index of the entry's name string in the key pool.
        int32_t getNameIndex() const { return mNameIndex; }
        void setNameIndex(int32_t index) { mNameIndex = index; }

        const Item* getItem() const { return mType == TYPE_ITEM ? &mItem : NULL; }
        const KeyedVector<String16, Item>& getBag() const { return mBag; }

        status_t generateAttributes(ResourceTable* table,
                                    const String16& package);

        status_t assignResourceIds(ResourceTable* table,
                                   const String16& package);

        status_t prepareFlatten(StringPool* strings, ResourceTable* table,
               const String8* configTypeName, const ConfigDescription* config);

        status_t remapStringValue(StringPool* strings);

        ssize_t flatten(Bundle*, const sp<AaptFile>& data, bool isPublic);

        const SourcePos& getPos() const { return mPos; }

    private:
        String16 mName;
        String16 mParent;
        type mType;
        Item mItem;
        int32_t mItemFormat;
        KeyedVector<String16, Item> mBag;
        int32_t mNameIndex;
        uint32_t mParentId;
        SourcePos mPos;
    };
    
    class ConfigList : public RefBase {
    public:
        ConfigList(const String16& name, const SourcePos& pos)
            : mName(name), mPos(pos), mPublic(false), mEntryIndex(-1) { }
        virtual ~ConfigList() { }
        
        String16 getName() const { return mName; }
        const SourcePos& getPos() const { return mPos; }
        
        void appendComment(const String16& comment, bool onlyIfEmpty = false);
        const String16& getComment() const { return mComment; }
        
        void appendTypeComment(const String16& comment);
        const String16& getTypeComment() const { return mTypeComment; }
        
        // Index of this entry in its Type.
        int32_t getEntryIndex() const { return mEntryIndex; }
        void setEntryIndex(int32_t index) { mEntryIndex = index; }
        
        void setPublic(bool pub) { mPublic = pub; }
        bool getPublic() const { return mPublic; }
        void setPublicSourcePos(const SourcePos& pos) { mPublicSourcePos = pos; }
        const SourcePos& getPublicSourcePos() { return mPublicSourcePos; }
        
        void addEntry(const ResTable_config& config, const sp<Entry>& entry) {
            mEntries.add(config, entry);
        }
        
        const DefaultKeyedVector<ConfigDescription, sp<Entry> >& getEntries() const { return mEntries; }
    private:
        const String16 mName;
        const SourcePos mPos;
        String16 mComment;
        String16 mTypeComment;
        bool mPublic;
        SourcePos mPublicSourcePos;
        int32_t mEntryIndex;
        DefaultKeyedVector<ConfigDescription, sp<Entry> > mEntries;
    };
    
    class Public {
    public:
        Public() : sourcePos(), ident(0) { }
        Public(const SourcePos& pos,
               const String16& _comment,
               uint32_t _ident)
            : sourcePos(pos),
            comment(_comment), ident(_ident) { }
        Public(const Public& o) : sourcePos(o.sourcePos),
            comment(o.comment), ident(o.ident) { }
        ~Public() { }
        
        Public& operator=(const Public& o) {
            sourcePos = o.sourcePos;
            comment = o.comment;
            ident = o.ident;
            return *this;
        }
        
        SourcePos   sourcePos;
        String16    comment;
        uint32_t    ident;
    };
    
    class Type : public RefBase {
    public:
        Type(const String16& name, const SourcePos& pos)
                : mName(name), mFirstPublicSourcePos(NULL), mPublicIndex(-1), mIndex(-1), mPos(pos)
        { }
        virtual ~Type() { delete mFirstPublicSourcePos; }

        status_t addPublic(const SourcePos& pos,
                           const String16& name,
                           const uint32_t ident);
                           
        void canAddEntry(const String16& name);
        
        String16 getName() const { return mName; }
        sp<Entry> getEntry(const String16& entry,
                           const SourcePos& pos,
                           const ResTable_config* config = NULL,
                           bool doSetIndex = false,
                           bool overlay = false,
                           bool autoAddOverlay = false);

        const SourcePos& getFirstPublicSourcePos() const { return *mFirstPublicSourcePos; }

        int32_t getPublicIndex() const { return mPublicIndex; }

        int32_t getIndex() const { return mIndex; }
        void setIndex(int32_t index) { mIndex = index; }

        status_t applyPublicEntryOrder();

        const SortedVector<ConfigDescription>& getUniqueConfigs() const { return mUniqueConfigs; }
        
        const DefaultKeyedVector<String16, sp<ConfigList> >& getConfigs() const { return mConfigs; }
        const Vector<sp<ConfigList> >& getOrderedConfigs() const { return mOrderedConfigs; }

        const SortedVector<String16>& getCanAddEntries() const { return mCanAddEntries; }
        
        const SourcePos& getPos() const { return mPos; }
    private:
        String16 mName;
        SourcePos* mFirstPublicSourcePos;
        DefaultKeyedVector<String16, Public> mPublic;
        SortedVector<ConfigDescription> mUniqueConfigs;
        DefaultKeyedVector<String16, sp<ConfigList> > mConfigs;
        Vector<sp<ConfigList> > mOrderedConfigs;
        SortedVector<String16> mCanAddEntries;
        int32_t mPublicIndex;
        int32_t mIndex;
        SourcePos mPos;
    };

    class Package : public RefBase {
    public:
        Package(const String16& name, size_t packageId);
        virtual ~Package() { }

        String16 getName() const { return mName; }
        sp<Type> getType(const String16& type,
                         const SourcePos& pos,
                         bool doSetIndex = false);

        size_t getAssignedId() const { return mPackageId; }

        const ResStringPool& getTypeStrings() const { return mTypeStrings; }
        uint32_t indexOfTypeString(const String16& s) const { return mTypeStringsMapping.valueFor(s); }
        const sp<AaptFile> getTypeStringsData() const { return mTypeStringsData; }
        status_t setTypeStrings(const sp<AaptFile>& data);

        const ResStringPool& getKeyStrings() const { return mKeyStrings; }
        uint32_t indexOfKeyString(const String16& s) const { return mKeyStringsMapping.valueFor(s); }
        const sp<AaptFile> getKeyStringsData() const { return mKeyStringsData; }
        status_t setKeyStrings(const sp<AaptFile>& data);

        status_t applyPublicTypeOrder();

        const DefaultKeyedVector<String16, sp<Type> >& getTypes() const { return mTypes; }
        const Vector<sp<Type> >& getOrderedTypes() const { return mOrderedTypes; }

    private:
        status_t setStrings(const sp<AaptFile>& data,
                            ResStringPool* strings,
                            DefaultKeyedVector<String16, uint32_t>* mappings);

        const String16 mName;
        const size_t mPackageId;
        DefaultKeyedVector<String16, sp<Type> > mTypes;
        Vector<sp<Type> > mOrderedTypes;
        sp<AaptFile> mTypeStringsData;
        sp<AaptFile> mKeyStringsData;
        ResStringPool mTypeStrings;
        ResStringPool mKeyStrings;
        DefaultKeyedVector<String16, uint32_t> mTypeStringsMapping;
        DefaultKeyedVector<String16, uint32_t> mKeyStringsMapping;
    };

private:
    void writePublicDefinitions(const String16& package, FILE* fp, bool pub);
    sp<Package> getPackage(const String16& package);
    sp<Type> getType(const String16& package,
                     const String16& type,
                     const SourcePos& pos,
                     bool doSetIndex = false);
    sp<Entry> getEntry(const String16& package,
                       const String16& type,
                       const String16& name,
                       const SourcePos& pos,
                       bool overlay,
                       const ResTable_config* config = NULL,
                       bool doSetIndex = false);
    sp<const Entry> getEntry(uint32_t resID,
                             const ResTable_config* config = NULL) const;
    sp<ConfigList> getConfigList(const String16& package,
                                 const String16& type,
                                 const String16& name) const;
    const Item* getItem(uint32_t resID, uint32_t attrID) const;
    bool getItemValue(uint32_t resID, uint32_t attrID,
                      Res_value* outValue);
    bool isAttributeFromL(uint32_t attrId);


    String16 mAssetsPackage;
    PackageType mPackageType;
    sp<AaptAssets> mAssets;
    uint32_t mTypeIdOffset;
    DefaultKeyedVector<String16, sp<Package> > mPackages;
    Vector<sp<Package> > mOrderedPackages;
    size_t mNumLocal;
    SourcePos mCurrentXmlPos;
    Bundle* mBundle;
    
    // key = string resource name, value = set of locales in which that name is defined
    map<String16, map<String8, SourcePos> > mLocalizations;
    queue<CompileResourceWorkItem> mWorkQueue;
};

#endif
