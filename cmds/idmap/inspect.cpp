#include "idmap.h"

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <utils/String8.h>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>

using namespace android;

namespace {
    static const uint32_t IDMAP_MAGIC = 0x504D4449;
    static const size_t PATH_LENGTH = 256;

    void printe(const char *fmt, ...);

    class IdmapBuffer {
        private:
            const char* buf_;
            size_t len_;
            size_t pos_;
        public:
            IdmapBuffer() : buf_((const char *)MAP_FAILED), len_(0), pos_(0) {}

            ~IdmapBuffer() {
                if (buf_ != MAP_FAILED) {
                    munmap(const_cast<char*>(buf_), len_);
                }
            }

            status_t init(const char *idmap_path) {
                struct stat st;
                int fd;

                if (stat(idmap_path, &st) < 0) {
                    printe("failed to stat idmap '%s': %s\n", idmap_path, strerror(errno));
                    return UNKNOWN_ERROR;
                }
                len_ = st.st_size;
                if ((fd = TEMP_FAILURE_RETRY(open(idmap_path, O_RDONLY))) < 0) {
                    printe("failed to open idmap '%s': %s\n", idmap_path, strerror(errno));
                    return UNKNOWN_ERROR;
                }
                if ((buf_ = (const char*)mmap(NULL, len_, PROT_READ, MAP_PRIVATE, fd, 0)) == MAP_FAILED) {
                    close(fd);
                    printe("failed to mmap idmap: %s\n", strerror(errno));
                    return UNKNOWN_ERROR;
                }
                close(fd);
                return NO_ERROR;
            }

            status_t nextUint32(uint32_t* i) {
                if (!buf_) {
                    printe("failed to read next uint32_t: buffer not initialized\n");
                    return UNKNOWN_ERROR;
                }

                if (pos_ + sizeof(uint32_t) > len_) {
                    printe("failed to read next uint32_t: end of buffer reached at pos=0x%08x\n",
                            pos_);
                    return UNKNOWN_ERROR;
                }

                if ((reinterpret_cast<uintptr_t>(buf_ + pos_) & 0x3) != 0) {
                    printe("failed to read next uint32_t: not aligned on 4-byte boundary\n");
                    return UNKNOWN_ERROR;
                }

                *i = dtohl(*reinterpret_cast<const uint32_t*>(buf_ + pos_));
                pos_ += sizeof(uint32_t);
                return NO_ERROR;
            }

            status_t nextUint16(uint16_t* i) {
                if (!buf_) {
                    printe("failed to read next uint16_t: buffer not initialized\n");
                    return UNKNOWN_ERROR;
                }

                if (pos_ + sizeof(uint16_t) > len_) {
                    printe("failed to read next uint16_t: end of buffer reached at pos=0x%08x\n",
                            pos_);
                    return UNKNOWN_ERROR;
                }

                if ((reinterpret_cast<uintptr_t>(buf_ + pos_) & 0x1) != 0) {
                    printe("failed to read next uint32_t: not aligned on 2-byte boundary\n");
                    return UNKNOWN_ERROR;
                }

                *i = dtohs(*reinterpret_cast<const uint16_t*>(buf_ + pos_));
                pos_ += sizeof(uint16_t);
                return NO_ERROR;
            }

            status_t nextPath(char *b) {
                if (!buf_) {
                    printe("failed to read next path: buffer not initialized\n");
                    return UNKNOWN_ERROR;
                }
                if (pos_ + PATH_LENGTH > len_) {
                    printe("failed to read next path: end of buffer reached at pos=0x%08x\n", pos_);
                    return UNKNOWN_ERROR;
                }
                memcpy(b, buf_ + pos_, PATH_LENGTH);
                pos_ += PATH_LENGTH;
                return NO_ERROR;
            }
    };

    void printe(const char *fmt, ...) {
        va_list ap;

        va_start(ap, fmt);
        fprintf(stderr, "error: ");
        vfprintf(stderr, fmt, ap);
        va_end(ap);
    }

    void print_header() {
        printf("SECTION      ENTRY        VALUE      COMMENT\n");
    }

    void print(const char *section, const char *subsection, uint32_t value, const char *fmt, ...) {
        va_list ap;

        va_start(ap, fmt);
        printf("%-12s %-12s 0x%08x ", section, subsection, value);
        vprintf(fmt, ap);
        printf("\n");
        va_end(ap);
    }

    void print_path(const char *section, const char *subsection, const char *fmt, ...) {
        va_list ap;

        va_start(ap, fmt);
        printf("%-12s %-12s .......... ", section, subsection);
        vprintf(fmt, ap);
        printf("\n");
        va_end(ap);
    }

