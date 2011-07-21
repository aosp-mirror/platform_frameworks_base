//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#define PNG_INTERNAL

#include "Images.h"

#include <utils/ResourceTypes.h>
#include <utils/ByteOrder.h>

#include <png.h>

#define NOISY(x) //x

static void
png_write_aapt_file(png_structp png_ptr, png_bytep data, png_size_t length)
{
    status_t err = ((AaptFile*)png_ptr->io_ptr)->writeData(data, length);
    if (err != NO_ERROR) {
        png_error(png_ptr, "Write Error");
    }
}


static void
png_flush_aapt_file(png_structp png_ptr)
{
}

// This holds an image as 8bpp RGBA.
struct image_info
{
    image_info() : rows(NULL), is9Patch(false), allocRows(NULL) { }
    ~image_info() {
        if (rows && rows != allocRows) {
            free(rows);
        }
        if (allocRows) {
            for (int i=0; i<(int)allocHeight; i++) {
                free(allocRows[i]);
            }
            free(allocRows);
        }
        free(info9Patch.xDivs);
        free(info9Patch.yDivs);
        free(info9Patch.colors);
    }

    png_uint_32 width;
    png_uint_32 height;
    png_bytepp rows;

    // 9-patch info.
    bool is9Patch;
    Res_png_9patch info9Patch;

    png_uint_32 allocHeight;
    png_bytepp allocRows;
};

static void read_png(const char* imageName,
                     png_structp read_ptr, png_infop read_info,
                     image_info* outImageInfo)
{
    int color_type;
    int bit_depth, interlace_type, compression_type;
    int i;

    png_read_info(read_ptr, read_info);

    png_get_IHDR(read_ptr, read_info, &outImageInfo->width,
       &outImageInfo->height, &bit_depth, &color_type,
       &interlace_type, &compression_type, NULL);

    //printf("Image %s:\n", imageName);
    //printf("color_type=%d, bit_depth=%d, interlace_type=%d, compression_type=%d\n",
    //       color_type, bit_depth, interlace_type, compression_type);

    if (color_type == PNG_COLOR_TYPE_PALETTE)
        png_set_palette_to_rgb(read_ptr);

    if (color_type == PNG_COLOR_TYPE_GRAY && bit_depth < 8)
        png_set_gray_1_2_4_to_8(read_ptr);

    if (png_get_valid(read_ptr, read_info, PNG_INFO_tRNS)) {
        //printf("Has PNG_INFO_tRNS!\n");
        png_set_tRNS_to_alpha(read_ptr);
    }

    if (bit_depth == 16)
        png_set_strip_16(read_ptr);

    if ((color_type&PNG_COLOR_MASK_ALPHA) == 0)
        png_set_add_alpha(read_ptr, 0xFF, PNG_FILLER_AFTER);

    if (color_type == PNG_COLOR_TYPE_GRAY || color_type == PNG_COLOR_TYPE_GRAY_ALPHA)
        png_set_gray_to_rgb(read_ptr);

    png_read_update_info(read_ptr, read_info);

    outImageInfo->rows = (png_bytepp)malloc(
        outImageInfo->height * png_sizeof(png_bytep));
    outImageInfo->allocHeight = outImageInfo->height;
    outImageInfo->allocRows = outImageInfo->rows;

    png_set_rows(read_ptr, read_info, outImageInfo->rows);

    for (i = 0; i < (int)outImageInfo->height; i++)
    {
        outImageInfo->rows[i] = (png_bytep)
            malloc(png_get_rowbytes(read_ptr, read_info));
    }

    png_read_image(read_ptr, outImageInfo->rows);

    png_read_end(read_ptr, read_info);

    NOISY(printf("Image %s: w=%d, h=%d, d=%d, colors=%d, inter=%d, comp=%d\n",
                 imageName,
                 (int)outImageInfo->width, (int)outImageInfo->height,
                 bit_depth, color_type,
                 interlace_type, compression_type));

    png_get_IHDR(read_ptr, read_info, &outImageInfo->width,
       &outImageInfo->height, &bit_depth, &color_type,
       &interlace_type, &compression_type, NULL);
}

static bool is_tick(png_bytep p, bool transparent, const char** outError)
{
    if (transparent) {
        if (p[3] == 0) {
            return false;
        }
        if (p[3] != 0xff) {
            *outError = "Frame pixels must be either solid or transparent (not intermediate alphas)";
            return false;
        }
        if (p[0] != 0 || p[1] != 0 || p[2] != 0) {
            *outError = "Ticks in transparent frame must be black";
        }
        return true;
    }

    if (p[3] != 0xFF) {
        *outError = "White frame must be a solid color (no alpha)";
    }
    if (p[0] == 0xFF && p[1] == 0xFF && p[2] == 0xFF) {
        return false;
    }
    if (p[0] != 0 || p[1] != 0 || p[2] != 0) {
        *outError = "Ticks in white frame must be black";
        return false;
    }
    return true;
}

enum {
    TICK_START,
    TICK_INSIDE_1,
    TICK_OUTSIDE_1
};

