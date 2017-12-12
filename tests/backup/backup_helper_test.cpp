/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include <androidfw/BackupHelpers.h>

#include <stdio.h>
#include <string.h>

using namespace android;

#if TEST_BACKUP_HELPERS

// ============================================================
// ============================================================
typedef int (*test_func)();

struct Test {
    const char* name;
    test_func func;
    int result;
    bool run;
};

Test TESTS[] = {
    { "backup_helper_test_empty", backup_helper_test_empty, 0, false },
    { "backup_helper_test_four", backup_helper_test_four, 0, false },
    { "backup_helper_test_files", backup_helper_test_files, 0, false },
    { "backup_helper_test_null_base", backup_helper_test_null_base, 0, false },
    { "backup_helper_test_missing_file", backup_helper_test_missing_file, 0, false },
    { "backup_helper_test_data_writer", backup_helper_test_data_writer, 0, false },
    { "backup_helper_test_data_reader", backup_helper_test_data_reader, 0, false },
    { 0, NULL, 0, false}
};

int
main(int argc, const char** argv)
{
    Test* t;

    if (argc == 1) {
        t = TESTS;
        while (t->name) {
            t->run = true;
            t++;
        }
    } else {
        t = TESTS;
        while (t->name) {
            for (int i=1; i<argc; i++) {
                if (0 == strcmp(t->name, argv[i])) {
                    t->run = true;
                }
            }
            t++;
        }
    }

    int testCount = 0;
    t = TESTS;
    while (t->name) {
        if (t->run) {
            testCount++;
        }
        t++;
    }


    int failed = 0;
    int i = 1;
    t = TESTS;
    while (t->name) {
        if (t->run) {
            printf("===== Running %s (%d of %d) ==============================\n",
                    t->name, i, testCount);
            fflush(stdout);
            fflush(stderr);
            t->result = t->func();
            if (t->result != 0) {
                failed++;
                printf("failed\n");
            } else {
                printf("passed\n");
            }
            i++;
        }
        t++;
    }

    printf("=================================================================\n");
    if (failed == 0) {
        printf("All %d test(s) passed\n", testCount);
    } else {
        printf("Tests failed: (%d of %d)\n", failed, testCount);
        t = TESTS;
        while (t->name) {
            if (t->run) {
                if (t->result != 0) {
                    printf("  %s\n", t->name);
                }
            }
            t++;
        }
    }
}

#else
int
main(int, char**)
{
    printf ("test_backup_helper built without the tests\n");
    return 0;
}
#endif
