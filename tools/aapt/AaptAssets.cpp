//
// Copyright 2006 The Android Open Source Project
//

#include "AaptAssets.h"
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

static bool isHidden(const char *root, const char *path)
{
    const char *ext  = NULL;
    const char *type = NULL;

    // Skip all hidden files.
    if (path[0] == '.') {
        // Skip ., .. and  .svn but don't chatter about it.
        if (strcmp(path, ".") == 0
            || strcmp(path, "..") == 0
            || strcmp(path, ".svn") == 0) {
            return true;
        }
        type = "hidden";
    } else if (path[0] == '_') {
        // skip directories starting with _ (don't chatter about it)
        String8 subdirName(root);
        subdirName.appendPath(path);
        if (getFileType(subdirName.string()) == kFileTypeDirectory) {
            return true;
        }
    } else if (strcmp(path, "CVS") == 0) {
        // Skip CVS but don't chatter about it.
        return true;
    } else if (strcasecmp(path, "thumbs.db") == 0
               || strcasecmp(path, "picasa.ini") == 0) {
        // Skip suspected image indexes files.
        type = "index";
    } else if (path[strlen(path)-1] == '~') {
        // Skip suspected emacs backup files.
        type = "backup";
    } else if ((ext = strrchr(path, '.')) != NULL && strcmp(ext, ".scc") == 0) {
        // Skip VisualSourceSafe files and don't chatter about it
        return true;
    } else {
        // Let everything else through.
        return false;
    }

    /* If we get this far, "type" should be set and the file
     * should be skipped.
     */
    String8 subdirName(root);
    subdirName.appendPath(path);
    fprintf(stderr, "    (skipping %s %s '%s')\n", type,
            getFileType(subdirName.string())==kFileTypeDirectory ? "dir":"file",
            subdirName.string());

    return true;
}

// =========================================================================
// =========================================================================
// =========================================================================

status_t
AaptGroupEntry::parseNamePart(const String8& part, int* axis, uint32_t* value)
{
    ResTable_config config;

    // IMSI - MCC
    if (getMccName(part.string(), &config)) {
        *axis = AXIS_MCC;
        *value = config.mcc;
        return 0;
    }

    // IMSI - MNC
    if (getMncName(part.string(), &config)) {
        *axis = AXIS_MNC;
        *value = config.mnc;
        return 0;
    }

    // locale - language
    if (part.length() == 2 && isalpha(part[0]) && isalpha(part[1])) {
        *axis = AXIS_LANGUAGE;
        *value = part[1] << 8 | part[0];
        return 0;
    }

    // locale - language_REGION
    if (part.length() == 5 && isalpha(part[0]) && isalpha(part[1])
            && part[2] == '_' && isalpha(part[3]) && isalpha(part[4])) {
        *axis = AXIS_LANGUAGE;
        *value = (part[4] << 24) | (part[3] << 16) | (part[1] << 8) | (part[0]);
        return 0;
    }

    // screen layout size
    if (getScreenLayoutSizeName(part.string(), &config)) {
        *axis = AXIS_SCREENLAYOUTSIZE;
        *value = (config.screenLayout&ResTable_config::MASK_SCREENSIZE);
        return 0;
    }

    // screen layout long
    if (getScreenLayoutLongName(part.string(), &config)) {
        *axis = AXIS_SCREENLAYOUTLONG;
        *value = (config.screenLayout&ResTable_config::MASK_SCREENLONG);
        return 0;
    }

    // orientation
    if (getOrientationName(part.string(), &config)) {
        *axis = AXIS_ORIENTATION;
        *value = config.orientation;
        return 0;
    }

    // ui mode type
    if (getUiModeTypeName(part.string(), &config)) {
        *axis = AXIS_UIMODETYPE;
        *value = (config.uiMode&ResTable_config::MASK_UI_MODE_TYPE);
        return 0;
    }

    // ui mode night
    if (getUiModeNightName(part.string(), &config)) {
        *axis = AXIS_UIMODENIGHT;
        *value = (config.uiMode&ResTable_config::MASK_UI_MODE_NIGHT);
        return 0;
    }

    // density
    if (getDensityName(part.string(), &config)) {
        *axis = AXIS_DENSITY;
        *value = config.density;
        return 0;
    }

    // touchscreen
    if (getTouchscreenName(part.string(), &config)) {
        *axis = AXIS_TOUCHSCREEN;
        *value = config.touchscreen;
        return 0;
    }

    // keyboard hidden
    if (getKeysHiddenName(part.string(), &config)) {
        *axis = AXIS_KEYSHIDDEN;
        *value = config.inputFlags;
        return 0;
    }

    // keyboard
    if (getKeyboardName(part.string(), &config)) {
        *axis = AXIS_KEYBOARD;
        *value = config.keyboard;
        return 0;
    }

    // navigation hidden
    if (getNavHiddenName(part.string(), &config)) {
        *axis = AXIS_NAVHIDDEN;
        *value = config.inputFlags;
        return 0;
    }

    // navigation
    if (getNavigationName(part.string(), &config)) {
        *axis = AXIS_NAVIGATION;
        *value = config.navigation;
        return 0;
    }

    // screen size
    if (getScreenSizeName(part.string(), &config)) {
        *axis = AXIS_SCREENSIZE;
        *value = config.screenSize;
        return 0;
    }

    // version
    if (getVersionName(part.string(), &config)) {
        *axis = AXIS_VERSION;
        *value = config.version;
        return 0;
    }

    return 1;
}

