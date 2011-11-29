/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <stdlib.h>
#include <string.h>

#define LOG_TAG "utils_test"
#include <utils/Log.h>

#include <gtest/gtest.h>

extern "C" {
#include "installd.h"
}

#define TEST_DATA_DIR "/data/"
#define TEST_APP_DIR "/data/app/"
#define TEST_APP_PRIVATE_DIR "/data/app-private/"
#define TEST_ASEC_DIR "/mnt/asec/"

#define TEST_SYSTEM_DIR1 "/system/app/"
#define TEST_SYSTEM_DIR2 "/vendor/app/"

#define REALLY_LONG_APP_NAME "com.example." \
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa." \
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa." \
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

#define REALLY_LONG_LEAF_NAME "shared_prefs_shared_prefs_shared_prefs_shared_prefs_shared_prefs_" \
        "shared_prefs_shared_prefs_shared_prefs_shared_prefs_shared_prefs_shared_prefs_" \
        "shared_prefs_shared_prefs_shared_prefs_shared_prefs_shared_prefs_shared_prefs_" \
        "shared_prefs_shared_prefs_shared_prefs_shared_prefs_shared_prefs_shared_prefs_"

namespace android {

class UtilsTest : public testing::Test {
protected:
    virtual void SetUp() {
        android_app_dir.path = TEST_APP_DIR;
        android_app_dir.len = strlen(TEST_APP_DIR);

        android_app_private_dir.path = TEST_APP_PRIVATE_DIR;
        android_app_private_dir.len = strlen(TEST_APP_PRIVATE_DIR);

        android_data_dir.path = TEST_DATA_DIR;
        android_data_dir.len = strlen(TEST_DATA_DIR);

        android_asec_dir.path = TEST_ASEC_DIR;
        android_asec_dir.len = strlen(TEST_ASEC_DIR);

        android_system_dirs.count = 2;

        android_system_dirs.dirs = (dir_rec_t*) calloc(android_system_dirs.count, sizeof(dir_rec_t));
        android_system_dirs.dirs[0].path = TEST_SYSTEM_DIR1;
        android_system_dirs.dirs[0].len = strlen(TEST_SYSTEM_DIR1);

        android_system_dirs.dirs[1].path = TEST_SYSTEM_DIR2;
        android_system_dirs.dirs[1].len = strlen(TEST_SYSTEM_DIR2);
    }

