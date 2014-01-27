#include <utils/ResourceTypes.h>
#include <utils/String8.h>
#include <utils/String16.h>
#include <zipfile/zipfile.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>

using namespace android;

static int
usage()
{
    fprintf(stderr,
            "usage: apk APKFILE\n"
            "\n"
            "APKFILE   an android packge file produced by aapt.\n"
            );
    return 1;
}


int
main(int argc, char** argv)
{
    const char* filename;
    int fd;
    ssize_t amt;
    off_t size;
    void* buf;
    zipfile_t zip;
    zipentry_t entry;
    void* cookie;
    void* resfile;
    int bufsize;
    int err;

    if (argc != 2) {
        return usage();
    }

    filename = argv[1];
    fd = open(filename, O_RDONLY);
    if (fd == -1) {
        fprintf(stderr, "apk: couldn't open file for read: %s\n", filename);
        return 1;
    }

    size = lseek(fd, 0, SEEK_END);
    amt = lseek(fd, 0, SEEK_SET);

    if (size < 0 || amt < 0) {
        fprintf(stderr, "apk: error determining file size: %s\n", filename);
        return 1;
    }

    buf = malloc(size);
    if (buf == NULL) {
        fprintf(stderr, "apk: file too big: %s\n", filename);
        return 1;
    }

    amt = read(fd, buf, size);
    if (amt != size) {
        fprintf(stderr, "apk: error reading file: %s\n", filename);
        return 1;
    }

    close(fd);

    zip = init_zipfile(buf, size);
    if (zip == NULL) {
        fprintf(stderr, "apk: file doesn't seem to be a zip file: %s\n",
                filename);
        return 1;
    }

    printf("files:\n");
    cookie = NULL;
    while ((entry = iterate_zipfile(zip, &cookie))) {
        char* name = get_zipentry_name(entry);
        printf("  %s\n", name);
        free(name);
    }

    entry = lookup_zipentry(zip, "resources.arsc");
    if (entry != NULL) {
        size = get_zipentry_size(entry);
        bufsize = size + (size / 1000) + 1;
        resfile = malloc(bufsize);

        err = decompress_zipentry(entry, resfile, bufsize);
        if (err != 0) {
            fprintf(stderr, "apk: error decompressing resources.arsc");
            return 1;
        }

        ResTable res(resfile, size, resfile);
        res.print();
#if 0
        size_t tableCount = res.getTableCount();
        printf("Tables: %d\n", (int)tableCount);
        for (size_t tableIndex=0; tableIndex<tableCount; tableIndex++) {
            const ResStringPool* strings = res.getTableStringBlock(tableIndex);
            size_t stringCount = strings->size();
            for (size_t stringIndex=0; stringIndex<stringCount; stringIndex++) {
                size_t len;
                const char16_t* ch = strings->stringAt(stringIndex, &len);
                String8 s(String16(ch, len));
                printf("  [%3d] %s\n", (int)stringIndex, s.string());
            }
        }

        size_t basePackageCount = res.getBasePackageCount();
        printf("Base Packages: %d\n", (int)basePackageCount);
        for (size_t bpIndex=0; bpIndex<basePackageCount; bpIndex++) {
            const char16_t* ch = res.getBasePackageName(bpIndex);
            String8 s = String8(String16(ch));
            printf("  [%3d] %s\n", (int)bpIndex, s.string());
        }
#endif
    }


    return 0;
}