    status_t resource_metadata(const AssetManager& am, uint32_t res_id,
            String8 *package, String8 *type, String8 *name) {
        const ResTable& rt = am.getResources();
        struct ResTable::resource_name data;
        if (!rt.getResourceName(res_id, false, &data)) {
            printe("failed to get resource name id=0x%08x\n", res_id);
            return UNKNOWN_ERROR;
        }
        if (package != NULL) {
            *package = String8(String16(data.package, data.packageLen));
        }
        if (type != NULL) {
            *type = String8(String16(data.type, data.typeLen));
        }
        if (name != NULL) {
            *name = String8(String16(data.name, data.nameLen));
        }
        return NO_ERROR;
    }

    status_t parse_idmap_header(IdmapBuffer& buf, AssetManager& am) {
        uint32_t i;
        char path[PATH_LENGTH];

        status_t err = buf.nextUint32(&i);
        if (err != NO_ERROR) {
            return err;
        }

        if (i != IDMAP_MAGIC) {
            printe("not an idmap file: actual magic constant 0x%08x does not match expected magic "
                    "constant 0x%08x\n", i, IDMAP_MAGIC);
            return UNKNOWN_ERROR;
        }

        print_header();
        print("IDMAP HEADER", "magic", i, "");

        err = buf.nextUint32(&i);
        if (err != NO_ERROR) {
            return err;
        }
        print("", "version", i, "");

        err = buf.nextUint32(&i);
        if (err != NO_ERROR) {
            return err;
        }
        print("", "base crc", i, "");

        err = buf.nextUint32(&i);
        if (err != NO_ERROR) {
            return err;
        }
        print("", "overlay crc", i, "");

        err = buf.nextPath(path);
        if (err != NO_ERROR) {
            // printe done from IdmapBuffer::nextPath
            return err;
        }
        print_path("", "base path", "%s", path);

        if (!am.addAssetPath(String8(path), NULL)) {
            printe("failed to add '%s' as asset path\n", path);
            return UNKNOWN_ERROR;
        }

        err = buf.nextPath(path);
        if (err != NO_ERROR) {
            // printe done from IdmapBuffer::nextPath
            return err;
        }
        print_path("", "overlay path", "%s", path);

        return NO_ERROR;
    }

    status_t parse_data(IdmapBuffer& buf, const AssetManager& am) {
        const uint32_t packageId = am.getResources().getBasePackageId(0);

        uint16_t data16;
        status_t err = buf.nextUint16(&data16);
        if (err != NO_ERROR) {
            return err;
        }
        print("DATA HEADER", "target pkg", static_cast<uint32_t>(data16), "");

        err = buf.nextUint16(&data16);
        if (err != NO_ERROR) {
            return err;
        }
        print("", "types count", static_cast<uint32_t>(data16), "");

        uint32_t typeCount = static_cast<uint32_t>(data16);
        while (typeCount > 0) {
            typeCount--;

            err = buf.nextUint16(&data16);
            if (err != NO_ERROR) {
                return err;
            }
            const uint32_t targetTypeId = static_cast<uint32_t>(data16);
            print("DATA BLOCK", "target type", targetTypeId, "");

            err = buf.nextUint16(&data16);
            if (err != NO_ERROR) {
                return err;
            }
            print("", "overlay type", static_cast<uint32_t>(data16), "");

            err = buf.nextUint16(&data16);
            if (err != NO_ERROR) {
                return err;
            }
            const uint32_t entryCount = static_cast<uint32_t>(data16);
            print("", "entry count", entryCount, "");

            err = buf.nextUint16(&data16);
            if (err != NO_ERROR) {
                return err;
            }
            const uint32_t entryOffset = static_cast<uint32_t>(data16);
            print("", "entry offset", entryOffset, "");

            for (uint32_t i = 0; i < entryCount; i++) {
                uint32_t data32;
                err = buf.nextUint32(&data32);
                if (err != NO_ERROR) {
                    return err;
                }

                uint32_t resID = (packageId << 24) | (targetTypeId << 16) | (entryOffset + i);
                String8 type;
                String8 name;
                err = resource_metadata(am, resID, NULL, &type, &name);
                if (err != NO_ERROR) {
                    return err;
                }
                print("", "entry", data32, "%s/%s", type.string(), name.string());
            }
        }

        return NO_ERROR;
    }
}

int idmap_inspect(const char *idmap_path) {
    IdmapBuffer buf;
    if (buf.init(idmap_path) < 0) {
        // printe done from IdmapBuffer::init
        return EXIT_FAILURE;
    }
    AssetManager am;
    if (parse_idmap_header(buf, am) != NO_ERROR) {
        // printe done from parse_idmap_header
        return EXIT_FAILURE;
    }
    if (parse_data(buf, am) != NO_ERROR) {
        // printe done from parse_data_header
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