    virtual void TearDown() {
        free(android_system_dirs.dirs);
    }
};

TEST_F(UtilsTest, IsValidApkPath_BadPrefix) {
    // Bad prefixes directories
    const char *badprefix1 = "/etc/passwd";
    EXPECT_EQ(-1, validate_apk_path(badprefix1))
            << badprefix1 << " should be allowed as a valid path";

    const char *badprefix2 = "../.." TEST_APP_DIR "../../../blah";
    EXPECT_EQ(-1, validate_apk_path(badprefix2))
            << badprefix2 << " should be allowed as a valid path";

    const char *badprefix3 = "init.rc";
    EXPECT_EQ(-1, validate_apk_path(badprefix3))
            << badprefix3 << " should be allowed as a valid path";

    const char *badprefix4 = "/init.rc";
    EXPECT_EQ(-1, validate_apk_path(badprefix4))
            << badprefix4 << " should be allowed as a valid path";
}

TEST_F(UtilsTest, IsValidApkPath_Internal) {
    // Internal directories
    const char *internal1 = TEST_APP_DIR "example.apk";
    EXPECT_EQ(0, validate_apk_path(internal1))
            << internal1 << " should be allowed as a valid path";

    const char *badint1 = TEST_APP_DIR "../example.apk";
    EXPECT_EQ(-1, validate_apk_path(badint1))
            << badint1 << " should be rejected as a invalid path";

    const char *badint2 = TEST_APP_DIR "/../example.apk";
    EXPECT_EQ(-1, validate_apk_path(badint2))
            << badint2 << " should be rejected as a invalid path";

    const char *badint3 = TEST_APP_DIR "example.com/pkg.apk";
    EXPECT_EQ(-1, validate_apk_path(badint3))
            << badint3 << " should be rejected as a invalid path";
}

TEST_F(UtilsTest, IsValidApkPath_Private) {
    // Internal directories
    const char *private1 = TEST_APP_PRIVATE_DIR "example.apk";
    EXPECT_EQ(0, validate_apk_path(private1))
            << private1 << " should be allowed as a valid path";

    const char *badpriv1 = TEST_APP_PRIVATE_DIR "../example.apk";
    EXPECT_EQ(-1, validate_apk_path(badpriv1))
            << badpriv1 << " should be rejected as a invalid path";

    const char *badpriv2 = TEST_APP_PRIVATE_DIR "/../example.apk";
    EXPECT_EQ(-1, validate_apk_path(badpriv2))
            << badpriv2 << " should be rejected as a invalid path";

    const char *badpriv3 = TEST_APP_PRIVATE_DIR "example.com/pkg.apk";
    EXPECT_EQ(-1, validate_apk_path(badpriv3))
            << badpriv3 << " should be rejected as a invalid path";
}


TEST_F(UtilsTest, IsValidApkPath_AsecGood1) {
    const char *asec1 = TEST_ASEC_DIR "example.apk";
    EXPECT_EQ(0, validate_apk_path(asec1))
            << asec1 << " should be allowed as a valid path";
}

TEST_F(UtilsTest, IsValidApkPath_AsecGood2) {
    const char *asec2 = TEST_ASEC_DIR "com.example.asec/pkg.apk";
    EXPECT_EQ(0, validate_apk_path(asec2))
            << asec2 << " should be allowed as a valid path";
}

TEST_F(UtilsTest, IsValidApkPath_EscapeFail) {
    const char *badasec1 = TEST_ASEC_DIR "../example.apk";
    EXPECT_EQ(-1, validate_apk_path(badasec1))
            << badasec1 << " should be rejected as a invalid path";
}

TEST_F(UtilsTest, IsValidApkPath_DoubleSlashFail) {
    const char *badasec2 = TEST_ASEC_DIR "com.example.asec//pkg.apk";
    EXPECT_EQ(-1, validate_apk_path(badasec2))
            << badasec2 << " should be rejected as a invalid path";
}

TEST_F(UtilsTest, IsValidApkPath_SubdirEscapeFail) {
    const char *badasec3 = TEST_ASEC_DIR "com.example.asec/../../../pkg.apk";
    EXPECT_EQ(-1, validate_apk_path(badasec3))
            << badasec3  << " should be rejected as a invalid path";
}

TEST_F(UtilsTest, IsValidApkPath_SlashEscapeFail) {
    const char *badasec4 = TEST_ASEC_DIR "/../example.apk";
    EXPECT_EQ(-1, validate_apk_path(badasec4))
            << badasec4 << " should be rejected as a invalid path";
}

TEST_F(UtilsTest, IsValidApkPath_CrazyDirFail) {
    const char *badasec5 = TEST_ASEC_DIR ".//../..";
    EXPECT_EQ(-1, validate_apk_path(badasec5))
            << badasec5 << " should be rejected as a invalid path";
}

TEST_F(UtilsTest, IsValidApkPath_SubdirEscapeSingleFail) {
    const char *badasec6 = TEST_ASEC_DIR "com.example.asec/../pkg.apk";
    EXPECT_EQ(-1, validate_apk_path(badasec6))
            << badasec6 << " should be rejected as a invalid path";
}

TEST_F(UtilsTest, IsValidApkPath_TwoSubdirFail) {
    const char *badasec7 = TEST_ASEC_DIR "com.example.asec/subdir1/pkg.apk";
    EXPECT_EQ(-1, validate_apk_path(badasec7))
            << badasec7 << " should be rejected as a invalid path";
}

TEST_F(UtilsTest, CheckSystemApp_Dir1) {
    const char *sysapp1 = TEST_SYSTEM_DIR1 "Voice.apk";
    EXPECT_EQ(0, validate_system_app_path(sysapp1))
            << sysapp1 << " should be allowed as a system path";
}

TEST_F(UtilsTest, CheckSystemApp_Dir2) {
    const char *sysapp2 = TEST_SYSTEM_DIR2 "com.example.myapp.apk";
    EXPECT_EQ(0, validate_system_app_path(sysapp2))
            << sysapp2 << " should be allowed as a system path";
}

TEST_F(UtilsTest, CheckSystemApp_EscapeFail) {
    const char *badapp1 = TEST_SYSTEM_DIR1 "../com.example.apk";
    EXPECT_EQ(-1, validate_system_app_path(badapp1))
            << badapp1 << " should be rejected not a system path";
}

TEST_F(UtilsTest, CheckSystemApp_DoubleEscapeFail) {
    const char *badapp2 = TEST_SYSTEM_DIR2 "/../../com.example.apk";
    EXPECT_EQ(-1, validate_system_app_path(badapp2))
            << badapp2 << " should be rejected not a system path";
}

TEST_F(UtilsTest, CheckSystemApp_BadPathEscapeFail) {
    const char *badapp3 = TEST_APP_DIR "/../../com.example.apk";
    EXPECT_EQ(-1, validate_system_app_path(badapp3))
            << badapp3 << " should be rejected not a system path";
}

TEST_F(UtilsTest, GetPathFromString_NullPathFail) {
    dir_rec_t test1;
    EXPECT_EQ(-1, get_path_from_string(&test1, (const char *) NULL))
            << "Should not allow NULL as a path.";
}

TEST_F(UtilsTest, GetPathFromString_EmptyPathFail) {
    dir_rec_t test1;
    EXPECT_EQ(-1, get_path_from_string(&test1, ""))
            << "Should not allow empty paths.";
}

TEST_F(UtilsTest, GetPathFromString_RelativePathFail) {
    dir_rec_t test1;
    EXPECT_EQ(-1, get_path_from_string(&test1, "mnt/asec"))
            << "Should not allow relative paths.";
}

TEST_F(UtilsTest, GetPathFromString_NonCanonical) {
    dir_rec_t test1;

    EXPECT_EQ(0, get_path_from_string(&test1, "/mnt/asec"))
            << "Should be able to canonicalize directory /mnt/asec";
    EXPECT_STREQ("/mnt/asec/", test1.path)
            << "/mnt/asec should be canonicalized to /mnt/asec/";
    EXPECT_EQ(10, (ssize_t) test1.len)
            << "path len should be equal to the length of /mnt/asec/ (10)";
    free(test1.path);
}

TEST_F(UtilsTest, GetPathFromString_CanonicalPath) {
    dir_rec_t test3;
    EXPECT_EQ(0, get_path_from_string(&test3, "/data/app/"))
            << "Should be able to canonicalize directory /data/app/";
    EXPECT_STREQ("/data/app/", test3.path)
            << "/data/app/ should be canonicalized to /data/app/";
    EXPECT_EQ(10, (ssize_t) test3.len)
            << "path len should be equal to the length of /data/app/ (10)";
    free(test3.path);
}

TEST_F(UtilsTest, CreatePkgPath_LongPkgNameSuccess) {
    char path[PKG_PATH_MAX];

    // Create long packagename of "aaaaa..."
    size_t pkgnameSize = PKG_NAME_MAX;
    char pkgname[pkgnameSize + 1];
    memset(pkgname, 'a', pkgnameSize);
    pkgname[pkgnameSize] = '\0';

    EXPECT_EQ(0, create_pkg_path(path, pkgname, "", 0))
            << "Should successfully be able to create package name.";

    const char *prefix = TEST_DATA_DIR PRIMARY_USER_PREFIX;
    size_t offset = strlen(prefix);
    EXPECT_STREQ(pkgname, path + offset)
             << "Package path should be a really long string of a's";
}

TEST_F(UtilsTest, CreatePkgPath_LongPkgNameFail) {
    char path[PKG_PATH_MAX];

    // Create long packagename of "aaaaa..."
    size_t pkgnameSize = PKG_NAME_MAX + 1;
    char pkgname[pkgnameSize + 1];
    memset(pkgname, 'a', pkgnameSize);
    pkgname[pkgnameSize] = '\0';

    EXPECT_EQ(-1, create_pkg_path(path, pkgname, "", 0))
            << "Should return error because package name is too long.";
}

TEST_F(UtilsTest, CreatePkgPath_LongPostfixFail) {
    char path[PKG_PATH_MAX];

    // Create long packagename of "aaaaa..."
    size_t postfixSize = PKG_PATH_MAX;
    char postfix[postfixSize + 1];
    memset(postfix, 'a', postfixSize);
    postfix[postfixSize] = '\0';

    EXPECT_EQ(-1, create_pkg_path(path, "com.example.package", postfix, 0))
            << "Should return error because postfix is too long.";
}

TEST_F(UtilsTest, CreatePkgPath_PrimaryUser) {
    char path[PKG_PATH_MAX];

    EXPECT_EQ(0, create_pkg_path(path, "com.example.package", "", 0))
            << "Should return error because postfix is too long.";

    EXPECT_STREQ(TEST_DATA_DIR PRIMARY_USER_PREFIX "com.example.package", path)
            << "Package path should be in /data/data/";
}

TEST_F(UtilsTest, CreatePkgPath_SecondaryUser) {
    char path[PKG_PATH_MAX];

    EXPECT_EQ(0, create_pkg_path(path, "com.example.package", "", 1))
            << "Should successfully create package path.";

    EXPECT_STREQ(TEST_DATA_DIR SECONDARY_USER_PREFIX "1/com.example.package", path)
            << "Package path should be in /data/user/";
}

TEST_F(UtilsTest, CreatePkgPathInDir_ProtectedDir) {
    char path[PKG_PATH_MAX];

    dir_rec_t dir;
    dir.path = "/data/app-private/";
    dir.len = strlen(dir.path);

    EXPECT_EQ(0, create_pkg_path_in_dir(path, &dir, "com.example.package", ".apk"))
            << "Should successfully create package path.";

    EXPECT_STREQ("/data/app-private/com.example.package.apk", path)
            << "Package path should be in /data/app-private/";
}

TEST_F(UtilsTest, CreatePersonaPath_Primary) {
    char path[PKG_PATH_MAX];

    EXPECT_EQ(0, create_persona_path(path, 0))
            << "Should successfully build primary user path.";

    EXPECT_STREQ("/data/data/", path)
            << "Primary user should have correct path";
}

TEST_F(UtilsTest, CreatePersonaPath_Secondary) {
    char path[PKG_PATH_MAX];

    EXPECT_EQ(0, create_persona_path(path, 1))
            << "Should successfully build primary user path.";

    EXPECT_STREQ("/data/user/1/", path)
            << "Primary user should have correct path";
}

TEST_F(UtilsTest, CreateMovePath_Primary) {
    char path[PKG_PATH_MAX];

    EXPECT_EQ(0, create_move_path(path, "com.android.test", "shared_prefs", 0))
            << "Should be able to create move path for primary user";

    EXPECT_STREQ("/data/data/com.android.test/shared_prefs", path)
            << "Primary user package directory should be created correctly";
}

TEST_F(UtilsTest, CreateMovePath_Fail_AppTooLong) {
    char path[PKG_PATH_MAX];

    EXPECT_EQ(-1, create_move_path(path, REALLY_LONG_APP_NAME, "shared_prefs", 0))
            << "Should fail to create move path for primary user";
}

TEST_F(UtilsTest, CreateMovePath_Fail_LeafTooLong) {
    char path[PKG_PATH_MAX];

    EXPECT_EQ(-1, create_move_path(path, "com.android.test", REALLY_LONG_LEAF_NAME, 0))
            << "Should fail to create move path for primary user";
}

TEST_F(UtilsTest, CopyAndAppend_Normal) {
    //int copy_and_append(dir_rec_t* dst, dir_rec_t* src, char* suffix)
    dir_rec_t dst;
    dir_rec_t src;

    src.path = "/data/";
    src.len = strlen(src.path);

    EXPECT_EQ(0, copy_and_append(&dst, &src, "app/"))
            << "Should return error because postfix is too long.";

    EXPECT_STREQ("/data/app/", dst.path)
            << "Appended path should be correct";

    EXPECT_EQ(10, (ssize_t) dst.len)
            << "Appended path should be length of '/data/app/' (10)";
}

TEST_F(UtilsTest, AppendAndIncrement_Normal) {
    size_t dst_size = 10;
    char dst[dst_size];
    char *dstp = dst;
    const char* src = "FOO";

    EXPECT_EQ(0, append_and_increment(&dstp, src, &dst_size))
            << "String should append successfully";

    EXPECT_STREQ("FOO", dst)
            << "String should append correctly";

    EXPECT_EQ(0, append_and_increment(&dstp, src, &dst_size))
            << "String should append successfully again";

    EXPECT_STREQ("FOOFOO", dst)
            << "String should append correctly again";
}

TEST_F(UtilsTest, AppendAndIncrement_TooBig) {
    size_t dst_size = 5;
    char dst[dst_size];
    char *dstp = dst;
    const char* src = "FOO";

    EXPECT_EQ(0, append_and_increment(&dstp, src, &dst_size))
            << "String should append successfully";

    EXPECT_STREQ("FOO", dst)
            << "String should append correctly";

    EXPECT_EQ(-1, append_and_increment(&dstp, src, &dst_size))
            << "String should fail because it's too large to fit";
}

}
