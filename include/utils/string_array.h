/*
 * Copyright (C) 2005 The Android Open Source Project
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
// Sortable array of strings.  STL-ish, but STL-free.
//  
#ifndef _LIBS_UTILS_STRING_ARRAY_H
#define _LIBS_UTILS_STRING_ARRAY_H

#include <stdlib.h>
#include <string.h>

namespace android {

//
// An expanding array of strings.  Add, get, sort, delete.
//
class StringArray {
public:
    StringArray()
        : mMax(0), mCurrent(0), mArray(NULL)
        {}
    virtual ~StringArray() {
        for (int i = 0; i < mCurrent; i++)
            delete[] mArray[i];
        delete[] mArray;
    }

    //
    // Add a string.  A copy of the string is made.
    //
    bool push_back(const char* str) {
        if (mCurrent >= mMax) {
            char** tmp;

            if (mMax == 0)
                mMax = 16;      // initial storage
            else
                mMax *= 2;

            tmp = new char*[mMax];
            if (tmp == NULL)
                return false;

            memcpy(tmp, mArray, mCurrent * sizeof(char*));
            delete[] mArray;
            mArray = tmp;
        }

        int len = strlen(str);
        mArray[mCurrent] = new char[len+1];
        memcpy(mArray[mCurrent], str, len+1);
        mCurrent++;

        return true;
    }

    //
    // Delete an entry.
    //
    void erase(int idx) {
        if (idx < 0 || idx >= mCurrent)
            return;
        delete[] mArray[idx];
        if (idx < mCurrent-1) {
            memmove(&mArray[idx], &mArray[idx+1],
                (mCurrent-1 - idx) * sizeof(char*));
        }
        mCurrent--;
    }

    //
    // Sort the array.
    //
    void sort(int (*compare)(const void*, const void*)) {
        qsort(mArray, mCurrent, sizeof(char*), compare);
    }

    //
    // Pass this to the sort routine to do an ascending alphabetical sort.
    //
    static int cmpAscendingAlpha(const void* pstr1, const void* pstr2) {
        return strcmp(*(const char**)pstr1, *(const char**)pstr2);
    }

    //
    // Get the #of items in the array.
    //
    inline int size(void) const { return mCurrent; }

    //
    // Return entry N.
    // [should use operator[] here]
    //
    const char* getEntry(int idx) const {
        if (idx < 0 || idx >= mCurrent)
            return NULL;
        return mArray[idx];
    }

    //
    // Set entry N to specified string.
    // [should use operator[] here]
    //
    void setEntry(int idx, const char* str) {
        if (idx < 0 || idx >= mCurrent)
            return;
        delete[] mArray[idx];
        int len = strlen(str);
        mArray[idx] = new char[len+1];
        memcpy(mArray[idx], str, len+1);
    }

private:
    int     mMax;
    int     mCurrent;
    char**  mArray;
};

}; // namespace android

#endif // _LIBS_UTILS_STRING_ARRAY_H