static status_t get_horizontal_ticks(
        png_bytep row, int width, bool transparent, bool required,
        int32_t* outLeft, int32_t* outRight, const char** outError,
        uint8_t* outDivs, bool multipleAllowed)
{
    int i;
    *outLeft = *outRight = -1;
    int state = TICK_START;
    bool found = false;

    for (i=1; i<width-1; i++) {
        if (is_tick(row+i*4, transparent, outError)) {
            if (state == TICK_START ||
                (state == TICK_OUTSIDE_1 && multipleAllowed)) {
                *outLeft = i-1;
                *outRight = width-2;
                found = true;
                if (outDivs != NULL) {
                    *outDivs += 2;
                }
                state = TICK_INSIDE_1;
            } else if (state == TICK_OUTSIDE_1) {
                *outError = "Can't have more than one marked region along edge";
                *outLeft = i;
                return UNKNOWN_ERROR;
            }
        } else if (*outError == NULL) {
            if (state == TICK_INSIDE_1) {
                // We're done with this div.  Move on to the next.
                *outRight = i-1;
                outRight += 2;
                outLeft += 2;
                state = TICK_OUTSIDE_1;
            }
        } else {
            *outLeft = i;
            return UNKNOWN_ERROR;
        }
    }

    if (required && !found) {
        *outError = "No marked region found along edge";
        *outLeft = -1;
        return UNKNOWN_ERROR;
    }

    return NO_ERROR;
}

static status_t get_vertical_ticks(
        png_bytepp rows, int offset, int height, bool transparent, bool required,
        int32_t* outTop, int32_t* outBottom, const char** outError,
        uint8_t* outDivs, bool multipleAllowed)
{
    int i;
    *outTop = *outBottom = -1;
    int state = TICK_START;
    bool found = false;

    for (i=1; i<height-1; i++) {
        if (is_tick(rows[i]+offset, transparent, outError)) {
            if (state == TICK_START ||
                (state == TICK_OUTSIDE_1 && multipleAllowed)) {
                *outTop = i-1;
                *outBottom = height-2;
                found = true;
                if (outDivs != NULL) {
                    *outDivs += 2;
                }
                state = TICK_INSIDE_1;
            } else if (state == TICK_OUTSIDE_1) {
                *outError = "Can't have more than one marked region along edge";
                *outTop = i;
                return UNKNOWN_ERROR;
            }
        } else if (*outError == NULL) {
            if (state == TICK_INSIDE_1) {
                // We're done with this div.  Move on to the next.
                *outBottom = i-1;
                outTop += 2;
                outBottom += 2;
                state = TICK_OUTSIDE_1;
            }
        } else {
            *outTop = i;
            return UNKNOWN_ERROR;
        }
    }

    if (required && !found) {
        *outError = "No marked region found along edge";
        *outTop = -1;
        return UNKNOWN_ERROR;
    }

    return NO_ERROR;
}

static uint32_t get_color(
    png_bytepp rows, int left, int top, int right, int bottom)
{
    png_bytep color = rows[top] + left*4;

    if (left > right || top > bottom) {
        return Res_png_9patch::TRANSPARENT_COLOR;
    }

    while (top <= bottom) {
        for (int i = left; i <= right; i++) {
            png_bytep p = rows[top]+i*4;
            if (color[3] == 0) {
                if (p[3] != 0) {
                    return Res_png_9patch::NO_COLOR;
                }
            } else if (p[0] != color[0] || p[1] != color[1]
                       || p[2] != color[2] || p[3] != color[3]) {
                return Res_png_9patch::NO_COLOR;
            }
        }
        top++;
    }

    if (color[3] == 0) {
        return Res_png_9patch::TRANSPARENT_COLOR;
    }
    return (color[3]<<24) | (color[0]<<16) | (color[1]<<8) | color[2];
}

static void select_patch(
    int which, int front, int back, int size, int* start, int* end)
{
    switch (which) {
    case 0:
        *start = 0;
        *end = front-1;
        break;
    case 1:
        *start = front;
        *end = back-1;
        break;
    case 2:
        *start = back;
        *end = size-1;
        break;
    }
}

static uint32_t get_color(image_info* image, int hpatch, int vpatch)
{
    int left, right, top, bottom;
    select_patch(
        hpatch, image->info9Patch.xDivs[0], image->info9Patch.xDivs[1],
        image->width, &left, &right);
    select_patch(
        vpatch, image->info9Patch.yDivs[0], image->info9Patch.yDivs[1],
        image->height, &top, &bottom);
    //printf("Selecting h=%d v=%d: (%d,%d)-(%d,%d)\n",
    //       hpatch, vpatch, left, top, right, bottom);
    const uint32_t c = get_color(image->rows, left, top, right, bottom);
    NOISY(printf("Color in (%d,%d)-(%d,%d): #%08x\n", left, top, right, bottom, c));
    return c;
}

