//
// Copyright 2006 The Android Open Source Project
//
// Some global defines that don't really merit their own header.
//
#ifndef __MAIN_H
#define __MAIN_H

#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Errors.h>
#include <utils/StrongPointer.h>

#include "AaptAssets.h"
#include "ApkBuilder.h"
#include "Bundle.h"
#include "ResourceFilter.h"
#include "ZipFile.h"


/* Benchmarking Flag */
//#define BENCHMARK 1

#if BENCHMARK
    #include <time.h>
#endif /* BENCHMARK */

class OutputSet;

extern int doVersion(Bundle* bundle);
extern int doList(Bundle* bundle);
extern int doDump(Bundle* bundle);
extern int doAdd(Bundle* bundle);
extern int doRemove(Bundle* bundle);
extern int doPackage(Bundle* bundle);
extern int doCrunch(Bundle* bundle);
extern int doSingleCrunch(Bundle* bundle);

extern int calcPercent(long uncompressedLen, long compressedLen);

extern android::status_t writeAPK(Bundle* bundle,
    const android::String8& outputFile,
    const android::sp<OutputSet>& outputSet);

extern android::status_t updatePreProcessedCache(Bundle* bundle);

extern android::status_t buildResources(Bundle* bundle,
    const sp<AaptAssets>& assets, sp<ApkBuilder>& builder);

extern android::status_t writeResourceSymbols(Bundle* bundle,
        const sp<AaptAssets>& assets, const String8& pkgName,
        bool includePrivate, bool emitCallback);

extern android::status_t writeProguardFile(Bundle* bundle, const sp<AaptAssets>& assets);

extern bool isValidResourceType(const String8& type);

extern status_t filterResources(Bundle* bundle, const sp<AaptAssets>& assets);

int dumpResources(Bundle* bundle);

String8 getAttribute(const ResXMLTree& tree, const char* ns,
                            const char* attr, String8* outError);

status_t writeDependencyPreReqs(Bundle* bundle, const sp<AaptAssets>& assets,
                                FILE* fp, bool includeRaw);
#endif // __MAIN_H