bool
AaptGroupEntry::initFromDirName(const char* dir, String8* resType)
{
    Vector<String8> parts;

    String8 mcc, mnc, loc, layoutsize, layoutlong, orient, den;
    String8 touch, key, keysHidden, nav, navHidden, size, vers;
    String8 uiModeType, uiModeNight;

    const char *p = dir;
    const char *q;
    while (NULL != (q = strchr(p, '-'))) {
        String8 val(p, q-p);
        val.toLower();
        parts.add(val);
        //printf("part: %s\n", parts[parts.size()-1].string());
        p = q+1;
    }
    String8 val(p);
    val.toLower();
    parts.add(val);
    //printf("part: %s\n", parts[parts.size()-1].string());

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
        //printf("not mcc: %s\n", part.string());
    }

    // locale - language
    if (part.length() == 2 && isalpha(part[0]) && isalpha(part[1])) {
        loc = part;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not language: %s\n", part.string());
    }

    // locale - region
    if (loc.length() > 0
            && part.length() == 3 && part[0] == 'r' && part[0] && part[1]) {
        loc += "-";
        part.toUpper();
        loc += part.string() + 1;

        index++;
        if (index == N) {
            goto success;
        }
        part = parts[index];
    } else {
        //printf("not region: %s\n", part.string());
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
    this->locale = loc;
    this->screenLayoutSize = layoutsize;
    this->screenLayoutLong = layoutlong;
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
    s += this->locale;
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
        s += "-";
        s += mcc;
    }
    if (this->mnc != "") {
        s += "-";
        s += mnc;
    }
    if (this->locale != "") {
        s += "-";
        s += locale;
    }
    if (this->screenLayoutSize != "") {
        s += "-";
        s += screenLayoutSize;
    }
    if (this->screenLayoutLong != "") {
        s += "-";
        s += screenLayoutLong;
    }
    if (this->orientation != "") {
        s += "-";
        s += orientation;
    }
    if (this->uiModeType != "") {
        s += "-";
        s += uiModeType;
    }
    if (this->uiModeNight != "") {
        s += "-";
        s += uiModeNight;
    }
    if (this->density != "") {
        s += "-";
        s += density;
    }
    if (this->touchscreen != "") {
        s += "-";
        s += touchscreen;
    }
    if (this->keysHidden != "") {
        s += "-";
        s += keysHidden;
    }
    if (this->keyboard != "") {
        s += "-";
        s += keyboard;
    }
    if (this->navHidden != "") {
        s += "-";
        s += navHidden;
    }
    if (this->navigation != "") {
        s += "-";
        s += navigation;
    }
    if (this->screenSize != "") {
        s += "-";
        s += screenSize;
    }
    if (this->version != "") {
        s += "-";
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
    }

    return true;
}

/*
 * Does this directory name fit the pattern of a locale dir ("en-rUS" or
 * "default")?
 *
 * TODO: Should insist that the first two letters are lower case, and the
 * second two are upper.
 */