static status_t do_9patch(const char* imageName, image_info* image)
{
    image->is9Patch = true;

    int W = image->width;
    int H = image->height;
    int i, j;

    int maxSizeXDivs = W * sizeof(int32_t);
    int maxSizeYDivs = H * sizeof(int32_t);
    int32_t* xDivs = (int32_t*) malloc(maxSizeXDivs);
    int32_t* yDivs = (int32_t*) malloc(maxSizeYDivs);
    uint8_t  numXDivs = 0;
    uint8_t  numYDivs = 0;
    int8_t numColors;
    int numRows;
    int numCols;
    int top;
    int left;
    int right;
    int bottom;
    memset(xDivs, -1, maxSizeXDivs);
    memset(yDivs, -1, maxSizeYDivs);
    image->info9Patch.paddingLeft = image->info9Patch.paddingRight =
        image->info9Patch.paddingTop = image->info9Patch.paddingBottom = -1;

    png_bytep p = image->rows[0];
    bool transparent = p[3] == 0;
    bool hasColor = false;

    const char* errorMsg = NULL;
    int errorPixel = -1;
    const char* errorEdge = NULL;

    int colorIndex = 0;

    // Validate size...
    if (W < 3 || H < 3) {
        errorMsg = "Image must be at least 3x3 (1x1 without frame) pixels";
        goto getout;
    }

    // Validate frame...
    if (!transparent &&
        (p[0] != 0xFF || p[1] != 0xFF || p[2] != 0xFF || p[3] != 0xFF)) {
        errorMsg = "Must have one-pixel frame that is either transparent or white";
        goto getout;
    }

    // Find left and right of sizing areas...
    if (get_horizontal_ticks(p, W, transparent, true, &xDivs[0],
                             &xDivs[1], &errorMsg, &numXDivs, true) != NO_ERROR) {
        errorPixel = xDivs[0];
        errorEdge = "top";
        goto getout;
    }

    // Find top and bottom of sizing areas...
    if (get_vertical_ticks(image->rows, 0, H, transparent, true, &yDivs[0],
                           &yDivs[1], &errorMsg, &numYDivs, true) != NO_ERROR) {
        errorPixel = yDivs[0];
        errorEdge = "left";
        goto getout;
    }

    // Find left and right of padding area...
    if (get_horizontal_ticks(image->rows[H-1], W, transparent, false, &image->info9Patch.paddingLeft,
                             &image->info9Patch.paddingRight, &errorMsg, NULL, false) != NO_ERROR) {
        errorPixel = image->info9Patch.paddingLeft;
        errorEdge = "bottom";
        goto getout;
    }

    // Find top and bottom of padding area...
    if (get_vertical_ticks(image->rows, (W-1)*4, H, transparent, false, &image->info9Patch.paddingTop,
                           &image->info9Patch.paddingBottom, &errorMsg, NULL, false) != NO_ERROR) {
        errorPixel = image->info9Patch.paddingTop;
        errorEdge = "right";
        goto getout;
    }

    // Copy patch data into image
    image->info9Patch.numXDivs = numXDivs;
    image->info9Patch.numYDivs = numYDivs;
    image->info9Patch.xDivs = xDivs;
    image->info9Patch.yDivs = yDivs;

    // If padding is not yet specified, take values from size.
    if (image->info9Patch.paddingLeft < 0) {
        image->info9Patch.paddingLeft = xDivs[0];
        image->info9Patch.paddingRight = W - 2 - xDivs[1];
    } else {
        // Adjust value to be correct!
        image->info9Patch.paddingRight = W - 2 - image->info9Patch.paddingRight;
    }
    if (image->info9Patch.paddingTop < 0) {
        image->info9Patch.paddingTop = yDivs[0];
        image->info9Patch.paddingBottom = H - 2 - yDivs[1];
    } else {
        // Adjust value to be correct!
        image->info9Patch.paddingBottom = H - 2 - image->info9Patch.paddingBottom;
    }

    NOISY(printf("Size ticks for %s: x0=%d, x1=%d, y0=%d, y1=%d\n", imageName,
                 image->info9Patch.xDivs[0], image->info9Patch.xDivs[1],
                 image->info9Patch.yDivs[0], image->info9Patch.yDivs[1]));
    NOISY(printf("padding ticks for %s: l=%d, r=%d, t=%d, b=%d\n", imageName,
                 image->info9Patch.paddingLeft, image->info9Patch.paddingRight,
                 image->info9Patch.paddingTop, image->info9Patch.paddingBottom));

    // Remove frame from image.
    image->rows = (png_bytepp)malloc((H-2) * png_sizeof(png_bytep));
    for (i=0; i<(H-2); i++) {
        image->rows[i] = image->allocRows[i+1];
        memmove(image->rows[i], image->rows[i]+4, (W-2)*4);
    }
    image->width -= 2;
    W = image->width;
    image->height -= 2;
    H = image->height;

    // Figure out the number of rows and columns in the N-patch
    numCols = numXDivs + 1;
    if (xDivs[0] == 0) {  // Column 1 is strechable
        numCols--;
    }
    if (xDivs[numXDivs - 1] == W) {
        numCols--;
    }
    numRows = numYDivs + 1;
    if (yDivs[0] == 0) {  // Row 1 is strechable
        numRows--;
    }
    if (yDivs[numYDivs - 1] == H) {
        numRows--;
    }

    // Make sure the amount of rows and columns will fit in the number of
    // colors we can use in the 9-patch format.
    if (numRows * numCols > 0x7F) {
        errorMsg = "Too many rows and columns in 9-patch perimeter";
        goto getout;
    }

    numColors = numRows * numCols;
    image->info9Patch.numColors = numColors;
    image->info9Patch.colors = (uint32_t*)malloc(numColors * sizeof(uint32_t));

    // Fill in color information for each patch.

    uint32_t c;
    top = 0;

    // The first row always starts with the top being at y=0 and the bottom
    // being either yDivs[1] (if yDivs[0]=0) of yDivs[0].  In the former case
    // the first row is stretchable along the Y axis, otherwise it is fixed.
    // The last row always ends with the bottom being bitmap.height and the top
    // being either yDivs[numYDivs-2] (if yDivs[numYDivs-1]=bitmap.height) or
    // yDivs[numYDivs-1]. In the former case the last row is stretchable along
    // the Y axis, otherwise it is fixed.
    //
    // The first and last columns are similarly treated with respect to the X
    // axis.
    //
    // The above is to help explain some of the special casing that goes on the
    // code below.

    // The initial yDiv and whether the first row is considered stretchable or
    // not depends on whether yDiv[0] was zero or not.
    for (j = (yDivs[0] == 0 ? 1 : 0);
          j <= numYDivs && top < H;
          j++) {
        if (j == numYDivs) {
            bottom = H;
        } else {
            bottom = yDivs[j];
        }
        left = 0;
        // The initial xDiv and whether the first column is considered
        // stretchable or not depends on whether xDiv[0] was zero or not.
        for (i = xDivs[0] == 0 ? 1 : 0;
              i <= numXDivs && left < W;
              i++) {
            if (i == numXDivs) {
                right = W;
            } else {
                right = xDivs[i];
            }
            c = get_color(image->rows, left, top, right - 1, bottom - 1);
            image->info9Patch.colors[colorIndex++] = c;
            NOISY(if (c != Res_png_9patch::NO_COLOR) hasColor = true);
            left = right;
        }
        top = bottom;
    }

    assert(colorIndex == numColors);

    for (i=0; i<numColors; i++) {
        if (hasColor) {
            if (i == 0) printf("Colors in %s:\n ", imageName);
            printf(" #%08x", image->info9Patch.colors[i]);
            if (i == numColors - 1) printf("\n");
        }
    }

    image->is9Patch = true;
    image->info9Patch.deviceToFile();

getout:
    if (errorMsg) {
        fprintf(stderr,
            "ERROR: 9-patch image %s malformed.\n"
            "       %s.\n", imageName, errorMsg);
        if (errorEdge != NULL) {
            if (errorPixel >= 0) {
                fprintf(stderr,
                    "       Found at pixel #%d along %s edge.\n", errorPixel, errorEdge);
            } else {
                fprintf(stderr,
                    "       Found along %s edge.\n", errorEdge);
            }
        }
        return UNKNOWN_ERROR;
    }
    return NO_ERROR;
}

