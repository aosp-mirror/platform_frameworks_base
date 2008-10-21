#ifndef KEYLAYOUTMAP_H
#define KEYLAYOUTMAP_H

#include <utils/KeyedVector.h>

namespace android {

class KeyLayoutMap
{
public:
    KeyLayoutMap();
    ~KeyLayoutMap();

    status_t load(const char* filename);

    status_t map(int32_t scancode, int32_t *keycode, uint32_t *flags) const;
    status_t findScancodes(int32_t keycode, Vector<int32_t>* outScancodes) const;

private:
    struct Key {
        int32_t keycode;
        uint32_t flags;
    };

    status_t m_status;
    KeyedVector<int32_t,Key> m_keys;
};

};

#endif // KEYLAYOUTMAP_H
