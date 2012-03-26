/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <errno.h>
#include <setjmp.h>
#include <stdio.h>

extern "C" {
#include "jpeglib.h"
}

static inline uint8_t from565to8(uint16_t p, int start, int bits) {
    uint8_t c = (p >> start) & ((1 << bits) - 1);
    return (c << (8 - bits)) | (c >> (bits - (8 - bits)));
}

struct sf_jpeg_error_mgr {
    struct jpeg_error_mgr jerr;
    jmp_buf longjmp_buffer;
};

void sf_jpeg_error_exit(j_common_ptr cinfo) {
    struct sf_jpeg_error_mgr *sf_err = (struct sf_jpeg_error_mgr *)cinfo->err;
    longjmp(sf_err->longjmp_buffer, 0);
}

int writeJpegFile(const char *filename, uint8_t *frame, int width, int height) {
    struct sf_jpeg_error_mgr sf_err;
    struct jpeg_compress_struct cinfo;
    uint8_t row_data[width * 3];
    JSAMPROW row_pointer = row_data;
    FILE *f;

    f = fopen(filename, "w");
    if (!f) {
        return -errno;
    }

    cinfo.err = jpeg_std_error(&sf_err.jerr);
    sf_err.jerr.error_exit = sf_jpeg_error_exit;
    if (setjmp(sf_err.longjmp_buffer)) {
        jpeg_destroy_compress(&cinfo);
        fclose(f);
        return -1;
    }

    jpeg_create_compress(&cinfo);
    jpeg_stdio_dest(&cinfo, f);

    cinfo.image_width = width;
    cinfo.image_height = height;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;

    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, 80, TRUE);

    jpeg_start_compress(&cinfo, TRUE);

    for (int row = 0; row < height; row++) {
        uint16_t *src = (uint16_t *)(frame + row * width * 2);
        uint8_t *dst = row_data;
        for (int col = 0; col < width; col++) {
            dst[0] = from565to8(*src, 11, 5);
            dst[1] = from565to8(*src, 5, 6);
            dst[2] = from565to8(*src, 0, 5);
            dst += 3;
            src++;
        }
        jpeg_write_scanlines(&cinfo, &row_pointer, 1);
    }

    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);

    fclose(f);
    return 0;
}
