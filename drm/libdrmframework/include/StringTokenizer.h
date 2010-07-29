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

#ifndef __STRING_TOKENIZER_H__
#define __STRING_TOKENIZER_H__

#include <drm/drm_framework_common.h>

namespace android {

/**
 * This is an utility class for String manipulation.
 *
 */
class StringTokenizer {
public:
    /**
     * Iterator for string tokens
     */
    class Iterator {
        friend class StringTokenizer;
    private:
        Iterator(StringTokenizer* StringTokenizer)
         : mStringTokenizer(StringTokenizer), mIndex(0) {}

    public:
        Iterator(const Iterator& iterator);
        Iterator& operator=(const Iterator& iterator);
        virtual ~Iterator() {}

    public:
        bool hasNext();
        String8& next();

    private:
        StringTokenizer* mStringTokenizer;
        unsigned int mIndex;
    };

public:
    /**
     * Constructor for StringTokenizer
     *
     * @param[in] string Complete string data
     * @param[in] delimeter Delimeter used to split the string
     */
    StringTokenizer(const String8& string, const String8& delimeter);

    /**
     * Destructor for StringTokenizer
     */
    ~StringTokenizer() {}

private:
    /**
     * Splits the string according to the delimeter
     */
    void splitString(const String8& string, const String8& delimeter);

public:
    /**
     * Returns Iterator object to walk through the split string values
     *
     * @return Iterator object
     */
    Iterator iterator();

private:
    Vector<String8> mStringTokenizerVector;
};

};
#endif /* __STRING_TOKENIZER_H__ */

