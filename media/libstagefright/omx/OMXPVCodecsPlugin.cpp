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

#include "OMXPVCodecsPlugin.h"

#include "pv_omxcore.h"

#include <media/stagefright/MediaDebug.h>

namespace android {

OMXPVCodecsPlugin::OMXPVCodecsPlugin() {
    OMX_MasterInit();
}

OMXPVCodecsPlugin::~OMXPVCodecsPlugin() {
    OMX_MasterDeinit();
}

OMX_ERRORTYPE OMXPVCodecsPlugin::makeComponentInstance(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component) {
    return OMX_MasterGetHandle(
            reinterpret_cast<OMX_HANDLETYPE *>(component),
            const_cast<char *>(name),
            appData,
            const_cast<OMX_CALLBACKTYPE *>(callbacks));
}

OMX_ERRORTYPE OMXPVCodecsPlugin::destroyComponentInstance(
        OMX_COMPONENTTYPE *component) {
    return OMX_MasterFreeHandle(component);
}

OMX_ERRORTYPE OMXPVCodecsPlugin::enumerateComponents(
        OMX_STRING name,
        size_t size,
        OMX_U32 index) {
    return OMX_MasterComponentNameEnum(name, size, index);
}

OMX_ERRORTYPE OMXPVCodecsPlugin::getRolesOfComponent(
        const char *name,
        Vector<String8> *roles) {
    roles->clear();

    OMX_U32 numRoles;
    OMX_ERRORTYPE err =
        OMX_MasterGetRolesOfComponent(
                const_cast<char *>(name),
                &numRoles,
                NULL);

    if (err != OMX_ErrorNone) {
        return err;
    }

    if (numRoles > 0) {
        OMX_U8 **array = new OMX_U8 *[numRoles];
        for (OMX_U32 i = 0; i < numRoles; ++i) {
            array[i] = new OMX_U8[OMX_MAX_STRINGNAME_SIZE];
        }

        OMX_U32 numRoles2;
        err = OMX_MasterGetRolesOfComponent(
                const_cast<char *>(name), &numRoles2, array);

        CHECK_EQ(err, OMX_ErrorNone);
        CHECK_EQ(numRoles, numRoles2);

        for (OMX_U32 i = 0; i < numRoles; ++i) {
            String8 s((const char *)array[i]);
            roles->push(s);

            delete[] array[i];
            array[i] = NULL;
        }

        delete[] array;
        array = NULL;
    }

    return OMX_ErrorNone;
}

}  // namespace android
