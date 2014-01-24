/*
 * Copyright (C) 2006 The Android Open Source Project
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
// Provide access to a virtual directory in "asset space".  Most of the
// implementation is in the header file or in friend functions in
// AssetManager.
//
#include <androidfw/AssetDir.h>

using namespace android;


/*
 * Find a matching entry in a vector of FileInfo.  Because it's sorted, we
 * can use a binary search.
 *
 * Assumes the vector is sorted in ascending order.
 */
/*static*/ int AssetDir::FileInfo::findEntry(const SortedVector<FileInfo>* pVector,
    const String8& fileName)
{
    FileInfo tmpInfo;

    tmpInfo.setFileName(fileName);
    return pVector->indexOf(tmpInfo);

#if 0  // don't need this after all (uses 1/2 compares of SortedVector though)
    int lo, hi, cur;

    lo = 0;
    hi = pVector->size() -1;
    while (lo <= hi) {
        int cmp;

        cur = (hi + lo) / 2;
        cmp = strcmp(pVector->itemAt(cur).getFileName(), fileName);
        if (cmp == 0) {
            /* match, bail */
            return cur;
        } else if (cmp < 0) {
            /* too low */
            lo = cur + 1;
        } else {
            /* too high */
            hi = cur -1;
        }
    }

    return -1;
#endif
}

