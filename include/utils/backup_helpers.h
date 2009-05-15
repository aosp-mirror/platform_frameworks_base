#ifndef _UTILS_BACKUP_HELPERS_H
#define _UTILS_BACKUP_HELPERS_H

int back_up_files(int oldSnapshotFD, int oldDataStream, int newSnapshotFD,
        char const* fileBase, char const* const* files, int fileCount);

#define TEST_BACKUP_HELPERS 0

#if TEST_BACKUP_HELPERS
int backup_helper_test_empty();
int backup_helper_test_four();
int backup_helper_test_files();
#endif

#endif // _UTILS_BACKUP_HELPERS_H
