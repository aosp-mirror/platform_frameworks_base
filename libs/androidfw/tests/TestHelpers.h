#ifndef __TEST_HELPERS_H
#define __TEST_HELPERS_H

#include <ostream>

#include <androidfw/ResourceTypes.h>
#include <utils/String8.h>
#include <utils/String16.h>
#include <gtest/gtest.h>

static inline ::std::ostream& operator<<(::std::ostream& out, const android::String8& str) {
    return out << str.string();
}

static inline ::std::ostream& operator<<(::std::ostream& out, const android::String16& str) {
    return out << android::String8(str).string();
}

namespace android {

enum { MAY_NOT_BE_BAG = false };

static inline bool operator==(const android::ResTable_config& a, const android::ResTable_config& b) {
    return a.compare(b) == 0;
}

static inline ::std::ostream& operator<<(::std::ostream& out, const android::ResTable_config& c) {
    return out << c.toString().string();
}

::testing::AssertionResult IsStringEqual(const ResTable& table, uint32_t resourceId, const char* expectedStr);

} // namespace android

#endif // __TEST_HELPERS_H