static void checkNinePatchSerialization(Res_png_9patch* inPatch,  void * data)
{
    if (sizeof(void*) != sizeof(int32_t)) {
        // can't deserialize on a non-32 bit system
        return;
    }
    size_t patchSize = inPatch->serializedSize();
    void * newData = malloc(patchSize);
    memcpy(newData, data, patchSize);
    Res_png_9patch* outPatch = inPatch->deserialize(newData);
    // deserialization is done in place, so outPatch == newData
    assert(outPatch == newData);
    assert(outPatch->numXDivs == inPatch->numXDivs);
    assert(outPatch->numYDivs == inPatch->numYDivs);
    assert(outPatch->paddingLeft == inPatch->paddingLeft);
    assert(outPatch->paddingRight == inPatch->paddingRight);
    assert(outPatch->paddingTop == inPatch->paddingTop);
    assert(outPatch->paddingBottom == inPatch->paddingBottom);
    for (int i = 0; i < outPatch->numXDivs; i++) {
        assert(outPatch->xDivs[i] == inPatch->xDivs[i]);
    }
    for (int i = 0; i < outPatch->numYDivs; i++) {
        assert(outPatch->yDivs[i] == inPatch->yDivs[i]);
    }
    for (int i = 0; i < outPatch->numColors; i++) {
        assert(outPatch->colors[i] == inPatch->colors[i]);
    }
    free(newData);
}

static bool patch_equals(Res_png_9patch& patch1, Res_png_9patch& patch2) {
    if (!(patch1.numXDivs == patch2.numXDivs &&
          patch1.numYDivs == patch2.numYDivs &&
          patch1.numColors == patch2.numColors &&
          patch1.paddingLeft == patch2.paddingLeft &&
          patch1.paddingRight == patch2.paddingRight &&
          patch1.paddingTop == patch2.paddingTop &&
          patch1.paddingBottom == patch2.paddingBottom)) {
            return false;
    }
    for (int i = 0; i < patch1.numColors; i++) {
        if (patch1.colors[i] != patch2.colors[i]) {
            return false;
        }
    }
    for (int i = 0; i < patch1.numXDivs; i++) {
        if (patch1.xDivs[i] != patch2.xDivs[i]) {
            return false;
        }
    }
    for (int i = 0; i < patch1.numYDivs; i++) {
        if (patch1.yDivs[i] != patch2.yDivs[i]) {
            return false;
        }
    }
    return true;
}

static void dump_image(int w, int h, png_bytepp rows, int color_type)
{
    int i, j, rr, gg, bb, aa;

    int bpp;
    if (color_type == PNG_COLOR_TYPE_PALETTE || color_type == PNG_COLOR_TYPE_GRAY) {
        bpp = 1;
    } else if (color_type == PNG_COLOR_TYPE_GRAY_ALPHA) {
        bpp = 2;
    } else if (color_type == PNG_COLOR_TYPE_RGB || color_type == PNG_COLOR_TYPE_RGB_ALPHA) {
        // We use a padding byte even when there is no alpha
        bpp = 4;
    } else {
        printf("Unknown color type %d.\n", color_type);
    }

    for (j = 0; j < h; j++) {
        png_bytep row = rows[j];
        for (i = 0; i < w; i++) {
            rr = row[0];
            gg = row[1];
            bb = row[2];
            aa = row[3];
            row += bpp;

            if (i == 0) {
                printf("Row %d:", j);
            }
            switch (bpp) {
            case 1:
                printf(" (%d)", rr);
                break;
            case 2:
                printf(" (%d %d", rr, gg);
                break;
            case 3:
                printf(" (%d %d %d)", rr, gg, bb);
                break;
            case 4:
                printf(" (%d %d %d %d)", rr, gg, bb, aa);
                break;
            }
            if (i == (w - 1)) {
                NOISY(printf("\n"));
            }
        }
    }
}

