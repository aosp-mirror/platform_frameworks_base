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

    uint32_t mInvokeFunctionCount;
    InvokeFunc_t *mInvokeFunctions;
    uint32_t mFieldCount;
    void ** mFieldAddress;
    bool * mFieldIsObject;

    const uint8_t * mScriptText;
    uint32_t mScriptTextLength;

    //uint32_t * mObjectSlots;
    //uint32_t mObjectSlotCount;

    uint32_t mPragmaCount;
    const char ** mPragmaKeys;
    const char ** mPragmaValues;

};

// Input: cacheDir
// Input: resName
// Input: extName
//
// Note: cacheFile = resName + extName
//
// Output: Returns cachePath == cacheDir + cacheFile
static char *genCacheFileName(const char *cacheDir,
                              const char *resName,
                              const char *extName) {
    char cachePath[512];
    char cacheFile[sizeof(cachePath)];
    const size_t kBufLen = sizeof(cachePath) - 1;

    cacheFile[0] = '\0';
    // Note: resName today is usually something like
    //       "/com.android.fountain:raw/fountain"
    if (resName[0] != '/') {
        // Get the absolute path of the raw/***.bc file.

        // Generate the absolute path.  This doesn't do everything it
        // should, e.g. if resName is "./out/whatever" it doesn't crunch
        // the leading "./" out because this if-block is not triggered,
        // but it'll make do.
        //
        if (getcwd(cacheFile, kBufLen) == NULL) {
            LOGE("Can't get CWD while opening raw/***.bc file\n");
            return NULL;
        }
        // Append "/" at the end of cacheFile so far.
        strncat(cacheFile, "/", kBufLen);
    }

    // cacheFile = resName + extName
    //
    strncat(cacheFile, resName, kBufLen);
    if (extName != NULL) {
        // TODO(srhines): strncat() is a bit dangerous
        strncat(cacheFile, extName, kBufLen);
    }

    // Turn the path into a flat filename by replacing
    // any slashes after the first one with '@' characters.
    char *cp = cacheFile + 1;
    while (*cp != '\0') {
        if (*cp == '/') {
            *cp = '@';
        }
        cp++;
    }

    // Tack on the file name for the actual cache file path.
    strncpy(cachePath, cacheDir, kBufLen);
    strncat(cachePath, cacheFile, kBufLen);

    LOGV("Cache file for '%s' '%s' is '%s'\n", resName, extName, cachePath);
    return strdup(cachePath);
}

