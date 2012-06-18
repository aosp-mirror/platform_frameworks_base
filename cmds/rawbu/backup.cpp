// Copyright 2009 The Android Open Source Project

#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <dirent.h>
#include <errno.h>
#include <assert.h>
#include <ctype.h>
#include <utime.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <stdint.h>

#include <cutils/properties.h>

#include <private/android_filesystem_config.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

// First version.
#define FILE_VERSION_1 0xffff0001

// Introduces backup all option to header.
#define FILE_VERSION_2 0xffff0002

#define FILE_VERSION FILE_VERSION_2

namespace android {

static char nameBuffer[PATH_MAX];
static struct stat statBuffer;

static char copyBuffer[8192];
static char *backupFilePath = NULL;

static uint32_t inputFileVersion;

static int opt_backupAll;

#define SPECIAL_NO_TOUCH 0
#define SPECIAL_NO_BACKUP 1

struct special_dir {
    const char* path;
    int type;
};

/* Directory paths that we will not backup/restore */
static const struct special_dir SKIP_PATHS[] = {
    { "/data/misc", SPECIAL_NO_TOUCH },
    { "/data/system/batterystats.bin", SPECIAL_NO_TOUCH },
    { "/data/system/location", SPECIAL_NO_TOUCH },
    { "/data/dalvik-cache", SPECIAL_NO_BACKUP },
    { NULL, 0 },
};

/* This is just copied from the shell's built-in wipe command. */
static int wipe (const char *path) 
{
    DIR *dir;
    struct dirent *de;
    int ret;
    int i;

    dir = opendir(path);

    if (dir == NULL) {
        fprintf (stderr, "Error opendir'ing %s: %s\n",
                    path, strerror(errno));
        return 0;
    }

    char *filenameOffset;

    strcpy(nameBuffer, path);
    strcat(nameBuffer, "/");

    filenameOffset = nameBuffer + strlen(nameBuffer);

    for (;;) {
        de = readdir(dir);

        if (de == NULL) {
            break;
        }

        if (0 == strcmp(de->d_name, ".")
                || 0 == strcmp(de->d_name, "..")
                || 0 == strcmp(de->d_name, "lost+found")
        ) {
            continue;
        }

        strcpy(filenameOffset, de->d_name);
        bool noBackup = false;
        
        /* See if this is a path we should skip. */
        for (i = 0; SKIP_PATHS[i].path; i++) {
            if (strcmp(SKIP_PATHS[i].path, nameBuffer) == 0) {
                if (opt_backupAll || SKIP_PATHS[i].type == SPECIAL_NO_BACKUP) {
                    // In this case we didn't back up the directory --
                    // we do want to wipe its contents, but not the
                    // directory itself, since the restore file won't
                    // contain the directory.
                    noBackup = true;
                }
                break;
            }
        }
        
        if (!noBackup && SKIP_PATHS[i].path != NULL) {
            // This is a SPECIAL_NO_TOUCH directory.
            continue;
        }

        ret = lstat (nameBuffer, &statBuffer);

        if (ret != 0) {
            fprintf(stderr, "warning -- stat() error on '%s': %s\n", 
                    nameBuffer, strerror(errno));
            continue;
        }

        if(S_ISDIR(statBuffer.st_mode)) {
            int i;
            char *newpath;

            newpath = strdup(nameBuffer);
            if (wipe(newpath) == 0) {
                free(newpath);
                closedir(dir);
                return 0;
            }
            
            if (!noBackup) {
                ret = rmdir(newpath);
                if (ret != 0) {
                    fprintf(stderr, "warning -- rmdir() error on '%s': %s\n", 
                        newpath, strerror(errno));
                }
            }

            free(newpath);

            strcpy(nameBuffer, path);
            strcat(nameBuffer, "/");

        } else {
            // Don't delete the backup file
            if (backupFilePath && strcmp(backupFilePath, nameBuffer) == 0) {
                continue;
            }
            ret = unlink(nameBuffer);

            if (ret != 0) {
                fprintf(stderr, "warning -- unlink() error on '%s': %s\n", 
                    nameBuffer, strerror(errno));
            }
        }
    }

    closedir(dir);
    
    return 1;
}

static int write_int32(FILE* fh, int32_t val)
{
    int res = fwrite(&val, 1, sizeof(val), fh);
    if (res != sizeof(val)) {
        fprintf(stderr, "unable to write int32 (%d bytes): %s\n", res, strerror(errno));
        return 0;
    }
    
    return 1;
}

static int write_int64(FILE* fh, int64_t val)
{
    int res = fwrite(&val, 1, sizeof(val), fh); 
    if (res != sizeof(val)) {
        fprintf(stderr, "unable to write int64 (%d bytes): %s\n", res, strerror(errno));
        return 0;
    }
    
    return 1;
}

static int copy_file(FILE* dest, FILE* src, off_t size, const char* destName,
        const char* srcName)
{
    errno = 0;
    
    off_t origSize = size;
    
    while (size > 0) {
        int amt = size > (off_t)sizeof(copyBuffer) ? sizeof(copyBuffer) : (int)size;
        int readLen = fread(copyBuffer, 1, amt, src);
        if (readLen <= 0) {
            if (srcName != NULL) {
                fprintf(stderr, "unable to read source (%d of %ld bytes) file '%s': %s\n",
                    amt, origSize, srcName, errno != 0 ? strerror(errno) : "unexpected EOF");
            } else {
                fprintf(stderr, "unable to read buffer (%d of %ld bytes): %s\n",
                    amt, origSize, errno != 0 ? strerror(errno) : "unexpected EOF");
            }
            return 0;
        }
        int writeLen = fwrite(copyBuffer, 1, readLen, dest); 
        if (writeLen != readLen) {
            if (destName != NULL) {
                fprintf(stderr, "unable to write file (%d of %d bytes) '%s': '%s'\n",
                    writeLen, readLen, destName, strerror(errno));
            } else {
                fprintf(stderr, "unable to write buffer (%d of %d bytes): '%s'\n",
                    writeLen, readLen, strerror(errno));
            }
            return 0;
        }
        size -= readLen;
    }
    return 1;
}

#define TYPE_END 0
#define TYPE_DIR 1
#define TYPE_FILE 2

static int write_header(FILE* fh, int type, const char* path, const struct stat* st)
{
    int pathLen = strlen(path);
    if (!write_int32(fh, type)) return 0;
    if (!write_int32(fh, pathLen)) return 0;
    if (fwrite(path, 1, pathLen, fh) != (size_t)pathLen) {
        fprintf(stderr, "unable to write: %s\n", strerror(errno));
        return 0;
    }
    
    if (!write_int32(fh, st->st_uid)) return 0;
    if (!write_int32(fh, st->st_gid)) return 0;
    if (!write_int32(fh, st->st_mode)) return 0;
    if (!write_int64(fh, ((int64_t)st->st_atime)*1000*1000*1000)) return 0;
    if (!write_int64(fh, ((int64_t)st->st_mtime)*1000*1000*1000)) return 0;
    if (!write_int64(fh, ((int64_t)st->st_ctime)*1000*1000*1000)) return 0;
    
    return 1;
}

static int backup_dir(FILE* fh, const char* srcPath)
{
    DIR *dir;
    struct dirent *de;
    char* fullPath = NULL;
    int srcLen = strlen(srcPath);
    int result = 1;
    int i;
    
    dir = opendir(srcPath);

    if (dir == NULL) {
        fprintf (stderr, "error opendir'ing '%s': %s\n",
                    srcPath, strerror(errno));
        return 0;
    }
    
    for (;;) {
        de = readdir(dir);

        if (de == NULL) {
            break;
        }

        if (0 == strcmp(de->d_name, ".")
                || 0 == strcmp(de->d_name, "..")
                || 0 == strcmp(de->d_name, "lost+found")
        ) {
            continue;
        }

        if (fullPath != NULL) {
            free(fullPath);
        }
        fullPath = (char*)malloc(srcLen + strlen(de->d_name) + 2);
        strcpy(fullPath, srcPath);
        fullPath[srcLen] = '/';
        strcpy(fullPath+srcLen+1, de->d_name);

        /* See if this is a path we should skip. */
        if (!opt_backupAll) {
            for (i = 0; SKIP_PATHS[i].path; i++) {
                if (strcmp(SKIP_PATHS[i].path, fullPath) == 0) {
                    break;
                }
            }
            if (SKIP_PATHS[i].path != NULL) {
                continue;
            }
        }

        int ret = lstat(fullPath, &statBuffer);

        if (ret != 0) {
            fprintf(stderr, "stat() error on '%s': %s\n", 
                    fullPath, strerror(errno));
            result = 0;
            goto done;
        }

        if(S_ISDIR(statBuffer.st_mode)) {
            printf("Saving dir %s...\n", fullPath);
            
            if (write_header(fh, TYPE_DIR, fullPath, &statBuffer) == 0) {
                result = 0;
                goto done;
            }
            if (backup_dir(fh, fullPath) == 0) {
                result = 0;
                goto done;
            }
        } else if (S_ISREG(statBuffer.st_mode)) {
            // Skip the backup file
            if (backupFilePath && strcmp(fullPath, backupFilePath) == 0) {
                printf("Skipping backup file %s...\n", backupFilePath);
                continue;
            } else {
                printf("Saving file %s...\n", fullPath);
            }
            if (write_header(fh, TYPE_FILE, fullPath, &statBuffer) == 0) {
                result = 0;
                goto done;
            }
            
            off_t size = statBuffer.st_size;
            if (!write_int64(fh, size)) {
                result = 0;
                goto done;
            }
            
            FILE* src = fopen(fullPath, "r");
            if (src == NULL) {
                fprintf(stderr, "unable to open source file '%s': %s\n",
                    fullPath, strerror(errno));
                result = 0;
                goto done;
            }
            
            int copyres = copy_file(fh, src, size, NULL, fullPath);
            fclose(src);
            if (!copyres) {
                result = 0;
                goto done;
            }
        }
    }

done:
    if (fullPath != NULL) {
        free(fullPath);
    }
    
    closedir(dir);
    
    return result;
}

static int backup_data(const char* destPath)
{
    int res = -1;
    
    FILE* fh = fopen(destPath, "w");
    if (fh == NULL) {
        fprintf(stderr, "unable to open destination '%s': %s\n",
                destPath, strerror(errno));
        return -1;
    }
    
    printf("Backing up /data to %s...\n", destPath);

    // The path that shouldn't be backed up
    backupFilePath = strdup(destPath);

    if (!write_int32(fh, FILE_VERSION)) goto done;
    if (!write_int32(fh, opt_backupAll)) goto done;
    if (!backup_dir(fh, "/data")) goto done;
    if (!write_int32(fh, 0)) goto done;
    
    res = 0;
    
done:
    if (fflush(fh) != 0) {
        fprintf(stderr, "error flushing destination '%s': %s\n",
            destPath, strerror(errno));
        res = -1;
        goto donedone;
    }
    if (fsync(fileno(fh)) != 0) {
        fprintf(stderr, "error syncing destination '%s': %s\n",
            destPath, strerror(errno));
        res = -1;
        goto donedone;
    }
    fclose(fh);
    sync();

donedone:    
    return res;
}

static int32_t read_int32(FILE* fh, int32_t defVal)
{
    int32_t val;
    if (fread(&val, 1, sizeof(val), fh) != sizeof(val)) {
        fprintf(stderr, "unable to read: %s\n", strerror(errno));
        return defVal;
    }
    
    return val;
}

static int64_t read_int64(FILE* fh, int64_t defVal)
{
    int64_t val;
    if (fread(&val, 1, sizeof(val), fh) != sizeof(val)) {
        fprintf(stderr, "unable to read: %s\n", strerror(errno));
        return defVal;
    }
    
    return val;
}

static int read_header(FILE* fh, int* type, char** path, struct stat* st)
{
    *type = read_int32(fh, -1);
    if (*type == TYPE_END) {
        return 1;
    }
    
    if (*type < 0) {
        fprintf(stderr, "bad token %d in restore file\n", *type);
        return 0;
    }
    
    int32_t pathLen = read_int32(fh, -1);
    if (pathLen <= 0) {
        fprintf(stderr, "bad path length %d in restore file\n", pathLen);
        return 0;
    }
    char* readPath = (char*)malloc(pathLen+1);
    if (fread(readPath, 1, pathLen, fh) != (size_t)pathLen) {
        fprintf(stderr, "truncated path in restore file\n");
        free(readPath);
        return 0;
    }
    readPath[pathLen] = 0;
    *path = readPath;
    
    st->st_uid = read_int32(fh, -1);
    if (st->st_uid == (uid_t)-1) {
        fprintf(stderr, "bad uid in restore file at '%s'\n", readPath);
        return 0;
    }
    st->st_gid = read_int32(fh, -1);
    if (st->st_gid == (gid_t)-1) {
        fprintf(stderr, "bad gid in restore file at '%s'\n", readPath);
        return 0;
    }
    st->st_mode = read_int32(fh, -1);
    if (st->st_mode == (mode_t)-1) {
        fprintf(stderr, "bad mode in restore file at '%s'\n", readPath);
        return 0;
    }
    int64_t ltime = read_int64(fh, -1);
    if (ltime < 0) {
        fprintf(stderr, "bad atime in restore file at '%s'\n", readPath);
        return 0;
    }
    st->st_atime = (time_t)(ltime/1000/1000/1000);
    ltime = read_int64(fh, -1);
    if (ltime < 0) {
        fprintf(stderr, "bad mtime in restore file at '%s'\n", readPath);
        return 0;
    }
    st->st_mtime = (time_t)(ltime/1000/1000/1000);
    ltime = read_int64(fh, -1);
    if (ltime < 0) {
        fprintf(stderr, "bad ctime in restore file at '%s'\n", readPath);
        return 0;
    }
    st->st_ctime = (time_t)(ltime/1000/1000/1000);
    
    st->st_mode &= (S_IRWXU|S_IRWXG|S_IRWXO);
    
    return 1;
}

static int restore_data(const char* srcPath)
{
    int res = -1;
    
    FILE* fh = fopen(srcPath, "r");
    if (fh == NULL) {
        fprintf(stderr, "Unable to open source '%s': %s\n",
                srcPath, strerror(errno));
        return -1;
    }
    
    inputFileVersion = read_int32(fh, 0);
    if (inputFileVersion < FILE_VERSION_1 || inputFileVersion > FILE_VERSION) {
        fprintf(stderr, "Restore file has bad version: 0x%x\n", inputFileVersion);
        goto done;
    }
    
    if (inputFileVersion >= FILE_VERSION_2) {
        opt_backupAll = read_int32(fh, 0);
    } else {
        opt_backupAll = 0;
    }

    // The path that shouldn't be deleted
    backupFilePath = strdup(srcPath);
    
    printf("Wiping contents of /data...\n");
    if (!wipe("/data")) {
        goto done;
    }

    printf("Restoring from %s to /data...\n", srcPath);

    while (1) {
        int type;
        char* path = NULL;
        if (read_header(fh, &type, &path, &statBuffer) == 0) {
            goto done;
        }
        if (type == 0) {
            break;
        }
        
        const char* typeName = "?";
        
        if (type == TYPE_DIR) {
            typeName = "dir";
            
            printf("Restoring dir %s...\n", path);
            
            if (mkdir(path, statBuffer.st_mode) != 0) {
                if (errno != EEXIST) {
                    fprintf(stderr, "unable to create directory '%s': %s\n",
                        path, strerror(errno));
                    free(path);
                    goto done;
                }
            }
            
        } else if (type == TYPE_FILE) {
            typeName = "file";
            off_t size = read_int64(fh, -1);
            if (size < 0) {
                fprintf(stderr, "bad file size %ld in restore file\n", size);
                free(path);
                goto done;
            }
            
            printf("Restoring file %s...\n", path);
            
            FILE* dest = fopen(path, "w");
            if (dest == NULL) {
                fprintf(stderr, "unable to open destination file '%s': %s\n",
                    path, strerror(errno));
                free(path);
                goto done;
            }
            
            int copyres = copy_file(dest, fh, size, path, NULL);
            fclose(dest);
            if (!copyres) {
                free(path);
                goto done;
            }
        
        } else {
            fprintf(stderr, "unknown node type %d\n", type);
            goto done;
        }
        
        // Do this even for directories, since the dir may have already existed
        // so we need to make sure it gets the correct mode.    
        if (chmod(path, statBuffer.st_mode&(S_IRWXU|S_IRWXG|S_IRWXO)) != 0) {
            fprintf(stderr, "unable to chmod destination %s '%s' to 0x%x: %s\n",
                typeName, path, statBuffer.st_mode, strerror(errno));
            free(path);
            goto done;
        }
        
        if (chown(path, statBuffer.st_uid, statBuffer.st_gid) != 0) {
            fprintf(stderr, "unable to chown destination %s '%s' to uid %d / gid %d: %s\n",
                typeName, path, (int)statBuffer.st_uid, (int)statBuffer.st_gid, strerror(errno));
            free(path);
            goto done;
        }
        
        struct utimbuf timbuf;
        timbuf.actime = statBuffer.st_atime;
        timbuf.modtime = statBuffer.st_mtime;
        if (utime(path, &timbuf) != 0) {
            fprintf(stderr, "unable to utime destination %s '%s': %s\n",
                typeName, path, strerror(errno));
            free(path);
            goto done;
        }
        
        
        free(path);
    }
    
    res = 0;
        
done:    
    fclose(fh);
    
    return res;
}

static void show_help(const char *cmd)
{
    fprintf(stderr,"Usage: %s COMMAND [options] [backup-file-path]\n", cmd);

    fprintf(stderr, "commands are:\n"
                    "  help            Show this help text.\n"
                    "  backup          Perform a backup of /data.\n"
                    "  restore         Perform a restore of /data.\n");
    fprintf(stderr, "options include:\n"
                    "  -h              Show this help text.\n"
                    "  -a              Backup all files.\n");
    fprintf(stderr, "\nThe %s command allows you to perform low-level\n"
                    "backup and restore of the /data partition.  This is\n"
                    "where all user data is kept, allowing for a fairly\n"
                    "complete restore of a device's state.  Note that\n"
                    "because this is low-level, it will only work across\n"
                    "builds of the same (or very similar) device software.\n",
                    cmd);
}

} /* namespace android */

