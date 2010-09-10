/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include "StringTokenizer.h"

using namespace android;

StringTokenizer::StringTokenizer(const String8& string, const String8& delimiter) {
    splitString(string, delimiter);
}

void StringTokenizer::splitString(const String8& string, const String8& delimiter) {
    for (unsigned int i = 0; i < string.length(); i++) {
        unsigned int position = string.find(delimiter.string(), i);
        if (string.length() != position) {
            String8 token(string.string()+i, position-i);
            if (token.length()) {
                mStringTokenizerVector.push(token);
                i = position + delimiter.length() - 1;
            }
        } else {
            mStringTokenizerVector.push(String8(string.string()+i, string.length()-i));
            break;
        }
    }
}

StringTokenizer::Iterator StringTokenizer::iterator() {
    return Iterator(this);
}

StringTokenizer::Iterator::Iterator(const StringTokenizer::Iterator& iterator) :
    mStringTokenizer(iterator.mStringTokenizer),
    mIndex(iterator.mIndex) {
    LOGV("StringTokenizer::Iterator::Iterator");
}

StringTokenizer::Iterator& StringTokenizer::Iterator::operator=(
            const StringTokenizer::Iterator& iterator) {
    LOGV("StringTokenizer::Iterator::operator=");
    mStringTokenizer = iterator.mStringTokenizer;
    mIndex = iterator.mIndex;
    return *this;
}

bool StringTokenizer::Iterator::hasNext() {
    return mIndex < mStringTokenizer->mStringTokenizerVector.size();
}

String8& StringTokenizer::Iterator::next() {
    String8& value = mStringTokenizer->mStringTokenizerVector.editItemAt(mIndex);
    mIndex++;
    return value;
}

