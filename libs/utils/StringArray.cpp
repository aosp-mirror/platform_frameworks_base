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

//
// Sortable array of strings.  STL-ish, but STL-free.
//  

#include <stdlib.h>
#include <string.h>

#include <utils/StringArray.h>

namespace android {

//
// An expanding array of strings.  Add, get, sort, delete.
//
StringArray::StringArray()
    : mMax(0), mCurrent(0), mArray(NULL)
{
}

StringArray:: ~StringArray() {
    for (int i = 0; i < mCurrent; i++)
        delete[] mArray[i];
    delete[] mArray;
}

//
// Add a string.  A copy of the string is made.
//
bool StringArray::push_back(const char* str) {
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
void StringArray::erase(int idx) {
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
void StringArray::sort(int (*compare)(const void*, const void*)) {
    qsort(mArray, mCurrent, sizeof(char*), compare);
}

//
// Pass this to the sort routine to do an ascending alphabetical sort.
//
int StringArray::cmpAscendingAlpha(const void* pstr1, const void* pstr2) {
    return strcmp(*(const char**)pstr1, *(const char**)pstr2);
}

//
// Set entry N to specified string.
// [should use operator[] here]
//
void StringArray::setEntry(int idx, const char* str) {
    if (idx < 0 || idx >= mCurrent)
        return;
    delete[] mArray[idx];
    int len = strlen(str);
    mArray[idx] = new char[len+1];
    memcpy(mArray[idx], str, len+1);
}


}; // namespace android
