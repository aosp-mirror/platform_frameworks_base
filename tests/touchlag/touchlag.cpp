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

#include <stdint.h>
#include <sys/types.h>

#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/fb.h>
#include <linux/input.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <cutils/memory.h>
#include <asm-generic/mman.h>
#include <sys/mman.h>
#include <utils/threads.h>
#include <unistd.h>
#include <math.h>

using namespace android;

#ifndef FBIO_WAITFORVSYNC
#define FBIO_WAITFORVSYNC   _IOW('F', 0x20, __u32)
#endif

struct Buffer {
    size_t w;
    size_t h;
    size_t s;
    union {
        void* addr;
        uint32_t* pixels;
    };
};

void clearBuffer(Buffer* buf, uint32_t pixel) {
    android_memset32(buf->pixels, pixel, buf->s * buf->h * 4);
}

void drawTwoPixels(Buffer* buf, uint32_t pixel, ssize_t x, ssize_t y, size_t w) {
    if (y>0 && y<ssize_t(buf->h)) {
        uint32_t* bits = buf->pixels + y * buf->s;
        if (x>=0 && x<ssize_t(buf->w)) {
            bits[x] = pixel;
        }
        ssize_t W(w);
        if ((x+W)>=0 && (x+W)<ssize_t(buf->w)) {
            bits[x+W] = pixel;
        }
    }
}

void drawHLine(Buffer* buf, uint32_t pixel, ssize_t x, ssize_t y, size_t w) {
    if (y>0 && y<ssize_t(buf->h)) {
        ssize_t W(w);
        if (x<0) {
            W += x;
            x = 0;
        }
        if (x+w > buf->w) {
            W = buf->w - x;
        }
        if (W>0) {
            uint32_t* bits = buf->pixels + y * buf->s + x;
            android_memset32(bits, pixel, W*4);
        }
    }
}

void drawRect(Buffer* buf, uint32_t pixel, ssize_t x, ssize_t y, size_t w, size_t h) {
    ssize_t W(w), H(h);
    if (x<0) {
        w += x;
        x = 0;
    }
    if (y<0) {
        h += y;
        y = 0;
    }
    if (x+w > buf->w)   W = buf->w - x;
    if (y+h > buf->h)   H = buf->h - y;
    if (W>0 && H>0) {
        uint32_t* bits = buf->pixels + y * buf->s + x;
        for (ssize_t i=0 ; i<H ; i++) {
            android_memset32(bits, pixel, W*4);
            bits += buf->s;
        }
    }
}

void drawCircle(Buffer* buf, uint32_t pixel,
        size_t x0, size_t y0, size_t radius, bool filled = false) {
    ssize_t f = 1 - radius;
    ssize_t ddF_x = 1;
    ssize_t ddF_y = -2 * radius;
    ssize_t x = 0;
    ssize_t y = radius;
    if (filled) {
        drawHLine(buf, pixel, x0-radius, y0, 2*radius);
    } else {
        drawTwoPixels(buf, pixel, x0-radius, y0, 2*radius);
    }
    while (x < y) {
        if (f >= 0) {
            y--;
            ddF_y += 2;
            f += ddF_y;
        }
        x++;
        ddF_x += 2;
        f += ddF_x;
        if (filled) {
            drawHLine(buf, pixel, x0-x, y0+y, 2*x);
            drawHLine(buf, pixel, x0-x, y0-y, 2*x);
            drawHLine(buf, pixel, x0-y, y0+x, 2*y);
            drawHLine(buf, pixel, x0-y, y0-x, 2*y);
        } else {
            drawTwoPixels(buf, pixel, x0-x, y0+y, 2*x);
            drawTwoPixels(buf, pixel, x0-x, y0-y, 2*x);
            drawTwoPixels(buf, pixel, x0-y, y0+x, 2*y);
            drawTwoPixels(buf, pixel, x0-y, y0-x, 2*y);
        }
    }
}

class TouchEvents {
    class EventThread : public Thread {
        int fd;

