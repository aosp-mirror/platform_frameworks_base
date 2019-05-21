//
// Copyright 2006 The Android Open Source Project
//

#include "AaptAssets.h"
#include "AaptConfig.h"
#include "AaptUtil.h"
#include "Main.h"
#include "ResourceFilter.h"

#include <utils/misc.h>
#include <utils/SortedVector.h>

#include <ctype.h>
#include <dirent.h>
#include <errno.h>

static const char* kAssetDir = "assets";
static const char* kResourceDir = "res";
static const char* kValuesDir = "values";
static const char* kMipmapDir = "mipmap";
static const char* kInvalidChars = "/\\:";
static const size_t kMaxAssetFileName = 100;

static const String8 kResString(kResourceDir);

/*
 * Names of asset files must meet the following criteria:
 *
 *  - the filename length must be less than kMaxAssetFileName bytes long
 *    (and can't be empty)
 *  - all characters must be 7-bit printable ASCII
 *  - none of { '/' '\\' ':' }
 *
 * Pass in just the filename, not the full path.
 */
static bool validateFileName(const char* fileName)
{
    const char* cp = fileName;
    size_t len = 0;

    while (*cp != '\0') {
        if ((*cp & 0x80) != 0)
            return false;           // reject high ASCII
        if (*cp < 0x20 || *cp >= 0x7f)
            return false;           // reject control chars and 0x7f
        if (strchr(kInvalidChars, *cp) != NULL)
            return false;           // reject path sep chars
        cp++;
        len++;
    }

    if (len < 1 || len > kMaxAssetFileName)
        return false;               // reject empty or too long

    return true;
}

// The default to use if no other ignore pattern is defined.
const char * const gDefaultIgnoreAssets =
    "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~";
// The ignore pattern that can be passed via --ignore-assets in Main.cpp
const char * gUserIgnoreAssets = NULL;

static bool isHidden(const char *root, const char *path)
{
    // Patterns syntax:
    // - Delimiter is :
    // - Entry can start with the flag ! to avoid printing a warning
    //   about the file being ignored.
    // - Entry can have the flag "<dir>" to match only directories
    //   or <file> to match only files. Default is to match both.
    // - Entry can be a simplified glob "<prefix>*" or "*<suffix>"
    //   where prefix/suffix must have at least 1 character (so that
    //   we don't match a '*' catch-all pattern.)
    // - The special filenames "." and ".." are always ignored.
    // - Otherwise the full string is matched.
    // - match is not case-sensitive.

    if (strcmp(path, ".") == 0 || strcmp(path, "..") == 0) {
        return true;
    }

    const char *delim = ":";
    const char *p = gUserIgnoreAssets;
    if (!p || !p[0]) {
        p = getenv("ANDROID_AAPT_IGNORE");
    }
    if (!p || !p[0]) {
        p = gDefaultIgnoreAssets;
    }
    char *patterns = strdup(p);

    bool ignore = false;
    bool chatty = true;
    char *matchedPattern = NULL;

    String8 fullPath(root);
    fullPath.appendPath(path);
    FileType type = getFileType(fullPath);

    int plen = strlen(path);

    // Note: we don't have strtok_r under mingw.
    for(char *token = strtok(patterns, delim);
            !ignore && token != NULL;
            token = strtok(NULL, delim)) {
        chatty = token[0] != '!';
        if (!chatty) token++; // skip !
        if (strncasecmp(token, "<dir>" , 5) == 0) {
            if (type != kFileTypeDirectory) continue;
            token += 5;
        }
        if (strncasecmp(token, "<file>", 6) == 0) {
            if (type != kFileTypeRegular) continue;
            token += 6;
        }

        matchedPattern = token;
        int n = strlen(token);

        if (token[0] == '*') {
            // Match *suffix
            token++;
            n--;
            if (n <= plen) {
                ignore = strncasecmp(token, path + plen - n, n) == 0;
            }
        } else if (n > 1 && token[n - 1] == '*') {
            // Match prefix*
            ignore = strncasecmp(token, path, n - 1) == 0;
        } else {
            ignore = strcasecmp(token, path) == 0;
        }
    }

    if (ignore && chatty) {
        fprintf(stderr, "    (skipping %s '%s' due to ANDROID_AAPT_IGNORE pattern '%s')\n",
                type == kFileTypeDirectory ? "dir" : "file",
                path,
                matchedPattern ? matchedPattern : "");
    }

    free(patterns);
    return ignore;
}

// =========================================================================
// =========================================================================
// =========================================================================

/* static */
inline bool isAlpha(const String8& string) {
     const size_t length = string.length();
     for (size_t i = 0; i < length; ++i) {
          if (!isalpha(string[i])) {
              return false;
          }
     }

     return true;
}

/* static */
inline bool isNumber(const String8& string) {
     const size_t length = string.length();
     for (size_t i = 0; i < length; ++i) {
          if (!isdigit(string[i])) {
              return false;
          }
     }

     return true;
}

void AaptLocaleValue::setLanguage(const char* languageChars) {
     size_t i = 0;
     while ((*languageChars) != '\0' && i < sizeof(language)/sizeof(language[0])) {
          language[i++] = tolower(*languageChars);
          languageChars++;
     }
}

void AaptLocaleValue::setRegion(const char* regionChars) {
    size_t i = 0;
    while ((*regionChars) != '\0' && i < sizeof(region)/sizeof(region[0])) {
         region[i++] = toupper(*regionChars);
         regionChars++;
    }
}

void AaptLocaleValue::setScript(const char* scriptChars) {
    size_t i = 0;
    while ((*scriptChars) != '\0' && i < sizeof(script)/sizeof(script[0])) {
         if (i == 0) {
             script[i++] = toupper(*scriptChars);
         } else {
             script[i++] = tolower(*scriptChars);
         }
         scriptChars++;
    }
}

void AaptLocaleValue::setVariant(const char* variantChars) {
     size_t i = 0;
     while ((*variantChars) != '\0' && i < sizeof(variant)/sizeof(variant[0])) {
          variant[i++] = *variantChars;
          variantChars++;
     }
}

