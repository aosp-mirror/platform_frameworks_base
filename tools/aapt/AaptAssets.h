//
// Copyright 2006 The Android Open Source Project
//
// Information about assets being operated on.
//
#ifndef __AAPT_ASSETS_H
#define __AAPT_ASSETS_H

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <stdlib.h>
#include <set>
#include <utils/KeyedVector.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>
#include <utils/Vector.h>

#include "AaptConfig.h"
#include "Bundle.h"
#include "ConfigDescription.h"
#include "SourcePos.h"
#include "ZipFile.h"

using namespace android;

extern const char * const gDefaultIgnoreAssets;
extern const char * gUserIgnoreAssets;

bool valid_symbol_name(const String8& str);

class AaptAssets;

enum {
    AXIS_NONE = 0,
    AXIS_MCC = 1,
    AXIS_MNC,
    AXIS_LOCALE,
    AXIS_SCREENLAYOUTSIZE,
    AXIS_SCREENLAYOUTLONG,
    AXIS_ORIENTATION,
    AXIS_UIMODETYPE,
    AXIS_UIMODENIGHT,
    AXIS_DENSITY,
    AXIS_TOUCHSCREEN,
    AXIS_KEYSHIDDEN,
    AXIS_KEYBOARD,
    AXIS_NAVHIDDEN,
    AXIS_NAVIGATION,
    AXIS_SCREENSIZE,
    AXIS_SMALLESTSCREENWIDTHDP,
    AXIS_SCREENWIDTHDP,
    AXIS_SCREENHEIGHTDP,
    AXIS_LAYOUTDIR,
    AXIS_VERSION,

    AXIS_START = AXIS_MCC,
    AXIS_END = AXIS_VERSION,
};

struct AaptLocaleValue {
     char language[4];
     char region[4];
     char script[4];
     char variant[8];

     AaptLocaleValue() {
         memset(this, 0, sizeof(AaptLocaleValue));
     }

     // Initialize this AaptLocaleValue from a config string.
     bool initFromFilterString(const String8& config);

     int initFromDirName(const Vector<String8>& parts, const int startIndex);

     // Initialize this AaptLocaleValue from a ResTable_config.
     void initFromResTable(const ResTable_config& config);

     void writeTo(ResTable_config* out) const;

     int compare(const AaptLocaleValue& other) const {
         return memcmp(this, &other, sizeof(AaptLocaleValue));
     }

     inline bool operator<(const AaptLocaleValue& o) const { return compare(o) < 0; }
     inline bool operator<=(const AaptLocaleValue& o) const { return compare(o) <= 0; }
     inline bool operator==(const AaptLocaleValue& o) const { return compare(o) == 0; }
     inline bool operator!=(const AaptLocaleValue& o) const { return compare(o) != 0; }
     inline bool operator>=(const AaptLocaleValue& o) const { return compare(o) >= 0; }
     inline bool operator>(const AaptLocaleValue& o) const { return compare(o) > 0; }
private:
     void setLanguage(const char* language);
     void setRegion(const char* language);
     void setScript(const char* script);
     void setVariant(const char* variant);
};

/**
 * This structure contains a specific variation of a single file out
 * of all the variations it can have that we can have.
 */
struct AaptGroupEntry
{
public:
    AaptGroupEntry() {}
    AaptGroupEntry(const ConfigDescription& config) : mParams(config) {}

    bool initFromDirName(const char* dir, String8* resType);

    inline const ConfigDescription& toParams() const { return mParams; }

    inline int compare(const AaptGroupEntry& o) const { return mParams.compareLogical(o.mParams); }
    inline bool operator<(const AaptGroupEntry& o) const { return compare(o) < 0; }
    inline bool operator<=(const AaptGroupEntry& o) const { return compare(o) <= 0; }
    inline bool operator==(const AaptGroupEntry& o) const { return compare(o) == 0; }
    inline bool operator!=(const AaptGroupEntry& o) const { return compare(o) != 0; }
    inline bool operator>=(const AaptGroupEntry& o) const { return compare(o) >= 0; }
    inline bool operator>(const AaptGroupEntry& o) const { return compare(o) > 0; }

