//
// Copyright 2010 The Android Open Source Project
//

#include <ui/InputDispatcher.h>
#include <gtest/gtest.h>

namespace android {

class InputDispatcherTest : public testing::Test {
public:
};

TEST_F(InputDispatcherTest, Dummy) {
    SCOPED_TRACE("Trace");
    ASSERT_FALSE(true);
}

} // namespace android
