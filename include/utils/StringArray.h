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
    StringArray();
    virtual ~StringArray();

    //
    // Add a string.  A copy of the string is made.
    //
    bool push_back(const char* str);

    //
    // Delete an entry.
    //
    void erase(int idx);

    //
    // Sort the array.
    //
    void sort(int (*compare)(const void*, const void*));
    
    //
    // Pass this to the sort routine to do an ascending alphabetical sort.
    //
    static int cmpAscendingAlpha(const void* pstr1, const void* pstr2);
    
    //
    // Get the #of items in the array.
    //
    inline int size(void) const { return mCurrent; }

    //
    // Return entry N.
    // [should use operator[] here]
    //
    const char* getEntry(int idx) const {
        return (unsigned(idx) >= unsigned(mCurrent)) ? NULL : mArray[idx];
    }

    //
    // Set entry N to specified string.
    // [should use operator[] here]
    //
    void setEntry(int idx, const char* str);

private:
    int     mMax;
    int     mCurrent;
    char**  mArray;
};

}; // namespace android

#endif // _LIBS_UTILS_STRING_ARRAY_H
