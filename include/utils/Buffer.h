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

#ifndef __UTILS_BUFFER_H__
#define __UTILS_BUFFER_H__ 1

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

namespace android {

class Buffer
{
private:
    char *buf;
    int bufsiz;
    int used;
    void ensureCapacity(int len);

    void
    makeRoomFor(int len)
    {
        if (len + used >= bufsiz) {
            bufsiz = (len + used) * 3/2 + 2;
            char *blah = new char[bufsiz];

            memcpy(blah, buf, used);
            delete[] buf;
            buf = blah;
        }
    }
    
public:
    Buffer()
    {
        bufsiz = 16;
        buf = new char[bufsiz];
        clear();
    }

    ~Buffer()
    {
       delete[] buf;
    }

    void
    clear()
    {
        buf[0] = '\0';
        used = 0;
    }

    int
    length()
    {
        return used;
    }

    void
    append(const char c)
    {
        makeRoomFor(1);
        buf[used] = c;
        used++;
        buf[used] = '\0';
    }

    void
    append(const char *s, int len)
    {
        makeRoomFor(len);

        memcpy(buf + used, s, len);
        used += len;
        buf[used] = '\0';
    }

    void
    append(const char *s)
    {
        append(s, strlen(s));
    }

    char *
    getBytes()
    {
        return buf;
    }
};

}; // namespace android

#endif