bool AaptLocaleValue::initFromFilterString(const String8& str) {
     // A locale (as specified in the filter) is an underscore separated name such
     // as "en_US", "en_Latn_US", or "en_US_POSIX".
     Vector<String8> parts = AaptUtil::splitAndLowerCase(str, '_');

     const int numTags = parts.size();
     bool valid = false;
     if (numTags >= 1) {
         const String8& lang = parts[0];
         if (isAlpha(lang) && (lang.length() == 2 || lang.length() == 3)) {
             setLanguage(lang.string());
             valid = true;
         }
     }

     if (!valid || numTags == 1) {
         return valid;
     }

     // At this point, valid == true && numTags > 1.
     const String8& part2 = parts[1];
     if ((part2.length() == 2 && isAlpha(part2)) ||
         (part2.length() == 3 && isNumber(part2))) {
         setRegion(part2.string());
     } else if (part2.length() == 4 && isAlpha(part2)) {
         setScript(part2.string());
     } else if (part2.length() >= 4 && part2.length() <= 8) {
         setVariant(part2.string());
     } else {
         valid = false;
     }

     if (!valid || numTags == 2) {
         return valid;
     }

     // At this point, valid == true && numTags > 1.
     const String8& part3 = parts[2];
     if (((part3.length() == 2 && isAlpha(part3)) ||
         (part3.length() == 3 && isNumber(part3))) && script[0]) {
         setRegion(part3.string());
     } else if (part3.length() >= 4 && part3.length() <= 8) {
         setVariant(part3.string());
     } else {
         valid = false;
     }

     if (!valid || numTags == 3) {
         return valid;
     }

     const String8& part4 = parts[3];
     if (part4.length() >= 4 && part4.length() <= 8) {
         setVariant(part4.string());
     } else {
         valid = false;
     }

     if (!valid || numTags > 4) {
         return false;
     }

     return true;
}

int AaptLocaleValue::initFromDirName(const Vector<String8>& parts, const int startIndex) {
    const int size = parts.size();
    int currentIndex = startIndex;

    String8 part = parts[currentIndex];
    if (part[0] == 'b' && part[1] == '+') {
        // This is a "modified" BCP 47 language tag. Same semantics as BCP 47 tags,
        // except that the separator is "+" and not "-".
        Vector<String8> subtags = AaptUtil::splitAndLowerCase(part, '+');
        subtags.removeItemsAt(0);
        if (subtags.size() == 1) {
            setLanguage(subtags[0]);
        } else if (subtags.size() == 2) {
            setLanguage(subtags[0]);

            // The second tag can either be a region, a variant or a script.
            switch (subtags[1].size()) {
                case 2:
                case 3:
                    setRegion(subtags[1]);
                    break;
                case 4:
                    if (isAlpha(subtags[1])) {
                        setScript(subtags[1]);
                        break;
                    }
                    // This is not alphabetical, so we fall through to variant
                    [[fallthrough]];
                case 5:
                case 6:
                case 7:
                case 8:
                    setVariant(subtags[1]);
                    break;
                default:
                    fprintf(stderr, "ERROR: Invalid BCP 47 tag in directory name %s\n",
                            part.string());
                    return -1;
            }
        } else if (subtags.size() == 3) {
            // The language is always the first subtag.
            setLanguage(subtags[0]);

            // The second subtag can either be a script or a region code.
            // If its size is 4, it's a script code, else it's a region code.
            bool hasRegion = false;
            if (subtags[1].size() == 4) {
                setScript(subtags[1]);
            } else if (subtags[1].size() == 2 || subtags[1].size() == 3) {
                setRegion(subtags[1]);
                hasRegion = true;
            } else {
                fprintf(stderr, "ERROR: Invalid BCP 47 tag in directory name %s\n", part.string());
                return -1;
            }

            // The third tag can either be a region code (if the second tag was
            // a script), else a variant code.
            if (subtags[2].size() >= 4) {
                setVariant(subtags[2]);
            } else {
                setRegion(subtags[2]);
            }
        } else if (subtags.size() == 4) {
            setLanguage(subtags[0]);
            setScript(subtags[1]);
            setRegion(subtags[2]);
            setVariant(subtags[3]);
        } else {
            fprintf(stderr, "ERROR: Invalid BCP 47 tag in directory name: %s\n", part.string());
            return -1;
        }

        return ++currentIndex;
    } else {
        if ((part.length() == 2 || part.length() == 3)
               && isAlpha(part) && strcmp("car", part.string())) {
            setLanguage(part);
            if (++currentIndex == size) {
                return size;
            }
        } else {
            return currentIndex;
        }

        part = parts[currentIndex];
        if (part.string()[0] == 'r' && part.length() == 3) {
            setRegion(part.string() + 1);
            if (++currentIndex == size) {
                return size;
            }
        }
    }

    return currentIndex;
}

void AaptLocaleValue::initFromResTable(const ResTable_config& config) {
    config.unpackLanguage(language);
    config.unpackRegion(region);
    if (config.localeScript[0] && !config.localeScriptWasComputed) {
        memcpy(script, config.localeScript, sizeof(config.localeScript));
    }

    if (config.localeVariant[0]) {
        memcpy(variant, config.localeVariant, sizeof(config.localeVariant));
    }
}

void AaptLocaleValue::writeTo(ResTable_config* out) const {
    out->packLanguage(language);
    out->packRegion(region);

    if (script[0]) {
        memcpy(out->localeScript, script, sizeof(out->localeScript));
    }

    if (variant[0]) {
        memcpy(out->localeVariant, variant, sizeof(out->localeVariant));
    }
}

bool
AaptGroupEntry::initFromDirName(const char* dir, String8* resType)
{
    const char* q = strchr(dir, '-');
    size_t typeLen;
    if (q != NULL) {
        typeLen = q - dir;
    } else {
        typeLen = strlen(dir);
    }

    String8 type(dir, typeLen);
    if (!isValidResourceType(type)) {
        return false;
    }

    if (q != NULL) {
        if (!AaptConfig::parse(String8(q + 1), &mParams)) {
            return false;
        }
    }

    *resType = type;
    return true;
}

String8
AaptGroupEntry::toDirName(const String8& resType) const
{
    String8 s = resType;
    String8 params = mParams.toString();
    if (params.length() > 0) {
        if (s.length() > 0) {
            s += "-";
        }
        s += params;
    }
    return s;
}


// =========================================================================
// =========================================================================
// =========================================================================

