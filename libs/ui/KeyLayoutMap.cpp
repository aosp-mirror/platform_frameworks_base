#define LOG_TAG "KeyLayoutMap"

#include "KeyLayoutMap.h"
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <utils/String8.h>
#include <stdlib.h>
#include <ui/KeycodeLabels.h>
#include <utils/Log.h>

namespace android {

KeyLayoutMap::KeyLayoutMap()
    :m_status(NO_INIT),
     m_keys()
{
}

KeyLayoutMap::~KeyLayoutMap()
{
}

static String8
next_token(char const** p, int *line)
{
    bool begun = false;
    const char* begin = *p;
    const char* end = *p;
    while (true) {
        if (*end == '\n') {
            (*line)++;
        }
        switch (*end)
        {
            case '#':
                if (begun) {
                    *p = end;
                    return String8(begin, end-begin);
                } else {
                    do {
                        begin++;
                        end++;
                    } while (*begin != '\0' && *begin != '\n');
                }
            case '\0':
            case ' ':
            case '\n':
            case '\r':
            case '\t':
                if (begun || (*end == '\0')) {
                    *p = end;
                    return String8(begin, end-begin);
                } else {
                    begin++;
                    end++;
                    break;
                }
            default:
                end++;
                begun = true;
        }
    }
}

static int32_t
token_to_value(const char *literal, const KeycodeLabel *list)
{
    while (list->literal) {
        if (0 == strcmp(literal, list->literal)) {
            return list->value;
        }
        list++;
    }
    return list->value;
}

status_t
KeyLayoutMap::load(const char* filename)
{
    int fd = open(filename, O_RDONLY);
    if (fd < 0) {
        LOGE("error opening file=%s err=%s\n", filename, strerror(errno));
        m_status = errno;
        return errno;
    }

    off_t len = lseek(fd, 0, SEEK_END);
    off_t errlen = lseek(fd, 0, SEEK_SET);
    if (len < 0 || errlen < 0) {
        close(fd);
        LOGE("error seeking file=%s err=%s\n", filename, strerror(errno));
        m_status = errno;
        return errno;
    }

    char* buf = (char*)malloc(len+1);
    if (read(fd, buf, len) != len) {
        LOGE("error reading file=%s err=%s\n", filename, strerror(errno));
        m_status = errno != 0 ? errno : ((int)NOT_ENOUGH_DATA);
        return errno != 0 ? errno : ((int)NOT_ENOUGH_DATA);
    }
    errno = 0;
    buf[len] = '\0';

    int32_t scancode = -1;
    int32_t keycode = -1;
    uint32_t flags = 0;
    uint32_t tmp;
    char* end;
    status_t err = NO_ERROR;
    int line = 1;
    char const* p = buf;
    enum { BEGIN, SCANCODE, KEYCODE, FLAG } state = BEGIN;
    while (true) {
        String8 token = next_token(&p, &line);
        if (*p == '\0') {
            break;
        }
        switch (state)
        {
            case BEGIN:
                if (token == "key") {
                    state = SCANCODE;
                } else {
                    LOGE("%s:%d: expected key, got '%s'\n", filename, line,
                            token.string());
                    err = BAD_VALUE;
                    goto done;
                }
                break;
            case SCANCODE:
                scancode = strtol(token.string(), &end, 0);
                if (*end != '\0') {
                    LOGE("%s:%d: expected scancode (a number), got '%s'\n",
                            filename, line, token.string());
                    goto done;
                }
                //LOGI("%s:%d: got scancode %d\n", filename, line, scancode );
                state = KEYCODE;
                break;
            case KEYCODE:
                keycode = token_to_value(token.string(), KEYCODES);
                //LOGI("%s:%d: got keycode %d for %s\n", filename, line, keycode, token.string() );
                if (keycode == 0) {
                    LOGE("%s:%d: expected keycode, got '%s'\n",
                            filename, line, token.string());
                    goto done;
                }
                state = FLAG;
                break;
            case FLAG:
                if (token == "key") {
                    if (scancode != -1) {
                        //LOGI("got key decl scancode=%d keycode=%d"
                        //       " flags=0x%08x\n", scancode, keycode, flags);
                        Key k = { keycode, flags };
                        m_keys.add(scancode, k);
                        state = SCANCODE;
                        scancode = -1;
                        keycode = -1;
                        flags = 0;
                        break;
                    }
                }
                tmp = token_to_value(token.string(), FLAGS);
                //LOGI("%s:%d: got flags %x for %s\n", filename, line, tmp, token.string() );
                if (tmp == 0) {
                    LOGE("%s:%d: expected flag, got '%s'\n",
                            filename, line, token.string());
                    goto done;
                }
                flags |= tmp;
                break;
        }
    }
    if (state == FLAG && scancode != -1 ) {
        //LOGI("got key decl scancode=%d keycode=%d"
        //       " flags=0x%08x\n", scancode, keycode, flags);
        Key k = { keycode, flags };
        m_keys.add(scancode, k);
    }

done:
    free(buf);
    close(fd);

    m_status = err;
    return err;
}

status_t
KeyLayoutMap::map(int32_t scancode, int32_t *keycode, uint32_t *flags) const
{
    if (m_status != NO_ERROR) {
        return m_status;
    }

    ssize_t index = m_keys.indexOfKey(scancode);
    if (index < 0) {
        //LOGW("couldn't map scancode=%d\n", scancode);
        return NAME_NOT_FOUND;
    }

    const Key& k = m_keys.valueAt(index);

    *keycode = k.keycode;
    *flags = k.flags;

    //LOGD("mapped scancode=%d to keycode=%d flags=0x%08x\n", scancode,
    //        keycode, flags);

    return NO_ERROR;
}

status_t
KeyLayoutMap::findScancodes(int32_t keycode, Vector<int32_t>* outScancodes) const
{
    if (m_status != NO_ERROR) {
        return m_status;
    }
    
    const size_t N = m_keys.size();
    for (size_t i=0; i<N; i++) {
        if (m_keys.valueAt(i).keycode == keycode) {
            outScancodes->add(m_keys.keyAt(i));
        }
    }
    
    return NO_ERROR;
}

};
