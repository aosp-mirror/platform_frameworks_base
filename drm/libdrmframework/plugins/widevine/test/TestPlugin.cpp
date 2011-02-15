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

#include <dlfcn.h>

#include <iostream>

#include "WVMDrmPlugin.h"
#include "drm/DrmInfoRequest.h"
#include "drm/DrmInfoStatus.h"
#include "drm/DrmConstraints.h"
#include "drm/DrmInfo.h"

using namespace android;
using namespace std;

class WVMDrmPluginTest
{
public:
    WVMDrmPluginTest() {}
    ~WVMDrmPluginTest() {}

    void TestAsset(IDrmEngine *plugin, String8 &url);

    void TestRemoveAllRights(IDrmEngine *plugin);
    void TestAcquireRights(IDrmEngine *plugin, String8 &url);
    void TestCheckRightsNotAcquired(IDrmEngine *plugin, String8 &url);
    void TestCheckValidRights(IDrmEngine *plugin, String8 &url);
    void TestGetConstraints(IDrmEngine *plugin, String8 &url);
    void TestRemoveRights(IDrmEngine *plugin, String8 &url);

    // Tests
    void Run();
};

void WVMDrmPluginTest::Run()
{
    cout << "WVDrmPluginTest::Run" << endl;
    const char *path = "/system/lib/drm/libdrmwvmplugin.so";
    void *handle = dlopen(path, RTLD_NOW);
    if (handle == NULL) {
        fprintf(stderr, "Can't open plugin: %s\n", path);
        exit(-1);
    }

    typedef IDrmEngine *(*create_t)();
    create_t creator = (create_t)dlsym(handle, "create");
    if (!creator) {
        fprintf(stderr, "Can't find create method\n");
        exit(-1);
    }

    typedef void (*destroy_t)(IDrmEngine *);
    destroy_t destroyer = (destroy_t)dlsym(handle, "destroy");
    if (!destroyer) {
        fprintf(stderr, "Can't find destroy method\n");
        exit(-1);
    }

    // Basic test - see if we can instantiate the object and call a method
    IDrmEngine *plugin = (*creator)();
    if (plugin->initialize(0) != DRM_NO_ERROR) {
        fprintf(stderr, "onInitialize failed!\n");
        exit(-1);
    }

    // Remote asset
    String8 url;
    url = String8("http://seawwws001.cdn.shibboleth.tv/videos/qa/adventures_d_ch_444169.wvm");
    TestAsset(plugin, url);

    // Local asset
    url = String8("file:///sdcard/Widevine/trailers_d_ch_444169.wvm");
    TestAsset(plugin, url);

    // Remote asset with query parameters
    url = String8("http://seawwws001.cdn.shibboleth.tv/videos/qa/adventures_d_ch_444169.wvm?a=b");
    TestAsset(plugin, url);

    // Shut down and clean up
    if (plugin->terminate(0) != DRM_NO_ERROR) {
        fprintf(stderr, "onTerminate failed!\n");
        exit(-1);
    }
    destroyer(plugin);
    dlclose(handle);
    printf("Test successful!\n");
    exit(0);
}

void WVMDrmPluginTest::TestAcquireRights(IDrmEngine *plugin, String8 &url)
{
    cout << "WVDrmPluginTest::TestAcquireRights url=" << url << endl;

    String8 mimeType("video/wvm");
    DrmInfoRequest rightsAcquisitionInfo(DrmInfoRequest::TYPE_RIGHTS_ACQUISITION_INFO, mimeType);
    rightsAcquisitionInfo.put(String8("WVDRMServerKey"), String8("http://wstfcps005.shibboleth.tv/widevine/cypherpc/cgi-bin/GetEMMs.cgi"));
    rightsAcquisitionInfo.put(String8("WVAssetURIKey"), url);
    rightsAcquisitionInfo.put(String8("WVDeviceIDKey"), String8("device1234"));
    rightsAcquisitionInfo.put(String8("WVPortalKey"), String8("YouTube"));

    DrmInfo *info = plugin->acquireDrmInfo(0, &rightsAcquisitionInfo);
    if (info == NULL) {
        fprintf(stderr, "acquireDrmInfo failed!\n");
        exit(-1);
    }

    DrmInfoStatus *status = plugin->processDrmInfo(0, info);
    if (status == NULL || status->statusCode != DrmInfoStatus::STATUS_OK) {
        fprintf(stderr, "processDrmInfo failed!\n");
        exit(-1);
    }

    delete status;
    delete info;
}

