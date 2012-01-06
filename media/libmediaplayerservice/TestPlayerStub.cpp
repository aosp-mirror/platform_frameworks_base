/*
 * Copyright (C) 2009 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "TestPlayerStub"
#include "utils/Log.h"

#include "TestPlayerStub.h"

#include <dlfcn.h>  // for dlopen/dlclose
#include <stdlib.h>
#include <string.h>
#include <cutils/properties.h>
#include <utils/Errors.h>  // for status_t

#include "media/MediaPlayerInterface.h"


namespace {
using android::status_t;
using android::MediaPlayerBase;

const char *kTestUrlScheme = "test:";
const char *kUrlParam = "url=";

const char *kBuildTypePropName = "ro.build.type";
const char *kEngBuild = "eng";
const char *kTestBuild = "test";

// @return true if the current build is 'eng' or 'test'.
bool isTestBuild()
{
    char prop[PROPERTY_VALUE_MAX] = { '\0', };

    property_get(kBuildTypePropName, prop, '\0');
    return strcmp(prop, kEngBuild) == 0 || strcmp(prop, kTestBuild) == 0;
}

// @return true if the url scheme is 'test:'
bool isTestUrl(const char *url)
{
    return url && strncmp(url, kTestUrlScheme, strlen(kTestUrlScheme)) == 0;
}

}  // anonymous namespace

namespace android {

TestPlayerStub::TestPlayerStub()
    :mUrl(NULL), mFilename(NULL), mContentUrl(NULL),
     mHandle(NULL), mNewPlayer(NULL), mDeletePlayer(NULL),
     mPlayer(NULL) { }

TestPlayerStub::~TestPlayerStub()
{
    resetInternal();
}

status_t TestPlayerStub::initCheck()
{
    return isTestBuild() ? OK : INVALID_OPERATION;
}

// Parse mUrl to get:
// * The library to be dlopened.
// * The url to be passed to the real setDataSource impl.
//
// mUrl is expected to be in following format:
//
// test:<name of the .so>?url=<url for setDataSource>
//
// The value of the url parameter is treated as a string (no
// unescaping of illegal charaters).
status_t TestPlayerStub::parseUrl()
{
    if (strlen(mUrl) < strlen(kTestUrlScheme)) {
        resetInternal();
        return BAD_VALUE;
    }

    char *i = mUrl + strlen(kTestUrlScheme);

    mFilename = i;

    while (*i != '\0' && *i != '?') {
        ++i;
    }

    if (*i == '\0' || strncmp(i + 1, kUrlParam, strlen(kUrlParam)) != 0) {
        resetInternal();
        return BAD_VALUE;
    }
    *i = '\0';  // replace '?' to nul-terminate mFilename

    mContentUrl = i + 1 + strlen(kUrlParam);
    return OK;
}

// Load the dynamic library.
// Create the test player.
// Call setDataSource on the test player with the url in param.
status_t TestPlayerStub::setDataSource(
        const char *url, const KeyedVector<String8, String8> *headers) {
    if (!isTestUrl(url) || NULL != mHandle) {
        return INVALID_OPERATION;
    }

    mUrl = strdup(url);

    status_t status = parseUrl();

    if (OK != status) {
        resetInternal();
        return status;
    }

    ::dlerror();  // Clears any pending error.

    // Load the test player from the url. dlopen will fail if the lib
    // is not there. dls are under /system/lib
    // None of the entry points should be NULL.
    mHandle = ::dlopen(mFilename, RTLD_NOW | RTLD_GLOBAL);
    if (!mHandle) {
        ALOGE("dlopen failed: %s", ::dlerror());
        resetInternal();
        return UNKNOWN_ERROR;
    }

    // Load the 2 entry points to create and delete instances.
    const char *err;
    mNewPlayer = reinterpret_cast<NEW_PLAYER>(dlsym(mHandle,
                                                    "newPlayer"));
    err = ::dlerror();
    if (err || mNewPlayer == NULL) {
        // if err is NULL the string <null> is inserted in the logs =>
        // mNewPlayer was NULL.
        ALOGE("dlsym for newPlayer failed %s", err);
        resetInternal();
        return UNKNOWN_ERROR;
    }

    mDeletePlayer = reinterpret_cast<DELETE_PLAYER>(dlsym(mHandle,
                                                          "deletePlayer"));
    err = ::dlerror();
    if (err || mDeletePlayer == NULL) {
        ALOGE("dlsym for deletePlayer failed %s", err);
        resetInternal();
        return UNKNOWN_ERROR;
    }

    mPlayer = (*mNewPlayer)();
    return mPlayer->setDataSource(mContentUrl, headers);
}

// Internal cleanup.
status_t TestPlayerStub::resetInternal()
{
    if(mUrl) {
        free(mUrl);
        mUrl = NULL;
    }
    mFilename = NULL;
    mContentUrl = NULL;

    if (mPlayer) {
        LOG_ASSERT(mDeletePlayer != NULL, "mDeletePlayer is null");
        (*mDeletePlayer)(mPlayer);
        mPlayer = NULL;
    }

    if (mHandle) {
        ::dlclose(mHandle);
        mHandle = NULL;
    }
    return OK;
}

/* static */ bool TestPlayerStub::canBeUsed(const char *url)
{
    return isTestBuild() && isTestUrl(url);
}

}  // namespace android
