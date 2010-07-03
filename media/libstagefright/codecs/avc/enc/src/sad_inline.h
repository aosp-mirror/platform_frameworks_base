/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
#ifndef _SAD_INLINE_H_
#define _SAD_INLINE_H_

#ifdef __cplusplus
extern "C"
{
#endif

#if defined(__GNUC__) && defined(__arm__) /* ARM GNU COMPILER  */

    __inline int32 SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
        tmp = tmp - tmp2;
        if (tmp > 0) sad += tmp;
        else sad -= tmp;

        return sad;
    }

    __inline int32 sad_4pixel(int32 src1, int32 src2, int32 mask)
    {
        int32 x7;

        x7 = src2 ^ src1;       /* check odd/even combination */
        if ((uint32)src2 >= (uint32)src1)
        {
            src1 = src2 - src1;     /* subs */
        }
        else
        {
            src1 = src1 - src2;
        }
        x7 = x7 ^ src1;     /* only odd bytes need to add carry */
        x7 = mask & ((uint32)x7 >> 1);
        x7 = (x7 << 8) - x7;
        src1 = src1 + (x7 >> 7); /* add 0xFF to the negative byte, add back carry */
        src1 = src1 ^(x7 >> 7);   /* take absolute value of negative byte */

        return src1;
    }

#define NUMBER 3
#define SHIFT 24

#include "sad_mb_offset.h"

#undef NUMBER
#define NUMBER 2
#undef SHIFT
#define SHIFT 16
#include "sad_mb_offset.h"

#undef NUMBER
#define NUMBER 1
#undef SHIFT
#define SHIFT 8
#include "sad_mb_offset.h"


    __inline int32 simd_sad_mb(uint8 *ref, uint8 *blk, int dmin, int lx)
    {
        int32 x4, x5, x6, x8, x9, x10, x11, x12, x14;

        x9 = 0x80808080; /* const. */

        x8 = (uint32)ref & 0x3;
        if (x8 == 3)
            goto SadMBOffset3;
        if (x8 == 2)
            goto SadMBOffset2;
        if (x8 == 1)
            goto SadMBOffset1;

//  x5 = (x4<<8)-x4; /* x5 = x4*255; */
        x4 = x5 = 0;

        x6 = 0xFFFF00FF;

        ref -= lx;
        blk -= 16;

        x8 = 16;

LOOP_SAD0:
        /****** process 8 pixels ******/
        x10 = *((uint32*)(ref += lx));
        x11 = *((uint32*)(ref + 4));
        x12 = *((uint32*)(blk += 16));
        x14 = *((uint32*)(blk + 4));

        /* process x11 & x14 */
        x11 = sad_4pixel(x11, x14, x9);

        /* process x12 & x10 */
        x10 = sad_4pixel(x10, x12, x9);

        x5 = x5 + x10; /* accumulate low bytes */
        x10 = x10 & (x6 << 8); /* x10 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x10 >> 8);  /* accumulate high bytes */
        x5 = x5 + x11;  /* accumulate low bytes */
        x11 = x11 & (x6 << 8); /* x11 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x11 >> 8);  /* accumulate high bytes */

        /****** process 8 pixels ******/
        x10 = *((uint32*)(ref + 8));
        x11 = *((uint32*)(ref + 12));
        x12 = *((uint32*)(blk + 8));
        x14 = *((uint32*)(blk + 12));

        /* process x11 & x14 */
        x11 = sad_4pixel(x11, x14, x9);

        /* process x12 & x10 */
        x10 = sad_4pixel(x10, x12, x9);

        x5 = x5 + x10;  /* accumulate low bytes */
        x10 = x10 & (x6 << 8); /* x10 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x10 >> 8); /* accumulate high bytes */
        x5 = x5 + x11;  /* accumulate low bytes */
        x11 = x11 & (x6 << 8); /* x11 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x11 >> 8);  /* accumulate high bytes */

        /****************/
        x10 = x5 - (x4 << 8); /* extract low bytes */
        x10 = x10 + x4;     /* add with high bytes */
        x10 = x10 + (x10 << 16); /* add with lower half word */

        if ((int)((uint32)x10 >> 16) <= dmin) /* compare with dmin */
        {
            if (--x8)
            {
                goto LOOP_SAD0;
            }

        }

        return ((uint32)x10 >> 16);

SadMBOffset3:

        return sad_mb_offset3(ref, blk, lx, dmin);

SadMBOffset2:

        return sad_mb_offset2(ref, blk, lx, dmin);

SadMBOffset1:

        return sad_mb_offset1(ref, blk, lx, dmin);

    }