#define MAX(a,b) ((a)>(b)?(a):(b))
#define ABS(a)   ((a)<0?-(a):(a))

static void analyze_image(const char *imageName, image_info &imageInfo, int grayscaleTolerance,
                          png_colorp rgbPalette, png_bytep alphaPalette,
                          int *paletteEntries, bool *hasTransparency, int *colorType,
                          png_bytepp outRows)
{
    int w = imageInfo.width;
    int h = imageInfo.height;
    int i, j, rr, gg, bb, aa, idx;
    uint32_t colors[256], col;
    int num_colors = 0;
    int maxGrayDeviation = 0;

    bool isOpaque = true;
    bool isPalette = true;
    bool isGrayscale = true;

    // Scan the entire image and determine if:
    // 1. Every pixel has R == G == B (grayscale)
    // 2. Every pixel has A == 255 (opaque)
    // 3. There are no more than 256 distinct RGBA colors

    // NOISY(printf("Initial image data:\n"));
    // dump_image(w, h, imageInfo.rows, PNG_COLOR_TYPE_RGB_ALPHA);

    for (j = 0; j < h; j++) {
        png_bytep row = imageInfo.rows[j];
        png_bytep out = outRows[j];
        for (i = 0; i < w; i++) {
            rr = *row++;
            gg = *row++;
            bb = *row++;
            aa = *row++;

            int odev = maxGrayDeviation;
            maxGrayDeviation = MAX(ABS(rr - gg), maxGrayDeviation);
            maxGrayDeviation = MAX(ABS(gg - bb), maxGrayDeviation);
            maxGrayDeviation = MAX(ABS(bb - rr), maxGrayDeviation);
            if (maxGrayDeviation > odev) {
                NOISY(printf("New max dev. = %d at pixel (%d, %d) = (%d %d %d %d)\n",
                             maxGrayDeviation, i, j, rr, gg, bb, aa));
            }

            // Check if image is really grayscale
            if (isGrayscale) {
                if (rr != gg || rr != bb) {
                     NOISY(printf("Found a non-gray pixel at %d, %d = (%d %d %d %d)\n",
                                  i, j, rr, gg, bb, aa));
                    isGrayscale = false;
                }
            }

            // Check if image is really opaque
            if (isOpaque) {
                if (aa != 0xff) {
                    NOISY(printf("Found a non-opaque pixel at %d, %d = (%d %d %d %d)\n",
                                 i, j, rr, gg, bb, aa));
                    isOpaque = false;
                }
            }

            // Check if image is really <= 256 colors
            if (isPalette) {
                col = (uint32_t) ((rr << 24) | (gg << 16) | (bb << 8) | aa);
                bool match = false;
                for (idx = 0; idx < num_colors; idx++) {
                    if (colors[idx] == col) {
                        match = true;
                        break;
                    }
                }

                // Write the palette index for the pixel to outRows optimistically
                // We might overwrite it later if we decide to encode as gray or
                // gray + alpha
                *out++ = idx;
                if (!match) {
                    if (num_colors == 256) {
                        NOISY(printf("Found 257th color at %d, %d\n", i, j));
                        isPalette = false;
                    } else {
                        colors[num_colors++] = col;
                    }
                }
            }
        }
    }

    *paletteEntries = 0;
    *hasTransparency = !isOpaque;
    int bpp = isOpaque ? 3 : 4;
    int paletteSize = w * h + bpp * num_colors;

    NOISY(printf("isGrayscale = %s\n", isGrayscale ? "true" : "false"));
    NOISY(printf("isOpaque = %s\n", isOpaque ? "true" : "false"));
    NOISY(printf("isPalette = %s\n", isPalette ? "true" : "false"));
    NOISY(printf("Size w/ palette = %d, gray+alpha = %d, rgb(a) = %d\n",
                 paletteSize, 2 * w * h, bpp * w * h));
    NOISY(printf("Max gray deviation = %d, tolerance = %d\n", maxGrayDeviation, grayscaleTolerance));

    // Choose the best color type for the image.
    // 1. Opaque gray - use COLOR_TYPE_GRAY at 1 byte/pixel
    // 2. Gray + alpha - use COLOR_TYPE_PALETTE if the number of distinct combinations
    //     is sufficiently small, otherwise use COLOR_TYPE_GRAY_ALPHA
    // 3. RGB(A) - use COLOR_TYPE_PALETTE if the number of distinct colors is sufficiently
    //     small, otherwise use COLOR_TYPE_RGB{_ALPHA}
    if (isGrayscale) {
        if (isOpaque) {
            *colorType = PNG_COLOR_TYPE_GRAY; // 1 byte/pixel
        } else {
            // Use a simple heuristic to determine whether using a palette will
            // save space versus using gray + alpha for each pixel.
            // This doesn't take into account chunk overhead, filtering, LZ
            // compression, etc.
            if (isPalette && (paletteSize < 2 * w * h)) {
                *colorType = PNG_COLOR_TYPE_PALETTE; // 1 byte/pixel + 4 bytes/color
            } else {
                *colorType = PNG_COLOR_TYPE_GRAY_ALPHA; // 2 bytes per pixel
            }
        }
    } else if (isPalette && (paletteSize < bpp * w * h)) {
        *colorType = PNG_COLOR_TYPE_PALETTE;
    } else {
        if (maxGrayDeviation <= grayscaleTolerance) {
            printf("%s: forcing image to gray (max deviation = %d)\n", imageName, maxGrayDeviation);
            *colorType = isOpaque ? PNG_COLOR_TYPE_GRAY : PNG_COLOR_TYPE_GRAY_ALPHA;
        } else {
            *colorType = isOpaque ? PNG_COLOR_TYPE_RGB : PNG_COLOR_TYPE_RGB_ALPHA;
        }
    }

    // Perform postprocessing of the image or palette data based on the final
    // color type chosen

    if (*colorType == PNG_COLOR_TYPE_PALETTE) {
        // Create separate RGB and Alpha palettes and set the number of colors
        *paletteEntries = num_colors;

        // Create the RGB and alpha palettes
        for (int idx = 0; idx < num_colors; idx++) {
            col = colors[idx];
            rgbPalette[idx].red   = (png_byte) ((col >> 24) & 0xff);
            rgbPalette[idx].green = (png_byte) ((col >> 16) & 0xff);
            rgbPalette[idx].blue  = (png_byte) ((col >>  8) & 0xff);
            alphaPalette[idx]     = (png_byte)  (col        & 0xff);
        }
    } else if (*colorType == PNG_COLOR_TYPE_GRAY || *colorType == PNG_COLOR_TYPE_GRAY_ALPHA) {
        // If the image is gray or gray + alpha, compact the pixels into outRows
        for (j = 0; j < h; j++) {
            png_bytep row = imageInfo.rows[j];
            png_bytep out = outRows[j];
            for (i = 0; i < w; i++) {
                rr = *row++;
                gg = *row++;
                bb = *row++;
                aa = *row++;
                
                if (isGrayscale) {
                    *out++ = rr;
                } else {
                    *out++ = (png_byte) (rr * 0.2126f + gg * 0.7152f + bb * 0.0722f);
                }
                if (!isOpaque) {
                    *out++ = aa;
                }
           }
        }
    }
}