    String8 toString() const { return mParams.toString(); }
    String8 toDirName(const String8& resType) const;

    const String8 getVersionString() const { return AaptConfig::getVersion(mParams); }

private:
    ConfigDescription mParams;
};

inline int compare_type(const AaptGroupEntry& lhs, const AaptGroupEntry& rhs)
{
    return lhs.compare(rhs);
}

inline int strictly_order_type(const AaptGroupEntry& lhs, const AaptGroupEntry& rhs)
{
    return compare_type(lhs, rhs) < 0;
}

class AaptGroup;
class FilePathStore;

/**
 * A single asset file we know about.
 */
class AaptFile : public RefBase
{
public:
    AaptFile(const String8& sourceFile, const AaptGroupEntry& groupEntry,
             const String8& resType)
        : mGroupEntry(groupEntry)
        , mResourceType(resType)
        , mSourceFile(sourceFile)
        , mData(NULL)
        , mDataSize(0)
        , mBufferSize(0)
        , mCompression(ZipEntry::kCompressStored)
        {
            //printf("new AaptFile created %s\n", (const char*)sourceFile);
        }
    virtual ~AaptFile() {
        free(mData);
    }

    const String8& getPath() const { return mPath; }
    const AaptGroupEntry& getGroupEntry() const { return mGroupEntry; }

    // Data API.  If there is data attached to the file,
    // getSourceFile() is not used.
    bool hasData() const { return mData != NULL; }
    const void* getData() const { return mData; }
    size_t getSize() const { return mDataSize; }
    void* editData(size_t size);
    void* editData(size_t* outSize = NULL);
    void* editDataInRange(size_t offset, size_t size);
    void* padData(size_t wordSize);
    status_t writeData(const void* data, size_t size);
    void clearData();

    const String8& getResourceType() const { return mResourceType; }

    // File API.  If the file does not hold raw data, this is
    // a full path to a file on the filesystem that holds its data.
    const String8& getSourceFile() const { return mSourceFile; }

    String8 getPrintableSource() const;

    // Desired compression method, as per utils/ZipEntry.h.  For example,
    // no compression is ZipEntry::kCompressStored.
    int getCompressionMethod() const { return mCompression; }
    void setCompressionMethod(int c) { mCompression = c; }
private:
    friend class AaptGroup;

    String8 mPath;
    AaptGroupEntry mGroupEntry;
    String8 mResourceType;
    String8 mSourceFile;
    void* mData;
    size_t mDataSize;
    size_t mBufferSize;
    int mCompression;
};

/**
 * A group of related files (the same file, with different
 * vendor/locale variations).
 */
class AaptGroup : public RefBase
{
public:
    AaptGroup(const String8& leaf, const String8& path)
        : mLeaf(leaf), mPath(path) { }
    virtual ~AaptGroup() { }

    const String8& getLeaf() const { return mLeaf; }

    // Returns the relative path after the AaptGroupEntry dirs.
    const String8& getPath() const { return mPath; }

    const DefaultKeyedVector<AaptGroupEntry, sp<AaptFile> >& getFiles() const
        { return mFiles; }

    status_t addFile(const sp<AaptFile>& file, const bool overwriteDuplicate=false);
    void removeFile(size_t index);

    void print(const String8& prefix) const;

    String8 getPrintableSource() const;

private:
    String8 mLeaf;
    String8 mPath;

    DefaultKeyedVector<AaptGroupEntry, sp<AaptFile> > mFiles;
};

/**
 * A single directory of assets, which can contain files and other
 * sub-directories.
 */
class AaptDir : public RefBase
{
public:
    AaptDir(const String8& leaf, const String8& path)
        : mLeaf(leaf), mPath(path) { }
    virtual ~AaptDir() { }

    const String8& getLeaf() const { return mLeaf; }

    const String8& getPath() const { return mPath; }

    const DefaultKeyedVector<String8, sp<AaptGroup> >& getFiles() const { return mFiles; }
    const DefaultKeyedVector<String8, sp<AaptDir> >& getDirs() const { return mDirs; }

    virtual status_t addFile(const String8& name, const sp<AaptGroup>& file);

    void removeFile(const String8& name);
    void removeDir(const String8& name);