#elif defined(__CC_ARM)  /* only work with arm v5 */

    __inline int32 SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
        __asm
        {
            rsbs    tmp, tmp, tmp2 ;
            rsbmi   tmp, tmp, #0 ;
            add     sad, sad, tmp ;
        }

        return sad;
    }

    __inline int32 sad_4pixel(int32 src1, int32 src2, int32 mask)
    {
        int32 x7;

        __asm
        {
            EOR     x7, src2, src1;     /* check odd/even combination */
            SUBS    src1, src2, src1;
            EOR     x7, x7, src1;
            AND     x7, mask, x7, lsr #1;
            ORRCC   x7, x7, #0x80000000;
            RSB     x7, x7, x7, lsl #8;
            ADD     src1, src1, x7, asr #7;   /* add 0xFF to the negative byte, add back carry */
            EOR     src1, src1, x7, asr #7;   /* take absolute value of negative byte */
        }

        return src1;
    }

    __inline int32 sad_4pixelN(int32 src1, int32 src2, int32 mask)
    {
        int32 x7;

        __asm
        {
            EOR      x7, src2, src1;        /* check odd/even combination */
            ADDS     src1, src2, src1;
            EOR      x7, x7, src1;      /* only odd bytes need to add carry */
            ANDS     x7, mask, x7, rrx;
            RSB      x7, x7, x7, lsl #8;
            SUB      src1, src1, x7, asr #7;  /* add 0xFF to the negative byte, add back carry */
            EOR      src1, src1, x7, asr #7; /* take absolute value of negative byte */
        }

        return src1;
    }

#define sum_accumulate  __asm{      SBC      x5, x5, x10;  /* accumulate low bytes */ \
        BIC      x10, x6, x10;   /* x10 & 0xFF00FF00 */ \
        ADD      x4, x4, x10,lsr #8;   /* accumulate high bytes */ \
        SBC      x5, x5, x11;    /* accumulate low bytes */ \
        BIC      x11, x6, x11;   /* x11 & 0xFF00FF00 */ \
        ADD      x4, x4, x11,lsr #8; } /* accumulate high bytes */


#define NUMBER 3
#define SHIFT 24
#define INC_X8 0x08000001

#include "sad_mb_offset.h"

#undef NUMBER
#define NUMBER 2
#undef SHIFT
#define SHIFT 16
#undef INC_X8
#define INC_X8 0x10000001
#include "sad_mb_offset.h"