bool rsdScriptInit(const Context *rsc,
                     ScriptC *script,
                     char const *resName,
                     char const *cacheDir,
                     uint8_t const *bitcode,
                     size_t bitcodeSize,
                     uint32_t flags,
                     RsHalSymbolLookupFunc lookupFunc) {
    //LOGE("rsdScriptCreate %p %p %p %p %i %i %p", rsc, resName, cacheDir, bitcode, bitcodeSize, flags, lookupFunc);

    char *cachePath = NULL;
    uint32_t objectSlotCount = 0;

    DrvScript *drv = (DrvScript *)calloc(1, sizeof(DrvScript));
    if (drv == NULL) {
        return false;
    }
    script->mHal.drv = drv;

    drv->mBccScript = bccCreateScript();
    script->mHal.info.isThreadable = true;
    drv->mScriptText = bitcode;
    drv->mScriptTextLength = bitcodeSize;

    //LOGE("mBccScript %p", script->mBccScript);

    if (bccRegisterSymbolCallback(drv->mBccScript, lookupFunc, script) != 0) {
        LOGE("bcc: FAILS to register symbol callback");
        goto error;
    }

    if (bccReadBC(drv->mBccScript,
                  resName,
                  (char const *)drv->mScriptText,
                  drv->mScriptTextLength, 0) != 0) {
        LOGE("bcc: FAILS to read bitcode");
        return NULL;
    }

#if 1
    if (bccLinkFile(drv->mBccScript, "/system/lib/libclcore.bc", 0) != 0) {
        LOGE("bcc: FAILS to link bitcode");
        return NULL;
    }
#endif
    cachePath = genCacheFileName(cacheDir, resName, ".oBCC");

    if (bccPrepareExecutable(drv->mBccScript, cachePath, 0) != 0) {
        LOGE("bcc: FAILS to prepare executable");
        return NULL;
    }

    free(cachePath);

    drv->mRoot = reinterpret_cast<int (*)()>(bccGetFuncAddr(drv->mBccScript, "root"));
    drv->mInit = reinterpret_cast<void (*)()>(bccGetFuncAddr(drv->mBccScript, "init"));

    drv->mInvokeFunctionCount = bccGetExportFuncCount(drv->mBccScript);
    if (drv->mInvokeFunctionCount <= 0)
        drv->mInvokeFunctions = NULL;
    else {
        drv->mInvokeFunctions = (InvokeFunc_t*) calloc(drv->mInvokeFunctionCount, sizeof(InvokeFunc_t));
        bccGetExportFuncList(drv->mBccScript, drv->mInvokeFunctionCount, (void **) drv->mInvokeFunctions);
    }

    drv->mFieldCount = bccGetExportVarCount(drv->mBccScript);
    if (drv->mFieldCount <= 0) {
        drv->mFieldAddress = NULL;
        drv->mFieldIsObject = NULL;
    } else {
        drv->mFieldAddress = (void **) calloc(drv->mFieldCount, sizeof(void *));
        drv->mFieldIsObject = (bool *) calloc(drv->mFieldCount, sizeof(bool));
        bccGetExportVarList(drv->mBccScript, drv->mFieldCount, (void **) drv->mFieldAddress);
    }

    objectSlotCount = bccGetObjectSlotCount(drv->mBccScript);
    if (objectSlotCount) {
        uint32_t * slots = new uint32_t[objectSlotCount];
        bccGetObjectSlotList(drv->mBccScript, objectSlotCount, slots);
        for (uint32_t ct=0; ct < objectSlotCount; ct++) {
            drv->mFieldIsObject[slots[ct]] = true;
        }
        delete [] slots;
    }

    uint32_t mPragmaCount;
    const char ** mPragmaKeys;
    const char ** mPragmaValues;

    const static int pragmaMax = 16;
    drv->mPragmaCount = bccGetPragmaCount(drv->mBccScript);
    if (drv->mPragmaCount <= 0) {
        drv->mPragmaKeys = NULL;
        drv->mPragmaValues = NULL;
    } else {
        drv->mPragmaKeys = (const char **) calloc(drv->mFieldCount, sizeof(const char *));
        drv->mPragmaValues = (const char **) calloc(drv->mFieldCount, sizeof(const char *));
        bccGetPragmaList(drv->mBccScript, drv->mPragmaCount, drv->mPragmaKeys, drv->mPragmaValues);
    }



    // Copy info over to runtime
    script->mHal.info.exportedFunctionCount = drv->mInvokeFunctionCount;
    script->mHal.info.exportedVariableCount = drv->mFieldCount;
    script->mHal.info.exportedPragmaCount = drv->mPragmaCount;
    script->mHal.info.exportedPragmaKeyList = drv->mPragmaKeys;
    script->mHal.info.exportedPragmaValueList = drv->mPragmaValues;
    script->mHal.info.root = drv->mRoot;


    return true;

error:

    free(drv);
    return false;

}


int rsdScriptInvokeRoot(const Context *dc, const Script *script) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;
    return drv->mRoot();
}

void rsdScriptInvokeInit(const Context *dc, const Script *script) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;

    if (drv->mInit) {
        drv->mInit();
    }
}


void rsdScriptInvokeFunction(const Context *dc, const Script *script,
                            uint32_t slot,
                            const void *params,
                            size_t paramLength) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;
    //LOGE("invoke %p %p %i %p %i", dc, script, slot, params, paramLength);

    ((void (*)(const void *, uint32_t))
        drv->mInvokeFunctions[slot])(params, paramLength);
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

    rsiSetObject((ObjectBase **)destPtr, data);
}

void rsdScriptDestroy(const Context *dc, Script *script) {
    DrvScript *drv = (DrvScript *)script->mHal.drv;

    if (drv->mFieldAddress) {
        for (size_t ct=0; ct < drv->mFieldCount; ct++) {
            if (drv->mFieldIsObject[ct]) {
                rsiClearObject((ObjectBase **)&drv->mFieldAddress[ct]);
            }
        }
        delete [] drv->mFieldAddress;
        delete [] drv->mFieldIsObject;
        drv->mFieldAddress = NULL;
        drv->mFieldIsObject = NULL;
        drv->mFieldCount = 0;
    }

    if (drv->mInvokeFunctions) {
        delete [] drv->mInvokeFunctions;
        drv->mInvokeFunctions = NULL;
        drv->mInvokeFunctionCount = 0;
    }
    free(drv);
    script->mHal.drv = NULL;

}


