#ifndef __TEST_HELPERS_H
#define __TEST_HELPERS_H

#include <ostream>

#include <utils/String8.h>
#include <utils/String16.h>

static inline ::std::ostream& operator<<(::std::ostream& out, const android::String8& str) {
    return out << str.string();
}

static inline ::std::ostream& operator<<(::std::ostream& out, const android::String16& str) {
    return out << android::String8(str).string();
}

#endif // __TEST_HELPERS_H
