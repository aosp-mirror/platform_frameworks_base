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


#include "rsdCore.h"
#include "rsdBcc.h"
#include "rsdRuntime.h"

#include <bcinfo/MetadataExtractor.h>

#include "rsContext.h"
#include "rsScriptC.h"

#include "utils/Timers.h"
#include "utils/StopWatch.h"
extern "C" {
#include "libdex/ZipArchive.h"
}


using namespace android;
using namespace android::renderscript;

struct DrvScript {
    int (*mRoot)();
    void (*mInit)();

    BCCScriptRef mBccScript;

    bcinfo::MetadataExtractor *ME;

    InvokeFunc_t *mInvokeFunctions;
    void ** mFieldAddress;
    bool * mFieldIsObject;

    const uint8_t * mScriptText;
    uint32_t mScriptTextLength;
};


static Script * setTLS(Script *sc) {
    ScriptTLSStruct * tls = (ScriptTLSStruct *)pthread_getspecific(rsdgThreadTLSKey);
    rsAssert(tls);
    Script *old = tls->mScript;
    tls->mScript = sc;
    return old;
}


bool rsdScriptInit(const Context *rsc,
                     ScriptC *script,
                     char const *resName,
                     char const *cacheDir,
                     uint8_t const *bitcode,
                     size_t bitcodeSize,
                     uint32_t flags) {
    //LOGE("rsdScriptCreate %p %p %p %p %i %i %p", rsc, resName, cacheDir, bitcode, bitcodeSize, flags, lookupFunc);

    pthread_mutex_lock(&rsdgInitMutex);
    char *cachePath = NULL;
    size_t exportFuncCount = 0;
    size_t exportVarCount = 0;
    size_t objectSlotCount = 0;

    DrvScript *drv = (DrvScript *)calloc(1, sizeof(DrvScript));
    if (drv == NULL) {
        goto error;
    }
    script->mHal.drv = drv;

    drv->mBccScript = bccCreateScript();
    script->mHal.info.isThreadable = true;
    drv->mScriptText = bitcode;
    drv->mScriptTextLength = bitcodeSize;


    drv->ME = new bcinfo::MetadataExtractor((const char*)drv->mScriptText,
                                            drv->mScriptTextLength);
    if (!drv->ME->extract()) {
      LOGE("bcinfo: failed to read script metadata");
      goto error;
    }

    //LOGE("mBccScript %p", script->mBccScript);

    if (bccRegisterSymbolCallback(drv->mBccScript, &rsdLookupRuntimeStub, script) != 0) {
        LOGE("bcc: FAILS to register symbol callback");
        goto error;
    }

    if (bccReadBC(drv->mBccScript,
                  resName,
                  (char const *)drv->mScriptText,
                  drv->mScriptTextLength, 0) != 0) {
        LOGE("bcc: FAILS to read bitcode");
        goto error;
    }

    if (bccLinkFile(drv->mBccScript, "/system/lib/libclcore.bc", 0) != 0) {
        LOGE("bcc: FAILS to link bitcode");
        goto error;
    }

    if (bccPrepareExecutable(drv->mBccScript, cacheDir, resName, 0) != 0) {
        LOGE("bcc: FAILS to prepare executable");
        goto error;
    }

    free(cachePath);

    drv->mRoot = reinterpret_cast<int (*)()>(bccGetFuncAddr(drv->mBccScript, "root"));
    drv->mInit = reinterpret_cast<void (*)()>(bccGetFuncAddr(drv->mBccScript, "init"));

    exportFuncCount = drv->ME->getExportFuncCount();
    if (exportFuncCount > 0) {
        drv->mInvokeFunctions = (InvokeFunc_t*) calloc(exportFuncCount,
                                                       sizeof(InvokeFunc_t));
        bccGetExportFuncList(drv->mBccScript, exportFuncCount,
                             (void **) drv->mInvokeFunctions);
    } else {
        drv->mInvokeFunctions = NULL;
    }

    exportVarCount = drv->ME->getExportVarCount();
    if (exportVarCount > 0) {
        drv->mFieldAddress = (void **) calloc(exportVarCount, sizeof(void*));
        drv->mFieldIsObject = (bool *) calloc(exportVarCount, sizeof(bool));
        bccGetExportVarList(drv->mBccScript, exportVarCount,
                            (void **) drv->mFieldAddress);
    } else {
        drv->mFieldAddress = NULL;
        drv->mFieldIsObject = NULL;
    }

    objectSlotCount = drv->ME->getObjectSlotCount();
    if (objectSlotCount > 0) {
        const uint32_t *objectSlotList = drv->ME->getObjectSlotList();
        for (uint32_t ct=0; ct < objectSlotCount; ct++) {
            drv->mFieldIsObject[objectSlotList[ct]] = true;
        }
    }

    // Copy info over to runtime
    script->mHal.info.exportedFunctionCount = drv->ME->getExportFuncCount();
    script->mHal.info.exportedVariableCount = drv->ME->getExportVarCount();
    script->mHal.info.exportedPragmaCount = drv->ME->getPragmaCount();
    script->mHal.info.exportedPragmaKeyList = drv->ME->getPragmaKeyList();
    script->mHal.info.exportedPragmaValueList = drv->ME->getPragmaValueList();
    script->mHal.info.root = drv->mRoot;

    pthread_mutex_unlock(&rsdgInitMutex);
    return true;

error:

    pthread_mutex_unlock(&rsdgInitMutex);
    if (drv->ME) {
        delete drv->ME;
        drv->ME = NULL;
    }
    free(drv);
    return false;

}