        virtual bool threadLoop() {
            input_event event;
            int first_down = 0;
            do {
                read(fd, &event, sizeof(event));
                if (event.type == EV_ABS) {
                    if (event.code == ABS_MT_TRACKING_ID) {
                        down = event.value == -1 ? 0 : 1;
                        first_down = down;
                    }
                    if (event.code == ABS_MT_POSITION_X) {
                        x = event.value;
                    }
                    if (event.code == ABS_MT_POSITION_Y) {
                        y = event.value;
                    }
                }
            } while (event.type == EV_SYN);
            return true;
        }

    public:
        int x, y, down;
        EventThread() : Thread(false),
                x(0), y(0), down(0)
        {
            fd = open("/dev/input/event1", O_RDONLY);
        }
};
    sp<EventThread> thread;

public:
    TouchEvents() {
        thread = new EventThread();
        thread->run("EventThread", PRIORITY_URGENT_DISPLAY);
    }

    int getMostRecentPosition(int* x, int* y) {
        *x = thread->x;
        *y = thread->y;
        return thread->down;
    }
};


struct Queue {
    struct position {
        int x, y;
    };
    int index;
    position q[16];
    Queue() : index(0) { }
    void push(int x, int y) {
        index++;
        index &= 0xF;
        q[index].x = x;
        q[index].y = y;
    }
    void get(int lag, int* x, int* y) {
        const int i = (index - lag) & 0xF;
        *x = q[i].x;
        *y = q[i].y;
    }
};

extern char *optarg;
extern int optind;
extern int optopt;
extern int opterr;
extern int optreset;

void usage(const char* name) {
    printf("\nusage: %s [-h] [-l lag]\n", name);
}

int main(int argc, char** argv) {
    fb_var_screeninfo vi;
    fb_fix_screeninfo fi;

    int lag = 0;
    int fd = open("/dev/graphics/fb0", O_RDWR);
    ioctl(fd, FBIOGET_VSCREENINFO, &vi);
    ioctl(fd, FBIOGET_FSCREENINFO, &fi);
    void* bits = mmap(0, fi.smem_len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    Buffer framebuffer;
    framebuffer.w = vi.xres;
    framebuffer.h = vi.yres;
    framebuffer.s = fi.line_length / (vi.bits_per_pixel >> 3);
    framebuffer.addr = bits;

    int ch;
    while ((ch = getopt(argc, argv, "hl:")) != -1) {
        switch (ch) {
            case 'l':
                lag = atoi(optarg);
                break;
            case 'h':
            default:
                usage(argv[0]);
                exit(0);
        }
    }
    argc -= optind;
    argv += optind;


    TouchEvents touch;
    Queue queue;


    int x=0, y=0;
    int lag_x=0, lag_y=0;

    clearBuffer(&framebuffer, 0);
    while (true) {
        uint32_t crt = 0;
        ioctl(fd, FBIO_WAITFORVSYNC, &crt);

        // draw beam marker
        drawRect(&framebuffer, 0x400000, framebuffer.w-2, 0, 2, framebuffer.h);
        // erase screen
        if (lag) {
            drawCircle(&framebuffer, 0, lag_x, lag_y, 100);
            drawHLine(&framebuffer, 0, 0, lag_y, 32);
        }
        drawCircle(&framebuffer, 0, x, y, 100, true);
        drawHLine(&framebuffer, 0, 0, y, 32);

        // draw a line at y=1000
        drawHLine(&framebuffer, 0x808080, 0, 1000, framebuffer.w);

        // get touch events
        touch.getMostRecentPosition(&x, &y);
        queue.push(x, y);
        queue.get(lag, &lag_x, &lag_y);

        if (lag) {
            drawCircle(&framebuffer, 0x00FF00, lag_x, lag_y, 100);
            drawHLine(&framebuffer, 0x00FF00, 0, lag_y, 32);
        }

        drawCircle(&framebuffer, 0xFFFFFF, x, y, 100, true);
        drawHLine(&framebuffer, 0xFFFFFF, 0, y, 32);

        // draw end of frame beam marker
        drawRect(&framebuffer, 0x004000, framebuffer.w-2, 0, 2, framebuffer.h);
    }

    close(fd);
    return 0;
}
