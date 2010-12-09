/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include <utils/ZipFileCRO.h>
#include <utils/ZipFileRO.h>

using namespace android;

ZipFileCRO ZipFileXRO_open(const char* path) {
    ZipFileRO* zip = new ZipFileRO();
    if (zip->open(path) == NO_ERROR) {
        return (ZipFileCRO)zip;
    }
    return NULL;
}

void ZipFileCRO_destroy(ZipFileCRO zipToken) {
    ZipFileRO* zip = (ZipFileRO*)zipToken;
    delete zip;
}

ZipEntryCRO ZipFileCRO_findEntryByName(ZipFileCRO zipToken,
        const char* fileName) {
    ZipFileRO* zip = (ZipFileRO*)zipToken;
    return (ZipEntryCRO)zip->findEntryByName(fileName);
}

bool ZipFileCRO_getEntryInfo(ZipFileCRO zipToken, ZipEntryRO entryToken,
        int* pMethod, size_t* pUncompLen,
        size_t* pCompLen, off64_t* pOffset, long* pModWhen, long* pCrc32) {
    ZipFileRO* zip = (ZipFileRO*)zipToken;
    ZipEntryRO entry = (ZipEntryRO)entryToken;
    return zip->getEntryInfo(entry, pMethod, pUncompLen, pCompLen, pOffset,
            pModWhen, pCrc32);
}

bool ZipFileCRO_uncompressEntry(ZipFileCRO zipToken, ZipEntryRO entryToken, int fd) {
    ZipFileRO* zip = (ZipFileRO*)zipToken;
    ZipEntryRO entry = (ZipEntryRO)entryToken;
    return zip->uncompressEntry(entry, fd);
}
