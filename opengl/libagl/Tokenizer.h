/* libs/opengles/Tokenizer.h
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/


#ifndef ANDROID_OPENGLES_TOKENIZER_H
#define ANDROID_OPENGLES_TOKENIZER_H

#include <utils/Vector.h>
#include <utils/Errors.h>

// ----------------------------------------------------------------------------

namespace android {

class Tokenizer
{
public:
                Tokenizer();
                Tokenizer(const Tokenizer& other);
                ~Tokenizer();

    uint32_t    acquire();
    status_t    reserve(uint32_t token);
    status_t    release(uint32_t token);
    bool        isAcquired(uint32_t token) const;

    void dump() const;

    struct run_t {
        run_t() {};
        run_t(uint32_t f, uint32_t l) : first(f), length(l) {}
        uint32_t    first;
        uint32_t    length;
    };
private:
    ssize_t _indexOrderOf(uint32_t token, size_t* order=0) const;
    ssize_t _insertTokenAt(uint32_t token, size_t index);
    Vector<run_t>   mRanges;
};

}; // namespace android

// ----------------------------------------------------------------------------

#endif // ANDROID_OPENGLES_TOKENIZER_H
