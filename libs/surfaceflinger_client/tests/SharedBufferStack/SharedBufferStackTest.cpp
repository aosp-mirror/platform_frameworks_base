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

void log(const char* prefix, int *b, size_t num);
void test0(SharedBufferServer& s, SharedBufferClient& c, size_t num, int* list);

// ----------------------------------------------------------------------------

int main(int argc, char** argv)
{
    SharedClient client;
    SharedBufferServer s(&client, 0, 4, 0);
    SharedBufferClient c(&client, 0, 4, 0);

    printf("basic test 0\n");
    int list0[4] = {0, 1, 2, 3};
    test0(s, c, 4, list0);

    printf("basic test 1\n");
    int list1[4] = {2, 1, 0, 3};
    test0(s, c, 4, list1);

    int b = c.dequeue();
    c.lock(b);
    c.queue(b);
    s.retireAndLock();

    printf("basic test 2\n");
    int list2[4] = {1, 2, 3, 0};
    test0(s, c, 4, list2);


    printf("resize test\n");
    class SetBufferCountIPC : public SharedBufferClient::SetBufferCountCallback {
        SharedBufferServer& s;
        virtual status_t operator()(int bufferCount) const {
            return s.resize(bufferCount);
        }
    public:
        SetBufferCountIPC(SharedBufferServer& s) : s(s) { }
    } resize(s);

    c.setBufferCount(6, resize);
    int list3[6] = {3, 2, 1, 4, 5, 0};
    test0(s, c, 6, list3);

    return 0;
}

void log(const char* prefix, int *b, size_t num)
{
    printf("%s: ", prefix);
    for (size_t i=0 ; i<num ; i++) {
        printf("%d ", b[i]);
    }
    printf("\n");
}

// ----------------------------------------------------------------------------

void test0(
        SharedBufferServer& s,
        SharedBufferClient& c,
        size_t num,
        int* list)
{
    status_t err;
    int b[num], u[num], r[num];

    for (size_t i=0 ; i<num ; i++) {
        b[i] = c.dequeue();
        assert(b[i]==list[i]);
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
        assert(r[i]==list[i]);
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
    assert(r[num-1]==list[num-1]);
    err = s.unlock(r[num-1]);
    assert(err == 0);
    log("RT", r+num-1, 1);

    // ------------------------------------
    printf("\n");

    for (size_t i=0 ; i<num ; i++) {
        b[i] = c.dequeue();
        assert(b[i]==list[i]);
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
    u[num-1] = b[num-1];

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
    assert(r[num-1]==list[num-1]);
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
    assert(r[num-1]==u[num-1]);
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
    assert(r[num-1]==u[num-1]);
    err = s.unlock(r[num-1]);
    assert(err == 0);
    log("RT", r+num-1, 1);
    printf("\n");
}