typedef struct {
    Context *rsc;
    Script *script;
    const Allocation * ain;
    Allocation * aout;
    const void * usr;

    uint32_t mSliceSize;
    volatile int mSliceNum;

    const uint8_t *ptrIn;
    uint32_t eStrideIn;
    uint8_t *ptrOut;
    uint32_t eStrideOut;

    uint32_t xStart;
    uint32_t xEnd;
    uint32_t yStart;
    uint32_t yEnd;
    uint32_t zStart;
    uint32_t zEnd;
    uint32_t arrayStart;
    uint32_t arrayEnd;

    uint32_t dimX;
    uint32_t dimY;
    uint32_t dimZ;
    uint32_t dimArray;
} MTLaunchStruct;
typedef int (*rs_t)(const void *, void *, const void *, uint32_t, uint32_t, uint32_t, uint32_t);

static void wc_xy(void *usr, uint32_t idx) {
    MTLaunchStruct *mtls = (MTLaunchStruct *)usr;

    while (1) {
        uint32_t slice = (uint32_t)android_atomic_inc(&mtls->mSliceNum);
        uint32_t yStart = mtls->yStart + slice * mtls->mSliceSize;
        uint32_t yEnd = yStart + mtls->mSliceSize;
        yEnd = rsMin(yEnd, mtls->yEnd);
        if (yEnd <= yStart) {
            return;
        }

        //LOGE("usr idx %i, x %i,%i  y %i,%i", idx, mtls->xStart, mtls->xEnd, yStart, yEnd);
        //LOGE("usr ptr in %p,  out %p", mtls->ptrIn, mtls->ptrOut);
        for (uint32_t y = yStart; y < yEnd; y++) {
            uint32_t offset = mtls->dimX * y;
            uint8_t *xPtrOut = mtls->ptrOut + (mtls->eStrideOut * offset);
            const uint8_t *xPtrIn = mtls->ptrIn + (mtls->eStrideIn * offset);

            for (uint32_t x = mtls->xStart; x < mtls->xEnd; x++) {
                ((rs_t)mtls->script->mHal.info.root) (xPtrIn, xPtrOut, mtls->usr, x, y, 0, 0);
                xPtrIn += mtls->eStrideIn;
                xPtrOut += mtls->eStrideOut;
            }
        }
    }
}

static void wc_x(void *usr, uint32_t idx) {
    MTLaunchStruct *mtls = (MTLaunchStruct *)usr;

    while (1) {
        uint32_t slice = (uint32_t)android_atomic_inc(&mtls->mSliceNum);
        uint32_t xStart = mtls->xStart + slice * mtls->mSliceSize;
        uint32_t xEnd = xStart + mtls->mSliceSize;
        xEnd = rsMin(xEnd, mtls->xEnd);
        if (xEnd <= xStart) {
            return;
        }

        //LOGE("usr idx %i, x %i,%i  y %i,%i", idx, mtls->xStart, mtls->xEnd, yStart, yEnd);
        //LOGE("usr ptr in %p,  out %p", mtls->ptrIn, mtls->ptrOut);
        uint8_t *xPtrOut = mtls->ptrOut + (mtls->eStrideOut * xStart);
        const uint8_t *xPtrIn = mtls->ptrIn + (mtls->eStrideIn * xStart);
        for (uint32_t x = xStart; x < xEnd; x++) {
            ((rs_t)mtls->script->mHal.info.root) (xPtrIn, xPtrOut, mtls->usr, x, 0, 0, 0);
            xPtrIn += mtls->eStrideIn;
            xPtrOut += mtls->eStrideOut;
        }
    }
}

