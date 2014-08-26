#ifndef __TEST_HELPERS_H
#define __TEST_HELPERS_H

#include <ostream>

#include <androidfw/ResourceTypes.h>
#include <utils/String8.h>
#include <utils/String16.h>

static inline ::std::ostream& operator<<(::std::ostream& out, const android::String8& str) {
    return out << str.string();
}

static inline ::std::ostream& operator<<(::std::ostream& out, const android::String16& str) {
    return out << android::String8(str).string();
}

namespace android {

static inline bool operator==(const android::ResTable_config& a, const android::ResTable_config& b) {
    return memcmp(&a, &b, sizeof(a)) == 0;
}

static inline ::std::ostream& operator<<(::std::ostream& out, const android::ResTable_config& c) {
    return out << c.toString().string();
}

} // namespace android

#endif // __TEST_HELPERS_H
