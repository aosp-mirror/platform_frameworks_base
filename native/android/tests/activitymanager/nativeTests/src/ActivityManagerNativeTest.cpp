/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "ActivityManagerNativeTest"

#include <android-base/logging.h>
#include <android/activity_manager.h>
#include <binder/PermissionController.h>
#include <binder/ProcessState.h>
#include <gtest/gtest.h>

constexpr const char* kTestPackage = "com.android.tests.UidImportanceHelper";
constexpr const char* kTestActivity = "com.android.tests.UidImportanceHelper.MainActivity";
constexpr int64_t kEventTimeoutUs = 500000;

//-----------------------------------------------------------------
class ActivityManagerNativeTest : public ::testing::Test {
protected:
    ActivityManagerNativeTest() : mUidObserver(nullptr), mTestAppUid(-1), mLastUidImportance(-1) {}

    virtual ~ActivityManagerNativeTest() {}

    /* Test setup*/
    virtual void SetUp() { android::ProcessState::self()->startThreadPool(); }

    /* Test tear down */
    virtual void TearDown() {}

    bool waitForImportance(int32_t val, int64_t timeoutUs) {
        std::unique_lock lock(mLock);

        if (mLastUidImportance != val && timeoutUs > 0) {
            mCondition.wait_for(lock, std::chrono::microseconds(timeoutUs));
        }

        return mLastUidImportance == val;
    }

    void onUidImportanceChanged(uid_t uid, int32_t uidImportance) {
        LOG(ERROR) << "OnUidImportance: uid " << uid << ", importance " << uidImportance;
        std::unique_lock lock(mLock);

        if (uid == mTestAppUid) {
            mLastUidImportance = uidImportance;
            mCondition.notify_one();
        }
    }

    static void OnUidImportance(uid_t uid, int32_t uidImportance, void* cookie) {
        ActivityManagerNativeTest* owner = reinterpret_cast<ActivityManagerNativeTest*>(cookie);
        owner->onUidImportanceChanged(uid, uidImportance);
    }

    AActivityManager_UidImportanceListener* mUidObserver;
    uid_t mTestAppUid;
    std::mutex mLock;
    std::condition_variable mCondition;
    int32_t mLastUidImportance;
};

static bool getUidForPackage(const char* packageName, /*inout*/ uid_t& uid) {
    android::PermissionController pc;
    uid = pc.getPackageUid(android::String16(packageName), 0);
    if (uid <= 0) {
        ALOGE("Unknown package: '%s'", packageName);
        return false;
    }
    return true;
}

struct ShellHelper {
    static bool RunCmd(const std::string& cmdStr) {
        int ret = system(cmdStr.c_str());
        if (ret != 0) {
            LOG(ERROR) << "Failed to run cmd: " << cmdStr << ", exitcode " << ret;
            return false;
        }
        return true;
    }

    static bool Start(const char* packageName, const char* activityName) {
        return RunCmd("am start -W " + std::string(packageName) + "/" + std::string(activityName) +
                      " &> /dev/null");
    }

    static bool Stop(const char* packageName) {
        return RunCmd("am force-stop " + std::string(packageName));
    }
};

//-------------------------------------------------------------------------------------------------
TEST_F(ActivityManagerNativeTest, testUidImportance) {
    pid_t selfPid = ::getpid();
    uid_t selfUid = ::getuid();

    uid_t testAppUid;
    EXPECT_TRUE(getUidForPackage(kTestPackage, testAppUid));
    LOG(INFO) << "testUidImportance: uidselfUid" << selfUid << ", selfPid " << selfPid
              << ", testAppUid " << testAppUid;
    mTestAppUid = testAppUid;

    // Expect the initial UidImportance to be GONE.
    EXPECT_FALSE(AActivityManager_isUidActive(testAppUid));
    EXPECT_EQ(AActivityManager_getUidImportance(testAppUid), AACTIVITYMANAGER_IMPORTANCE_GONE);

    mUidObserver = AActivityManager_addUidImportanceListener(&OnUidImportance,
                                                             AACTIVITYMANAGER_IMPORTANCE_FOREGROUND,
                                                             (void*)this);
    EXPECT_TRUE(mUidObserver != nullptr);

    // Start the test activity, and expect to receive UidImportance change to FOREGROUND.
    EXPECT_TRUE(ShellHelper::Start(kTestPackage, kTestActivity));
    EXPECT_TRUE(waitForImportance(AACTIVITYMANAGER_IMPORTANCE_FOREGROUND, kEventTimeoutUs));
    EXPECT_TRUE(AActivityManager_isUidActive(testAppUid));
    EXPECT_EQ(AActivityManager_getUidImportance(testAppUid),
              AACTIVITYMANAGER_IMPORTANCE_FOREGROUND);

    // Stop the test activity, and expect to receive UidImportance change to GONE.
    EXPECT_TRUE(ShellHelper::Stop(kTestPackage));
    EXPECT_TRUE(waitForImportance(AACTIVITYMANAGER_IMPORTANCE_GONE, kEventTimeoutUs));
    EXPECT_FALSE(AActivityManager_isUidActive(testAppUid));
    EXPECT_EQ(AActivityManager_getUidImportance(testAppUid), AACTIVITYMANAGER_IMPORTANCE_GONE);

    AActivityManager_removeUidImportanceListener(mUidObserver);
    mUidObserver = nullptr;
}
