
#include <utils/backup_helpers.h>
#include <utils/String8.h>

#include <fcntl.h>
#include <stdio.h>
#include <string.h>

using namespace android;

#include <unistd.h>

int
usage(int argc, const char** argv)
{
    const char* p = argv[0];

    fprintf(stderr, "%s: Backs up your data.\n"
                    "\n"
                    "usage: %s\n"
                    "  Prints all of the data that can be backed up to stdout.\n"
                    "\n"
                    "usage: %s list FILE\n"
                    "  Lists the backup entities in the file.\n"
                    "\n"
                    "usage: %s print NAME FILE\n"
                    "  Prints the entity named NAME in FILE.\n",
                    p, p, p, p);
    return 1;
}

int
perform_full_backup()
{
    printf("this would have written all of your data to stdout\n");
    return 0;
}

int
perform_list(const char* filename)
{
    int err;
    int fd;

    fd = open(filename, O_RDONLY);
    if (fd == -1) {
        fprintf(stderr, "Error opening: %s\n", filename);
        return 1;
    }

    BackupDataReader reader(fd);
    int type;

    while (reader.ReadNextHeader(&type) == 0) {
        switch (type) {
            case BACKUP_HEADER_APP_V1:
            {
                String8 packageName;
                int cookie;
                err = reader.ReadAppHeader(&packageName, &cookie);
                if (err == 0) {
                    printf("App header: %s 0x%08x (%d)\n", packageName.string(), cookie, cookie);
                } else {
                    printf("Error reading app header\n");
                }
                break;
            }
            case BACKUP_HEADER_ENTITY_V1:
            {
                String8 key;
                size_t dataSize;
                err = reader.ReadEntityHeader(&key, &dataSize);
                if (err == 0) {
                    printf("   entity: %s (%d bytes)\n", key.string(), dataSize);
                } else {
                    printf("   Error reading entity header\n");
                }
                break;
            }
            case BACKUP_FOOTER_APP_V1:
            {
                int cookie;
                err = reader.ReadAppFooter(&cookie);
                if (err == 0) {
                    printf("   App footer: 0x%08x (%d)\n", cookie, cookie);
                } else {
                    printf("   Error reading entity header\n");
                }
                break;
            }
            default:
            {
                printf("Unknown chunk type: 0x%08x\n", type);
                break;
            }
        }
    }

    return 0;
}

int perform_print(const char* entityname, const char* filename)
{
    printf("perform_print(%s, %s);", entityname, filename);
    return 0;
}

int
main(int argc, const char** argv)
{
    if (argc <= 1) {
        return perform_full_backup();
    }
    if (argc == 3 && 0 == strcmp(argv[1], "list")) {
        return perform_list(argv[2]);
    }
    if (argc == 4 && 0 == strcmp(argv[1], "print")) {
        return perform_print(argv[2], argv[3]);
    }
    return usage(argc, argv);
}

