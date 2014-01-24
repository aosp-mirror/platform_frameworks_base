//
// Copyright 2011 The Android Open Source Project
//
#include <utils/Vector.h>
#include <utils/KeyedVector.h>
#include <iostream>
#include <cassert>
#include <utils/String8.h>
#include <utility>

#include "DirectoryWalker.h"
#include "MockDirectoryWalker.h"
#include "FileFinder.h"

using namespace android;

using std::pair;
using std::cout;
using std::endl;



int main()
{

    cout << "\n\n STARTING FILE FINDER TESTS" << endl;
    String8 path("ApiDemos");

    // Storage to pass to findFiles()
    KeyedVector<String8,time_t> testStorage;

    // Mock Directory Walker initialization. First data, then sdw
    Vector< pair<String8,time_t> > data;
    data.push( pair<String8,time_t>(String8("hello.png"),3) );
    data.push( pair<String8,time_t>(String8("world.PNG"),3) );
    data.push( pair<String8,time_t>(String8("foo.pNg"),3) );
    // Neither of these should be found
    data.push( pair<String8,time_t>(String8("hello.jpg"),3) );
    data.push( pair<String8,time_t>(String8(".hidden.png"),3));

    DirectoryWalker* sdw = new StringDirectoryWalker(path,data);

    // Extensions to look for
    Vector<String8> exts;
    exts.push(String8(".png"));

    errno = 0;

    // Make sure we get a valid mock directory walker
    // Make sure we finish without errors
    cout << "Checking DirectoryWalker...";
    assert(sdw != NULL);
    cout << "PASSED" << endl;

    // Make sure we finish without errors
    cout << "Running findFiles()...";
    bool findStatus = FileFinder::findFiles(path,exts, testStorage, sdw);
    assert(findStatus);
    cout << "PASSED" << endl;

    const size_t SIZE_EXPECTED = 3;
    // Check to make sure we have the right number of things in our storage
    cout << "Running size comparison: Size is " << testStorage.size() << ", ";
    cout << "Expected " << SIZE_EXPECTED << "...";
    if(testStorage.size() == SIZE_EXPECTED)
        cout << "PASSED" << endl;
    else {
        cout << "FAILED" << endl;
        errno++;
    }

    // Check to make sure that each of our found items has the right extension
    cout << "Checking Returned Extensions...";
    bool extsOkay = true;
    String8 wrongExts;
    for (size_t i = 0; i < SIZE_EXPECTED; ++i) {
        String8 testExt(testStorage.keyAt(i).getPathExtension());
        testExt.toLower();
        if (testExt != ".png") {
            wrongExts += testStorage.keyAt(i);
            wrongExts += "\n";
            extsOkay = false;
        }
    }
    if (extsOkay)
        cout << "PASSED" << endl;
    else {
        cout << "FAILED" << endl;
        cout << "The following extensions didn't check out" << endl << wrongExts;
    }

    // Clean up
    delete sdw;

    if(errno == 0) {
        cout << "ALL TESTS PASSED" << endl;
    } else {
        cout << errno << " TESTS FAILED" << endl;
    }
    return errno;
}