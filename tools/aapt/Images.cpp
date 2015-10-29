//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#define PNG_INTERNAL

#include "Images.h"

#include <androidfw/ResourceTypes.h>
#include <utils/ByteOrder.h>

#include <png.h>
#include <zlib.h>

// Change this to true for noisy debug output.
static const bool kIsDebug = false;

static void
png_write_aapt_file(png_structp png_ptr, png_bytep data, png_size_t length)
{
    AaptFile* aaptfile = (AaptFile*) png_get_io_ptr(png_ptr);
    status_t err = aaptfile->writeData(data, length);
    if (err != NO_ERROR) {
        png_error(png_ptr, "Write Error");
    }
}


static void
png_flush_aapt_file(png_structp /* png_ptr */)
{
}

// This holds an image as 8bpp RGBA.
struct image_info
{
    image_info() : rows(NULL), is9Patch(false),
        xDivs(NULL), yDivs(NULL), colors(NULL), allocRows(NULL) { }

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
        free(xDivs);
        free(yDivs);
        free(colors);
    }

    void* serialize9patch() {
        void* serialized = Res_png_9patch::serialize(info9Patch, xDivs, yDivs, colors);
        reinterpret_cast<Res_png_9patch*>(serialized)->deviceToFile();
        return serialized;
    }

    png_uint_32 width;
    png_uint_32 height;
    png_bytepp rows;

    // 9-patch info.
    bool is9Patch;
    Res_png_9patch info9Patch;
    int32_t* xDivs;
    int32_t* yDivs;
    uint32_t* colors;

    // Layout padding, if relevant
    bool haveLayoutBounds;
    int32_t layoutBoundsLeft;
    int32_t layoutBoundsTop;
    int32_t layoutBoundsRight;
    int32_t layoutBoundsBottom;

    // Round rect outline description
    int32_t outlineInsetsLeft;
    int32_t outlineInsetsTop;
    int32_t outlineInsetsRight;
    int32_t outlineInsetsBottom;
    float outlineRadius;
    uint8_t outlineAlpha;

    png_uint_32 allocHeight;
    png_bytepp allocRows;
};

static void log_warning(png_structp png_ptr, png_const_charp warning_message)
{
    const char* imageName = (const char*) png_get_error_ptr(png_ptr);
    fprintf(stderr, "%s: libpng warning: %s\n", imageName, warning_message);
}

static void read_png(const char* imageName,
                     png_structp read_ptr, png_infop read_info,
                     image_info* outImageInfo)
{
    int color_type;
    int bit_depth, interlace_type, compression_type;
    int i;

    png_set_error_fn(read_ptr, const_cast<char*>(imageName),
            NULL /* use default errorfn */, log_warning);
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
        png_set_expand_gray_1_2_4_to_8(read_ptr);

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

    png_set_interlace_handling(read_ptr);

    png_read_update_info(read_ptr, read_info);

    outImageInfo->rows = (png_bytepp)malloc(
        outImageInfo->height * sizeof(png_bytep));
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

    if (kIsDebug) {
        printf("Image %s: w=%d, h=%d, d=%d, colors=%d, inter=%d, comp=%d\n",
                imageName,
                (int)outImageInfo->width, (int)outImageInfo->height,
                bit_depth, color_type,
                interlace_type, compression_type);
    }

    png_get_IHDR(read_ptr, read_info, &outImageInfo->width,
       &outImageInfo->height, &bit_depth, &color_type,
       &interlace_type, &compression_type, NULL);
}

#define COLOR_TRANSPARENT 0
#define COLOR_WHITE 0xFFFFFFFF
#define COLOR_TICK  0xFF000000
#define COLOR_LAYOUT_BOUNDS_TICK 0xFF0000FF

enum {
    TICK_TYPE_NONE,
    TICK_TYPE_TICK,
    TICK_TYPE_LAYOUT_BOUNDS,
    TICK_TYPE_BOTH
};