static void write_png(const char* imageName,
                      png_structp write_ptr, png_infop write_info,
                      image_info& imageInfo, int grayscaleTolerance)
{
    bool optimize = true;
    png_uint_32 width, height;
    int color_type;
    int bit_depth, interlace_type, compression_type;
    int i;

    png_unknown_chunk unknowns[1];
    unknowns[0].data = NULL;

    png_bytepp outRows = (png_bytepp) malloc((int) imageInfo.height * png_sizeof(png_bytep));
    if (outRows == (png_bytepp) 0) {
        printf("Can't allocate output buffer!\n");
        exit(1);
    }
    for (i = 0; i < (int) imageInfo.height; i++) {
        outRows[i] = (png_bytep) malloc(2 * (int) imageInfo.width);
        if (outRows[i] == (png_bytep) 0) {
            printf("Can't allocate output buffer!\n");
            exit(1);
        }
    }

    png_set_compression_level(write_ptr, Z_BEST_COMPRESSION);

    NOISY(printf("Writing image %s: w = %d, h = %d\n", imageName,
          (int) imageInfo.width, (int) imageInfo.height));

    png_color rgbPalette[256];
    png_byte alphaPalette[256];
    bool hasTransparency;
    int paletteEntries;

    analyze_image(imageName, imageInfo, grayscaleTolerance, rgbPalette, alphaPalette,
                  &paletteEntries, &hasTransparency, &color_type, outRows);

    // If the image is a 9-patch, we need to preserve it as a ARGB file to make
    // sure the pixels will not be pre-dithered/clamped until we decide they are
    if (imageInfo.is9Patch && (color_type == PNG_COLOR_TYPE_RGB ||
            color_type == PNG_COLOR_TYPE_GRAY || color_type == PNG_COLOR_TYPE_PALETTE)) {
        color_type = PNG_COLOR_TYPE_RGB_ALPHA;
    }

    switch (color_type) {
    case PNG_COLOR_TYPE_PALETTE:
        NOISY(printf("Image %s has %d colors%s, using PNG_COLOR_TYPE_PALETTE\n",
                     imageName, paletteEntries,
                     hasTransparency ? " (with alpha)" : ""));
        break;
    case PNG_COLOR_TYPE_GRAY:
        NOISY(printf("Image %s is opaque gray, using PNG_COLOR_TYPE_GRAY\n", imageName));
        break;
    case PNG_COLOR_TYPE_GRAY_ALPHA:
        NOISY(printf("Image %s is gray + alpha, using PNG_COLOR_TYPE_GRAY_ALPHA\n", imageName));
        break;
    case PNG_COLOR_TYPE_RGB:
        NOISY(printf("Image %s is opaque RGB, using PNG_COLOR_TYPE_RGB\n", imageName));
        break;
    case PNG_COLOR_TYPE_RGB_ALPHA:
        NOISY(printf("Image %s is RGB + alpha, using PNG_COLOR_TYPE_RGB_ALPHA\n", imageName));
        break;
    }

    png_set_IHDR(write_ptr, write_info, imageInfo.width, imageInfo.height,
                 8, color_type, PNG_INTERLACE_NONE,
                 PNG_COMPRESSION_TYPE_DEFAULT, PNG_FILTER_TYPE_DEFAULT);

    if (color_type == PNG_COLOR_TYPE_PALETTE) {
        png_set_PLTE(write_ptr, write_info, rgbPalette, paletteEntries);
        if (hasTransparency) {
            png_set_tRNS(write_ptr, write_info, alphaPalette, paletteEntries, (png_color_16p) 0);
        }
       png_set_filter(write_ptr, 0, PNG_NO_FILTERS);
    } else {
       png_set_filter(write_ptr, 0, PNG_ALL_FILTERS);
    }

    if (imageInfo.is9Patch) {
        NOISY(printf("Adding 9-patch info...\n"));
        strcpy((char*)unknowns[0].name, "npTc");
        unknowns[0].data = (png_byte*)imageInfo.info9Patch.serialize();
        unknowns[0].size = imageInfo.info9Patch.serializedSize();
        // TODO: remove the check below when everything works
        checkNinePatchSerialization(&imageInfo.info9Patch, unknowns[0].data);
        png_set_keep_unknown_chunks(write_ptr, PNG_HANDLE_CHUNK_ALWAYS,
                                    (png_byte*)"npTc", 1);
        png_set_unknown_chunks(write_ptr, write_info, unknowns, 1);
        // XXX I can't get this to work without forcibly changing
        // the location to what I want...  which apparently is supposed
        // to be a private API, but everything else I have tried results
        // in the location being set to what I -last- wrote so I never
        // get written. :p
        png_set_unknown_chunk_location(write_ptr, write_info, 0, PNG_HAVE_PLTE);
    }

    png_write_info(write_ptr, write_info);

    png_bytepp rows;
    if (color_type == PNG_COLOR_TYPE_RGB || color_type == PNG_COLOR_TYPE_RGB_ALPHA) {
        png_set_filler(write_ptr, 0, PNG_FILLER_AFTER);
        rows = imageInfo.rows;
    } else {
        rows = outRows;
    }
    png_write_image(write_ptr, rows);

//     NOISY(printf("Final image data:\n"));
//     dump_image(imageInfo.width, imageInfo.height, rows, color_type);

    png_write_end(write_ptr, write_info);

    for (i = 0; i < (int) imageInfo.height; i++) {
        free(outRows[i]);
    }
    free(outRows);
    free(unknowns[0].data);

    png_get_IHDR(write_ptr, write_info, &width, &height,
       &bit_depth, &color_type, &interlace_type,
       &compression_type, NULL);

    NOISY(printf("Image written: w=%d, h=%d, d=%d, colors=%d, inter=%d, comp=%d\n",
                 (int)width, (int)height, bit_depth, color_type, interlace_type,
                 compression_type));
}

