#include "idmap.h"

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <utils/String8.h>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>

using namespace android;

#define NEXT(b, i, o) do { if (buf.next(&i, &o) < 0) { return -1; } } while (0)

namespace {
    static const uint32_t IDMAP_MAGIC = 0x706d6469;
    static const size_t PATH_LENGTH = 256;
    static const uint32_t IDMAP_HEADER_SIZE = (3 + 2 * (PATH_LENGTH / sizeof(uint32_t)));

    void printe(const char *fmt, ...);

    class IdmapBuffer {
        private:
            char *buf_;
            size_t len_;
            mutable size_t pos_;
        public:
            IdmapBuffer() : buf_((char *)MAP_FAILED), len_(0), pos_(0) {}

            ~IdmapBuffer() {
                if (buf_ != MAP_FAILED) {
                    munmap(buf_, len_);
                }
            }

            int init(const char *idmap_path)
            {
                struct stat st;
                int fd;

                if (stat(idmap_path, &st) < 0) {
                    printe("failed to stat idmap '%s': %s\n", idmap_path, strerror(errno));
                    return -1;
                }
                len_ = st.st_size;
                if ((fd = TEMP_FAILURE_RETRY(open(idmap_path, O_RDONLY))) < 0) {
                    printe("failed to open idmap '%s': %s\n", idmap_path, strerror(errno));
                    return -1;
                }
                if ((buf_ = (char*)mmap(NULL, len_, PROT_READ, MAP_PRIVATE, fd, 0)) == MAP_FAILED) {
                    close(fd);
                    printe("failed to mmap idmap: %s\n", strerror(errno));
                    return -1;
                }
                close(fd);
                return 0;
            }

            int next(uint32_t *i, uint32_t *offset) const
            {
                if (!buf_) {
                    printe("failed to read next uint32_t: buffer not initialized\n");
                    return -1;
                }
                if (pos_ + 4 > len_) {
                    printe("failed to read next uint32_t: end of buffer reached at pos=0x%08x\n",
                            pos_);
                    return -1;
                }
                *offset = pos_ / sizeof(uint32_t);
                char a = buf_[pos_++];
                char b = buf_[pos_++];
                char c = buf_[pos_++];
                char d = buf_[pos_++];
                *i = (d << 24) | (c << 16) | (b << 8) | a;
                return 0;
            }

            int nextPath(char *b, uint32_t *offset_start, uint32_t *offset_end) const
            {
                if (!buf_) {
                    printe("failed to read next path: buffer not initialized\n");
                    return -1;
                }
                if (pos_ + PATH_LENGTH > len_) {
                    printe("failed to read next path: end of buffer reached at pos=0x%08x\n", pos_);
                    return -1;
                }
                memcpy(b, buf_ + pos_, PATH_LENGTH);
                *offset_start = pos_ / sizeof(uint32_t);
                pos_ += PATH_LENGTH;
                *offset_end = pos_ / sizeof(uint32_t) - 1;
                return 0;
            }
    };

    void printe(const char *fmt, ...)
    {
        va_list ap;

        va_start(ap, fmt);
        fprintf(stderr, "error: ");
        vfprintf(stderr, fmt, ap);
        va_end(ap);
    }

    void print_header()
    {
        printf("SECTION      ENTRY        VALUE      OFFSET    COMMENT\n");
    }

    void print(const char *section, const char *subsection, uint32_t value, uint32_t offset,
            const char *fmt, ...)
    {
        va_list ap;

        va_start(ap, fmt);
        printf("%-12s %-12s 0x%08x 0x%-4x    ", section, subsection, value, offset);
        vprintf(fmt, ap);
        printf("\n");
        va_end(ap);
    }

    void print_path(const char *section, const char *subsection, uint32_t offset_start,
            uint32_t offset_end, const char *fmt, ...)
    {
        va_list ap;

        va_start(ap, fmt);
        printf("%-12s %-12s .......... 0x%02x-0x%02x ", section, subsection, offset_start,
                offset_end);
        vprintf(fmt, ap);
        printf("\n");
        va_end(ap);
    }

    int resource_metadata(const AssetManager& am, uint32_t res_id,
            String8 *package, String8 *type, String8 *name)
    {
        const ResTable& rt = am.getResources();
        struct ResTable::resource_name data;
        if (!rt.getResourceName(res_id, false, &data)) {
            printe("failed to get resource name id=0x%08x\n", res_id);
            return -1;
        }
        if (package) {
            *package = String8(String16(data.package, data.packageLen));
        }
        if (type) {
            *type = String8(String16(data.type, data.typeLen));
        }
        if (name) {
            *name = String8(String16(data.name, data.nameLen));
        }
        return 0;
    }

