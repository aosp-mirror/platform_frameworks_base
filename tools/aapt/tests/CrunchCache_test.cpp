//
// Copyright 2011 The Android Open Source Project
//
#include <utils/String8.h>
#include <iostream>
#include <errno.h>

#include "CrunchCache.h"
#include "FileFinder.h"
#include "MockFileFinder.h"
#include "CacheUpdater.h"
#include "MockCacheUpdater.h"

using namespace android;
using std::cout;
using std::endl;

void expectEqual(int got, int expected, const char* desc) {
    cout << "Checking " << desc << ": ";
    cout << "Got " << got << ", expected " << expected << "...";
    cout << ( (got == expected) ? "PASSED" : "FAILED") << endl;
    errno += ((got == expected) ? 0 : 1);
}

int main() {

    errno = 0;

    String8 source("res");
    String8 dest("res2");

    // Create data for MockFileFinder to feed to the cache
    KeyedVector<String8, time_t> sourceData;
    // This shouldn't be updated
    sourceData.add(String8("res/drawable/hello.png"),3);
    // This should be updated
    sourceData.add(String8("res/drawable/world.png"),5);
    // This should cause make directory to be called
    sourceData.add(String8("res/drawable-cool/hello.png"),3);

    KeyedVector<String8, time_t> destData;
    destData.add(String8("res2/drawable/hello.png"),3);
    destData.add(String8("res2/drawable/world.png"),3);
    // this should call delete
    destData.add(String8("res2/drawable/dead.png"),3);

    // Package up data and create mock file finder
    KeyedVector<String8, KeyedVector<String8,time_t> > data;
    data.add(source,sourceData);
    data.add(dest,destData);
    FileFinder* ff = new MockFileFinder(data);
    CrunchCache cc(source,dest,ff);

    MockCacheUpdater* mcu = new MockCacheUpdater();
    CacheUpdater* cu(mcu);

    cout << "Running Crunch...";
    int result = cc.crunch(cu);
    cout << ((result > 0) ? "PASSED" : "FAILED") << endl;
    errno += ((result > 0) ? 0 : 1);

    const int EXPECTED_RESULT = 2;
    expectEqual(result, EXPECTED_RESULT, "number of files touched");

    cout << "Checking calls to deleteFile and processImage:" << endl;
    const int EXPECTED_DELETES = 1;
    const int EXPECTED_PROCESSED = 2;
    // Deletes
    expectEqual(mcu->deleteCount, EXPECTED_DELETES, "deleteFile");
    // processImage
    expectEqual(mcu->processCount, EXPECTED_PROCESSED, "processImage");

    const int EXPECTED_OVERWRITES = 3;
    result = cc.crunch(cu, true);
    expectEqual(result, EXPECTED_OVERWRITES, "number of files touched with overwrite");
    \

    if (errno == 0)
        cout << "ALL TESTS PASSED!" << endl;
    else
        cout << errno << " TESTS FAILED" << endl;

    delete ff;
    delete cu;

    // TESTS BELOW WILL GO AWAY SOON

    String8 source2("ApiDemos/res");
    String8 dest2("ApiDemos/res2");

    FileFinder* sff = new SystemFileFinder();
    CacheUpdater* scu = new SystemCacheUpdater();

    CrunchCache scc(source2,dest2,sff);

    scc.crunch(scu);
}