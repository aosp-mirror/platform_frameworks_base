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
/*********************************************************************************/
/*  Filename: sad_inline.h                                                      */
/*  Description: Implementation for in-line functions used in dct.cpp           */
/*  Modified:                                                                   */
/*********************************************************************************/
#ifndef _SAD_INLINE_H_
#define _SAD_INLINE_H_

#ifdef __cplusplus
extern "C"
{
#endif

#if !defined(PV_ARM_GCC_V5) && !defined(PV_ARM_GCC_V4) /* ARM GNU COMPILER  */

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


    __inline int32 simd_sad_mb(UChar *ref, UChar *blk, Int dmin, Int lx)
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

        if (((uint32)x10 >> 16) <= (uint32)dmin) /* compare with dmin */
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


    __inline int32 simd_sad_mb(UChar *ref, UChar *blk, Int dmin, Int lx)
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


#elif ( defined(PV_ARM_GCC_V5) || defined(PV_ARM_GCC_V4) ) /* ARM GNU COMPILER  */

    __inline int32 SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
        register int32 out;
        register int32 temp1;
        register int32 ss = sad;
        register int32 tt = tmp;
        register int32 uu = tmp2;

        asm volatile("rsbs  %1, %4, %3\n\t"
                     "rsbmi %1, %1, #0\n\t"
                     "add   %0, %2, %1"
             : "=&r"(out),
                     "=&r"(temp1)
                             : "r"(ss),
                             "r"(tt),
                             "r"(uu));
        return out;
    }

    __inline int32 sad_4pixel(int32 src1, int32 src2, int32 mask)
{
        register int32 out;
        register int32 temp1;
        register int32 s1 = src1;
        register int32 s2 = src2;
        register int32 mm = mask;

        asm volatile("eor   %0, %3, %2\n\t"
                     "subs  %1, %3, %2\n\t"
                     "eor   %0, %0, %1\n\t"
                     "and   %0, %4, %0, lsr #1\n\t"
                     "orrcc %0, %0, #0x80000000\n\t"
                     "rsb   %0, %0, %0, lsl #8\n\t"
                     "add   %1, %1, %0, asr #7\n\t"
                     "eor   %1, %1, %0, asr #7"
             : "=&r"(out),
                     "=&r"(temp1)
                             : "r"(s1),
                             "r"(s2),
                             "r"(mm));

        return temp1;
    }

    __inline int32 sad_4pixelN(int32 src1, int32 src2, int32 mask)
{
        register int32 out;
        register int32 temp1;
        register int32 s1 = src1;
        register int32 s2 = src2;
        register int32 mm = mask;

        asm volatile("eor    %1, %3, %2\n\t"
                     "adds   %0, %3, %2\n\t"
                     "eor    %1, %1, %0\n\t"
                     "ands   %1, %4, %1,rrx\n\t"
                     "rsb    %1, %1, %1, lsl #8\n\t"
                     "sub    %0, %0, %1, asr #7\n\t"
                     "eor    %0, %0, %1, asr #7"
             : "=&r"(out),
                     "=&r"(temp1)
                             : "r"(s1),
                             "r"(s2),
                             "r"(mm));

        return (out);
    }

#define sum_accumulate asm volatile("sbc  %0, %0, %1\n\t" \
                                "bic  %1, %4, %1\n\t" \
                                "add  %2, %2, %1, lsr #8\n\t" \
                                "sbc  %0, %0, %3\n\t" \
                                "bic  %3, %4, %3\n\t" \
                                "add  %2, %2, %3, lsr #8" \
                                :"+r"(x5), "+r"(x10), "+r"(x4), "+r"(x11) \
                                :"r"(x6));

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


    __inline int32 simd_sad_mb(UChar *ref, UChar *blk, Int dmin, Int lx)
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

asm volatile("mvn %0, #0xFF00": "=r"(x6));

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

        asm volatile("ldr  %0, [%4, #4]\n\t"
                     "ldr  %1, [%4], %6\n\t"
                     "ldr  %2, [%5, #4]\n\t"
                     "ldr  %3, [%5], #16"
             : "=r"(x11), "=r"(x10), "=r"(x14), "=r"(x12), "+r"(ref), "+r"(blk)
                             : "r"(lx));

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

        if (((uint32)x10 >> 16) <= (uint32)dmin) /* compare with dmin */
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

#endif // OS

#ifdef __cplusplus
}
#endif

#endif // _SAD_INLINE_H_