bool AaptGroupEntry::getLocaleName(const char* fileName,
                                   ResTable_config* out)
{
    if (strcmp(fileName, kWildcardName) == 0
            || strcmp(fileName, kDefaultLocale) == 0) {
        if (out) {
            out->language[0] = 0;
            out->language[1] = 0;
            out->country[0] = 0;
            out->country[1] = 0;
        }
        return true;
    }

    if (strlen(fileName) == 2 && isalpha(fileName[0]) && isalpha(fileName[1])) {
        if (out) {
            out->language[0] = fileName[0];
            out->language[1] = fileName[1];
            out->country[0] = 0;
            out->country[1] = 0;
        }
        return true;
    }

    if (strlen(fileName) == 5 &&
        isalpha(fileName[0]) &&
        isalpha(fileName[1]) &&
        fileName[2] == '-' &&
        isalpha(fileName[3]) &&
        isalpha(fileName[4])) {
        if (out) {
            out->language[0] = fileName[0];
            out->language[1] = fileName[1];
            out->country[0] = fileName[3];
            out->country[1] = fileName[4];
        }
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
    
    if (strcmp(name, "hdpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_HIGH;
        return true;
    }
    
    if (strcmp(name, "xhdpi") == 0) {
        if (out) out->density = ResTable_config::DENSITY_MEDIUM*2;
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

bool AaptGroupEntry::getScreenSizeName(const char* name,
                                       ResTable_config* out)
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

bool AaptGroupEntry::getVersionName(const char* name,
                                    ResTable_config* out)
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
    if (v == 0) v = vendor.compare(o.vendor);
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

ResTable_config AaptGroupEntry::toParams() const
{
    ResTable_config params;
    memset(&params, 0, sizeof(params));
    getMccName(mcc.string(), &params);
    getMncName(mnc.string(), &params);
    getLocaleName(locale.string(), &params);
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
    if ((params.uiMode&ResTable_config::MASK_UI_MODE_TYPE)
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
        String8 name(mGroupEntry.locale.string());
        name.appendPath(mGroupEntry.vendor.string());
        name.appendPath(mPath);
        name.append(" #generated");
        return name;
    }
    return mSourceFile;
}

// =========================================================================
// =========================================================================
// =========================================================================

status_t AaptGroup::addFile(const sp<AaptFile>& file)
{
    if (mFiles.indexOfKey(file->getGroupEntry()) < 0) {
        file->mPath = mPath;
        mFiles.add(file->getGroupEntry(), file);
        return NO_ERROR;
    }

    SourcePos(file->getSourceFile(), -1).error("Duplicate file.\n%s: Original is here.",
                                               getPrintableSource().string());
    return UNKNOWN_ERROR;
}

void AaptGroup::removeFile(size_t index)
{
	mFiles.removeItemsAt(index);
}

void AaptGroup::print() const
{
    printf("  %s\n", getPath().string());
    const size_t N=mFiles.size();
    size_t i;
    for (i=0; i<N; i++) {
        sp<AaptFile> file = mFiles.valueAt(i);
        const AaptGroupEntry& e = file->getGroupEntry();
        if (file->hasData()) {
            printf("      Gen: (%s) %d bytes\n", e.toString().string(),
                    (int)file->getSize());
        } else {
            printf("      Src: %s\n", file->getPrintableSource().string());
        }
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

status_t AaptDir::renameFile(const sp<AaptFile>& file, const String8& newName)
{
	sp<AaptGroup> origGroup;

	// Find and remove the given file with shear, brute force!
	const size_t NG = mFiles.size();
	size_t i;
	for (i=0; origGroup == NULL && i<NG; i++) {
		sp<AaptGroup> g = mFiles.valueAt(i);
		const size_t NF = g->getFiles().size();
		for (size_t j=0; j<NF; j++) {
			if (g->getFiles().valueAt(j) == file) {
				origGroup = g;
				g->removeFile(j);
				if (NF == 1) {
					mFiles.removeItemsAt(i);
				}
				break;
			}
		}
	}

	//printf("Renaming %s to %s\n", file->getPath().getPathName(), newName.string());

	// Place the file under its new name.
	if (origGroup != NULL) {
		return addLeafFile(newName, file);
	}

	return NO_ERROR;
}

status_t AaptDir::addLeafFile(const String8& leafName, const sp<AaptFile>& file)
{
    sp<AaptGroup> group;
    if (mFiles.indexOfKey(leafName) >= 0) {
        group = mFiles.valueFor(leafName);
    } else {
        group = new AaptGroup(leafName, mPath.appendPathCopy(leafName));
        mFiles.add(leafName, group);
    }

    return group->addFile(file);
}

ssize_t AaptDir::slurpFullTree(Bundle* bundle, const String8& srcDir,
                            const AaptGroupEntry& kind, const String8& resType,
                            sp<FilePathStore>& fullResPaths)
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
                                                resType, fullResPaths);
            if (res < NO_ERROR) {
                return res;
            }
            if (res > 0 && notAdded) {
                mDirs.add(fileNames[i], subdir);
            }
            count += res;
        } else if (type == kFileTypeRegular) {
            sp<AaptFile> file = new AaptFile(pathName, kind, resType);
            status_t err = addLeafFile(fileNames[i], file);
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

void AaptDir::print() const
{
    const size_t ND=getDirs().size();
    size_t i;
    for (i=0; i<ND; i++) {
        getDirs().valueAt(i)->print();
    }

    const size_t NF=getFiles().size();
    for (i=0; i<NF; i++) {
        getFiles().valueAt(i)->print();
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
    if (bundle->getAssetSourceDir()) {
        const char* assetDir = bundle->getAssetSourceDir();

        FileType type = getFileType(assetDir);
        if (type == kFileTypeNonexistent) {
            fprintf(stderr, "ERROR: asset directory '%s' does not exist\n", assetDir);
            return UNKNOWN_ERROR;
        }
        if (type != kFileTypeDirectory) {
            fprintf(stderr, "ERROR: '%s' is not a directory\n", assetDir);
            return UNKNOWN_ERROR;
        }

        String8 assetRoot(assetDir);
        sp<AaptDir> assetAaptDir = makeDir(String8(kAssetDir));
        AaptGroupEntry group;
        count = assetAaptDir->slurpFullTree(bundle, assetRoot, group,
                                            String8(), mFullAssetPaths);
        if (count < 0) {
            totalCount = count;
            goto bail;
        }
        if (count > 0) {
            mGroupEntries.add(group);
        }
        totalCount += count;

        if (bundle->getVerbose())
            printf("Found %d custom asset file%s in %s\n",
                   count, (count==1) ? "" : "s", assetDir);
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

        if (bundle->getMaxResVersion() != NULL && group.version.length() != 0) {
            int maxResInt = atoi(bundle->getMaxResVersion());
            const char *verString = group.version.string();
            int dirVersionInt = atoi(verString + 1); // skip 'v' in version name
            if (dirVersionInt > maxResInt) {
              fprintf(stderr, "max res %d, skipping %s\n", maxResInt, entry->d_name);
              continue;
            }
        }

        FileType type = getFileType(subdirName.string());

        if (type == kFileTypeDirectory) {
            sp<AaptDir> dir = makeDir(String8(entry->d_name));
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

            mDirs.add(dir);
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

sp<AaptSymbols> AaptAssets::getSymbolsFor(const String8& name)
{
    sp<AaptSymbols> sym = mSymbols.valueFor(name);
    if (sym == NULL) {
        sym = new AaptSymbols();
        mSymbols.add(name, sym);
    }
    return sym;
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
    return const_cast<ResTable&>(res).add(file->getData(), file->getSize(), NULL);
}

const ResTable& AaptAssets::getIncludedResources() const
{
    return mIncludedAssets.getResources(false);
}

void AaptAssets::print() const
{
    printf("Locale/Vendor pairs:\n");
    const size_t N=mGroupEntries.size();
    for (size_t i=0; i<N; i++) {
        printf("   %s/%s\n",
               mGroupEntries.itemAt(i).locale.string(),
               mGroupEntries.itemAt(i).vendor.string());
    }

    printf("\nFiles:\n");
    AaptDir::print();
}

sp<AaptDir> AaptAssets::resDir(const String8& name)
{
    const Vector<sp<AaptDir> >& dirs = mDirs;
    const size_t N = dirs.size();
    for (size_t i=0; i<N; i++) {
        const sp<AaptDir>& d = dirs.itemAt(i);
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
