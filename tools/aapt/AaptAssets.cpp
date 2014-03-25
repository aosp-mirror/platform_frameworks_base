//
// Copyright 2006 The Android Open Source Project
//

#include "AaptAssets.h"
#include "ResourceFilter.h"
#include "Main.h"

#include <utils/misc.h>
#include <utils/SortedVector.h>

#include <ctype.h>
#include <dirent.h>
#include <errno.h>

static const char* kDefaultLocale = "default";
static const char* kWildcardName = "any";
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

/* static */ void AaptLocaleValue::splitAndLowerCase(const char* const chars,
        Vector<String8>* parts, const char separator) {
    const char *p = chars;
    const char *q;
    while (NULL != (q = strchr(p, separator))) {
         String8 val(p, q - p);
         val.toLower();
         parts->add(val);
         p = q+1;
    }

    if (p < chars + strlen(chars)) {
        String8 val(p);
        val.toLower();
        parts->add(val);
    }
}

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
     while ((*languageChars) != '\0') {
          language[i++] = tolower(*languageChars);
          languageChars++;
     }
}

void AaptLocaleValue::setRegion(const char* regionChars) {
    size_t i = 0;
    while ((*regionChars) != '\0') {
         region[i++] = toupper(*regionChars);
         regionChars++;
    }
}