void* AaptFile::editData(size_t size)
{
    if (size <= mBufferSize) {
        mDataSize = size;
        return mData;
    }
    size_t allocSize = (size*3)/2;
    void* buf = realloc(mData, allocSize);
    if (buf == NULL) {
        return NULL;
    }
    mData = buf;
    mDataSize = size;
    mBufferSize = allocSize;
    return buf;
}

void* AaptFile::editDataInRange(size_t offset, size_t size)
{
    return (void*)(((uint8_t*) editData(offset + size)) + offset);
}

void* AaptFile::editData(size_t* outSize)
{
    if (outSize) {
        *outSize = mDataSize;
    }
    return mData;
}

void* AaptFile::padData(size_t wordSize)
{
    const size_t extra = mDataSize%wordSize;
    if (extra == 0) {
        return mData;
    }

    size_t initial = mDataSize;
    void* data = editData(initial+(wordSize-extra));
    if (data != NULL) {
        memset(((uint8_t*)data) + initial, 0, wordSize-extra);
    }
    return data;
}

status_t AaptFile::writeData(const void* data, size_t size)
{
    size_t end = mDataSize;
    size_t total = size + end;
    void* buf = editData(total);
    if (buf == NULL) {
        return UNKNOWN_ERROR;
    }
    memcpy(((char*)buf)+end, data, size);
    return NO_ERROR;
}

void AaptFile::clearData()
{
    if (mData != NULL) free(mData);
    mData = NULL;
    mDataSize = 0;
    mBufferSize = 0;
}

String8 AaptFile::getPrintableSource() const
{
    if (hasData()) {
        String8 name(mGroupEntry.toDirName(String8()));
        name.appendPath(mPath);
        name.append(" #generated");
        return name;
    }
    return mSourceFile;
}

// =========================================================================
// =========================================================================
// =========================================================================

status_t AaptGroup::addFile(const sp<AaptFile>& file, const bool overwriteDuplicate)
{
    ssize_t index = mFiles.indexOfKey(file->getGroupEntry());
    if (index >= 0 && overwriteDuplicate) {
        fprintf(stderr, "warning: overwriting '%s' with '%s'\n",
                mFiles[index]->getSourceFile().string(),
                file->getSourceFile().string());
        removeFile(index);
        index = -1;
    }

    if (index < 0) {
        file->mPath = mPath;
        mFiles.add(file->getGroupEntry(), file);
        return NO_ERROR;
    }

    // Check if the version is automatically applied. This is a common source of
    // error.
    ConfigDescription withoutVersion = file->getGroupEntry().toParams();
    withoutVersion.version = 0;
    AaptConfig::applyVersionForCompatibility(&withoutVersion);

    const sp<AaptFile>& originalFile = mFiles.valueAt(index);
    SourcePos(file->getSourceFile(), -1)
            .error("Duplicate file.\n%s: Original is here. %s",
                   originalFile->getPrintableSource().string(),
                   (withoutVersion.version != 0) ? "The version qualifier may be implied." : "");
    return UNKNOWN_ERROR;
}

void AaptGroup::removeFile(size_t index)
{
	mFiles.removeItemsAt(index);
}

void AaptGroup::print(const String8& prefix) const
{
    printf("%s%s\n", prefix.string(), getPath().string());
    const size_t N=mFiles.size();
    size_t i;
    for (i=0; i<N; i++) {
        sp<AaptFile> file = mFiles.valueAt(i);
        const AaptGroupEntry& e = file->getGroupEntry();
        if (file->hasData()) {
            printf("%s  Gen: (%s) %d bytes\n", prefix.string(), e.toDirName(String8()).string(),
                    (int)file->getSize());
        } else {
            printf("%s  Src: (%s) %s\n", prefix.string(), e.toDirName(String8()).string(),
                    file->getPrintableSource().string());
        }
        //printf("%s  File Group Entry: %s\n", prefix.string(),
        //        file->getGroupEntry().toDirName(String8()).string());
    }
}

String8 AaptGroup::getPrintableSource() const
{
    if (mFiles.size() > 0) {
        // Arbitrarily pull the first source file out of the list.
        return mFiles.valueAt(0)->getPrintableSource();
    }

    // Should never hit this case, but to be safe...
    return getPath();

}

// =========================================================================
// =========================================================================
// =========================================================================

status_t AaptDir::addFile(const String8& name, const sp<AaptGroup>& file)
{
    if (mFiles.indexOfKey(name) >= 0) {
        return ALREADY_EXISTS;
    }
    mFiles.add(name, file);
    return NO_ERROR;
}

status_t AaptDir::addDir(const String8& name, const sp<AaptDir>& dir)
{
    if (mDirs.indexOfKey(name) >= 0) {
        return ALREADY_EXISTS;
    }
    mDirs.add(name, dir);
    return NO_ERROR;
}

sp<AaptDir> AaptDir::makeDir(const String8& path)
{
    String8 name;
    String8 remain = path;

    sp<AaptDir> subdir = this;
    while (name = remain.walkPath(&remain), remain != "") {
        subdir = subdir->makeDir(name);
    }

    ssize_t i = subdir->mDirs.indexOfKey(name);
    if (i >= 0) {
        return subdir->mDirs.valueAt(i);
    }
    sp<AaptDir> dir = new AaptDir(name, subdir->mPath.appendPathCopy(name));
    subdir->mDirs.add(name, dir);
    return dir;
}

void AaptDir::removeFile(const String8& name)
{
    mFiles.removeItem(name);
}

void AaptDir::removeDir(const String8& name)
{
    mDirs.removeItem(name);
}

status_t AaptDir::addLeafFile(const String8& leafName, const sp<AaptFile>& file,
        const bool overwrite)
{
    sp<AaptGroup> group;
    if (mFiles.indexOfKey(leafName) >= 0) {
        group = mFiles.valueFor(leafName);
    } else {
        group = new AaptGroup(leafName, mPath.appendPathCopy(leafName));
        mFiles.add(leafName, group);
    }

    return group->addFile(file, overwrite);
}