void rsdScriptInvokeForEach(const Context *rsc,
                            Script *s,
                            uint32_t slot,
                            const Allocation * ain,
                            Allocation * aout,
                            const void * usr,
                            uint32_t usrLen,
                            const RsScriptCall *sc) {

    RsdHal * dc = (RsdHal *)rsc->mHal.drv;

    MTLaunchStruct mtls;
    memset(&mtls, 0, sizeof(mtls));

    if (ain) {
        mtls.dimX = ain->getType()->getDimX();
        mtls.dimY = ain->getType()->getDimY();
        mtls.dimZ = ain->getType()->getDimZ();
        //mtls.dimArray = ain->getType()->getDimArray();
    } else if (aout) {
        mtls.dimX = aout->getType()->getDimX();
        mtls.dimY = aout->getType()->getDimY();
        mtls.dimZ = aout->getType()->getDimZ();
        //mtls.dimArray = aout->getType()->getDimArray();
    } else {
        rsc->setError(RS_ERROR_BAD_SCRIPT, "rsForEach called with null allocations");
        return;
    }

    if (!sc || (sc->xEnd == 0)) {
        mtls.xEnd = mtls.dimX;
    } else {
        rsAssert(sc->xStart < mtls.dimX);
        rsAssert(sc->xEnd <= mtls.dimX);
        rsAssert(sc->xStart < sc->xEnd);
        mtls.xStart = rsMin(mtls.dimX, sc->xStart);
        mtls.xEnd = rsMin(mtls.dimX, sc->xEnd);
        if (mtls.xStart >= mtls.xEnd) return;
    }

    if (!sc || (sc->yEnd == 0)) {
        mtls.yEnd = mtls.dimY;
    } else {
        rsAssert(sc->yStart < mtls.dimY);
        rsAssert(sc->yEnd <= mtls.dimY);
        rsAssert(sc->yStart < sc->yEnd);
        mtls.yStart = rsMin(mtls.dimY, sc->yStart);
        mtls.yEnd = rsMin(mtls.dimY, sc->yEnd);
        if (mtls.yStart >= mtls.yEnd) return;
    }

    mtls.xEnd = rsMax((uint32_t)1, mtls.xEnd);
    mtls.yEnd = rsMax((uint32_t)1, mtls.yEnd);
    mtls.zEnd = rsMax((uint32_t)1, mtls.zEnd);
    mtls.arrayEnd = rsMax((uint32_t)1, mtls.arrayEnd);

    rsAssert(!ain || (ain->getType()->getDimZ() == 0));

    Context *mrsc = (Context *)rsc;
    Script * oldTLS = setTLS(s);

    mtls.rsc = mrsc;
    mtls.ain = ain;
    mtls.aout = aout;
    mtls.script = s;
    mtls.usr = usr;
    mtls.mSliceSize = 10;
    mtls.mSliceNum = 0;

    mtls.ptrIn = NULL;
    mtls.eStrideIn = 0;
    if (ain) {
        mtls.ptrIn = (const uint8_t *)ain->getPtr();
        mtls.eStrideIn = ain->getType()->getElementSizeBytes();
    }

    mtls.ptrOut = NULL;
    mtls.eStrideOut = 0;
    if (aout) {
        mtls.ptrOut = (uint8_t *)aout->getPtr();
        mtls.eStrideOut = aout->getType()->getElementSizeBytes();
    }

    if ((dc->mWorkers.mCount > 1) && s->mHal.info.isThreadable) {
        if (mtls.dimY > 1) {
            rsdLaunchThreads(mrsc, wc_xy, &mtls);
        } else {
            rsdLaunchThreads(mrsc, wc_x, &mtls);
        }

        //LOGE("launch 1");
    } else {
        //LOGE("launch 3");
        for (uint32_t ar = mtls.arrayStart; ar < mtls.arrayEnd; ar++) {
            for (uint32_t z = mtls.zStart; z < mtls.zEnd; z++) {
                for (uint32_t y = mtls.yStart; y < mtls.yEnd; y++) {
                    uint32_t offset = mtls.dimX * mtls.dimY * mtls.dimZ * ar +
                                      mtls.dimX * mtls.dimY * z +
                                      mtls.dimX * y;
                    uint8_t *xPtrOut = mtls.ptrOut + (mtls.eStrideOut * offset);
                    const uint8_t *xPtrIn = mtls.ptrIn + (mtls.eStrideIn * offset);

                    for (uint32_t x = mtls.xStart; x < mtls.xEnd; x++) {
                        ((rs_t)s->mHal.info.root) (xPtrIn, xPtrOut, usr, x, y, z, ar);
                        xPtrIn += mtls.eStrideIn;
                        xPtrOut += mtls.eStrideOut;
                    }
                }
            }
        }
    }

    setTLS(oldTLS);
}