void WVMDrmPluginTest::TestCheckRightsNotAcquired(IDrmEngine *plugin, String8 &url)
{
    cout << "WVDrmPluginTest::TestCheckRightsNotAcquired url=" << url << endl;

    if (plugin->checkRightsStatus(0, url, Action::DEFAULT) != RightsStatus::RIGHTS_NOT_ACQUIRED) {
        fprintf(stderr, "checkRightsNotAcquired default action failed!\n");
        exit(-1);
    }

    if (plugin->checkRightsStatus(0, url, Action::PLAY) != RightsStatus::RIGHTS_NOT_ACQUIRED) {
        fprintf(stderr, "checkRightsNotAcquired failed!\n");
        exit(-1);
    }
}

void WVMDrmPluginTest::TestCheckValidRights(IDrmEngine *plugin, String8 &url)
{
    cout << "WVDrmPluginTest::TestCheckValidRights url=" << url << endl;

    if (plugin->checkRightsStatus(0, url, Action::DEFAULT) != RightsStatus::RIGHTS_VALID) {
        fprintf(stderr, "checkValidRights default action failed!\n");
        exit(-1);
    }

    if (plugin->checkRightsStatus(0, url, Action::PLAY) != RightsStatus::RIGHTS_VALID) {
        fprintf(stderr, "checkValidRights play action failed!\n");
        exit(-1);
    }
}

void WVMDrmPluginTest::TestGetConstraints(IDrmEngine *plugin, String8 &url)
{
    cout << "WVDrmPluginTest::TestGetConstraints url=" << url << endl;

    DrmConstraints *constraints;
    constraints = plugin->getConstraints(0, &url, Action::PLAY);
    if (constraints == NULL) {
        fprintf(stderr, "getConstraints returned NULL constraints!\n");
        exit(-1);
    }

    if (constraints->getCount() != 3) {
        fprintf(stderr, "getConstraints returned unexpected count!\n");
        exit(-1);
    }

    if (constraints->get(DrmConstraints::LICENSE_START_TIME) == "") {
        fprintf(stderr, "getConstraints returned unexpected count!\n");
        exit(-1);
    }

    if (constraints->get(DrmConstraints::LICENSE_AVAILABLE_TIME) == "") {
        fprintf(stderr, "getConstraints returned unexpected count!\n");
        exit(-1);
    }

    if (constraints->get(DrmConstraints::LICENSE_EXPIRY_TIME) == "") {
        fprintf(stderr, "getConstraints returned unexpected count!\n");
        exit(-1);
    }

    delete constraints;
}

void WVMDrmPluginTest::TestRemoveRights(IDrmEngine *plugin, String8 &url)
{
    cout << "WVDrmPluginTest::TestRemoveRights url=" << url << endl;

    status_t status = plugin->removeRights(0, url);
    if (status != DRM_NO_ERROR) {
        fprintf(stderr, "removeRights returned error: %d!\n", (int)status);
        exit(-1);
    }
}

void WVMDrmPluginTest::TestRemoveAllRights(IDrmEngine *plugin)
{
    cout << "WVDrmPluginTest::TestRemoveAllRights" << endl;

    status_t status = plugin->removeAllRights(0);
    if (status != DRM_NO_ERROR) {
        fprintf(stderr, "removeAllRights returned error: %d!\n", (int)status);
        exit(-1);
    }
}

void WVMDrmPluginTest::TestAsset(IDrmEngine *plugin, String8 &url)
{
    cout << "WVDrmPluginTest::TestAsset url=" << url << endl;

    TestAcquireRights(plugin, url);
    TestCheckValidRights(plugin, url);
    TestGetConstraints(plugin, url);
    TestRemoveRights(plugin, url);
    TestCheckRightsNotAcquired(plugin, url);
    TestAcquireRights(plugin, url);
    TestRemoveAllRights(plugin);
    TestCheckRightsNotAcquired(plugin, url);
}

int main(int argc, char **argv)
{
    // turn off some noisy printing in WVStreamControl
    setenv("WV_SILENT", "true", 1);
    WVMDrmPluginTest test;
    test.Run();
}