ssize_t AaptDir::slurpFullTree(Bundle* bundle, const String8& srcDir,
                            const AaptGroupEntry& kind, const String8& resType,
                            sp<FilePathStore>& fullResPaths, const bool overwrite)
{
    Vector<String8> fileNames;
    {
        DIR* dir = NULL;

        dir = opendir(srcDir.string());
        if (dir == NULL) {
            fprintf(stderr, "ERROR: opendir(%s): %s\n", srcDir.string(), strerror(errno));
            return UNKNOWN_ERROR;
        }

        /*
         * Slurp the filenames out of the directory.
         */
        while (1) {
            struct dirent* entry;

            entry = readdir(dir);
            if (entry == NULL)
                break;

            if (isHidden(srcDir.string(), entry->d_name))
                continue;

            String8 name(entry->d_name);
            fileNames.add(name);
            // Add fully qualified path for dependency purposes
            // if we're collecting them
            if (fullResPaths != NULL) {
                fullResPaths->add(srcDir.appendPathCopy(name));
            }
        }
        closedir(dir);
    }

    ssize_t count = 0;

    /*
     * Stash away the files and recursively descend into subdirectories.
     */
    const size_t N = fileNames.size();
    size_t i;
    for (i = 0; i < N; i++) {
        String8 pathName(srcDir);
        FileType type;

        pathName.appendPath(fileNames[i].string());
        type = getFileType(pathName.string());
        if (type == kFileTypeDirectory) {
            sp<AaptDir> subdir;
            bool notAdded = false;
            if (mDirs.indexOfKey(fileNames[i]) >= 0) {
                subdir = mDirs.valueFor(fileNames[i]);
            } else {
                subdir = new AaptDir(fileNames[i], mPath.appendPathCopy(fileNames[i]));
                notAdded = true;
            }
            ssize_t res = subdir->slurpFullTree(bundle, pathName, kind,
                                                resType, fullResPaths, overwrite);
            if (res < NO_ERROR) {
                return res;
            }
            if (res > 0 && notAdded) {
                mDirs.add(fileNames[i], subdir);
            }
            count += res;
        } else if (type == kFileTypeRegular) {
            sp<AaptFile> file = new AaptFile(pathName, kind, resType);
            status_t err = addLeafFile(fileNames[i], file, overwrite);
            if (err != NO_ERROR) {
                return err;
            }

            count++;

        } else {
            if (bundle->getVerbose())
                printf("   (ignoring non-file/dir '%s')\n", pathName.string());
        }
    }

    return count;
}

status_t AaptDir::validate() const
{
    const size_t NF = mFiles.size();
    const size_t ND = mDirs.size();
    size_t i;
    for (i = 0; i < NF; i++) {
        if (!validateFileName(mFiles.valueAt(i)->getLeaf().string())) {
            SourcePos(mFiles.valueAt(i)->getPrintableSource(), -1).error(
                    "Invalid filename.  Unable to add.");
            return UNKNOWN_ERROR;
        }

        size_t j;
        for (j = i+1; j < NF; j++) {
            if (strcasecmp(mFiles.valueAt(i)->getLeaf().string(),
                           mFiles.valueAt(j)->getLeaf().string()) == 0) {
                SourcePos(mFiles.valueAt(i)->getPrintableSource(), -1).error(
                        "File is case-insensitive equivalent to: %s",
                        mFiles.valueAt(j)->getPrintableSource().string());
                return UNKNOWN_ERROR;
            }

            // TODO: if ".gz", check for non-.gz; if non-, check for ".gz"
            // (this is mostly caught by the "marked" stuff, below)
        }

        for (j = 0; j < ND; j++) {
            if (strcasecmp(mFiles.valueAt(i)->getLeaf().string(),
                           mDirs.valueAt(j)->getLeaf().string()) == 0) {
                SourcePos(mFiles.valueAt(i)->getPrintableSource(), -1).error(
                        "File conflicts with dir from: %s",
                        mDirs.valueAt(j)->getPrintableSource().string());
                return UNKNOWN_ERROR;
            }
        }
    }

    for (i = 0; i < ND; i++) {
        if (!validateFileName(mDirs.valueAt(i)->getLeaf().string())) {
            SourcePos(mDirs.valueAt(i)->getPrintableSource(), -1).error(
                    "Invalid directory name, unable to add.");
            return UNKNOWN_ERROR;
        }

        size_t j;
        for (j = i+1; j < ND; j++) {
            if (strcasecmp(mDirs.valueAt(i)->getLeaf().string(),
                           mDirs.valueAt(j)->getLeaf().string()) == 0) {
                SourcePos(mDirs.valueAt(i)->getPrintableSource(), -1).error(
                        "Directory is case-insensitive equivalent to: %s",
                        mDirs.valueAt(j)->getPrintableSource().string());
                return UNKNOWN_ERROR;
            }
        }

        status_t err = mDirs.valueAt(i)->validate();
        if (err != NO_ERROR) {
            return err;
        }
    }

    return NO_ERROR;
}

void AaptDir::print(const String8& prefix) const
{
    const size_t ND=getDirs().size();
    size_t i;
    for (i=0; i<ND; i++) {
        getDirs().valueAt(i)->print(prefix);
    }

    const size_t NF=getFiles().size();
    for (i=0; i<NF; i++) {
        getFiles().valueAt(i)->print(prefix);
    }
}

String8 AaptDir::getPrintableSource() const
{
    if (mFiles.size() > 0) {
        // Arbitrarily pull the first file out of the list as the source dir.
        return mFiles.valueAt(0)->getPrintableSource().getPathDir();
    }
    if (mDirs.size() > 0) {
        // Or arbitrarily pull the first dir out of the list as the source dir.
        return mDirs.valueAt(0)->getPrintableSource().getPathDir();
    }

    // Should never hit this case, but to be safe...
    return mPath;

}

// =========================================================================
// =========================================================================
// =========================================================================