    /*
     * Perform some sanity checks on the names of files and directories here.
     * In particular:
     *  - Check for illegal chars in filenames.
     *  - Check filename length.
     *  - Check for presence of ".gz" and non-".gz" copies of same file.
     *  - Check for multiple files whose names match in a case-insensitive
     *    fashion (problematic for some systems).
     *
     * Comparing names against all other names is O(n^2).  We could speed
     * it up some by sorting the entries and being smarter about what we
     * compare against, but I'm not expecting to have enough files in a
     * single directory to make a noticeable difference in speed.
     *
     * Note that sorting here is not enough to guarantee that the package
     * contents are sorted -- subsequent updates can rearrange things.
     */
    status_t validate() const;

    void print(const String8& prefix) const;

    String8 getPrintableSource() const;

private:
    friend class AaptAssets;

    status_t addDir(const String8& name, const sp<AaptDir>& dir);
    sp<AaptDir> makeDir(const String8& name);
    status_t addLeafFile(const String8& leafName,
                         const sp<AaptFile>& file,
                         const bool overwrite=false);
    virtual ssize_t slurpFullTree(Bundle* bundle,
                                  const String8& srcDir,
                                  const AaptGroupEntry& kind,
                                  const String8& resType,
                                  sp<FilePathStore>& fullResPaths,
                                  const bool overwrite=false);

    String8 mLeaf;
    String8 mPath;

    DefaultKeyedVector<String8, sp<AaptGroup> > mFiles;
    DefaultKeyedVector<String8, sp<AaptDir> > mDirs;
};

/**
 * All information we know about a particular symbol.
 */
class AaptSymbolEntry
{
public:
    AaptSymbolEntry()
        : isPublic(false), isJavaSymbol(false), typeCode(TYPE_UNKNOWN)
    {
    }
    AaptSymbolEntry(const String8& _name)
        : name(_name), isPublic(false), isJavaSymbol(false), typeCode(TYPE_UNKNOWN)
    {
    }
    AaptSymbolEntry(const AaptSymbolEntry& o)
        : name(o.name), sourcePos(o.sourcePos), isPublic(o.isPublic)
        , isJavaSymbol(o.isJavaSymbol), comment(o.comment), typeComment(o.typeComment)
        , typeCode(o.typeCode), int32Val(o.int32Val), stringVal(o.stringVal)
    {
    }
    AaptSymbolEntry operator=(const AaptSymbolEntry& o)
    {
        sourcePos = o.sourcePos;
        isPublic = o.isPublic;
        isJavaSymbol = o.isJavaSymbol;
        comment = o.comment;
        typeComment = o.typeComment;
        typeCode = o.typeCode;
        int32Val = o.int32Val;
        stringVal = o.stringVal;
        return *this;
    }
    
    const String8 name;
    
    SourcePos sourcePos;
    bool isPublic;
    bool isJavaSymbol;
    
    String16 comment;
    String16 typeComment;
    
    enum {
        TYPE_UNKNOWN = 0,
        TYPE_INT32,
        TYPE_STRING
    };
    
    int typeCode;
    
    // Value.  May be one of these.
    int32_t int32Val;
    String8 stringVal;
};

/**
 * A group of related symbols (such as indices into a string block)
 * that have been generated from the assets.
 */
class AaptSymbols : public RefBase
{
public:
    AaptSymbols() { }
    virtual ~AaptSymbols() { }

    status_t addSymbol(const String8& name, int32_t value, const SourcePos& pos) {
        if (!check_valid_symbol_name(name, pos, "symbol")) {
            return BAD_VALUE;
        }
        AaptSymbolEntry& sym = edit_symbol(name, &pos);
        sym.typeCode = AaptSymbolEntry::TYPE_INT32;
        sym.int32Val = value;
        return NO_ERROR;
    }

    status_t addStringSymbol(const String8& name, const String8& value,
            const SourcePos& pos) {
        if (!check_valid_symbol_name(name, pos, "symbol")) {
            return BAD_VALUE;
        }
        AaptSymbolEntry& sym = edit_symbol(name, &pos);
        sym.typeCode = AaptSymbolEntry::TYPE_STRING;
        sym.stringVal = value;
        return NO_ERROR;
    }

