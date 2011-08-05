/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef RENDER_SCRIPT_H
#define RENDER_SCRIPT_H

#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

#include "RenderScriptDefines.h"

//
// A3D loading and object update code.
// Should only be called at object creation, not thread safe
RsObjectBase rsaFileA3DGetEntryByIndex(RsContext, uint32_t idx, RsFile);
RsFile rsaFileA3DCreateFromMemory(RsContext, const void *data, uint32_t len);
RsFile rsaFileA3DCreateFromAsset(RsContext, void *asset);
RsFile rsaFileA3DCreateFromFile(RsContext, const char *path);
void rsaFileA3DGetNumIndexEntries(RsContext, int32_t *numEntries, RsFile);
void rsaFileA3DGetIndexEntries(RsContext, RsFileIndexEntry *fileEntries,uint32_t numEntries, RsFile);
void rsaGetName(RsContext, void * obj, const char **name);
// Mesh update functions
void rsaMeshGetVertexBufferCount(RsContext, RsMesh, int32_t *vtxCount);
void rsaMeshGetIndexCount(RsContext, RsMesh, int32_t *idxCount);
void rsaMeshGetVertices(RsContext, RsMesh, RsAllocation *vtxData, uint32_t vtxDataCount);
void rsaMeshGetIndices(RsContext, RsMesh, RsAllocation *va, uint32_t *primType, uint32_t idxDataCount);
// Allocation update
const void* rsaAllocationGetType(RsContext con, RsAllocation va);
// Type update
void rsaTypeGetNativeData(RsContext, RsType, uint32_t *typeData, uint32_t typeDataSize);
// Element update
void rsaElementGetNativeData(RsContext, RsElement, uint32_t *elemData, uint32_t elemDataSize);
void rsaElementGetSubElements(RsContext, RsElement, uint32_t *ids, const char **names, uint32_t dataSize);

RsDevice rsDeviceCreate();
void rsDeviceDestroy(RsDevice dev);
void rsDeviceSetConfig(RsDevice dev, RsDeviceParam p, int32_t value);
RsContext rsContextCreate(RsDevice dev, uint32_t version, uint32_t sdkVersion);
RsContext rsContextCreateGL(RsDevice dev, uint32_t version, uint32_t sdkVersion, RsSurfaceConfig sc, uint32_t dpi);

#include "rsgApiFuncDecl.h"

#ifdef __cplusplus
};
#endif

#endif // RENDER_SCRIPT_H