static int tick_type(png_bytep p, bool transparent, const char** outError)
{
    png_uint_32 color = p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24);

    if (transparent) {
        if (p[3] == 0) {
            return TICK_TYPE_NONE;
        }
        if (color == COLOR_LAYOUT_BOUNDS_TICK) {
            return TICK_TYPE_LAYOUT_BOUNDS;
        }
        if (color == COLOR_TICK) {
            return TICK_TYPE_TICK;
        }

        // Error cases
        if (p[3] != 0xff) {
            *outError = "Frame pixels must be either solid or transparent (not intermediate alphas)";
            return TICK_TYPE_NONE;
        }
        if (p[0] != 0 || p[1] != 0 || p[2] != 0) {
            *outError = "Ticks in transparent frame must be black or red";
        }
        return TICK_TYPE_TICK;
    }

    if (p[3] != 0xFF) {
        *outError = "White frame must be a solid color (no alpha)";
    }
    if (color == COLOR_WHITE) {
        return TICK_TYPE_NONE;
    }
    if (color == COLOR_TICK) {
        return TICK_TYPE_TICK;
    }
    if (color == COLOR_LAYOUT_BOUNDS_TICK) {
        return TICK_TYPE_LAYOUT_BOUNDS;
    }

    if (p[0] != 0 || p[1] != 0 || p[2] != 0) {
        *outError = "Ticks in white frame must be black or red";
        return TICK_TYPE_NONE;
    }
    return TICK_TYPE_TICK;
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
        if (TICK_TYPE_TICK == tick_type(row+i*4, transparent, outError)) {
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
        if (TICK_TYPE_TICK == tick_type(rows[i]+offset, transparent, outError)) {
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

static status_t get_horizontal_layout_bounds_ticks(
        png_bytep row, int width, bool transparent, bool /* required */,
        int32_t* outLeft, int32_t* outRight, const char** outError)
{
    int i;
    *outLeft = *outRight = 0;

    // Look for left tick
    if (TICK_TYPE_LAYOUT_BOUNDS == tick_type(row + 4, transparent, outError)) {
        // Starting with a layout padding tick
        i = 1;
        while (i < width - 1) {
            (*outLeft)++;
            i++;
            int tick = tick_type(row + i * 4, transparent, outError);
            if (tick != TICK_TYPE_LAYOUT_BOUNDS) {
                break;
            }
        }
    }

    // Look for right tick
    if (TICK_TYPE_LAYOUT_BOUNDS == tick_type(row + (width - 2) * 4, transparent, outError)) {
        // Ending with a layout padding tick
        i = width - 2;
        while (i > 1) {
            (*outRight)++;
            i--;
            int tick = tick_type(row+i*4, transparent, outError);
            if (tick != TICK_TYPE_LAYOUT_BOUNDS) {
                break;
            }
        }
    }

    return NO_ERROR;
}

static status_t get_vertical_layout_bounds_ticks(
        png_bytepp rows, int offset, int height, bool transparent, bool /* required */,
        int32_t* outTop, int32_t* outBottom, const char** outError)
{
    int i;
    *outTop = *outBottom = 0;

    // Look for top tick
    if (TICK_TYPE_LAYOUT_BOUNDS == tick_type(rows[1] + offset, transparent, outError)) {
        // Starting with a layout padding tick
        i = 1;
        while (i < height - 1) {
            (*outTop)++;
            i++;
            int tick = tick_type(rows[i] + offset, transparent, outError);
            if (tick != TICK_TYPE_LAYOUT_BOUNDS) {
                break;
            }
        }
    }

    // Look for bottom tick
    if (TICK_TYPE_LAYOUT_BOUNDS == tick_type(rows[height - 2] + offset, transparent, outError)) {
        // Ending with a layout padding tick
        i = height - 2;
        while (i > 1) {
            (*outBottom)++;
            i--;
            int tick = tick_type(rows[i] + offset, transparent, outError);
            if (tick != TICK_TYPE_LAYOUT_BOUNDS) {
                break;
            }
        }
    }

    return NO_ERROR;
}

static void find_max_opacity(png_byte** rows,
                             int startX, int startY, int endX, int endY, int dX, int dY,
                             int* out_inset)
{
    uint8_t max_opacity = 0;
    int inset = 0;
    *out_inset = 0;
    for (int x = startX, y = startY; x != endX && y != endY; x += dX, y += dY, inset++) {
        png_byte* color = rows[y] + x * 4;
        uint8_t opacity = color[3];
        if (opacity > max_opacity) {
            max_opacity = opacity;
            *out_inset = inset;
        }
        if (opacity == 0xff) return;
    }
}

static uint8_t max_alpha_over_row(png_byte* row, int startX, int endX)
{
    uint8_t max_alpha = 0;
    for (int x = startX; x < endX; x++) {
        uint8_t alpha = (row + x * 4)[3];
        if (alpha > max_alpha) max_alpha = alpha;
    }
    return max_alpha;
}

static uint8_t max_alpha_over_col(png_byte** rows, int offsetX, int startY, int endY)
{
    uint8_t max_alpha = 0;
    for (int y = startY; y < endY; y++) {
        uint8_t alpha = (rows[y] + offsetX * 4)[3];
        if (alpha > max_alpha) max_alpha = alpha;
    }
    return max_alpha;
}

static void get_outline(image_info* image)
{
    int midX = image->width / 2;
    int midY = image->height / 2;
    int endX = image->width - 2;
    int endY = image->height - 2;

    // find left and right extent of nine patch content on center row
    if (image->width > 4) {
        find_max_opacity(image->rows, 1, midY, midX, -1, 1, 0, &image->outlineInsetsLeft);
        find_max_opacity(image->rows, endX, midY, midX, -1, -1, 0, &image->outlineInsetsRight);
    } else {
        image->outlineInsetsLeft = 0;
        image->outlineInsetsRight = 0;
    }

    // find top and bottom extent of nine patch content on center column
    if (image->height > 4) {
        find_max_opacity(image->rows, midX, 1, -1, midY, 0, 1, &image->outlineInsetsTop);
        find_max_opacity(image->rows, midX, endY, -1, midY, 0, -1, &image->outlineInsetsBottom);
    } else {
        image->outlineInsetsTop = 0;
        image->outlineInsetsBottom = 0;
    }

    int innerStartX = 1 + image->outlineInsetsLeft;
    int innerStartY = 1 + image->outlineInsetsTop;
    int innerEndX = endX - image->outlineInsetsRight;
    int innerEndY = endY - image->outlineInsetsBottom;
    int innerMidX = (innerEndX + innerStartX) / 2;
    int innerMidY = (innerEndY + innerStartY) / 2;

    // assuming the image is a round rect, compute the radius by marching
    // diagonally from the top left corner towards the center
    image->outlineAlpha = std::max(
        max_alpha_over_row(image->rows[innerMidY], innerStartX, innerEndX),
        max_alpha_over_col(image->rows, innerMidX, innerStartY, innerStartY));

    int diagonalInset = 0;
    find_max_opacity(image->rows, innerStartX, innerStartY, innerMidX, innerMidY, 1, 1,
            &diagonalInset);

    /* Determine source radius based upon inset:
     *     sqrt(r^2 + r^2) = sqrt(i^2 + i^2) + r
     *     sqrt(2) * r = sqrt(2) * i + r
     *     (sqrt(2) - 1) * r = sqrt(2) * i
     *     r = sqrt(2) / (sqrt(2) - 1) * i
     */
    image->outlineRadius = 3.4142f * diagonalInset;

    if (kIsDebug) {
        printf("outline insets %d %d %d %d, rad %f, alpha %x\n",
                image->outlineInsetsLeft,
                image->outlineInsetsTop,
                image->outlineInsetsRight,
                image->outlineInsetsBottom,
                image->outlineRadius,
                image->outlineAlpha);
    }
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

static status_t do_9patch(const char* imageName, image_info* image)
{
    image->is9Patch = true;

    int W = image->width;
    int H = image->height;
    int i, j;

    int maxSizeXDivs = W * sizeof(int32_t);
    int maxSizeYDivs = H * sizeof(int32_t);
    int32_t* xDivs = image->xDivs = (int32_t*) malloc(maxSizeXDivs);
    int32_t* yDivs = image->yDivs = (int32_t*) malloc(maxSizeYDivs);
    uint8_t numXDivs = 0;
    uint8_t numYDivs = 0;

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

    image->layoutBoundsLeft = image->layoutBoundsRight =
        image->layoutBoundsTop = image->layoutBoundsBottom = 0;

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

    // Copy patch size data into image...
    image->info9Patch.numXDivs = numXDivs;
    image->info9Patch.numYDivs = numYDivs;

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

    // Find left and right of layout padding...
    get_horizontal_layout_bounds_ticks(image->rows[H-1], W, transparent, false,
                                        &image->layoutBoundsLeft,
                                        &image->layoutBoundsRight, &errorMsg);

    get_vertical_layout_bounds_ticks(image->rows, (W-1)*4, H, transparent, false,
                                        &image->layoutBoundsTop,
                                        &image->layoutBoundsBottom, &errorMsg);

    image->haveLayoutBounds = image->layoutBoundsLeft != 0
                               || image->layoutBoundsRight != 0
                               || image->layoutBoundsTop != 0
                               || image->layoutBoundsBottom != 0;

    if (image->haveLayoutBounds) {
        if (kIsDebug) {
            printf("layoutBounds=%d %d %d %d\n", image->layoutBoundsLeft, image->layoutBoundsTop,
                    image->layoutBoundsRight, image->layoutBoundsBottom);
        }
    }

    // use opacity of pixels to estimate the round rect outline
    get_outline(image);

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

    if (kIsDebug) {
        printf("Size ticks for %s: x0=%d, x1=%d, y0=%d, y1=%d\n", imageName,
                xDivs[0], xDivs[1],
                yDivs[0], yDivs[1]);
        printf("padding ticks for %s: l=%d, r=%d, t=%d, b=%d\n", imageName,
                image->info9Patch.paddingLeft, image->info9Patch.paddingRight,
                image->info9Patch.paddingTop, image->info9Patch.paddingBottom);
    }

    // Remove frame from image.
    image->rows = (png_bytepp)malloc((H-2) * sizeof(png_bytep));
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
    image->colors = (uint32_t*)malloc(numColors * sizeof(uint32_t));

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
            image->colors[colorIndex++] = c;
            if (kIsDebug) {
                if (c != Res_png_9patch::NO_COLOR)
                    hasColor = true;
            }
            left = right;
        }
        top = bottom;
    }

    assert(colorIndex == numColors);

    for (i=0; i<numColors; i++) {
        if (hasColor) {
            if (i == 0) printf("Colors in %s:\n ", imageName);
            printf(" #%08x", image->colors[i]);
            if (i == numColors - 1) printf("\n");
        }
    }
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

static void checkNinePatchSerialization(Res_png_9patch* inPatch,  void* data)
{
    size_t patchSize = inPatch->serializedSize();
    void* newData = malloc(patchSize);
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
        assert(outPatch->getXDivs()[i] == inPatch->getXDivs()[i]);
    }
    for (int i = 0; i < outPatch->numYDivs; i++) {
        assert(outPatch->getYDivs()[i] == inPatch->getYDivs()[i]);
    }
    for (int i = 0; i < outPatch->numColors; i++) {
        assert(outPatch->getColors()[i] == inPatch->getColors()[i]);
    }
    free(newData);
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
        return;
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
                printf("\n");
            }
        }
    }
}