status_t AaptSymbols::applyJavaSymbols(const sp<AaptSymbols>& javaSymbols)
{
    status_t err = NO_ERROR;
    size_t N = javaSymbols->mSymbols.size();
    for (size_t i=0; i<N; i++) {
        const String8& name = javaSymbols->mSymbols.keyAt(i);
        const AaptSymbolEntry& entry = javaSymbols->mSymbols.valueAt(i);
        ssize_t pos = mSymbols.indexOfKey(name);
        if (pos < 0) {
            entry.sourcePos.error("Symbol '%s' declared with <java-symbol> not defined\n", name.string());
            err = UNKNOWN_ERROR;
            continue;
        }
        //printf("**** setting symbol #%d/%d %s to isJavaSymbol=%d\n",
        //        i, N, name.string(), entry.isJavaSymbol ? 1 : 0);
        mSymbols.editValueAt(pos).isJavaSymbol = entry.isJavaSymbol;
    }

    N = javaSymbols->mNestedSymbols.size();
    for (size_t i=0; i<N; i++) {
        const String8& name = javaSymbols->mNestedSymbols.keyAt(i);
        const sp<AaptSymbols>& symbols = javaSymbols->mNestedSymbols.valueAt(i);
        ssize_t pos = mNestedSymbols.indexOfKey(name);
        if (pos < 0) {
            SourcePos pos;
            pos.error("Java symbol dir %s not defined\n", name.string());
            err = UNKNOWN_ERROR;
            continue;
        }
        //printf("**** applying java symbols in dir %s\n", name.string());
        status_t myerr = mNestedSymbols.valueAt(pos)->applyJavaSymbols(symbols);
        if (myerr != NO_ERROR) {
            err = myerr;
        }
    }

    return err;
}

// =========================================================================
// =========================================================================
// =========================================================================

AaptAssets::AaptAssets()
    : AaptDir(String8(), String8()),
      mHavePrivateSymbols(false),
      mChanged(false), mHaveIncludedAssets(false),
      mRes(NULL) {}

const SortedVector<AaptGroupEntry>& AaptAssets::getGroupEntries() const {
    if (mChanged) {
    }
    return mGroupEntries;
}

status_t AaptAssets::addFile(const String8& name, const sp<AaptGroup>& file)
{
    mChanged = true;
    return AaptDir::addFile(name, file);
}

sp<AaptFile> AaptAssets::addFile(
        const String8& filePath, const AaptGroupEntry& entry,
        const String8& srcDir, sp<AaptGroup>* outGroup,
        const String8& resType)
{
    sp<AaptDir> dir = this;
    sp<AaptGroup> group;
    sp<AaptFile> file;
    String8 root, remain(filePath), partialPath;
    while (remain.length() > 0) {
        root = remain.walkPath(&remain);
        partialPath.appendPath(root);

        const String8 rootStr(root);

        if (remain.length() == 0) {
            ssize_t i = dir->getFiles().indexOfKey(rootStr);
            if (i >= 0) {
                group = dir->getFiles().valueAt(i);
            } else {
                group = new AaptGroup(rootStr, filePath);
                status_t res = dir->addFile(rootStr, group);
                if (res != NO_ERROR) {
                    return NULL;
                }
            }
            file = new AaptFile(srcDir.appendPathCopy(filePath), entry, resType);
            status_t res = group->addFile(file);
            if (res != NO_ERROR) {
                return NULL;
            }
            break;

        } else {
            ssize_t i = dir->getDirs().indexOfKey(rootStr);
            if (i >= 0) {
                dir = dir->getDirs().valueAt(i);
            } else {
                sp<AaptDir> subdir = new AaptDir(rootStr, partialPath);
                status_t res = dir->addDir(rootStr, subdir);
                if (res != NO_ERROR) {
                    return NULL;
                }
                dir = subdir;
            }
        }
    }

    mGroupEntries.add(entry);
    if (outGroup) *outGroup = group;
    return file;
}

void AaptAssets::addResource(const String8& leafName, const String8& path,
                const sp<AaptFile>& file, const String8& resType)
{
    sp<AaptDir> res = AaptDir::makeDir(kResString);
    String8 dirname = file->getGroupEntry().toDirName(resType);
    sp<AaptDir> subdir = res->makeDir(dirname);
    sp<AaptGroup> grr = new AaptGroup(leafName, path);
    grr->addFile(file);

    subdir->addFile(leafName, grr);
}


ssize_t AaptAssets::slurpFromArgs(Bundle* bundle)
{
    int count;
    int totalCount = 0;
    FileType type;
    const Vector<const char *>& resDirs = bundle->getResourceSourceDirs();
    const size_t dirCount =resDirs.size();
    sp<AaptAssets> current = this;

    const int N = bundle->getFileSpecCount();

    /*
     * If a package manifest was specified, include that first.
     */
    if (bundle->getAndroidManifestFile() != NULL) {
        // place at root of zip.
        String8 srcFile(bundle->getAndroidManifestFile());
        addFile(srcFile.getPathLeaf(), AaptGroupEntry(), srcFile.getPathDir(),
                NULL, String8());
        totalCount++;
    }

    /*
     * If a directory of custom assets was supplied, slurp 'em up.
     */
    const Vector<const char*>& assetDirs = bundle->getAssetSourceDirs();
    const int AN = assetDirs.size();
    for (int i = 0; i < AN; i++) {
        FileType type = getFileType(assetDirs[i]);
        if (type == kFileTypeNonexistent) {
            fprintf(stderr, "ERROR: asset directory '%s' does not exist\n", assetDirs[i]);
            return UNKNOWN_ERROR;
        }
        if (type != kFileTypeDirectory) {
            fprintf(stderr, "ERROR: '%s' is not a directory\n", assetDirs[i]);
            return UNKNOWN_ERROR;
        }

        String8 assetRoot(assetDirs[i]);
        sp<AaptDir> assetAaptDir = makeDir(String8(kAssetDir));
        AaptGroupEntry group;
        count = assetAaptDir->slurpFullTree(bundle, assetRoot, group,
                                            String8(), mFullAssetPaths, true);
        if (count < 0) {
            totalCount = count;
            goto bail;
        }
        if (count > 0) {
            mGroupEntries.add(group);
        }
        totalCount += count;

        if (bundle->getVerbose()) {
            printf("Found %d custom asset file%s in %s\n",
                   count, (count==1) ? "" : "s", assetDirs[i]);
        }
    }

    /*
     * If a directory of resource-specific assets was supplied, slurp 'em up.
     */
    for (size_t i=0; i<dirCount; i++) {
        const char *res = resDirs[i];
        if (res) {
            type = getFileType(res);
            if (type == kFileTypeNonexistent) {
                fprintf(stderr, "ERROR: resource directory '%s' does not exist\n", res);
                return UNKNOWN_ERROR;
            }
            if (type == kFileTypeDirectory) {
                if (i>0) {
                    sp<AaptAssets> nextOverlay = new AaptAssets();
                    current->setOverlay(nextOverlay);
                    current = nextOverlay;
                    current->setFullResPaths(mFullResPaths);
                }
                count = current->slurpResourceTree(bundle, String8(res));
                if (i > 0 && count > 0) {
                  count = current->filter(bundle);
                }

                if (count < 0) {
                    totalCount = count;
                    goto bail;
                }
                totalCount += count;
            }
            else {
                fprintf(stderr, "ERROR: '%s' is not a directory\n", res);
                return UNKNOWN_ERROR;
            }
        }
        
    }
    /*
     * Now do any additional raw files.
     */
    for (int arg=0; arg<N; arg++) {
        const char* assetDir = bundle->getFileSpecEntry(arg);

        FileType type = getFileType(assetDir);
        if (type == kFileTypeNonexistent) {
            fprintf(stderr, "ERROR: input directory '%s' does not exist\n", assetDir);
            return UNKNOWN_ERROR;
        }
        if (type != kFileTypeDirectory) {
            fprintf(stderr, "ERROR: '%s' is not a directory\n", assetDir);
            return UNKNOWN_ERROR;
        }

        String8 assetRoot(assetDir);

        if (bundle->getVerbose())
            printf("Processing raw dir '%s'\n", (const char*) assetDir);

        /*
         * Do a recursive traversal of subdir tree.  We don't make any
         * guarantees about ordering, so we're okay with an inorder search
         * using whatever order the OS happens to hand back to us.
         */
        count = slurpFullTree(bundle, assetRoot, AaptGroupEntry(), String8(), mFullAssetPaths);
        if (count < 0) {
            /* failure; report error and remove archive */
            totalCount = count;
            goto bail;
        }
        totalCount += count;

        if (bundle->getVerbose())
            printf("Found %d asset file%s in %s\n",
                   count, (count==1) ? "" : "s", assetDir);
    }

    count = validate();
    if (count != NO_ERROR) {
        totalCount = count;
        goto bail;
    }

    count = filter(bundle);
    if (count != NO_ERROR) {
        totalCount = count;
        goto bail;
    }

bail:
    return totalCount;
}