    int package_id(const AssetManager& am)
    {
        return (am.getResources().getBasePackageId(0)) << 24;
    }

    int parse_idmap_header(const IdmapBuffer& buf, AssetManager& am)
    {
        uint32_t i, o, e;
        char path[PATH_LENGTH];

        NEXT(buf, i, o);
        if (i != IDMAP_MAGIC) {
            printe("not an idmap file: actual magic constant 0x%08x does not match expected magic "
                    "constant 0x%08x\n", i, IDMAP_MAGIC);
            return -1;
        }
        print_header();
        print("IDMAP HEADER", "magic", i, o, "");

        NEXT(buf, i, o);
        print("", "base crc", i, o, "");

        NEXT(buf, i, o);
        print("", "overlay crc", i, o, "");

        if (buf.nextPath(path, &o, &e) < 0) {
            // printe done from IdmapBuffer::nextPath
            return -1;
        }
        print_path("", "base path", o, e, "%s", path);
        if (!am.addAssetPath(String8(path), NULL)) {
            printe("failed to add '%s' as asset path\n", path);
            return -1;
        }

        if (buf.nextPath(path, &o, &e) < 0) {
            // printe done from IdmapBuffer::nextPath
            return -1;
        }
        print_path("", "overlay path", o, e, "%s", path);

        return 0;
    }

    int parse_data_header(const IdmapBuffer& buf, const AssetManager& am, Vector<uint32_t>& types)
    {
        uint32_t i, o;
        const uint32_t numeric_package = package_id(am);

        NEXT(buf, i, o);
        print("DATA HEADER", "types count", i, o, "");
        const uint32_t N = i;

        for (uint32_t j = 0; j < N; ++j) {
            NEXT(buf, i, o);
            if (i == 0) {
                print("", "padding", i, o, "");
            } else {
                String8 type;
                const uint32_t numeric_type = (j + 1) << 16;
                const uint32_t res_id = numeric_package | numeric_type;
                if (resource_metadata(am, res_id, NULL, &type, NULL) < 0) {
                    // printe done from resource_metadata
                    return -1;
                }
                print("", "type offset", i, o, "absolute offset 0x%02x, %s",
                        i + IDMAP_HEADER_SIZE, type.string());
                types.add(numeric_type);
            }
        }

        return 0;
    }

    int parse_data_block(const IdmapBuffer& buf, const AssetManager& am, size_t numeric_type)
    {
        uint32_t i, o, n, id_offset;
        const uint32_t numeric_package = package_id(am);

        NEXT(buf, i, o);
        print("DATA BLOCK", "entry count", i, o, "");
        n = i;

        NEXT(buf, i, o);
        print("", "entry offset", i, o, "");
        id_offset = i;

        for ( ; n > 0; --n) {
            String8 type, name;

            NEXT(buf, i, o);
            if (i == 0) {
                print("", "padding", i, o, "");
            } else {
                uint32_t res_id = numeric_package | numeric_type | id_offset;
                if (resource_metadata(am, res_id, NULL, &type, &name) < 0) {
                    // printe done from resource_metadata
                    return -1;
                }
                print("", "entry", i, o, "%s/%s", type.string(), name.string());
            }
            ++id_offset;
        }

        return 0;
    }
}

int idmap_inspect(const char *idmap_path)
{
    IdmapBuffer buf;
    if (buf.init(idmap_path) < 0) {
        // printe done from IdmapBuffer::init
        return EXIT_FAILURE;
    }
    AssetManager am;
    if (parse_idmap_header(buf, am) < 0) {
        // printe done from parse_idmap_header
        return EXIT_FAILURE;
    }
    Vector<uint32_t> types;
    if (parse_data_header(buf, am, types) < 0) {
        // printe done from parse_data_header
        return EXIT_FAILURE;
    }
    const size_t N = types.size();
    for (size_t i = 0; i < N; ++i) {
        if (parse_data_block(buf, am, types.itemAt(i)) < 0) {
            // printe done from parse_data_block
            return EXIT_FAILURE;
        }
    }
    return EXIT_SUCCESS;
}