int rsdScriptInvokeRoot(const Context *dc, Script *script) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;

    Script * oldTLS = setTLS(script);
    int ret = drv->mRoot();
    setTLS(oldTLS);

    return ret;
}

void rsdScriptInvokeInit(const Context *dc, Script *script) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;

    if (drv->mInit) {
        drv->mInit();
    }
}


void rsdScriptInvokeFunction(const Context *dc, Script *script,
                            uint32_t slot,
                            const void *params,
                            size_t paramLength) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;
    //LOGE("invoke %p %p %i %p %i", dc, script, slot, params, paramLength);

    Script * oldTLS = setTLS(script);
    ((void (*)(const void *, uint32_t))
        drv->mInvokeFunctions[slot])(params, paramLength);
    setTLS(oldTLS);
}

void rsdScriptSetGlobalVar(const Context *dc, const Script *script,
                           uint32_t slot, void *data, size_t dataLength) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;
    //rsAssert(!script->mFieldIsObject[slot]);
    //LOGE("setGlobalVar %p %p %i %p %i", dc, script, slot, data, dataLength);

    int32_t *destPtr = ((int32_t **)drv->mFieldAddress)[slot];
    if (!destPtr) {
        //LOGV("Calling setVar on slot = %i which is null", slot);
        return;
    }

    memcpy(destPtr, data, dataLength);
}

void rsdScriptSetGlobalBind(const Context *dc, const Script *script, uint32_t slot, void *data) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;
    //rsAssert(!script->mFieldIsObject[slot]);
    //LOGE("setGlobalBind %p %p %i %p", dc, script, slot, data);

    int32_t *destPtr = ((int32_t **)drv->mFieldAddress)[slot];
    if (!destPtr) {
        //LOGV("Calling setVar on slot = %i which is null", slot);
        return;
    }

    memcpy(destPtr, &data, sizeof(void *));
}

void rsdScriptSetGlobalObj(const Context *dc, const Script *script, uint32_t slot, ObjectBase *data) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;
    //rsAssert(script->mFieldIsObject[slot]);
    //LOGE("setGlobalObj %p %p %i %p", dc, script, slot, data);

    int32_t *destPtr = ((int32_t **)drv->mFieldAddress)[slot];
    if (!destPtr) {
        //LOGV("Calling setVar on slot = %i which is null", slot);
        return;
    }

    rsrSetObject(dc, script, (ObjectBase **)destPtr, data);
}

void rsdScriptDestroy(const Context *dc, Script *script) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;

    if (drv->mFieldAddress) {
        size_t exportVarCount = drv->ME->getExportVarCount();
        for (size_t ct = 0; ct < exportVarCount; ct++) {
            if (drv->mFieldIsObject[ct]) {
                // The field address can be NULL if the script-side has
                // optimized the corresponding global variable away.
                if (drv->mFieldAddress[ct]) {
                    rsrClearObject(dc, script, (ObjectBase **)drv->mFieldAddress[ct]);
                }
            }
        }
        free(drv->mFieldAddress);
        drv->mFieldAddress = NULL;
        free(drv->mFieldIsObject);
        drv->mFieldIsObject = NULL;    }

    if (drv->mInvokeFunctions) {
        free(drv->mInvokeFunctions);
        drv->mInvokeFunctions = NULL;
    }

    delete drv->ME;
    drv->ME = NULL;

    free(drv);
    script->mHal.drv = NULL;

}