ssize_t AaptAssets::slurpFullTree(Bundle* bundle, const String8& srcDir,
                                    const AaptGroupEntry& kind,
                                    const String8& resType,
                                    sp<FilePathStore>& fullResPaths,
                                    const bool overwrite)
{
    ssize_t res = AaptDir::slurpFullTree(bundle, srcDir, kind, resType, fullResPaths, overwrite);
    if (res > 0) {
        mGroupEntries.add(kind);
    }

    return res;
}

ssize_t AaptAssets::slurpResourceTree(Bundle* bundle, const String8& srcDir)
{
    ssize_t err = 0;

    DIR* dir = opendir(srcDir.string());
    if (dir == NULL) {
        fprintf(stderr, "ERROR: opendir(%s): %s\n", srcDir.string(), strerror(errno));
        return UNKNOWN_ERROR;
    }

    status_t count = 0;

    /*
     * Run through the directory, looking for dirs that match the
     * expected pattern.
     */
    while (1) {
        struct dirent* entry = readdir(dir);
        if (entry == NULL) {
            break;
        }

        if (isHidden(srcDir.string(), entry->d_name)) {
            continue;
        }

        String8 subdirName(srcDir);
        subdirName.appendPath(entry->d_name);

        AaptGroupEntry group;
        String8 resType;
        bool b = group.initFromDirName(entry->d_name, &resType);
        if (!b) {
            fprintf(stderr, "invalid resource directory name: %s %s\n", srcDir.string(),
                    entry->d_name);
            err = -1;
            continue;
        }

        if (bundle->getMaxResVersion() != NULL && group.getVersionString().length() != 0) {
            int maxResInt = atoi(bundle->getMaxResVersion());
            const char *verString = group.getVersionString().string();
            int dirVersionInt = atoi(verString + 1); // skip 'v' in version name
            if (dirVersionInt > maxResInt) {
              fprintf(stderr, "max res %d, skipping %s\n", maxResInt, entry->d_name);
              continue;
            }
        }

        FileType type = getFileType(subdirName.string());

        if (type == kFileTypeDirectory) {
            sp<AaptDir> dir = makeDir(resType);
            ssize_t res = dir->slurpFullTree(bundle, subdirName, group,
                                                resType, mFullResPaths);
            if (res < 0) {
                count = res;
                goto bail;
            }
            if (res > 0) {
                mGroupEntries.add(group);
                count += res;
            }

            // Only add this directory if we don't already have a resource dir
            // for the current type.  This ensures that we only add the dir once
            // for all configs.
            sp<AaptDir> rdir = resDir(resType);
            if (rdir == NULL) {
                mResDirs.add(dir);
            }
        } else {
            if (bundle->getVerbose()) {
                fprintf(stderr, "   (ignoring file '%s')\n", subdirName.string());
            }
        }
    }

bail:
    closedir(dir);
    dir = NULL;

    if (err != 0) {
        return err;
    }
    return count;
}

ssize_t
AaptAssets::slurpResourceZip(Bundle* /* bundle */, const char* filename)
{
    int count = 0;
    SortedVector<AaptGroupEntry> entries;

    ZipFile* zip = new ZipFile;
    status_t err = zip->open(filename, ZipFile::kOpenReadOnly);
    if (err != NO_ERROR) {
        fprintf(stderr, "error opening zip file %s\n", filename);
        count = err;
        delete zip;
        return -1;
    }

    const int N = zip->getNumEntries();
    for (int i=0; i<N; i++) {
        ZipEntry* entry = zip->getEntryByIndex(i);
        if (entry->getDeleted()) {
            continue;
        }

        String8 entryName(entry->getFileName());

        String8 dirName = entryName.getPathDir();
        sp<AaptDir> dir = dirName == "" ? this : makeDir(dirName);

        String8 resType;
        AaptGroupEntry kind;

        String8 remain;
        if (entryName.walkPath(&remain) == kResourceDir) {
            // these are the resources, pull their type out of the directory name
            kind.initFromDirName(remain.walkPath().string(), &resType);
        } else {
            // these are untyped and don't have an AaptGroupEntry
        }
        if (entries.indexOf(kind) < 0) {
            entries.add(kind);
            mGroupEntries.add(kind);
        }

        // use the one from the zip file if they both exist.
        dir->removeFile(entryName.getPathLeaf());

        sp<AaptFile> file = new AaptFile(entryName, kind, resType);
        status_t err = dir->addLeafFile(entryName.getPathLeaf(), file);
        if (err != NO_ERROR) {
            fprintf(stderr, "err=%s entryName=%s\n", strerror(err), entryName.string());
            count = err;
            goto bail;
        }
        file->setCompressionMethod(entry->getCompressionMethod());

#if 0
        if (entryName == "AndroidManifest.xml") {
            printf("AndroidManifest.xml\n");
        }
        printf("\n\nfile: %s\n", entryName.string());
#endif

        size_t len = entry->getUncompressedLen();
        void* data = zip->uncompress(entry);
        void* buf = file->editData(len);
        memcpy(buf, data, len);

#if 0
        const int OFF = 0;
        const unsigned char* p = (unsigned char*)data;
        const unsigned char* end = p+len;
        p += OFF;
        for (int i=0; i<32 && p < end; i++) {
            printf("0x%03x ", i*0x10 + OFF);
            for (int j=0; j<0x10 && p < end; j++) {
                printf(" %02x", *p);
                p++;
            }
            printf("\n");
        }
#endif

        free(data);

        count++;
    }

bail:
    delete zip;
    return count;
}