void AaptLocaleValue::setScript(const char* scriptChars) {
    size_t i = 0;
    while ((*scriptChars) != '\0') {
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
     while ((*variantChars) != '\0') {
          variant[i++] = *variantChars;
          variantChars++;
     }
}

bool AaptLocaleValue::initFromFilterString(const String8& str) {
     // A locale (as specified in the filter) is an underscore separated name such
     // as "en_US", "en_Latn_US", or "en_US_POSIX".
     Vector<String8> parts;
     splitAndLowerCase(str.string(), &parts, '_');

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
     } else if (part2.length() >= 5 && part2.length() <= 8) {
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
     } else if (part3.length() >= 5 && part3.length() <= 8) {
         setVariant(part3.string());
     } else {
         valid = false;
     }

     if (!valid || numTags == 3) {
         return valid;
     }

     const String8& part4 = parts[3];
     if (part4.length() >= 5 && part4.length() <= 8) {
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
        // This is a "modified" BCP-47 language tag. Same semantics as BCP-47 tags,
        // except that the separator is "+" and not "-".
        Vector<String8> subtags;
        AaptLocaleValue::splitAndLowerCase(part.string(), &subtags, '+');
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
                    setScript(subtags[1]);
                    break;
                case 5:
                case 6:
                case 7:
                case 8:
                    setVariant(subtags[1]);
                    break;
                default:
                    fprintf(stderr, "ERROR: Invalid BCP-47 tag in directory name %s\n",
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
                fprintf(stderr, "ERROR: Invalid BCP-47 tag in directory name %s\n", part.string());
                return -1;
            }

            // The third tag can either be a region code (if the second tag was
            // a script), else a variant code.
            if (subtags[2].size() > 4) {
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
            fprintf(stderr, "ERROR: Invalid BCP-47 tag in directory name: %s\n", part.string());
            return -1;
        }

        return ++currentIndex;
    } else {
        if ((part.length() == 2 || part.length() == 3) && isAlpha(part)) {
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


String8 AaptLocaleValue::toDirName() const {
    String8 dirName("");
    if (language[0]) {
        dirName += language;
    } else {
        return dirName;
    }

    if (script[0]) {
        dirName += "-s";
        dirName += script;
    }

    if (region[0]) {
        dirName += "-r";
        dirName += region;
    }

    if (variant[0]) {
        dirName += "-v";
        dirName += variant;
    }

    return dirName;
}

void AaptLocaleValue::initFromResTable(const ResTable_config& config) {
    config.unpackLanguage(language);
    config.unpackRegion(region);
    if (config.localeScript[0]) {
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


/* static */ bool
AaptGroupEntry::parseFilterNamePart(const String8& part, int* axis, AxisValue* value)
{
    ResTable_config config;
    memset(&config, 0, sizeof(ResTable_config));

    // IMSI - MCC
    if (getMccName(part.string(), &config)) {
        *axis = AXIS_MCC;
        value->intValue = config.mcc;
        return true;
    }

    // IMSI - MNC
    if (getMncName(part.string(), &config)) {
        *axis = AXIS_MNC;
        value->intValue = config.mnc;
        return true;
    }

    // locale - language
    if (value->localeValue.initFromFilterString(part)) {
        *axis = AXIS_LOCALE;
        return true;
    }

    // layout direction
    if (getLayoutDirectionName(part.string(), &config)) {
        *axis = AXIS_LAYOUTDIR;
        value->intValue = (config.screenLayout&ResTable_config::MASK_LAYOUTDIR);
        return true;
    }

    // smallest screen dp width
    if (getSmallestScreenWidthDpName(part.string(), &config)) {
        *axis = AXIS_SMALLESTSCREENWIDTHDP;
        value->intValue = config.smallestScreenWidthDp;
        return true;
    }

    // screen dp width
    if (getScreenWidthDpName(part.string(), &config)) {
        *axis = AXIS_SCREENWIDTHDP;
        value->intValue = config.screenWidthDp;
        return true;
    }

    // screen dp height
    if (getScreenHeightDpName(part.string(), &config)) {
        *axis = AXIS_SCREENHEIGHTDP;
        value->intValue = config.screenHeightDp;
        return true;
    }

    // screen layout size
    if (getScreenLayoutSizeName(part.string(), &config)) {
        *axis = AXIS_SCREENLAYOUTSIZE;
        value->intValue = (config.screenLayout&ResTable_config::MASK_SCREENSIZE);
        return true;
    }

    // screen layout long
    if (getScreenLayoutLongName(part.string(), &config)) {
        *axis = AXIS_SCREENLAYOUTLONG;
        value->intValue = (config.screenLayout&ResTable_config::MASK_SCREENLONG);
        return true;
    }

    // orientation
    if (getOrientationName(part.string(), &config)) {
        *axis = AXIS_ORIENTATION;
        value->intValue = config.orientation;
        return true;
    }

    // ui mode type
    if (getUiModeTypeName(part.string(), &config)) {
        *axis = AXIS_UIMODETYPE;
        value->intValue = (config.uiMode&ResTable_config::MASK_UI_MODE_TYPE);
        return true;
    }

    // ui mode night
    if (getUiModeNightName(part.string(), &config)) {
        *axis = AXIS_UIMODENIGHT;
        value->intValue = (config.uiMode&ResTable_config::MASK_UI_MODE_NIGHT);
        return true;
    }

    // density
    if (getDensityName(part.string(), &config)) {
        *axis = AXIS_DENSITY;
        value->intValue = config.density;
        return true;
    }

    // touchscreen
    if (getTouchscreenName(part.string(), &config)) {
        *axis = AXIS_TOUCHSCREEN;
        value->intValue = config.touchscreen;
        return true;
    }

    // keyboard hidden
    if (getKeysHiddenName(part.string(), &config)) {
        *axis = AXIS_KEYSHIDDEN;
        value->intValue = config.inputFlags;
        return true;
    }

    // keyboard
    if (getKeyboardName(part.string(), &config)) {
        *axis = AXIS_KEYBOARD;
        value->intValue = config.keyboard;
        return true;
    }

    // navigation hidden
    if (getNavHiddenName(part.string(), &config)) {
        *axis = AXIS_NAVHIDDEN;
        value->intValue = config.inputFlags;
        return 0;
    }

    // navigation
    if (getNavigationName(part.string(), &config)) {
        *axis = AXIS_NAVIGATION;
        value->intValue = config.navigation;
        return true;
    }

    // screen size
    if (getScreenSizeName(part.string(), &config)) {
        *axis = AXIS_SCREENSIZE;
        value->intValue = config.screenSize;
        return true;
    }

    // version
    if (getVersionName(part.string(), &config)) {
        *axis = AXIS_VERSION;
        value->intValue = config.version;
        return true;
    }

    return false;
}

AxisValue
AaptGroupEntry::getConfigValueForAxis(const ResTable_config& config, int axis)
{
    AxisValue value;
    switch (axis) {
        case AXIS_MCC:
            value.intValue = config.mcc;
            break;
        case AXIS_MNC:
            value.intValue = config.mnc;
            break;
        case AXIS_LOCALE:
            value.localeValue.initFromResTable(config);
            break;
        case AXIS_LAYOUTDIR:
            value.intValue = config.screenLayout&ResTable_config::MASK_LAYOUTDIR;
            break;
        case AXIS_SCREENLAYOUTSIZE:
            value.intValue = config.screenLayout&ResTable_config::MASK_SCREENSIZE;
            break;
        case AXIS_ORIENTATION:
            value.intValue = config.orientation;
            break;
        case AXIS_UIMODETYPE:
            value.intValue = (config.uiMode&ResTable_config::MASK_UI_MODE_TYPE);
            break;
        case AXIS_UIMODENIGHT:
            value.intValue = (config.uiMode&ResTable_config::MASK_UI_MODE_NIGHT);
            break;
        case AXIS_DENSITY:
            value.intValue = config.density;
            break;
        case AXIS_TOUCHSCREEN:
            value.intValue = config.touchscreen;
            break;
        case AXIS_KEYSHIDDEN:
            value.intValue = config.inputFlags;
            break;
        case AXIS_KEYBOARD:
            value.intValue = config.keyboard;
            break;
        case AXIS_NAVIGATION:
            value.intValue = config.navigation;
            break;
        case AXIS_SCREENSIZE:
            value.intValue = config.screenSize;
            break;
        case AXIS_SMALLESTSCREENWIDTHDP:
            value.intValue = config.smallestScreenWidthDp;
            break;
        case AXIS_SCREENWIDTHDP:
            value.intValue = config.screenWidthDp;
            break;
        case AXIS_SCREENHEIGHTDP:
            value.intValue = config.screenHeightDp;
            break;
        case AXIS_VERSION:
            value.intValue = config.version;
            break;
    }

    return value;
}

bool
AaptGroupEntry::configSameExcept(const ResTable_config& config,
        const ResTable_config& otherConfig, int axis)
{
    for (int i=AXIS_START; i<=AXIS_END; i++) {
        if (i == axis) {
            continue;
        }
        if (getConfigValueForAxis(config, i) != getConfigValueForAxis(otherConfig, i)) {
            return false;
        }
    }
    return true;
}

bool
AaptGroupEntry::initFromDirName(const char* dir, String8* resType)
{
    mParamsChanged = true;

    Vector<String8> parts;
    AaptLocaleValue::splitAndLowerCase(dir, &parts, '-');

    String8 mcc, mnc, layoutsize, layoutlong, orient, den;
    String8 touch, key, keysHidden, nav, navHidden, size, layoutDir, vers;
    String8 uiModeType, uiModeNight, smallestwidthdp, widthdp, heightdp;

    AaptLocaleValue locale;
    int numLocaleComponents = 0;

    const int N = parts.size();
    int index = 0;
    String8 part = parts[index];

    // resource type
    if (!isValidResourceType(part)) {
        return false;
    }
    *resType = part;

    index++;
    if (index == N) {
        goto success;
    }
    part = parts[index];

    // imsi - mcc
    if (getMccName(part.string())) {
        mcc = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not mcc: %s\n", part.string());
    }

    // imsi - mnc
    if (getMncName(part.string())) {
        mnc = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not mnc: %s\n", part.string());
    }

    index = locale.initFromDirName(parts, index);
    if (index == -1) {
        return false;
    }
    if (index >= N){
        goto success;
    }

    part = parts[index];
    if (getLayoutDirectionName(part.string())) {
        layoutDir = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not layout direction: %s\n", part.string());
    }

    if (getSmallestScreenWidthDpName(part.string())) {
        smallestwidthdp = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not smallest screen width dp: %s\n", part.string());
    }

    if (getScreenWidthDpName(part.string())) {
        widthdp = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not screen width dp: %s\n", part.string());
    }

    if (getScreenHeightDpName(part.string())) {
        heightdp = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not screen height dp: %s\n", part.string());
    }

    if (getScreenLayoutSizeName(part.string())) {
        layoutsize = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not screen layout size: %s\n", part.string());
    }

    if (getScreenLayoutLongName(part.string())) {
        layoutlong = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not screen layout long: %s\n", part.string());
    }

    // orientation
    if (getOrientationName(part.string())) {
        orient = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not orientation: %s\n", part.string());
    }

    // ui mode type
    if (getUiModeTypeName(part.string())) {
        uiModeType = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not ui mode type: %s\n", part.string());
    }

    // ui mode night
    if (getUiModeNightName(part.string())) {
        uiModeNight = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not ui mode night: %s\n", part.string());
    }

    // density
    if (getDensityName(part.string())) {
        den = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not density: %s\n", part.string());
    }

    // touchscreen
    if (getTouchscreenName(part.string())) {
        touch = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not touchscreen: %s\n", part.string());
    }

    // keyboard hidden
    if (getKeysHiddenName(part.string())) {
        keysHidden = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not keysHidden: %s\n", part.string());
    }

    // keyboard
    if (getKeyboardName(part.string())) {
        key = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not keyboard: %s\n", part.string());
    }

    // navigation hidden
    if (getNavHiddenName(part.string())) {
        navHidden = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not navHidden: %s\n", part.string());
    }

    if (getNavigationName(part.string())) {
        nav = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not navigation: %s\n", part.string());
    }

    if (getScreenSizeName(part.string())) {
        size = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not screen size: %s\n", part.string());
    }

    if (getVersionName(part.string())) {
        vers = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not version: %s\n", part.string());
    }

    // if there are extra parts, it doesn't match
    return false;

success:
    this->mcc = mcc;
    this->mnc = mnc;
    this->locale = locale;
    this->screenLayoutSize = layoutsize;
    this->screenLayoutLong = layoutlong;
    this->smallestScreenWidthDp = smallestwidthdp;
    this->screenWidthDp = widthdp;
    this->screenHeightDp = heightdp;
    this->orientation = orient;
    this->uiModeType = uiModeType;
    this->uiModeNight = uiModeNight;
    this->density = den;
    this->touchscreen = touch;
    this->keysHidden = keysHidden;
    this->keyboard = key;
    this->navHidden = navHidden;
    this->navigation = nav;
    this->screenSize = size;
    this->layoutDirection = layoutDir;
    this->version = vers;

    // what is this anyway?
    this->vendor = "";

    return true;
}

String8
AaptGroupEntry::toString() const
{
    String8 s = this->mcc;
    s += ",";
    s += this->mnc;
    s += ",";
    s += locale.toDirName();
    s += ",";
    s += layoutDirection;
    s += ",";
    s += smallestScreenWidthDp;
    s += ",";
    s += screenWidthDp;
    s += ",";
    s += screenHeightDp;
    s += ",";
    s += screenLayoutSize;
    s += ",";
    s += screenLayoutLong;
    s += ",";
    s += this->orientation;
    s += ",";
    s += uiModeType;
    s += ",";
    s += uiModeNight;
    s += ",";
    s += density;
    s += ",";
    s += touchscreen;
    s += ",";
    s += keysHidden;
    s += ",";
    s += keyboard;
    s += ",";
    s += navHidden;
    s += ",";
    s += navigation;
    s += ",";
    s += screenSize;
    s += ",";
    s += version;
    return s;
}

String8
AaptGroupEntry::toDirName(const String8& resType) const
{
    String8 s = resType;
    if (this->mcc != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += mcc;
    }
    if (this->mnc != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += mnc;
    }

    const String8 localeComponent = locale.toDirName();
    if (localeComponent != "") {
         if (s.length() > 0) {
             s += "-";
         }
         s += localeComponent;
    }

    if (this->layoutDirection != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += layoutDirection;
    }
    if (this->smallestScreenWidthDp != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += smallestScreenWidthDp;
    }
    if (this->screenWidthDp != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += screenWidthDp;
    }
    if (this->screenHeightDp != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += screenHeightDp;
    }
    if (this->screenLayoutSize != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += screenLayoutSize;
    }
    if (this->screenLayoutLong != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += screenLayoutLong;
    }
    if (this->orientation != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += orientation;
    }
    if (this->uiModeType != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += uiModeType;
    }
    if (this->uiModeNight != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += uiModeNight;
    }
    if (this->density != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += density;
    }
    if (this->touchscreen != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += touchscreen;
    }
    if (this->keysHidden != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += keysHidden;
    }
    if (this->keyboard != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += keyboard;
    }
    if (this->navHidden != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += navHidden;
    }
    if (this->navigation != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += navigation;
    }
    if (this->screenSize != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += screenSize;
    }
    if (this->version != "") {
        if (s.length() > 0) {
            s += "-";
        }
        s += version;
    }

    return s;
}

bool AaptGroupEntry::getMccName(const char* name,
                                    ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->mcc = 0;
        return true;
    }
    const char* c = name;
    if (tolower(*c) != 'm') return false;
    c++;
    if (tolower(*c) != 'c') return false;
    c++;
    if (tolower(*c) != 'c') return false;
    c++;

    const char* val = c;

    while (*c >= '0' && *c <= '9') {
        c++;
    }
    if (*c != 0) return false;
    if (c-val != 3) return false;

    int d = atoi(val);
    if (d != 0) {
        if (out) out->mcc = d;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getMncName(const char* name,
                                    ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->mcc = 0;
        return true;
    }
    const char* c = name;
    if (tolower(*c) != 'm') return false;
    c++;
    if (tolower(*c) != 'n') return false;
    c++;
    if (tolower(*c) != 'c') return false;
    c++;

    const char* val = c;

    while (*c >= '0' && *c <= '9') {
        c++;
    }
    if (*c != 0) return false;
    if (c-val == 0 || c-val > 3) return false;

    if (out) {
        out->mnc = atoi(val);
        if (out->mnc == 0) {
            out->mnc = ACONFIGURATION_MNC_ZERO;
        }
    }

    return true;
}

bool AaptGroupEntry::getLayoutDirectionName(const char* name, ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_LAYOUTDIR)
                | ResTable_config::LAYOUTDIR_ANY;
        return true;
    } else if (strcmp(name, "ldltr") == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_LAYOUTDIR)
                | ResTable_config::LAYOUTDIR_LTR;
        return true;
    } else if (strcmp(name, "ldrtl") == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_LAYOUTDIR)
                | ResTable_config::LAYOUTDIR_RTL;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getScreenLayoutSizeName(const char* name,
                                     ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_SCREENSIZE)
                | ResTable_config::SCREENSIZE_ANY;
        return true;
    } else if (strcmp(name, "small") == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_SCREENSIZE)
                | ResTable_config::SCREENSIZE_SMALL;
        return true;
    } else if (strcmp(name, "normal") == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_SCREENSIZE)
                | ResTable_config::SCREENSIZE_NORMAL;
        return true;
    } else if (strcmp(name, "large") == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_SCREENSIZE)
                | ResTable_config::SCREENSIZE_LARGE;
        return true;
    } else if (strcmp(name, "xlarge") == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_SCREENSIZE)
                | ResTable_config::SCREENSIZE_XLARGE;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getScreenLayoutLongName(const char* name,
                                     ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_SCREENLONG)
                | ResTable_config::SCREENLONG_ANY;
        return true;
    } else if (strcmp(name, "long") == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_SCREENLONG)
                | ResTable_config::SCREENLONG_YES;
        return true;
    } else if (strcmp(name, "notlong") == 0) {
        if (out) out->screenLayout =
                (out->screenLayout&~ResTable_config::MASK_SCREENLONG)
                | ResTable_config::SCREENLONG_NO;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getOrientationName(const char* name,
                                        ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->orientation = out->ORIENTATION_ANY;
        return true;
    } else if (strcmp(name, "port") == 0) {
        if (out) out->orientation = out->ORIENTATION_PORT;
        return true;
    } else if (strcmp(name, "land") == 0) {
        if (out) out->orientation = out->ORIENTATION_LAND;
        return true;
    } else if (strcmp(name, "square") == 0) {
        if (out) out->orientation = out->ORIENTATION_SQUARE;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getUiModeTypeName(const char* name,
                                       ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->uiMode =
                (out->uiMode&~ResTable_config::MASK_UI_MODE_TYPE)
                | ResTable_config::UI_MODE_TYPE_ANY;
        return true;
    } else if (strcmp(name, "desk") == 0) {
      if (out) out->uiMode =
              (out->uiMode&~ResTable_config::MASK_UI_MODE_TYPE)
              | ResTable_config::UI_MODE_TYPE_DESK;
        return true;
    } else if (strcmp(name, "car") == 0) {
      if (out) out->uiMode =
              (out->uiMode&~ResTable_config::MASK_UI_MODE_TYPE)
              | ResTable_config::UI_MODE_TYPE_CAR;
        return true;
    } else if (strcmp(name, "television") == 0) {
      if (out) out->uiMode =
              (out->uiMode&~ResTable_config::MASK_UI_MODE_TYPE)
              | ResTable_config::UI_MODE_TYPE_TELEVISION;
        return true;
    } else if (strcmp(name, "appliance") == 0) {
      if (out) out->uiMode =
              (out->uiMode&~ResTable_config::MASK_UI_MODE_TYPE)
              | ResTable_config::UI_MODE_TYPE_APPLIANCE;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getUiModeNightName(const char* name,
                                          ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->uiMode =
                (out->uiMode&~ResTable_config::MASK_UI_MODE_NIGHT)
                | ResTable_config::UI_MODE_NIGHT_ANY;
        return true;
    } else if (strcmp(name, "night") == 0) {
        if (out) out->uiMode =
                (out->uiMode&~ResTable_config::MASK_UI_MODE_NIGHT)
                | ResTable_config::UI_MODE_NIGHT_YES;
        return true;
    } else if (strcmp(name, "notnight") == 0) {
      if (out) out->uiMode =
              (out->uiMode&~ResTable_config::MASK_UI_MODE_NIGHT)
              | ResTable_config::UI_MODE_NIGHT_NO;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getDensityName(const char* name,
                                    ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->density = ResTable_config::DENSITY_DEFAULT;
        return true;
    }
    
    if (strcmp(name, "nodpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_NONE;
        return true;
    }
    
    if (strcmp(name, "ldpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_LOW;
        return true;
    }
    
    if (strcmp(name, "mdpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_MEDIUM;
        return true;
    }
    
    if (strcmp(name, "tvdpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_TV;
        return true;
    }
    
    if (strcmp(name, "hdpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_HIGH;
        return true;
    }

    if (strcmp(name, "xhdpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_XHIGH;
        return true;
    }

    if (strcmp(name, "xxhdpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_XXHIGH;
        return true;
    }

    if (strcmp(name, "xxxhdpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_XXXHIGH;
        return true;
    }

    char* c = (char*)name;
    while (*c >= '0' && *c <= '9') {
        c++;
    }

    // check that we have 'dpi' after the last digit.
    if (toupper(c[0]) != 'D' ||
            toupper(c[1]) != 'P' ||
            toupper(c[2]) != 'I' ||
            c[3] != 0) {
        return false;
    }

    // temporarily replace the first letter with \0 to
    // use atoi.
    char tmp = c[0];
    c[0] = '\0';

    int d = atoi(name);
    c[0] = tmp;

    if (d != 0) {
        if (out) out->density = d;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getTouchscreenName(const char* name,
                                        ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->touchscreen = out->TOUCHSCREEN_ANY;
        return true;
    } else if (strcmp(name, "notouch") == 0) {
        if (out) out->touchscreen = out->TOUCHSCREEN_NOTOUCH;
        return true;
    } else if (strcmp(name, "stylus") == 0) {
        if (out) out->touchscreen = out->TOUCHSCREEN_STYLUS;
        return true;
    } else if (strcmp(name, "finger") == 0) {
        if (out) out->touchscreen = out->TOUCHSCREEN_FINGER;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getKeysHiddenName(const char* name,
                                       ResTable_config* out)
{
    uint8_t mask = 0;
    uint8_t value = 0;
    if (strcmp(name, kWildcardName) == 0) {
        mask = ResTable_config::MASK_KEYSHIDDEN;
        value = ResTable_config::KEYSHIDDEN_ANY;
    } else if (strcmp(name, "keysexposed") == 0) {
        mask = ResTable_config::MASK_KEYSHIDDEN;
        value = ResTable_config::KEYSHIDDEN_NO;
    } else if (strcmp(name, "keyshidden") == 0) {
        mask = ResTable_config::MASK_KEYSHIDDEN;
        value = ResTable_config::KEYSHIDDEN_YES;
    } else if (strcmp(name, "keyssoft") == 0) {
        mask = ResTable_config::MASK_KEYSHIDDEN;
        value = ResTable_config::KEYSHIDDEN_SOFT;
    }

    if (mask != 0) {
        if (out) out->inputFlags = (out->inputFlags&~mask) | value;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getKeyboardName(const char* name,
                                        ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->keyboard = out->KEYBOARD_ANY;
        return true;
    } else if (strcmp(name, "nokeys") == 0) {
        if (out) out->keyboard = out->KEYBOARD_NOKEYS;
        return true;
    } else if (strcmp(name, "qwerty") == 0) {
        if (out) out->keyboard = out->KEYBOARD_QWERTY;
        return true;
    } else if (strcmp(name, "12key") == 0) {
        if (out) out->keyboard = out->KEYBOARD_12KEY;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getNavHiddenName(const char* name,
                                       ResTable_config* out)
{
    uint8_t mask = 0;
    uint8_t value = 0;
    if (strcmp(name, kWildcardName) == 0) {
        mask = ResTable_config::MASK_NAVHIDDEN;
        value = ResTable_config::NAVHIDDEN_ANY;
    } else if (strcmp(name, "navexposed") == 0) {
        mask = ResTable_config::MASK_NAVHIDDEN;
        value = ResTable_config::NAVHIDDEN_NO;
    } else if (strcmp(name, "navhidden") == 0) {
        mask = ResTable_config::MASK_NAVHIDDEN;
        value = ResTable_config::NAVHIDDEN_YES;
    }

    if (mask != 0) {
        if (out) out->inputFlags = (out->inputFlags&~mask) | value;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getNavigationName(const char* name,
                                     ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) out->navigation = out->NAVIGATION_ANY;
        return true;
    } else if (strcmp(name, "nonav") == 0) {
        if (out) out->navigation = out->NAVIGATION_NONAV;
        return true;
    } else if (strcmp(name, "dpad") == 0) {
        if (out) out->navigation = out->NAVIGATION_DPAD;
        return true;
    } else if (strcmp(name, "trackball") == 0) {
        if (out) out->navigation = out->NAVIGATION_TRACKBALL;
        return true;
    } else if (strcmp(name, "wheel") == 0) {
        if (out) out->navigation = out->NAVIGATION_WHEEL;
        return true;
    }

    return false;
}

bool AaptGroupEntry::getScreenSizeName(const char* name, ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) {
            out->screenWidth = out->SCREENWIDTH_ANY;
            out->screenHeight = out->SCREENHEIGHT_ANY;
        }
        return true;
    }

    const char* x = name;
    while (*x >= '0' && *x <= '9') x++;
    if (x == name || *x != 'x') return false;
    String8 xName(name, x-name);
    x++;

    const char* y = x;
    while (*y >= '0' && *y <= '9') y++;
    if (y == name || *y != 0) return false;
    String8 yName(x, y-x);

    uint16_t w = (uint16_t)atoi(xName.string());
    uint16_t h = (uint16_t)atoi(yName.string());
    if (w < h) {
        return false;
    }

    if (out) {
        out->screenWidth = w;
        out->screenHeight = h;
    }

    return true;
}

bool AaptGroupEntry::getSmallestScreenWidthDpName(const char* name, ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) {
            out->smallestScreenWidthDp = out->SCREENWIDTH_ANY;
        }
        return true;
    }

    if (*name != 's') return false;
    name++;
    if (*name != 'w') return false;
    name++;
    const char* x = name;
    while (*x >= '0' && *x <= '9') x++;
    if (x == name || x[0] != 'd' || x[1] != 'p' || x[2] != 0) return false;
    String8 xName(name, x-name);

    if (out) {
        out->smallestScreenWidthDp = (uint16_t)atoi(xName.string());
    }

    return true;
}

bool AaptGroupEntry::getScreenWidthDpName(const char* name, ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) {
            out->screenWidthDp = out->SCREENWIDTH_ANY;
        }
        return true;
    }

    if (*name != 'w') return false;
    name++;
    const char* x = name;
    while (*x >= '0' && *x <= '9') x++;
    if (x == name || x[0] != 'd' || x[1] != 'p' || x[2] != 0) return false;
    String8 xName(name, x-name);

    if (out) {
        out->screenWidthDp = (uint16_t)atoi(xName.string());
    }

    return true;
}

bool AaptGroupEntry::getScreenHeightDpName(const char* name, ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) {
            out->screenHeightDp = out->SCREENWIDTH_ANY;
        }
        return true;
    }

    if (*name != 'h') return false;
    name++;
    const char* x = name;
    while (*x >= '0' && *x <= '9') x++;
    if (x == name || x[0] != 'd' || x[1] != 'p' || x[2] != 0) return false;
    String8 xName(name, x-name);

    if (out) {
        out->screenHeightDp = (uint16_t)atoi(xName.string());
    }

    return true;
}

bool AaptGroupEntry::getVersionName(const char* name, ResTable_config* out)
{
    if (strcmp(name, kWildcardName) == 0) {
        if (out) {
            out->sdkVersion = out->SDKVERSION_ANY;
            out->minorVersion = out->MINORVERSION_ANY;
        }
        return true;
    }

    if (*name != 'v') {
        return false;
    }

    name++;
    const char* s = name;
    while (*s >= '0' && *s <= '9') s++;
    if (s == name || *s != 0) return false;
    String8 sdkName(name, s-name);

    if (out) {
        out->sdkVersion = (uint16_t)atoi(sdkName.string());
        out->minorVersion = 0;
    }

    return true;
}

int AaptGroupEntry::compare(const AaptGroupEntry& o) const
{
    int v = mcc.compare(o.mcc);
    if (v == 0) v = mnc.compare(o.mnc);
    if (v == 0) v = locale.compare(o.locale);
    if (v == 0) v = layoutDirection.compare(o.layoutDirection);
    if (v == 0) v = vendor.compare(o.vendor);
    if (v == 0) v = smallestScreenWidthDp.compare(o.smallestScreenWidthDp);
    if (v == 0) v = screenWidthDp.compare(o.screenWidthDp);
    if (v == 0) v = screenHeightDp.compare(o.screenHeightDp);
    if (v == 0) v = screenLayoutSize.compare(o.screenLayoutSize);
    if (v == 0) v = screenLayoutLong.compare(o.screenLayoutLong);
    if (v == 0) v = orientation.compare(o.orientation);
    if (v == 0) v = uiModeType.compare(o.uiModeType);
    if (v == 0) v = uiModeNight.compare(o.uiModeNight);
    if (v == 0) v = density.compare(o.density);
    if (v == 0) v = touchscreen.compare(o.touchscreen);
    if (v == 0) v = keysHidden.compare(o.keysHidden);
    if (v == 0) v = keyboard.compare(o.keyboard);
    if (v == 0) v = navHidden.compare(o.navHidden);
    if (v == 0) v = navigation.compare(o.navigation);
    if (v == 0) v = screenSize.compare(o.screenSize);
    if (v == 0) v = version.compare(o.version);
    return v;
}

const ResTable_config AaptGroupEntry::toParams() const
{
    if (!mParamsChanged) {
        return mParams;
    }

    mParamsChanged = false;
    ResTable_config& params = mParams;
    memset(&params, 0, sizeof(ResTable_config));
    getMccName(mcc.string(), &params);
    getMncName(mnc.string(), &params);
    locale.writeTo(&params);
    getLayoutDirectionName(layoutDirection.string(), &params);
    getSmallestScreenWidthDpName(smallestScreenWidthDp.string(), &params);
    getScreenWidthDpName(screenWidthDp.string(), &params);
    getScreenHeightDpName(screenHeightDp.string(), &params);
    getScreenLayoutSizeName(screenLayoutSize.string(), &params);
    getScreenLayoutLongName(screenLayoutLong.string(), &params);
    getOrientationName(orientation.string(), &params);
    getUiModeTypeName(uiModeType.string(), &params);
    getUiModeNightName(uiModeNight.string(), &params);
    getDensityName(density.string(), &params);
    getTouchscreenName(touchscreen.string(), &params);
    getKeysHiddenName(keysHidden.string(), &params);
    getKeyboardName(keyboard.string(), &params);
    getNavHiddenName(navHidden.string(), &params);
    getNavigationName(navigation.string(), &params);
    getScreenSizeName(screenSize.string(), &params);
    getVersionName(version.string(), &params);
    
    // Fix up version number based on specified parameters.
    int minSdk = 0;
    if (params.smallestScreenWidthDp != ResTable_config::SCREENWIDTH_ANY
            || params.screenWidthDp != ResTable_config::SCREENWIDTH_ANY
            || params.screenHeightDp != ResTable_config::SCREENHEIGHT_ANY) {
        minSdk = SDK_HONEYCOMB_MR2;
    } else if ((params.uiMode&ResTable_config::MASK_UI_MODE_TYPE)
                != ResTable_config::UI_MODE_TYPE_ANY
            ||  (params.uiMode&ResTable_config::MASK_UI_MODE_NIGHT)
                != ResTable_config::UI_MODE_NIGHT_ANY) {
        minSdk = SDK_FROYO;
    } else if ((params.screenLayout&ResTable_config::MASK_SCREENSIZE)
                != ResTable_config::SCREENSIZE_ANY
            ||  (params.screenLayout&ResTable_config::MASK_SCREENLONG)
                != ResTable_config::SCREENLONG_ANY
            || params.density != ResTable_config::DENSITY_DEFAULT) {
        minSdk = SDK_DONUT;
    }
    
    if (minSdk > params.sdkVersion) {
        params.sdkVersion = minSdk;
    }
    
    return params;
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

#if 0
    printf("Error adding file %s: group %s already exists in leaf=%s path=%s\n",
            file->getSourceFile().string(),
            file->getGroupEntry().toDirName(String8()).string(),
            mLeaf.string(), mPath.string());
#endif

    SourcePos(file->getSourceFile(), -1).error("Duplicate file.\n%s: Original is here.",
                                               getPrintableSource().string());
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
      mRes(NULL)
{
}

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
                                    sp<FilePathStore>& fullResPaths)
{
    ssize_t res = AaptDir::slurpFullTree(bundle, srcDir, kind, resType, fullResPaths);
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
            fprintf(stderr, "invalid resource directory name: %s/%s\n", srcDir.string(),
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
AaptAssets::slurpResourceZip(Bundle* bundle, const char* filename)
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
    ResourceFilter reqFilter;
    status_t err = reqFilter.parse(bundle->getConfigurations());
    if (err != NO_ERROR) {
        return err;
    }

    ResourceFilter prefFilter;
    err = prefFilter.parse(bundle->getPreferredConfigurations());
    if (err != NO_ERROR) {
        return err;
    }

    if (reqFilter.isEmpty() && prefFilter.isEmpty()) {
        return NO_ERROR;
    }

    if (bundle->getVerbose()) {
        if (!reqFilter.isEmpty()) {
            printf("Applying required filter: %s\n",
                    bundle->getConfigurations());
        }
        if (!prefFilter.isEmpty()) {
            printf("Applying preferred filter: %s\n",
                    bundle->getPreferredConfigurations());
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
                if (!reqFilter.match(config)) {
                    if (bundle->getVerbose()) {
                        printf("Pruning unneeded resource: %s\n",
                                file->getPrintableSource().string());
                    }
                    grp->removeFile(k);
                    k--;
                }
            }

            // Quick check: no preferred filters, nothing more to do.
            if (prefFilter.isEmpty()) {
                continue;
            }

            // Get the preferred density if there is one. We do not match exactly for density.
            // If our preferred density is hdpi but we only have mdpi and xhdpi resources, we
            // pick xhdpi.
            uint32_t preferredDensity = 0;
            const SortedVector<AxisValue>* preferredConfigs = prefFilter.configsForAxis(AXIS_DENSITY);
            if (preferredConfigs != NULL && preferredConfigs->size() > 0) {
                preferredDensity = (*preferredConfigs)[0].intValue;
            }

            // Now deal with preferred configurations.
            for (int axis=AXIS_START; axis<=AXIS_END; axis++) {
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
                    if (!prefFilter.match(axis, config)) {
                        // This is a resource we would prefer not to have.  Check
                        // to see if have a similar variation that we would like
                        // to have and, if so, we can drop it.

                        uint32_t bestDensity = config.density;

                        for (size_t m=0; m<grp->getFiles().size(); m++) {
                            if (m == k) continue;
                            sp<AaptFile> mfile = grp->getFiles().valueAt(m);
                            const ResTable_config& mconfig(mfile->getGroupEntry().toParams());
                            if (AaptGroupEntry::configSameExcept(config, mconfig, axis)) {
                                if (axis == AXIS_DENSITY && preferredDensity > 0) {
                                    // See if there is a better density resource
                                    if (mconfig.density < bestDensity &&
                                            mconfig.density > preferredDensity &&
                                            bestDensity > preferredDensity) {
                                        // This density is between our best density and
                                        // the preferred density, therefore it is better.
                                        bestDensity = mconfig.density;
                                    } else if (mconfig.density > bestDensity &&
                                            bestDensity < preferredDensity) {
                                        // This density is better than our best density and
                                        // our best density was smaller than our preferred
                                        // density, so it is better.
                                        bestDensity = mconfig.density;
                                    }
                                } else if (prefFilter.match(axis, mconfig)) {
                                    if (bundle->getVerbose()) {
                                        printf("Pruning unneeded resource: %s\n",
                                                file->getPrintableSource().string());
                                    }
                                    grp->removeFile(k);
                                    k--;
                                    break;
                                }
                            }
                        }

                        if (axis == AXIS_DENSITY && preferredDensity > 0 &&
                                bestDensity != config.density) {
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
    if (!mHaveIncludedAssets) {
        // Add in all includes.
        const Vector<const char*>& incl = bundle->getPackageIncludes();
        const size_t N=incl.size();
        for (size_t i=0; i<N; i++) {
            if (bundle->getVerbose())
                printf("Including resources from package: %s\n", incl[i]);
            if (!mIncludedAssets.addAssetPath(String8(incl[i]), NULL)) {
                fprintf(stderr, "ERROR: Asset package include '%s' not found.\n",
                        incl[i]);
                return UNKNOWN_ERROR;
            }
        }
        mHaveIncludedAssets = true;
    }

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
