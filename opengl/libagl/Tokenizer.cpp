/* libs/opengles/Tokenizer.cpp
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

#include <stdio.h>

#include "Tokenizer.h"

// ----------------------------------------------------------------------------

namespace android {

ANDROID_BASIC_TYPES_TRAITS(Tokenizer::run_t)

Tokenizer::Tokenizer()
{
}

Tokenizer::Tokenizer(const Tokenizer& other)
    : mRanges(other.mRanges)
{
}

Tokenizer::~Tokenizer()
{
}

uint32_t Tokenizer::acquire()
{
    if (!mRanges.size() || mRanges[0].first) {
        _insertTokenAt(0,0);
        return 0;
    }
    
    // just extend the first run
    const run_t& run = mRanges[0];
    uint32_t token = run.first + run.length;
    _insertTokenAt(token, 1);
    return token;
}

bool Tokenizer::isAcquired(uint32_t token) const
{
    return (_indexOrderOf(token) >= 0);
}

status_t Tokenizer::reserve(uint32_t token)
{
    size_t o;
    const ssize_t i = _indexOrderOf(token, &o);
    if (i >= 0) {
        return BAD_VALUE; // this token is already taken
    }
    ssize_t err = _insertTokenAt(token, o);
    return (err<0) ? err : status_t(NO_ERROR);
}

status_t Tokenizer::release(uint32_t token)
{
    const ssize_t i = _indexOrderOf(token);
    if (i >= 0) {
        const run_t& run = mRanges[i];
        if ((token >= run.first) && (token < run.first+run.length)) {
            // token in this range, we need to split
            run_t& run = mRanges.editItemAt(i);
            if ((token == run.first) || (token == run.first+run.length-1)) {
                if (token == run.first) {
                    run.first += 1;
                }
                run.length -= 1;
                if (run.length == 0) {
                    // XXX: should we systematically remove a run that's empty?
                    mRanges.removeItemsAt(i);
                }
            } else {
                // split the run
                run_t new_run;
                new_run.first = token+1;
                new_run.length = run.first+run.length - new_run.first;
                run.length = token - run.first;
                mRanges.insertAt(new_run, i+1);
            }
            return NO_ERROR;
        }
    }
    return NAME_NOT_FOUND;
}

ssize_t Tokenizer::_indexOrderOf(uint32_t token, size_t* order) const
{
    // binary search
    ssize_t err = NAME_NOT_FOUND;
    ssize_t l = 0;
    ssize_t h = mRanges.size()-1;
    ssize_t mid;
    const run_t* a = mRanges.array();
    while (l <= h) {
        mid = l + (h - l)/2;
        const run_t* const curr = a + mid;
        int c = 0;
        if (token < curr->first)                        c = 1;
        else if (token >= curr->first+curr->length)     c = -1;
        if (c == 0) {
            err = l = mid;
            break;
        } else if (c < 0) {
            l = mid + 1;
        } else {
            h = mid - 1;
        }
    }
    if (order) *order = l;
    return err;
}

ssize_t Tokenizer::_insertTokenAt(uint32_t token, size_t index)
{
    const size_t c = mRanges.size();

    if (index >= 1) {
        // do we need to merge with the previous run?
        run_t& p = mRanges.editItemAt(index-1);
        if (p.first+p.length == token) {
            p.length += 1;
            if (index < c) {
                const run_t& n = mRanges[index];
                if (token+1 == n.first) {
                    p.length += n.length;
                    mRanges.removeItemsAt(index);
                }
            }
            return index;
        }
    }
    
    if (index < c) {
        // do we need to merge with the next run?
        run_t& n = mRanges.editItemAt(index);
        if (token+1 == n.first) {
            n.first -= 1;
            n.length += 1;
            return index;
        }
    }

    return mRanges.insertAt(run_t(token,1), index);
}

void Tokenizer::dump() const
{
    const run_t* ranges = mRanges.array();
    const size_t c = mRanges.size();
    ALOGD("Tokenizer (%p, size = %u)\n", this, c);
    for (size_t i=0 ; i<c ; i++) {
        ALOGD("%u: (%u, %u)\n", i, ranges[i].first, ranges[i].length);
    }
}

}; // namespace android