status_t preProcessImage(Bundle* bundle, const sp<AaptAssets>& assets,
                         const sp<AaptFile>& file, String8* outNewLeafName)
{
    String8 ext(file->getPath().getPathExtension());

    // We currently only process PNG images.
    if (strcmp(ext.string(), ".png") != 0) {
        return NO_ERROR;
    }

    // Example of renaming a file:
    //*outNewLeafName = file->getPath().getBasePath().getFileName();
    //outNewLeafName->append(".nupng");

    String8 printableName(file->getPrintableSource());

    png_structp read_ptr = NULL;
    png_infop read_info = NULL;
    FILE* fp;

    image_info imageInfo;

    png_structp write_ptr = NULL;
    png_infop write_info = NULL;

    status_t error = UNKNOWN_ERROR;

    const size_t nameLen = file->getPath().length();

    fp = fopen(file->getSourceFile().string(), "rb");
    if (fp == NULL) {
        fprintf(stderr, "%s: ERROR: Unable to open PNG file\n", printableName.string());
        goto bail;
    }

    read_ptr = png_create_read_struct(PNG_LIBPNG_VER_STRING, 0, (png_error_ptr)NULL,
                                        (png_error_ptr)NULL);
    if (!read_ptr) {
        goto bail;
    }

    read_info = png_create_info_struct(read_ptr);
    if (!read_info) {
        goto bail;
    }

    if (setjmp(png_jmpbuf(read_ptr))) {
        goto bail;
    }

    png_init_io(read_ptr, fp);

    read_png(printableName.string(), read_ptr, read_info, &imageInfo);

    if (nameLen > 6) {
        const char* name = file->getPath().string();
        if (name[nameLen-5] == '9' && name[nameLen-6] == '.') {
            if (do_9patch(printableName.string(), &imageInfo) != NO_ERROR) {
                goto bail;
            }
        }
    }

    write_ptr = png_create_write_struct(PNG_LIBPNG_VER_STRING, 0, (png_error_ptr)NULL,
                                        (png_error_ptr)NULL);
    if (!write_ptr)
    {
        goto bail;
    }

    write_info = png_create_info_struct(write_ptr);
    if (!write_info)
    {
        goto bail;
    }

    png_set_write_fn(write_ptr, (void*)file.get(),
                     png_write_aapt_file, png_flush_aapt_file);

    if (setjmp(png_jmpbuf(write_ptr)))
    {
        goto bail;
    }

    write_png(printableName.string(), write_ptr, write_info, imageInfo,
              bundle->getGrayscaleTolerance());

    error = NO_ERROR;

    if (bundle->getVerbose()) {
        fseek(fp, 0, SEEK_END);
        size_t oldSize = (size_t)ftell(fp);
        size_t newSize = file->getSize();
        float factor = ((float)newSize)/oldSize;
        int percent = (int)(factor*100);
        printf("    (processed image %s: %d%% size of source)\n", printableName.string(), percent);
    }

bail:
    if (read_ptr) {
        png_destroy_read_struct(&read_ptr, &read_info, (png_infopp)NULL);
    }
    if (fp) {
        fclose(fp);
    }
    if (write_ptr) {
        png_destroy_write_struct(&write_ptr, &write_info);
    }

    if (error != NO_ERROR) {
        fprintf(stderr, "ERROR: Failure processing PNG image %s\n",
                file->getPrintableSource().string());
    }
    return error;
}

