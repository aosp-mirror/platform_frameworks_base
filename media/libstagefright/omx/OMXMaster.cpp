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
#define LOG_TAG "OMXMaster"
#include <utils/Log.h>

#include "OMXMaster.h"

#include "SoftOMXPlugin.h"

#include <dlfcn.h>

#include <media/stagefright/MediaDebug.h>

namespace android {

OMXMaster::OMXMaster()
    : mVendorLibHandle(NULL) {
    addVendorPlugin();
    addPlugin(new SoftOMXPlugin);
}

OMXMaster::~OMXMaster() {
    clearPlugins();

    if (mVendorLibHandle != NULL) {
        dlclose(mVendorLibHandle);
        mVendorLibHandle = NULL;
    }
}

void OMXMaster::addVendorPlugin() {
    addPlugin("libstagefrighthw.so");
}

void OMXMaster::addPlugin(const char *libname) {
    mVendorLibHandle = dlopen(libname, RTLD_NOW);

    if (mVendorLibHandle == NULL) {
        return;
    }

    typedef OMXPluginBase *(*CreateOMXPluginFunc)();
    CreateOMXPluginFunc createOMXPlugin =
        (CreateOMXPluginFunc)dlsym(
                mVendorLibHandle, "createOMXPlugin");
    if (!createOMXPlugin)
        createOMXPlugin = (CreateOMXPluginFunc)dlsym(
                mVendorLibHandle, "_ZN7android15createOMXPluginEv");

    if (createOMXPlugin) {
        addPlugin((*createOMXPlugin)());
    }
}

void OMXMaster::addPlugin(OMXPluginBase *plugin) {
    Mutex::Autolock autoLock(mLock);

    mPlugins.push_back(plugin);

    OMX_U32 index = 0;

    char name[128];
    OMX_ERRORTYPE err;
    while ((err = plugin->enumerateComponents(
                    name, sizeof(name), index++)) == OMX_ErrorNone) {
        String8 name8(name);

        if (mPluginByComponentName.indexOfKey(name8) >= 0) {
            LOGE("A component of name '%s' already exists, ignoring this one.",
                 name8.string());

            continue;
        }

        mPluginByComponentName.add(name8, plugin);
    }

    if (err != OMX_ErrorNoMore) {
        LOGE("OMX plugin failed w/ error 0x%08x after registering %d "
             "components", err, mPluginByComponentName.size());
    }
}

void OMXMaster::clearPlugins() {
    Mutex::Autolock autoLock(mLock);

    typedef void (*DestroyOMXPluginFunc)(OMXPluginBase*);
    DestroyOMXPluginFunc destroyOMXPlugin =
        (DestroyOMXPluginFunc)dlsym(
                mVendorLibHandle, "destroyOMXPlugin");

    mPluginByComponentName.clear();

    for (List<OMXPluginBase *>::iterator it = mPlugins.begin();
            it != mPlugins.end(); ++it) {
        if (destroyOMXPlugin)
            destroyOMXPlugin(*it);
        else
            delete *it;
        *it = NULL;
    }

    mPlugins.clear();
}

OMX_ERRORTYPE OMXMaster::makeComponentInstance(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component) {
    Mutex::Autolock autoLock(mLock);

    *component = NULL;

    ssize_t index = mPluginByComponentName.indexOfKey(String8(name));

    if (index < 0) {
        return OMX_ErrorInvalidComponentName;
    }

    OMXPluginBase *plugin = mPluginByComponentName.valueAt(index);
    OMX_ERRORTYPE err =
        plugin->makeComponentInstance(name, callbacks, appData, component);

    if (err != OMX_ErrorNone) {
        return err;
    }

    mPluginByInstance.add(*component, plugin);

    return err;
}

OMX_ERRORTYPE OMXMaster::destroyComponentInstance(
        OMX_COMPONENTTYPE *component) {
    Mutex::Autolock autoLock(mLock);

    ssize_t index = mPluginByInstance.indexOfKey(component);

    if (index < 0) {
        return OMX_ErrorBadParameter;
    }

    OMXPluginBase *plugin = mPluginByInstance.valueAt(index);
    mPluginByInstance.removeItemsAt(index);

    return plugin->destroyComponentInstance(component);
}

OMX_ERRORTYPE OMXMaster::enumerateComponents(
        OMX_STRING name,
        size_t size,
        OMX_U32 index) {
    Mutex::Autolock autoLock(mLock);

    size_t numComponents = mPluginByComponentName.size();

    if (index >= numComponents) {
        return OMX_ErrorNoMore;
    }

    const String8 &name8 = mPluginByComponentName.keyAt(index);

    CHECK(size >= 1 + name8.size());
    strcpy(name, name8.string());

    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXMaster::getRolesOfComponent(
        const char *name,
        Vector<String8> *roles) {
    Mutex::Autolock autoLock(mLock);

    roles->clear();

    ssize_t index = mPluginByComponentName.indexOfKey(String8(name));

    if (index < 0) {
        return OMX_ErrorInvalidComponentName;
    }

    OMXPluginBase *plugin = mPluginByComponentName.valueAt(index);
    return plugin->getRolesOfComponent(name, roles);
}

}  // namespace android
