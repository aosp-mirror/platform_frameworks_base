#define LOG_TAG "KeyCharacterMap"

#include <ui/KeyCharacterMap.h>
#include <cutils/properties.h>

#include <utils/Log.h>
#include <sys/types.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <limits.h>
#include <string.h>

struct Header
{
    char magic[8];
    unsigned int endian;
    unsigned int version;
    unsigned int keycount;
    unsigned char kbdtype;
    char padding[11];
};

KeyCharacterMap::KeyCharacterMap()
{
}

KeyCharacterMap::~KeyCharacterMap()
{
    free(m_keys);
}

unsigned short
KeyCharacterMap::get(int keycode, int meta)
{
    Key* k = find_key(keycode);
    if (k != NULL) {
        return k->data[meta & META_MASK];
    }
    return 0;
}

unsigned short
KeyCharacterMap::getNumber(int keycode)
{
    Key* k = find_key(keycode);
    if (k != NULL) {
        return k->number;
    }
    return 0;
}

unsigned short
KeyCharacterMap::getMatch(int keycode, const unsigned short* chars,
                          int charsize, uint32_t modifiers)
{
    Key* k = find_key(keycode);
    modifiers &= 3; // ignore the SYM key because we don't have keymap entries for it
    if (k != NULL) {
        const uint16_t* data = k->data;
        for (int j=0; j<charsize; j++) {
            uint16_t c = chars[j];
            for (int i=0; i<(META_MASK + 1); i++) {
                if ((modifiers == 0) || ((modifiers & i) != 0)) {
                    if (c == data[i]) {
                        return c;
                    }
                }
            }
        }
    }
    return 0;
}

unsigned short
KeyCharacterMap::getDisplayLabel(int keycode)
{
    Key* k = find_key(keycode);
    if (k != NULL) {
        return k->display_label;
    }
    return 0;
}

bool
KeyCharacterMap::getKeyData(int keycode, unsigned short *displayLabel,
                            unsigned short *number, unsigned short* results)
{
    Key* k = find_key(keycode);
    if (k != NULL) {
        memcpy(results, k->data, sizeof(short)*(META_MASK + 1));
        *number = k->number;
        *displayLabel = k->display_label;
        return true;
    } else {
        return false;
    }
}

bool
KeyCharacterMap::find_char(uint16_t c, uint32_t* key, uint32_t* mods)
{
    uint32_t N = m_keyCount;
    for (int j=0; j<(META_MASK + 1); j++) {
        Key const* keys = m_keys;
        for (uint32_t i=0; i<N; i++) {
            if (keys->data[j] == c) {
                *key = keys->keycode;
                *mods = j;
                return true;
            }
            keys++;
        }
    }
    return false;
}

bool
KeyCharacterMap::getEvents(uint16_t* chars, size_t len,
                           Vector<int32_t>* keys, Vector<uint32_t>* modifiers)
{
    for (size_t i=0; i<len; i++) {
        uint32_t k, mods;
        if (find_char(chars[i], &k, &mods)) {
            keys->add(k);
            modifiers->add(mods);
        } else {
            return false;
        }
    }
    return true;
}

KeyCharacterMap::Key*
KeyCharacterMap::find_key(int keycode)
{
    Key* keys = m_keys;
    int low = 0;
    int high = m_keyCount - 1;
    int mid;
    int n;
    while (low <= high) {
        mid = (low + high) / 2;
        n = keys[mid].keycode;
        if (keycode < n) {
            high = mid - 1;
        } else if (keycode > n) {
            low = mid + 1;
        } else {
            return keys + mid;
        }
    }
    return NULL;
}

KeyCharacterMap*
KeyCharacterMap::load(int id)
{
    KeyCharacterMap* rv = NULL;
    char path[PATH_MAX];
    char propName[100];
    char dev[PROPERTY_VALUE_MAX];
    char tmpfn[PROPERTY_VALUE_MAX];
    int err;
    const char* root = getenv("ANDROID_ROOT");

    sprintf(propName, "hw.keyboards.%u.devname", id);
    err = property_get(propName, dev, "");
    if (err > 0) {
        // replace all the spaces with underscores
        strcpy(tmpfn, dev);
        for (char *p = strchr(tmpfn, ' '); p && *p; p = strchr(tmpfn, ' '))
            *p = '_';
        snprintf(path, sizeof(path), "%s/usr/keychars/%s.kcm.bin", root, tmpfn);
        //LOGD("load: dev='%s' path='%s'\n", dev, path);
        rv = try_file(path);
        if (rv != NULL) {
            return rv;
        }
        LOGW("Error loading keycharmap file '%s'. %s='%s'", path, propName, dev);
    } else {
        LOGW("No keyboard for id %d", id);
    }

    snprintf(path, sizeof(path), "%s/usr/keychars/qwerty.kcm.bin", root);
    rv = try_file(path);
    if (rv == NULL) {
        LOGE("Can't find any keycharmaps (also tried %s)", path);
        return NULL;
    }
    LOGW("Using default keymap: %s", path);

    return rv;
}

KeyCharacterMap*
KeyCharacterMap::try_file(const char* filename)
{
    KeyCharacterMap* rv = NULL;
    Key* keys;
    int fd;
    off_t filesize;
    Header header;
    int err;
    
    fd = open(filename, O_RDONLY);
    if (fd == -1) {
        LOGW("Can't open keycharmap file");
        return NULL;
    }

    filesize = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);

    // validate the header
    if (filesize <= (off_t)sizeof(header)) {
        LOGW("Bad keycharmap - filesize=%d\n", (int)filesize);
        goto cleanup1;
    }

    err = read(fd, &header, sizeof(header));
    if (err == -1) {
        LOGW("Error reading keycharmap file");
        goto cleanup1;
    }

    if (0 != memcmp(header.magic, "keychar", 8)) {
        LOGW("Bad keycharmap magic token");
        goto cleanup1;
    }
    if (header.endian != 0x12345678) {
        LOGW("Bad keycharmap endians");
        goto cleanup1;
    }
    if ((header.version & 0xff) != 2) {
        LOGW("Only support keycharmap version 2 (got 0x%08x)", header.version);
        goto cleanup1;
    }
    if (filesize < (off_t)(sizeof(Header)+(sizeof(Key)*header.keycount))) {
        LOGW("Bad keycharmap file size\n");
        goto cleanup1;
    }

    // read the key data
    keys = (Key*)malloc(sizeof(Key)*header.keycount);
    err = read(fd, keys, sizeof(Key)*header.keycount);
    if (err == -1) {
        LOGW("Error reading keycharmap file");
        free(keys);
        goto cleanup1;
    }

    // return the object
    rv = new KeyCharacterMap;
    rv->m_keyCount = header.keycount;
    rv->m_keys = keys;
    rv->m_type = header.kbdtype;

cleanup1:
    close(fd);

    return rv;
}