#undef NUMBER
#define NUMBER 1
#undef SHIFT
#define SHIFT 8
#undef INC_X8
#define INC_X8 0x08000001
#include "sad_mb_offset.h"


    __inline int32 simd_sad_mb(uint8 *ref, uint8 *blk, int dmin, int lx)
    {
        int32 x4, x5, x6, x8, x9, x10, x11, x12, x14;

        x9 = 0x80808080; /* const. */
        x4 = x5 = 0;

        __asm
        {
            MOVS    x8, ref, lsl #31 ;
            BHI     SadMBOffset3;
            BCS     SadMBOffset2;
            BMI     SadMBOffset1;

            MVN     x6, #0xFF00;
        }
LOOP_SAD0:
        /****** process 8 pixels ******/
        x11 = *((int32*)(ref + 12));
        x10 = *((int32*)(ref + 8));
        x14 = *((int32*)(blk + 12));
        x12 = *((int32*)(blk + 8));

        /* process x11 & x14 */
        x11 = sad_4pixel(x11, x14, x9);

        /* process x12 & x10 */
        x10 = sad_4pixel(x10, x12, x9);

        x5 = x5 + x10;  /* accumulate low bytes */
        x10 = x10 & (x6 << 8); /* x10 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x10 >> 8); /* accumulate high bytes */
        x5 = x5 + x11;  /* accumulate low bytes */
        x11 = x11 & (x6 << 8); /* x11 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x11 >> 8);  /* accumulate high bytes */

        __asm
        {
            /****** process 8 pixels ******/
            LDR     x11, [ref, #4];
            LDR     x10, [ref], lx ;
            LDR     x14, [blk, #4];
            LDR     x12, [blk], #16 ;
        }

        /* process x11 & x14 */
        x11 = sad_4pixel(x11, x14, x9);

        /* process x12 & x10 */
        x10 = sad_4pixel(x10, x12, x9);

        x5 = x5 + x10;  /* accumulate low bytes */
        x10 = x10 & (x6 << 8); /* x10 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x10 >> 8); /* accumulate high bytes */
        x5 = x5 + x11;  /* accumulate low bytes */
        x11 = x11 & (x6 << 8); /* x11 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x11 >> 8);  /* accumulate high bytes */

        /****************/
        x10 = x5 - (x4 << 8); /* extract low bytes */
        x10 = x10 + x4;     /* add with high bytes */
        x10 = x10 + (x10 << 16); /* add with lower half word */

        __asm
        {
            /****************/
            RSBS    x11, dmin, x10, lsr #16;
            ADDLSS  x8, x8, #0x10000001;
            BLS     LOOP_SAD0;
        }

        return ((uint32)x10 >> 16);

SadMBOffset3:

        return sad_mb_offset3(ref, blk, lx, dmin, x8);

SadMBOffset2:

        return sad_mb_offset2(ref, blk, lx, dmin, x8);

SadMBOffset1:

        return sad_mb_offset1(ref, blk, lx, dmin, x8);
    }


#elif defined(__GNUC__) && defined(__arm__) /* ARM GNU COMPILER  */

    __inline int32 SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
__asm__ volatile("rsbs	%1, %1, %2\n\trsbmi %1, %1, #0\n\tadd	%0, %0, %1": "=r"(sad): "r"(tmp), "r"(tmp2));
        return sad;
    }

    __inline int32 sad_4pixel(int32 src1, int32 src2, int32 mask)
    {
        int32 x7;

__asm__ volatile("EOR	%1, %2, %0\n\tSUBS  %0, %2, %0\n\tEOR	%1, %1, %0\n\tAND  %1, %3, %1, lsr #1\n\tORRCC	%1, %1, #0x80000000\n\tRSB  %1, %1, %1, lsl #8\n\tADD  %0, %0, %1, asr #7\n\tEOR  %0, %0, %1, asr #7": "=r"(src1), "=&r"(x7): "r"(src2), "r"(mask));

        return src1;
    }

    __inline int32 sad_4pixelN(int32 src1, int32 src2, int32 mask)
    {
        int32 x7;

__asm__ volatile("EOR	%1, %2, %0\n\tADDS  %0, %2, %0\n\tEOR  %1, %1, %0\n\tANDS  %1, %3, %1, rrx\n\tRSB  %1, %1, %1, lsl #8\n\tSUB	%0, %0, %1, asr #7\n\tEOR   %0, %0, %1, asr #7": "=r"(src1), "=&r"(x7): "r"(src2), "r"(mask));

        return src1;
    }

#define sum_accumulate  __asm__ volatile("SBC  %0, %0, %1\n\tBIC   %1, %4, %1\n\tADD   %2, %2, %1, lsr #8\n\tSBC   %0, %0, %3\n\tBIC   %3, %4, %3\n\tADD   %2, %2, %3, lsr #8": "=&r" (x5), "=&r" (x10), "=&r" (x4), "=&r" (x11): "r" (x6));

#define NUMBER 3
#define SHIFT 24
#define INC_X8 0x08000001

#include "sad_mb_offset.h"

#undef NUMBER
#define NUMBER 2
#undef SHIFT
#define SHIFT 16
#undef INC_X8
#define INC_X8 0x10000001
#include "sad_mb_offset.h"

#undef NUMBER
#define NUMBER 1
#undef SHIFT
#define SHIFT 8
#undef INC_X8
#define INC_X8 0x08000001
#include "sad_mb_offset.h"


    __inline int32 simd_sad_mb(uint8 *ref, uint8 *blk, int dmin, int lx)
    {
        int32 x4, x5, x6, x8, x9, x10, x11, x12, x14;

        x9 = 0x80808080; /* const. */
        x4 = x5 = 0;

        x8 = (uint32)ref & 0x3;
        if (x8 == 3)
            goto SadMBOffset3;
        if (x8 == 2)
            goto SadMBOffset2;
        if (x8 == 1)
            goto SadMBOffset1;

        x8 = 16;
///
__asm__ volatile("MVN	%0, #0xFF00": "=r"(x6));

LOOP_SAD0:
        /****** process 8 pixels ******/
        x11 = *((int32*)(ref + 12));
        x10 = *((int32*)(ref + 8));
        x14 = *((int32*)(blk + 12));
        x12 = *((int32*)(blk + 8));

        /* process x11 & x14 */
        x11 = sad_4pixel(x11, x14, x9);

        /* process x12 & x10 */
        x10 = sad_4pixel(x10, x12, x9);

        x5 = x5 + x10;  /* accumulate low bytes */
        x10 = x10 & (x6 << 8); /* x10 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x10 >> 8); /* accumulate high bytes */
        x5 = x5 + x11;  /* accumulate low bytes */
        x11 = x11 & (x6 << 8); /* x11 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x11 >> 8);  /* accumulate high bytes */

        /****** process 8 pixels ******/
        x11 = *((int32*)(ref + 4));
__asm__ volatile("LDR	%0, [%1], %2": "=&r"(x10), "=r"(ref): "r"(lx));
        //x10 = *((int32*)ref); ref+=lx;
        x14 = *((int32*)(blk + 4));
__asm__ volatile("LDR	%0, [%1], #16": "=&r"(x12), "=r"(blk));

        /* process x11 & x14 */
        x11 = sad_4pixel(x11, x14, x9);

        /* process x12 & x10 */
        x10 = sad_4pixel(x10, x12, x9);

        x5 = x5 + x10;  /* accumulate low bytes */
        x10 = x10 & (x6 << 8); /* x10 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x10 >> 8); /* accumulate high bytes */
        x5 = x5 + x11;  /* accumulate low bytes */
        x11 = x11 & (x6 << 8); /* x11 & 0xFF00FF00 */
        x4 = x4 + ((uint32)x11 >> 8);  /* accumulate high bytes */

        /****************/
        x10 = x5 - (x4 << 8); /* extract low bytes */
        x10 = x10 + x4;     /* add with high bytes */
        x10 = x10 + (x10 << 16); /* add with lower half word */

        /****************/

        if (((uint32)x10 >> 16) <= dmin) /* compare with dmin */
        {
            if (--x8)
            {
                goto LOOP_SAD0;
            }

        }

        return ((uint32)x10 >> 16);

SadMBOffset3:

        return sad_mb_offset3(ref, blk, lx, dmin);

SadMBOffset2:

        return sad_mb_offset2(ref, blk, lx, dmin);

SadMBOffset1:

        return sad_mb_offset1(ref, blk, lx, dmin);
    }


#endif

#ifdef __cplusplus
}
#endif

#endif // _SAD_INLINE_H_

