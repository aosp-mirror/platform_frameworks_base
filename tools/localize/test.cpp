#include "SourcePos.h"
#include <stdio.h>

int ValuesFile_test();
int XLIFFFile_test();
int XMLHandler_test();
int Perforce_test();
int localize_test();
int merge_test();

int
test()
{
    bool all = true;
    int err = 0;

    if (all) err |= XMLHandler_test();
    if (all) err |= ValuesFile_test();
    if (all) err |= XLIFFFile_test();
    if (all) err |= Perforce_test();
    if (all) err |= localize_test();
    if (all) err |= merge_test();

    if (err != 0) {
        fprintf(stderr, "some tests failed\n");
    } else {
        fprintf(stderr, "all tests passed\n");
    }

    return err;
}
