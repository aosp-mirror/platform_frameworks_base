//
// Copyright 2006 The Android Open Source Project
//
// Some global defines that don't really merit their own header.
//
#ifndef __MAIN_H
#define __MAIN_H

#include <utils.h>
#include "Bundle.h"
#include "AaptAssets.h"
#include <utils/ZipFile.h>

extern int doVersion(Bundle* bundle);
extern int doList(Bundle* bundle);
extern int doDump(Bundle* bundle);
extern int doAdd(Bundle* bundle);
extern int doRemove(Bundle* bundle);
extern int doPackage(Bundle* bundle);

extern int calcPercent(long uncompressedLen, long compressedLen);

extern android::status_t writeAPK(Bundle* bundle,
    const sp<AaptAssets>& assets,
    const android::String8& outputFile);

extern android::status_t buildResources(Bundle* bundle,
    const sp<AaptAssets>& assets);

extern android::status_t writeResourceSymbols(Bundle* bundle,
    const sp<AaptAssets>& assets, const String8& pkgName, bool includePrivate);

extern bool isValidResourceType(const String8& type);

ssize_t processAssets(Bundle* bundle, ZipFile* zip, const sp<AaptAssets>& assets);

extern status_t filterResources(Bundle* bundle, const sp<AaptAssets>& assets);

int dumpResources(Bundle* bundle);

#endif // __MAIN_H