status_t preProcessImageToCache(Bundle* bundle, String8 source, String8 dest)
{
    png_structp read_ptr = NULL;
    png_infop read_info = NULL;

    FILE* fp;

    image_info imageInfo;

    png_structp write_ptr = NULL;
    png_infop write_info = NULL;

    status_t error = UNKNOWN_ERROR;

    // Get a file handler to read from
    fp = fopen(source.string(),"rb");
    if (fp == NULL) {
        fprintf(stderr, "%s ERROR: Unable to open PNG file\n", source.string());
        return error;
    }

    // Call libpng to get a struct to read image data into
    read_ptr = png_create_read_struct(PNG_LIBPNG_VER_STRING, NULL, NULL, NULL);
    if (!read_ptr) {
        fclose(fp);
        png_destroy_read_struct(&read_ptr, &read_info,NULL);
        return error;
    }

    // Call libpng to get a struct to read image info into
    read_info = png_create_info_struct(read_ptr);
    if (!read_info) {
        fclose(fp);
        png_destroy_read_struct(&read_ptr, &read_info,NULL);
        return error;
    }

    // Set a jump point for libpng to long jump back to on error
    if (setjmp(png_jmpbuf(read_ptr))) {
        fclose(fp);
        png_destroy_read_struct(&read_ptr, &read_info,NULL);
        return error;
    }

    // Set up libpng to read from our file.
    png_init_io(read_ptr,fp);

    // Actually read data from the file
    read_png(source.string(), read_ptr, read_info, &imageInfo);

    // We're done reading so we can clean up
    // Find old file size before releasing handle
    fseek(fp, 0, SEEK_END);
    size_t oldSize = (size_t)ftell(fp);
    fclose(fp);
    png_destroy_read_struct(&read_ptr, &read_info,NULL);

    // Check to see if we're dealing with a 9-patch
    // If we are, process appropriately
    if (source.getBasePath().getPathExtension() == ".9")  {
        if (do_9patch(source.string(), &imageInfo) != NO_ERROR) {
            return error;
        }
    }

    // Call libpng to create a structure to hold the processed image data
    // that can be written to disk
    write_ptr = png_create_write_struct(PNG_LIBPNG_VER_STRING, NULL, NULL, NULL);
    if (!write_ptr) {
        png_destroy_write_struct(&write_ptr, &write_info);
        return error;
    }

    // Call libpng to create a structure to hold processed image info that can
    // be written to disk
    write_info = png_create_info_struct(write_ptr);
    if (!write_info) {
        png_destroy_write_struct(&write_ptr, &write_info);
        return error;
    }

    // Open up our destination file for writing
    fp = fopen(dest.string(), "wb");
    if (!fp) {
        fprintf(stderr, "%s ERROR: Unable to open PNG file\n", dest.string());
        png_destroy_write_struct(&write_ptr, &write_info);
        return error;
    }

    // Set up libpng to write to our file
    png_init_io(write_ptr, fp);

    // Set up a jump for libpng to long jump back on on errors
    if (setjmp(png_jmpbuf(write_ptr))) {
        fclose(fp);
        png_destroy_write_struct(&write_ptr, &write_info);
        return error;
    }

    // Actually write out to the new png
    write_png(dest.string(), write_ptr, write_info, imageInfo,
              bundle->getGrayscaleTolerance());

    if (bundle->getVerbose()) {
        // Find the size of our new file
        FILE* reader = fopen(dest.string(), "rb");
        fseek(reader, 0, SEEK_END);
        size_t newSize = (size_t)ftell(reader);
        fclose(reader);

        float factor = ((float)newSize)/oldSize;
        int percent = (int)(factor*100);
        printf("  (processed image to cache entry %s: %d%% size of source)\n",
               dest.string(), percent);
    }

    //Clean up
    fclose(fp);
    png_destroy_write_struct(&write_ptr, &write_info);

    return NO_ERROR;
}

status_t postProcessImage(const sp<AaptAssets>& assets,
                          ResourceTable* table, const sp<AaptFile>& file)
{
    String8 ext(file->getPath().getPathExtension());

    // At this point, now that we have all the resource data, all we need to
    // do is compile XML files.
    if (strcmp(ext.string(), ".xml") == 0) {
        return compileXmlFile(assets, file, table);
    }

    return NO_ERROR;
}