    status_t makeSymbolPublic(const String8& name, const SourcePos& pos) {
        if (!check_valid_symbol_name(name, pos, "symbol")) {
            return BAD_VALUE;
        }
        AaptSymbolEntry& sym = edit_symbol(name, &pos);
        sym.isPublic = true;
        return NO_ERROR;
    }

    status_t makeSymbolJavaSymbol(const String8& name, const SourcePos& pos) {
        if (!check_valid_symbol_name(name, pos, "symbol")) {
            return BAD_VALUE;
        }
        AaptSymbolEntry& sym = edit_symbol(name, &pos);
        sym.isJavaSymbol = true;
        return NO_ERROR;
    }

    void appendComment(const String8& name, const String16& comment, const SourcePos& pos) {
        if (comment.size() <= 0) {
            return;
        }
        AaptSymbolEntry& sym = edit_symbol(name, &pos);
        if (sym.comment.size() == 0) {
            sym.comment = comment;
        } else {
            sym.comment.append(String16("\n"));
            sym.comment.append(comment);
        }
    }

    void appendTypeComment(const String8& name, const String16& comment) {
        if (comment.size() <= 0) {
            return;
        }
        AaptSymbolEntry& sym = edit_symbol(name, NULL);
        if (sym.typeComment.size() == 0) {
            sym.typeComment = comment;
        } else {
            sym.typeComment.append(String16("\n"));
            sym.typeComment.append(comment);
        }
    }
    
    sp<AaptSymbols> addNestedSymbol(const String8& name, const SourcePos& pos) {
        if (!check_valid_symbol_name(name, pos, "nested symbol")) {
            return NULL;
        }
        
        sp<AaptSymbols> sym = mNestedSymbols.valueFor(name);
        if (sym == NULL) {
            sym = new AaptSymbols();
            mNestedSymbols.add(name, sym);
        }

        return sym;
    }

    status_t applyJavaSymbols(const sp<AaptSymbols>& javaSymbols);

    const KeyedVector<String8, AaptSymbolEntry>& getSymbols() const
        { return mSymbols; }
    const DefaultKeyedVector<String8, sp<AaptSymbols> >& getNestedSymbols() const
        { return mNestedSymbols; }

    const String16& getComment(const String8& name) const
        { return get_symbol(name).comment; }
    const String16& getTypeComment(const String8& name) const
        { return get_symbol(name).typeComment; }

private:
    bool check_valid_symbol_name(const String8& symbol, const SourcePos& pos, const char* label) {
        if (valid_symbol_name(symbol)) {
            return true;
        }
        pos.error("invalid %s: '%s'\n", label, symbol.string());
        return false;
    }
    AaptSymbolEntry& edit_symbol(const String8& symbol, const SourcePos* pos) {
        ssize_t i = mSymbols.indexOfKey(symbol);
        if (i < 0) {
            i = mSymbols.add(symbol, AaptSymbolEntry(symbol));
        }
        AaptSymbolEntry& sym = mSymbols.editValueAt(i);
        if (pos != NULL && sym.sourcePos.line < 0) {
            sym.sourcePos = *pos;
        }
        return sym;
    }
    const AaptSymbolEntry& get_symbol(const String8& symbol) const {
        ssize_t i = mSymbols.indexOfKey(symbol);
        if (i >= 0) {
            return mSymbols.valueAt(i);
        }
        return mDefSymbol;
    }

    KeyedVector<String8, AaptSymbolEntry>           mSymbols;
    DefaultKeyedVector<String8, sp<AaptSymbols> >   mNestedSymbols;
    AaptSymbolEntry                                 mDefSymbol;
};

class ResourceTypeSet : public RefBase,
                        public KeyedVector<String8,sp<AaptGroup> >
{
public:
    ResourceTypeSet();
};

// Storage for lists of fully qualified paths for
// resources encountered during slurping.
class FilePathStore : public RefBase,
                      public Vector<String8>
{
public:
    FilePathStore();
};

/**
 * Asset hierarchy being operated on.
 */
class AaptAssets : public AaptDir
{
public:
    AaptAssets();
    virtual ~AaptAssets() { delete mRes; }

