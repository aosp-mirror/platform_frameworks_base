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

#undef NDEBUG

#include <assert.h>
#include <cutils/memory.h>
#include <cutils/log.h>
#include <utils/Errors.h>
#include <private/surfaceflinger/SharedBufferStack.h>

using namespace android;


void log(const char* prefix, int *b, size_t num)
{
    printf("%s: ", prefix);
    for (size_t i=0 ; i<num ; i++) {
        printf("%d ", b[i]);
    }
    printf("\n");
}

int main(int argc, char** argv)
{
    status_t err;
    const size_t num = 4;
    SharedClient client;
    SharedBufferServer s(&client, 0, num, 0);
    SharedBufferClient c(&client, 0, num, 0);
    int b[num], u[num], r[num];

    for (size_t i=0 ; i<num ; i++) {
        b[i] = c.dequeue();
        assert(b[i]==i);
    }
    log("DQ", b, num);

    for (size_t i=0 ; i<num-1 ; i++) {
        err = c.lock(b[i]);
        assert(err==0);
    }
    log("LK", b, num-1);

    for (size_t i=0 ; i<num-1 ; i++) {
        err = c.queue(b[i]);
        assert(err==0);
    }
    log(" Q", b, num-1);


    for (size_t i=0 ; i<num-1 ; i++) {
        r[i] = s.retireAndLock();
        assert(r[i]==i);
        err = s.unlock(r[i]);
        assert(err == 0);
    }
    log("RT", r, num-1);

    err = c.lock(b[num-1]);
    assert(err == 0);
    log("LK", b+num-1, 1);

    err = c.queue(b[num-1]);
    assert(err == 0);
    log(" Q", b+num-1, 1);

    r[num-1] = s.retireAndLock();
    assert(r[num-1]==num-1);
    err = s.unlock(r[num-1]);
    assert(err == 0);
    log("RT", r+num-1, 1);

    // ------------------------------------
    printf("\n");

    for (size_t i=0 ; i<num ; i++) {
        b[i] = c.dequeue();
        assert(b[i]==i);
    }
    log("DQ", b, num);

    for (size_t i=0 ; i<num-1 ; i++) {
        err = c.lock(b[i]);
        assert(err==0);
    }
    log("LK", b, num-1);

    for (size_t i=0 ; i<num-1 ; i++) {
        u[i] = b[num-2-i];
    }
    u[num-1] = num-1;

    for (size_t i=0 ; i<num-1 ; i++) {
        err = c.queue(u[i]);
        assert(err==0);
    }
    log(" Q", u, num-1);

    for (size_t i=0 ; i<num-1 ; i++) {
        r[i] = s.retireAndLock();
        assert(r[i]==u[i]);
        err = s.unlock(r[i]);
        assert(err == 0);
    }
    log("RT", r, num-1);

    err = c.lock(b[num-1]);
    assert(err == 0);
    log("LK", b+num-1, 1);

    err = c.queue(b[num-1]);
    assert(err == 0);
    log(" Q", b+num-1, 1);

    r[num-1] = s.retireAndLock();
    assert(r[num-1]==num-1);
    err = s.unlock(r[num-1]);
    assert(err == 0);
    log("RT", r+num-1, 1);

    // ------------------------------------
    printf("\n");

    for (size_t i=0 ; i<num ; i++) {
        b[i] = c.dequeue();
        assert(b[i]==u[i]);
    }
    log("DQ", b, num);

    for (size_t i=0 ; i<num-1 ; i++) {
        err = c.lock(b[i]);
        assert(err==0);
    }
    log("LK", b, num-1);

    for (size_t i=0 ; i<num-1 ; i++) {
        err = c.queue(b[i]);
        assert(err==0);
    }
    log(" Q", b, num-1);

    for (size_t i=0 ; i<num-1 ; i++) {
        r[i] = s.retireAndLock();
        assert(r[i]==u[i]);
        err = s.unlock(r[i]);
        assert(err == 0);
    }
    log("RT", r, num-1);

    err = c.lock(u[num-1]);
    assert(err == 0);
    log("LK", u+num-1, 1);

    err = c.queue(u[num-1]);
    assert(err == 0);
    log(" Q", u+num-1, 1);

    r[num-1] = s.retireAndLock();
    assert(r[num-1]==num-1);
    err = s.unlock(r[num-1]);
    assert(err == 0);
    log("RT", r+num-1, 1);

    // ------------------------------------
    printf("\n");

    b[0] = c.dequeue();
    assert(b[0]==u[0]);
    log("DQ", b, 1);

    c.undoDequeue(b[0]);
    assert(err == 0);
    log("UDQ", b, 1);

    // ------------------------------------
    printf("\n");

    for (size_t i=0 ; i<num ; i++) {
        b[i] = c.dequeue();
        assert(b[i]==u[i]);
    }
    log("DQ", b, num);

    for (size_t i=0 ; i<num-1 ; i++) {
        err = c.lock(b[i]);
        assert(err==0);
    }
    log("LK", b, num-1);

    for (size_t i=0 ; i<num-1 ; i++) {
        err = c.queue(b[i]);
        assert(err==0);
    }
    log(" Q", b, num-1);

    for (size_t i=0 ; i<num-1 ; i++) {
        r[i] = s.retireAndLock();
        assert(r[i]==u[i]);
        err = s.unlock(r[i]);
        assert(err == 0);
    }
    log("RT", r, num-1);

    err = c.lock(u[num-1]);
    assert(err == 0);
    log("LK", u+num-1, 1);

    err = c.queue(u[num-1]);
    assert(err == 0);
    log(" Q", u+num-1, 1);

    r[num-1] = s.retireAndLock();
    assert(r[num-1]==num-1);
    err = s.unlock(r[num-1]);
    assert(err == 0);
    log("RT", r+num-1, 1);

    return 0;
}