#define MAX(a,b) ((a)>(b)?(a):(b))
#define ABS(a)   ((a)<0?-(a):(a))

static void analyze_image(const char *imageName, image_info &imageInfo, int grayscaleTolerance,
                          png_colorp rgbPalette, png_bytep alphaPalette,
                          int *paletteEntries, int *alphaPaletteEntries, bool *hasTransparency,
                          int *colorType, png_bytepp outRows)
{
    int w = imageInfo.width;
    int h = imageInfo.height;
    int i, j, rr, gg, bb, aa, idx;;
    uint32_t opaqueColors[256], alphaColors[256];
    uint32_t col;
    int numOpaqueColors = 0, numAlphaColors = 0;
    int maxGrayDeviation = 0;

    bool isOpaque = true;
    bool isPalette = true;
    bool isGrayscale = true;

    // Scan the entire image and determine if:
    // 1. Every pixel has R == G == B (grayscale)
    // 2. Every pixel has A == 255 (opaque)
    // 3. There are no more than 256 distinct RGBA colors
    //        We will track opaque colors separately from colors with
    //        alpha.  This allows us to reencode the color table more
    //        efficiently (color tables entries without a corresponding
    //        alpha value are assumed to be opaque).

    if (kIsDebug) {
        printf("Initial image data:\n");
        dump_image(w, h, imageInfo.rows, PNG_COLOR_TYPE_RGB_ALPHA);
    }

    for (j = 0; j < h; j++) {
        png_bytep row = imageInfo.rows[j];
        png_bytep out = outRows[j];
        for (i = 0; i < w; i++) {

            // Make sure any zero alpha pixels are fully zeroed.  On average,
            // each of our PNG assets seem to have about four distinct pixels
            // with zero alpha.
            // There are several advantages to setting these to zero:
            // (1) Images are more likely able to be encodable with a palette.
            // (2) Image palettes will be smaller.
            // (3) Premultiplied and unpremultiplied PNG decodes can skip
            //     writing zeros to memory, often saving significant numbers
            //     of memory pages.
            aa = *(row + 3);
            if (aa == 0) {
                rr = 0;
                gg = 0;
                bb = 0;

                // Also set red, green, and blue to zero in "row".  If we later
                // decide to encode the PNG as RGB or RGBA, we will use the
                // values stored there.
                *(row) = 0;
                *(row + 1) = 0;
                *(row + 2) = 0;
            } else {
                rr = *(row);
                gg = *(row + 1);
                bb = *(row + 2);
            }
            row += 4;

            int odev = maxGrayDeviation;
            maxGrayDeviation = MAX(ABS(rr - gg), maxGrayDeviation);
            maxGrayDeviation = MAX(ABS(gg - bb), maxGrayDeviation);
            maxGrayDeviation = MAX(ABS(bb - rr), maxGrayDeviation);
            if (maxGrayDeviation > odev) {
                if (kIsDebug) {
                    printf("New max dev. = %d at pixel (%d, %d) = (%d %d %d %d)\n",
                            maxGrayDeviation, i, j, rr, gg, bb, aa);
                }
            }

            // Check if image is really grayscale
            if (isGrayscale) {
                if (rr != gg || rr != bb) {
                    if (kIsDebug) {
                        printf("Found a non-gray pixel at %d, %d = (%d %d %d %d)\n",
                                i, j, rr, gg, bb, aa);
                    }
                    isGrayscale = false;
                }
            }

            // Check if image is really opaque
            if (isOpaque) {
                if (aa != 0xff) {
                    if (kIsDebug) {
                        printf("Found a non-opaque pixel at %d, %d = (%d %d %d %d)\n",
                                i, j, rr, gg, bb, aa);
                    }
                    isOpaque = false;
                }
            }

            // Check if image is really <= 256 colors
            if (isPalette) {
                col = (uint32_t) ((rr << 24) | (gg << 16) | (bb << 8) | aa);
                bool match = false;

                if (aa == 0xff) {
                    for (idx = 0; idx < numOpaqueColors; idx++) {
                        if (opaqueColors[idx] == col) {
                            match = true;
                            break;
                        }
                    }

                    if (!match) {
                        if (numOpaqueColors < 256) {
                            opaqueColors[numOpaqueColors] = col;
                        }
                        numOpaqueColors++;
                    }

                    // Write the palette index for the pixel to outRows optimistically.
                    // We might overwrite it later if we decide to encode as gray or
                    // gray + alpha.  We may also need to overwrite it when we combine
                    // into a single palette.
                    *out++ = idx;
                } else {
                    for (idx = 0; idx < numAlphaColors; idx++) {
                        if (alphaColors[idx] == col) {
                            match = true;
                            break;
                        }
                    }

                    if (!match) {
                        if (numAlphaColors < 256) {
                            alphaColors[numAlphaColors] = col;
                        }
                        numAlphaColors++;
                    }

                    // Write the palette index for the pixel to outRows optimistically.
                    // We might overwrite it later if we decide to encode as gray or
                    // gray + alpha.
                    *out++ = idx;
                }

                if (numOpaqueColors + numAlphaColors > 256) {
                    if (kIsDebug) {
                        printf("Found 257th color at %d, %d\n", i, j);
                    }
                    isPalette = false;
                }
            }
        }
    }

    // If we decide to encode the image using a palette, we will reset these counts
    // to the appropriate values later.  Initializing them here avoids compiler
    // complaints about uses of possibly uninitialized variables.
    *paletteEntries = 0;
    *alphaPaletteEntries = 0;

    *hasTransparency = !isOpaque;
    int paletteSize = w * h + 3 * numOpaqueColors + 4 * numAlphaColors;

    int bpp = isOpaque ? 3 : 4;
    if (kIsDebug) {
        printf("isGrayscale = %s\n", isGrayscale ? "true" : "false");
        printf("isOpaque = %s\n", isOpaque ? "true" : "false");
        printf("isPalette = %s\n", isPalette ? "true" : "false");
        printf("Size w/ palette = %d, gray+alpha = %d, rgb(a) = %d\n",
                paletteSize, 2 * w * h, bpp * w * h);
        printf("Max gray deviation = %d, tolerance = %d\n", maxGrayDeviation, grayscaleTolerance);
    }

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
        // Combine the alphaColors and the opaqueColors into a single palette.
        // The alphaColors must be at the start of the palette.
        uint32_t* colors = alphaColors;
        memcpy(colors + numAlphaColors, opaqueColors, 4 * numOpaqueColors);

        // Fix the indices of the opaque colors in the image.
        for (j = 0; j < h; j++) {
            png_bytep row = imageInfo.rows[j];
            png_bytep out = outRows[j];
            for (i = 0; i < w; i++) {
                uint32_t pixel = ((uint32_t*) row)[i];
                if (pixel >> 24 == 0xFF) {
                    out[i] += numAlphaColors;
                }
            }
        }

        // Create separate RGB and Alpha palettes and set the number of colors
        int numColors = numOpaqueColors + numAlphaColors;
        *paletteEntries = numColors;
        *alphaPaletteEntries = numAlphaColors;

        // Create the RGB and alpha palettes
        for (int idx = 0; idx < numColors; idx++) {
            col = colors[idx];
            rgbPalette[idx].red   = (png_byte) ((col >> 24) & 0xff);
            rgbPalette[idx].green = (png_byte) ((col >> 16) & 0xff);
            rgbPalette[idx].blue  = (png_byte) ((col >>  8) & 0xff);
            if (idx < numAlphaColors) {
                alphaPalette[idx] = (png_byte)  (col        & 0xff);
            }
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
                      image_info& imageInfo, const Bundle* bundle)
{
    png_uint_32 width, height;
    int color_type;
    int bit_depth, interlace_type, compression_type;
    int i;

    png_unknown_chunk unknowns[3];
    unknowns[0].data = NULL;
    unknowns[1].data = NULL;
    unknowns[2].data = NULL;

    png_bytepp outRows = (png_bytepp) malloc((int) imageInfo.height * sizeof(png_bytep));
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

    if (kIsDebug) {
        printf("Writing image %s: w = %d, h = %d\n", imageName,
                (int) imageInfo.width, (int) imageInfo.height);
    }

    png_color rgbPalette[256];
    png_byte alphaPalette[256];
    bool hasTransparency;
    int paletteEntries, alphaPaletteEntries;

    int grayscaleTolerance = bundle->getGrayscaleTolerance();
    analyze_image(imageName, imageInfo, grayscaleTolerance, rgbPalette, alphaPalette,
                  &paletteEntries, &alphaPaletteEntries, &hasTransparency, &color_type, outRows);

    // Legacy versions of aapt would always encode 9patch PNGs as RGBA.  This had the unintended
    // benefit of working around a bug decoding paletted images in Android 4.1.
    // https://code.google.com/p/android/issues/detail?id=34619
    //
    // If SDK_JELLY_BEAN is supported, we need to avoid a paletted encoding in order to not expose
    // this bug.
    if (!bundle->isMinSdkAtLeast(SDK_JELLY_BEAN_MR1)) {
        if (imageInfo.is9Patch && PNG_COLOR_TYPE_PALETTE == color_type) {
            if (hasTransparency) {
                color_type = PNG_COLOR_TYPE_RGB_ALPHA;
            } else {
                color_type = PNG_COLOR_TYPE_RGB;
            }
        }
    }

    if (kIsDebug) {
        switch (color_type) {
        case PNG_COLOR_TYPE_PALETTE:
            printf("Image %s has %d colors%s, using PNG_COLOR_TYPE_PALETTE\n",
                    imageName, paletteEntries,
                    hasTransparency ? " (with alpha)" : "");
            break;
        case PNG_COLOR_TYPE_GRAY:
            printf("Image %s is opaque gray, using PNG_COLOR_TYPE_GRAY\n", imageName);
            break;
        case PNG_COLOR_TYPE_GRAY_ALPHA:
            printf("Image %s is gray + alpha, using PNG_COLOR_TYPE_GRAY_ALPHA\n", imageName);
            break;
        case PNG_COLOR_TYPE_RGB:
            printf("Image %s is opaque RGB, using PNG_COLOR_TYPE_RGB\n", imageName);
            break;
        case PNG_COLOR_TYPE_RGB_ALPHA:
            printf("Image %s is RGB + alpha, using PNG_COLOR_TYPE_RGB_ALPHA\n", imageName);
            break;
        }
    }

    png_set_IHDR(write_ptr, write_info, imageInfo.width, imageInfo.height,
                 8, color_type, PNG_INTERLACE_NONE,
                 PNG_COMPRESSION_TYPE_DEFAULT, PNG_FILTER_TYPE_DEFAULT);

    if (color_type == PNG_COLOR_TYPE_PALETTE) {
        png_set_PLTE(write_ptr, write_info, rgbPalette, paletteEntries);
        if (hasTransparency) {
            png_set_tRNS(write_ptr, write_info, alphaPalette, alphaPaletteEntries,
                    (png_color_16p) 0);
        }
       png_set_filter(write_ptr, 0, PNG_NO_FILTERS);
    } else {
       png_set_filter(write_ptr, 0, PNG_ALL_FILTERS);
    }

    if (imageInfo.is9Patch) {
        int chunk_count = 2 + (imageInfo.haveLayoutBounds ? 1 : 0);
        int p_index = imageInfo.haveLayoutBounds ? 2 : 1;
        int b_index = 1;
        int o_index = 0;

        // Chunks ordered thusly because older platforms depend on the base 9 patch data being last
        png_byte *chunk_names = imageInfo.haveLayoutBounds
                ? (png_byte*)"npOl\0npLb\0npTc\0"
                : (png_byte*)"npOl\0npTc";

        // base 9 patch data
        if (kIsDebug) {
            printf("Adding 9-patch info...\n");
        }
        memcpy((char*)unknowns[p_index].name, "npTc", 5);
        unknowns[p_index].data = (png_byte*)imageInfo.serialize9patch();
        unknowns[p_index].size = imageInfo.info9Patch.serializedSize();
        // TODO: remove the check below when everything works
        checkNinePatchSerialization(&imageInfo.info9Patch, unknowns[p_index].data);

        // automatically generated 9 patch outline data
        int chunk_size = sizeof(png_uint_32) * 6;
        memcpy((char*)unknowns[o_index].name, "npOl", 5);
        unknowns[o_index].data = (png_byte*) calloc(chunk_size, 1);
        png_byte outputData[chunk_size];
        memcpy(&outputData, &imageInfo.outlineInsetsLeft, 4 * sizeof(png_uint_32));
        ((float*) outputData)[4] = imageInfo.outlineRadius;
        ((png_uint_32*) outputData)[5] = imageInfo.outlineAlpha;
        memcpy(unknowns[o_index].data, &outputData, chunk_size);
        unknowns[o_index].size = chunk_size;

        // optional optical inset / layout bounds data
        if (imageInfo.haveLayoutBounds) {
            int chunk_size = sizeof(png_uint_32) * 4;
            memcpy((char*)unknowns[b_index].name, "npLb", 5);
            unknowns[b_index].data = (png_byte*) calloc(chunk_size, 1);
            memcpy(unknowns[b_index].data, &imageInfo.layoutBoundsLeft, chunk_size);
            unknowns[b_index].size = chunk_size;
        }

        for (int i = 0; i < chunk_count; i++) {
            unknowns[i].location = PNG_HAVE_IHDR;
        }
        png_set_keep_unknown_chunks(write_ptr, PNG_HANDLE_CHUNK_ALWAYS,
                                    chunk_names, chunk_count);
        png_set_unknown_chunks(write_ptr, write_info, unknowns, chunk_count);
    }


    png_write_info(write_ptr, write_info);

    png_bytepp rows;
    if (color_type == PNG_COLOR_TYPE_RGB || color_type == PNG_COLOR_TYPE_RGB_ALPHA) {
        if (color_type == PNG_COLOR_TYPE_RGB) {
            png_set_filler(write_ptr, 0, PNG_FILLER_AFTER);
        }
        rows = imageInfo.rows;
    } else {
        rows = outRows;
    }
    png_write_image(write_ptr, rows);

    if (kIsDebug) {
        printf("Final image data:\n");
        dump_image(imageInfo.width, imageInfo.height, rows, color_type);
    }

    png_write_end(write_ptr, write_info);

    for (i = 0; i < (int) imageInfo.height; i++) {
        free(outRows[i]);
    }
    free(outRows);
    free(unknowns[0].data);
    free(unknowns[1].data);
    free(unknowns[2].data);

    png_get_IHDR(write_ptr, write_info, &width, &height,
       &bit_depth, &color_type, &interlace_type,
       &compression_type, NULL);

    if (kIsDebug) {
        printf("Image written: w=%d, h=%d, d=%d, colors=%d, inter=%d, comp=%d\n",
                (int)width, (int)height, bit_depth, color_type, interlace_type,
                compression_type);
    }
}

static bool read_png_protected(png_structp read_ptr, String8& printableName, png_infop read_info,
                               const sp<AaptFile>& file, FILE* fp, image_info* imageInfo) {
    if (setjmp(png_jmpbuf(read_ptr))) {
        return false;
    }

    png_init_io(read_ptr, fp);

    read_png(printableName.string(), read_ptr, read_info, imageInfo);

    const size_t nameLen = file->getPath().length();
    if (nameLen > 6) {
        const char* name = file->getPath().string();
        if (name[nameLen-5] == '9' && name[nameLen-6] == '.') {
            if (do_9patch(printableName.string(), imageInfo) != NO_ERROR) {
                return false;
            }
        }
    }

    return true;
}

static bool write_png_protected(png_structp write_ptr, String8& printableName, png_infop write_info,
                                image_info* imageInfo, const Bundle* bundle) {
    if (setjmp(png_jmpbuf(write_ptr))) {
        return false;
    }

    write_png(printableName.string(), write_ptr, write_info, *imageInfo, bundle);

    return true;
}

status_t preProcessImage(const Bundle* bundle, const sp<AaptAssets>& /* assets */,
                         const sp<AaptFile>& file, String8* /* outNewLeafName */)
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

    if (bundle->getVerbose()) {
        printf("Processing image: %s\n", printableName.string());
    }

    png_structp read_ptr = NULL;
    png_infop read_info = NULL;
    FILE* fp;

    image_info imageInfo;

    png_structp write_ptr = NULL;
    png_infop write_info = NULL;

    status_t error = UNKNOWN_ERROR;

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

    if (!read_png_protected(read_ptr, printableName, read_info, file, fp, &imageInfo)) {
        goto bail;
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

    if (!write_png_protected(write_ptr, printableName, write_info, &imageInfo, bundle)) {
        goto bail;
    }

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

status_t preProcessImageToCache(const Bundle* bundle, const String8& source, const String8& dest)
{
    png_structp read_ptr = NULL;
    png_infop read_info = NULL;

    FILE*volatile fp;

    image_info imageInfo;

    png_structp write_ptr = NULL;
    png_infop write_info = NULL;

    status_t error = UNKNOWN_ERROR;

    if (bundle->getVerbose()) {
        printf("Processing image to cache: %s => %s\n", source.string(), dest.string());
    }

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
    write_png(dest.string(), write_ptr, write_info, imageInfo, bundle);

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

status_t postProcessImage(const Bundle* bundle, const sp<AaptAssets>& assets,
                          ResourceTable* table, const sp<AaptFile>& file)
{
    String8 ext(file->getPath().getPathExtension());

    // At this point, now that we have all the resource data, all we need to
    // do is compile XML files.
    if (strcmp(ext.string(), ".xml") == 0) {
        String16 resourceName(parseResourceName(file->getSourceFile().getPathLeaf()));
        return compileXmlFile(bundle, assets, resourceName, file, table);
    }

    return NO_ERROR;
}