int main (int argc, char **argv)
{
    int restore = 0;

    if (getuid() != AID_ROOT) {
        fprintf(stderr, "error -- %s must run as root\n", argv[0]);
        exit(-1);
    }
    
    if (argc < 2) {
        fprintf(stderr, "No command specified.\n");
        android::show_help(argv[0]);
        exit(-1);
    }

    if (0 == strcmp(argv[1], "restore")) {
        restore = 1;
    } else if (0 == strcmp(argv[1], "help")) {
        android::show_help(argv[0]);
        exit(0);
    } else if (0 != strcmp(argv[1], "backup")) {
        fprintf(stderr, "Unknown command: %s\n", argv[1]);
        android::show_help(argv[0]);
        exit(-1);
    }

    android::opt_backupAll = 0;
                
    optind = 2;
    
    for (;;) {
        int ret;

        ret = getopt(argc, argv, "ah");

        if (ret < 0) {
            break;
        }

        switch(ret) {
            case 'a':
                android::opt_backupAll = 1;
                if (restore) fprintf(stderr, "Warning: -a option ignored on restore\n");
                break;
            case 'h':
                android::show_help(argv[0]);
                exit(0);
            break;

            default:
                fprintf(stderr,"Unrecognized Option\n");
                android::show_help(argv[0]);
                exit(-1);
            break;
        }
    }

    const char* backupFile = "/sdcard/backup.dat";
    
    if (argc > optind) {
        backupFile = argv[optind];
        optind++;
        if (argc != optind) {
            fprintf(stderr, "Too many arguments\n");
            android::show_help(argv[0]);
            exit(-1);
        }
    }
    
    printf("Stopping system...\n");
    property_set("ctl.stop", "runtime");
    property_set("ctl.stop", "zygote");
    sleep(1);
    
    int res;
    if (restore) {
        res = android::restore_data(backupFile);
        if (res != 0) {
            // Don't restart system, since the data partition is hosed.
            return res;
        }
        printf("Restore complete!  Restarting system, cross your fingers...\n");
    } else {
        res = android::backup_data(backupFile);
        if (res == 0) {
            printf("Backup complete!  Restarting system...\n");
        } else {
            printf("Restarting system...\n");
        }
    }
    
    property_set("ctl.start", "zygote");
    property_set("ctl.start", "runtime");
}
