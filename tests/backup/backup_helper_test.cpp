#include <utils/backup_helpers.h>

#include <stdio.h>
#include <string.h>

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
main(int argc, char** argv)
{
    printf ("test_backup_helper built without the tests\n");
    return 0;
}
#endif
