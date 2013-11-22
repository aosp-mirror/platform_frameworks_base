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

//
// Miscellaneous zip/gzip utility functions.
//
#ifndef __LIBS_ZIPUTILS_H
#define __LIBS_ZIPUTILS_H

#include <stdio.h>

namespace android {

/*
 * Container class for utility functions, primarily for namespace reasons.
 */
class ZipUtils {
public:
    /*
     * General utility function for uncompressing "deflate" data from a file
     * to a buffer.
     */
    static bool inflateToBuffer(int fd, void* buf, long uncompressedLen,
        long compressedLen);
    static bool inflateToBuffer(FILE* fp, void* buf, long uncompressedLen,
        long compressedLen);

    /*
     * Someday we might want to make this generic and handle bzip2 ".bz2"
     * files too.
     *
     * We could declare gzip to be a sub-class of zip that has exactly
     * one always-compressed entry, but we currently want to treat Zip
     * and gzip as distinct, so there's no value.
     *
     * The zlib library has some gzip utilities, but it has no interface
     * for extracting the uncompressed length of the file (you do *not*
     * want to gzseek to the end).
     *
     * Pass in a seeked file pointer for the gzip file.  If this is a gzip
     * file, we set our return values appropriately and return "true" with
     * the file seeked to the start of the compressed data.
     */
    static bool examineGzip(FILE* fp, int* pCompressionMethod,
        long* pUncompressedLen, long* pCompressedLen, unsigned long* pCRC32);

private:
    ZipUtils() {}
    ~ZipUtils() {}
};

}; // namespace android

#endif /*__LIBS_ZIPUTILS_H*/