status_t AaptAssets::filter(Bundle* bundle)
{
    sp<WeakResourceFilter> reqFilter(new WeakResourceFilter());
    status_t err = reqFilter->parse(bundle->getConfigurations());
    if (err != NO_ERROR) {
        return err;
    }

    uint32_t preferredDensity = 0;
    if (bundle->getPreferredDensity().size() > 0) {
        ResTable_config preferredConfig;
        if (!AaptConfig::parseDensity(bundle->getPreferredDensity().string(), &preferredConfig)) {
            fprintf(stderr, "Error parsing preferred density: %s\n",
                    bundle->getPreferredDensity().string());
            return UNKNOWN_ERROR;
        }
        preferredDensity = preferredConfig.density;
    }

    if (reqFilter->isEmpty() && preferredDensity == 0) {
        return NO_ERROR;
    }

    if (bundle->getVerbose()) {
        if (!reqFilter->isEmpty()) {
            printf("Applying required filter: %s\n",
                    bundle->getConfigurations().string());
        }
        if (preferredDensity > 0) {
            printf("Applying preferred density filter: %s\n",
                    bundle->getPreferredDensity().string());
        }
    }

    const Vector<sp<AaptDir> >& resdirs = mResDirs;
    const size_t ND = resdirs.size();
    for (size_t i=0; i<ND; i++) {
        const sp<AaptDir>& dir = resdirs.itemAt(i);
        if (dir->getLeaf() == kValuesDir) {
            // The "value" dir is special since a single file defines
            // multiple resources, so we can not do filtering on the
            // files themselves.
            continue;
        }
        if (dir->getLeaf() == kMipmapDir) {
            // We also skip the "mipmap" directory, since the point of this
            // is to include all densities without stripping.  If you put
            // other configurations in here as well they won't be stripped
            // either...  So don't do that.  Seriously.  What is wrong with you?
            continue;
        }

        const size_t NG = dir->getFiles().size();
        for (size_t j=0; j<NG; j++) {
            sp<AaptGroup> grp = dir->getFiles().valueAt(j);

            // First remove any configurations we know we don't need.
            for (size_t k=0; k<grp->getFiles().size(); k++) {
                sp<AaptFile> file = grp->getFiles().valueAt(k);
                if (k == 0 && grp->getFiles().size() == 1) {
                    // If this is the only file left, we need to keep it.
                    // Otherwise the resource IDs we are using will be inconsistent
                    // with what we get when not stripping.  Sucky, but at least
                    // for now we can rely on the back-end doing another filtering
                    // pass to take this out and leave us with this resource name
                    // containing no entries.
                    continue;
                }
                if (file->getPath().getPathExtension() == ".xml") {
                    // We can't remove .xml files at this point, because when
                    // we parse them they may add identifier resources, so
                    // removing them can cause our resource identifiers to
                    // become inconsistent.
                    continue;
                }
                const ResTable_config& config(file->getGroupEntry().toParams());
                if (!reqFilter->match(config)) {
                    if (bundle->getVerbose()) {
                        printf("Pruning unneeded resource: %s\n",
                                file->getPrintableSource().string());
                    }
                    grp->removeFile(k);
                    k--;
                }
            }

            // Quick check: no preferred filters, nothing more to do.
            if (preferredDensity == 0) {
                continue;
            }

            // Get the preferred density if there is one. We do not match exactly for density.
            // If our preferred density is hdpi but we only have mdpi and xhdpi resources, we
            // pick xhdpi.
            for (size_t k=0; k<grp->getFiles().size(); k++) {
                sp<AaptFile> file = grp->getFiles().valueAt(k);
                if (k == 0 && grp->getFiles().size() == 1) {
                    // If this is the only file left, we need to keep it.
                    // Otherwise the resource IDs we are using will be inconsistent
                    // with what we get when not stripping.  Sucky, but at least
                    // for now we can rely on the back-end doing another filtering
                    // pass to take this out and leave us with this resource name
                    // containing no entries.
                    continue;
                }
                if (file->getPath().getPathExtension() == ".xml") {
                    // We can't remove .xml files at this point, because when
                    // we parse them they may add identifier resources, so
                    // removing them can cause our resource identifiers to
                    // become inconsistent.
                    continue;
                }
                const ResTable_config& config(file->getGroupEntry().toParams());
                if (config.density != 0 && config.density != preferredDensity) {
                    // This is a resource we would prefer not to have.  Check
                    // to see if have a similar variation that we would like
                    // to have and, if so, we can drop it.
                    uint32_t bestDensity = config.density;

                    for (size_t m=0; m<grp->getFiles().size(); m++) {
                        if (m == k) {
                            continue;
                        }

                        sp<AaptFile> mfile = grp->getFiles().valueAt(m);
                        const ResTable_config& mconfig(mfile->getGroupEntry().toParams());
                        if (AaptConfig::isSameExcept(config, mconfig, ResTable_config::CONFIG_DENSITY)) {
                            // See if there is a better density resource
                            if (mconfig.density < bestDensity &&
                                    mconfig.density >= preferredDensity &&
                                    bestDensity > preferredDensity) {
                                // This density is our preferred density, or between our best density and
                                // the preferred density, therefore it is better.
                                bestDensity = mconfig.density;
                            } else if (mconfig.density > bestDensity &&
                                    bestDensity < preferredDensity) {
                                // This density is better than our best density and
                                // our best density was smaller than our preferred
                                // density, so it is better.
                                bestDensity = mconfig.density;
                            }
                        }
                    }

                    if (bestDensity != config.density) {
                        if (bundle->getVerbose()) {
                            printf("Pruning unneeded resource: %s\n",
                                    file->getPrintableSource().string());
                        }
                        grp->removeFile(k);
                        k--;
                    }
                }
            }
        }
    }

    return NO_ERROR;
}