    const String8& getPackage() const { return mPackage; }
    void setPackage(const String8& package) {
        mPackage = package;
        mSymbolsPrivatePackage = package;
        mHavePrivateSymbols = false;
    }

    const SortedVector<AaptGroupEntry>& getGroupEntries() const;

    virtual status_t addFile(const String8& name, const sp<AaptGroup>& file);

    sp<AaptFile> addFile(const String8& filePath,
                         const AaptGroupEntry& entry,
                         const String8& srcDir,
                         sp<AaptGroup>* outGroup,
                         const String8& resType);

    void addResource(const String8& leafName,
                     const String8& path,
                     const sp<AaptFile>& file,
                     const String8& resType);

    void addGroupEntry(const AaptGroupEntry& entry) { mGroupEntries.add(entry); }
    
    ssize_t slurpFromArgs(Bundle* bundle);

    sp<AaptSymbols> getSymbolsFor(const String8& name);

    sp<AaptSymbols> getJavaSymbolsFor(const String8& name);

    status_t applyJavaSymbols();

    const DefaultKeyedVector<String8, sp<AaptSymbols> >& getSymbols() const { return mSymbols; }

    String8 getSymbolsPrivatePackage() const { return mSymbolsPrivatePackage; }
    void setSymbolsPrivatePackage(const String8& pkg) {
        mSymbolsPrivatePackage = pkg;
        mHavePrivateSymbols = mSymbolsPrivatePackage != mPackage;
    }

    bool havePrivateSymbols() const { return mHavePrivateSymbols; }

    bool isJavaSymbol(const AaptSymbolEntry& sym, bool includePrivate) const;

    status_t buildIncludedResources(Bundle* bundle);
    status_t addIncludedResources(const sp<AaptFile>& file);
    const ResTable& getIncludedResources() const;
    AssetManager& getAssetManager();

    void print(const String8& prefix) const;

    inline const Vector<sp<AaptDir> >& resDirs() const { return mResDirs; }
    sp<AaptDir> resDir(const String8& name) const;

    inline sp<AaptAssets> getOverlay() { return mOverlay; }
    inline void setOverlay(sp<AaptAssets>& overlay) { mOverlay = overlay; }
    
    inline KeyedVector<String8, sp<ResourceTypeSet> >* getResources() { return mRes; }
    inline void 
        setResources(KeyedVector<String8, sp<ResourceTypeSet> >* res) { delete mRes; mRes = res; }

    inline sp<FilePathStore>& getFullResPaths() { return mFullResPaths; }
    inline void
        setFullResPaths(sp<FilePathStore>& res) { mFullResPaths = res; }

    inline sp<FilePathStore>& getFullAssetPaths() { return mFullAssetPaths; }
    inline void
        setFullAssetPaths(sp<FilePathStore>& res) { mFullAssetPaths = res; }

private:
    virtual ssize_t slurpFullTree(Bundle* bundle,
                                  const String8& srcDir,
                                  const AaptGroupEntry& kind,
                                  const String8& resType,
                                  sp<FilePathStore>& fullResPaths,
                                  const bool overwrite=false);

    ssize_t slurpResourceTree(Bundle* bundle, const String8& srcDir);
    ssize_t slurpResourceZip(Bundle* bundle, const char* filename);

    status_t filter(Bundle* bundle);

    String8 mPackage;
    SortedVector<AaptGroupEntry> mGroupEntries;
    DefaultKeyedVector<String8, sp<AaptSymbols> > mSymbols;
    DefaultKeyedVector<String8, sp<AaptSymbols> > mJavaSymbols;
    String8 mSymbolsPrivatePackage;
    bool mHavePrivateSymbols;

    Vector<sp<AaptDir> > mResDirs;

    bool mChanged;

    bool mHaveIncludedAssets;
    AssetManager mIncludedAssets;

    sp<AaptAssets> mOverlay;
    KeyedVector<String8, sp<ResourceTypeSet> >* mRes;

    sp<FilePathStore> mFullResPaths;
    sp<FilePathStore> mFullAssetPaths;
};

#endif // __AAPT_ASSETS_H