sp<AaptSymbols> AaptAssets::getSymbolsFor(const String8& name)
{
    sp<AaptSymbols> sym = mSymbols.valueFor(name);
    if (sym == NULL) {
        sym = new AaptSymbols();
        mSymbols.add(name, sym);
    }
    return sym;
}

sp<AaptSymbols> AaptAssets::getJavaSymbolsFor(const String8& name)
{
    sp<AaptSymbols> sym = mJavaSymbols.valueFor(name);
    if (sym == NULL) {
        sym = new AaptSymbols();
        mJavaSymbols.add(name, sym);
    }
    return sym;
}

status_t AaptAssets::applyJavaSymbols()
{
    size_t N = mJavaSymbols.size();
    for (size_t i=0; i<N; i++) {
        const String8& name = mJavaSymbols.keyAt(i);
        const sp<AaptSymbols>& symbols = mJavaSymbols.valueAt(i);
        ssize_t pos = mSymbols.indexOfKey(name);
        if (pos < 0) {
            SourcePos pos;
            pos.error("Java symbol dir %s not defined\n", name.string());
            return UNKNOWN_ERROR;
        }
        //printf("**** applying java symbols in dir %s\n", name.string());
        status_t err = mSymbols.valueAt(pos)->applyJavaSymbols(symbols);
        if (err != NO_ERROR) {
            return err;
        }
    }

    return NO_ERROR;
}

bool AaptAssets::isJavaSymbol(const AaptSymbolEntry& sym, bool includePrivate) const {
    //printf("isJavaSymbol %s: public=%d, includePrivate=%d, isJavaSymbol=%d\n",
    //        sym.name.string(), sym.isPublic ? 1 : 0, includePrivate ? 1 : 0,
    //        sym.isJavaSymbol ? 1 : 0);
    if (!mHavePrivateSymbols) return true;
    if (sym.isPublic) return true;
    if (includePrivate && sym.isJavaSymbol) return true;
    return false;
}

status_t AaptAssets::buildIncludedResources(Bundle* bundle)
{
    if (mHaveIncludedAssets) {
        return NO_ERROR;
    }

    // Add in all includes.
    const Vector<String8>& includes = bundle->getPackageIncludes();
    const size_t packageIncludeCount = includes.size();
    for (size_t i = 0; i < packageIncludeCount; i++) {
        if (bundle->getVerbose()) {
            printf("Including resources from package: %s\n", includes[i].string());
        }

        if (!mIncludedAssets.addAssetPath(includes[i], NULL)) {
            fprintf(stderr, "ERROR: Asset package include '%s' not found.\n",
                    includes[i].string());
            return UNKNOWN_ERROR;
        }
    }

    const String8& featureOfBase = bundle->getFeatureOfPackage();
    if (!featureOfBase.isEmpty()) {
        if (bundle->getVerbose()) {
            printf("Including base feature resources from package: %s\n",
                    featureOfBase.string());
        }

        if (!mIncludedAssets.addAssetPath(featureOfBase, NULL)) {
            fprintf(stderr, "ERROR: base feature package '%s' not found.\n",
                    featureOfBase.string());
            return UNKNOWN_ERROR;
        }
    }

    mHaveIncludedAssets = true;

    return NO_ERROR;
}

status_t AaptAssets::addIncludedResources(const sp<AaptFile>& file)
{
    const ResTable& res = getIncludedResources();
    // XXX dirty!
    return const_cast<ResTable&>(res).add(file->getData(), file->getSize());
}

const ResTable& AaptAssets::getIncludedResources() const
{
    return mIncludedAssets.getResources(false);
}

AssetManager& AaptAssets::getAssetManager()
{
    return mIncludedAssets;
}

void AaptAssets::print(const String8& prefix) const
{
    String8 innerPrefix(prefix);
    innerPrefix.append("  ");
    String8 innerInnerPrefix(innerPrefix);
    innerInnerPrefix.append("  ");
    printf("%sConfigurations:\n", prefix.string());
    const size_t N=mGroupEntries.size();
    for (size_t i=0; i<N; i++) {
        String8 cname = mGroupEntries.itemAt(i).toDirName(String8());
        printf("%s %s\n", prefix.string(),
                cname != "" ? cname.string() : "(default)");
    }

    printf("\n%sFiles:\n", prefix.string());
    AaptDir::print(innerPrefix);

    printf("\n%sResource Dirs:\n", prefix.string());
    const Vector<sp<AaptDir> >& resdirs = mResDirs;
    const size_t NR = resdirs.size();
    for (size_t i=0; i<NR; i++) {
        const sp<AaptDir>& d = resdirs.itemAt(i);
        printf("%s  Type %s\n", prefix.string(), d->getLeaf().string());
        d->print(innerInnerPrefix);
    }
}

sp<AaptDir> AaptAssets::resDir(const String8& name) const
{
    const Vector<sp<AaptDir> >& resdirs = mResDirs;
    const size_t N = resdirs.size();
    for (size_t i=0; i<N; i++) {
        const sp<AaptDir>& d = resdirs.itemAt(i);
        if (d->getLeaf() == name) {
            return d;
        }
    }
    return NULL;
}

bool
valid_symbol_name(const String8& symbol)
{
    static char const * const KEYWORDS[] = {
        "abstract", "assert", "boolean", "break",
        "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw",
        "throws", "transient", "try", "void", "volatile", "while",
        "true", "false", "null",
        NULL
    };
    const char*const* k = KEYWORDS;
    const char*const s = symbol.string();
    while (*k) {
        if (0 == strcmp(s, *k)) {
            return false;
        }
        k++;
    }
    return true;
}
