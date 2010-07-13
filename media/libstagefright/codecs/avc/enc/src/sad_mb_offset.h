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

#if defined(__GNUC__) && defined(__arm__) /* ARM GNU COMPILER  */

#if (NUMBER==3)
__inline int32 sad_mb_offset3(uint8 *ref, uint8 *blk, int lx, int dmin)
#elif (NUMBER==2)
__inline int32 sad_mb_offset2(uint8 *ref, uint8 *blk, int lx, int dmin)
#elif (NUMBER==1)
__inline int32 sad_mb_offset1(uint8 *ref, uint8 *blk, int lx, int dmin)
#endif
{
    int32 x4, x5, x6, x8, x9, x10, x11, x12, x14;

    //  x5 = (x4<<8) - x4;
    x4 = x5 = 0;
    x6 = 0xFFFF00FF;
    x9 = 0x80808080; /* const. */
    ref -= NUMBER; /* bic ref, ref, #3 */
    ref -= lx;
    blk -= 16;
    x8 = 16;

#if (NUMBER==3)
LOOP_SAD3:
#elif (NUMBER==2)
LOOP_SAD2:
#elif (NUMBER==1)
LOOP_SAD1:
#endif
    /****** process 8 pixels ******/
    x10 = *((uint32*)(ref += lx)); /* D C B A */
    x11 = *((uint32*)(ref + 4));    /* H G F E */
    x12 = *((uint32*)(ref + 8));    /* L K J I */

    x10 = ((uint32)x10 >> SHIFT); /* 0 0 0 D */
    x10 = x10 | (x11 << (32 - SHIFT));        /* G F E D */
    x11 = ((uint32)x11 >> SHIFT); /* 0 0 0 H */
    x11 = x11 | (x12 << (32 - SHIFT));        /* K J I H */

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
    x10 = *((uint32*)(ref + 8)); /* D C B A */
    x11 = *((uint32*)(ref + 12));   /* H G F E */
    x12 = *((uint32*)(ref + 16));   /* L K J I */

    x10 = ((uint32)x10 >> SHIFT); /* mvn x10, x10, lsr #24  = 0xFF 0xFF 0xFF ~D */
    x10 = x10 | (x11 << (32 - SHIFT));        /* bic x10, x10, x11, lsl #8 = ~G ~F ~E ~D */
    x11 = ((uint32)x11 >> SHIFT); /* 0xFF 0xFF 0xFF ~H */
    x11 = x11 | (x12 << (32 - SHIFT));        /* ~K ~J ~I ~H */

    x12 = *((uint32*)(blk + 8));
    x14 = *((uint32*)(blk + 12));

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

    /****************/
    x10 = x5 - (x4 << 8); /* extract low bytes */
    x10 = x10 + x4;     /* add with high bytes */
    x10 = x10 + (x10 << 16); /* add with lower half word */

    if ((int)((uint32)x10 >> 16) <= dmin) /* compare with dmin */
    {
        if (--x8)
        {
#if (NUMBER==3)
            goto         LOOP_SAD3;
#elif (NUMBER==2)
            goto         LOOP_SAD2;
#elif (NUMBER==1)
            goto         LOOP_SAD1;
#endif
        }

    }

    return ((uint32)x10 >> 16);
}

#elif defined(__CC_ARM)  /* only work with arm v5 */

#if (NUMBER==3)
__inline int32 sad_mb_offset3(uint8 *ref, uint8 *blk, int lx, int dmin, int32 x8)
#elif (NUMBER==2)
__inline int32 sad_mb_offset2(uint8 *ref, uint8 *blk, int lx, int dmin, int32 x8)
#elif (NUMBER==1)
__inline int32 sad_mb_offset1(uint8 *ref, uint8 *blk, int lx, int dmin, int32 x8)
#endif
{
    int32 x4, x5, x6, x9, x10, x11, x12, x14;

    x9 = 0x80808080; /* const. */
    x4 = x5 = 0;

    __asm{
        MVN      x6, #0xff0000;
#if (NUMBER==3)
LOOP_SAD3:
#elif (NUMBER==2)
LOOP_SAD2:
#elif (NUMBER==1)
LOOP_SAD1:
#endif
        BIC      ref, ref, #3;
    }
    /****** process 8 pixels ******/
    x11 = *((int32*)(ref + 12));
    x12 = *((int32*)(ref + 16));
    x10 = *((int32*)(ref + 8));
    x14 = *((int32*)(blk + 12));

    __asm{
        MVN      x10, x10, lsr #SHIFT;
        BIC      x10, x10, x11, lsl #(32-SHIFT);
        MVN      x11, x11, lsr #SHIFT;
        BIC      x11, x11, x12, lsl #(32-SHIFT);

        LDR      x12, [blk, #8];
    }

    /* process x11 & x14 */
    x11 = sad_4pixelN(x11, x14, x9);

    /* process x12 & x10 */
    x10 = sad_4pixelN(x10, x12, x9);

    sum_accumulate;

    __asm{
        /****** process 8 pixels ******/
        LDR      x11, [ref, #4];
        LDR      x12, [ref, #8];
        LDR  x10, [ref], lx ;
        LDR  x14, [blk, #4];

        MVN      x10, x10, lsr #SHIFT;
        BIC      x10, x10, x11, lsl #(32-SHIFT);
        MVN      x11, x11, lsr #SHIFT;
        BIC      x11, x11, x12, lsl #(32-SHIFT);

        LDR      x12, [blk], #16;
    }

    /* process x11 & x14 */
    x11 = sad_4pixelN(x11, x14, x9);

    /* process x12 & x10 */
    x10 = sad_4pixelN(x10, x12, x9);

    sum_accumulate;

    /****************/
    x10 = x5 - (x4 << 8); /* extract low bytes */
    x10 = x10 + x4;     /* add with high bytes */
    x10 = x10 + (x10 << 16); /* add with lower half word */

    __asm{
        RSBS     x11, dmin, x10, lsr #16
        ADDLSS   x8, x8, #INC_X8
#if (NUMBER==3)
        BLS      LOOP_SAD3;
#elif (NUMBER==2)
BLS      LOOP_SAD2;
#elif (NUMBER==1)
BLS      LOOP_SAD1;
#endif
    }

    return ((uint32)x10 >> 16);
}

#elif defined(__GNUC__) && defined(__arm__) /* ARM GNU COMPILER  */

#if (NUMBER==3)
__inline int32 sad_mb_offset3(uint8 *ref, uint8 *blk, int lx, int dmin)
#elif (NUMBER==2)
__inline int32 sad_mb_offset2(uint8 *ref, uint8 *blk, int lx, int dmin)
#elif (NUMBER==1)
__inline int32 sad_mb_offset1(uint8 *ref, uint8 *blk, int lx, int dmin)
#endif
{
    int32 x4, x5, x6, x8, x9, x10, x11, x12, x14;

    x9 = 0x80808080; /* const. */
    x4 = x5 = 0;
    x8 = 16; //<<===========*******

__asm__ volatile("MVN	%0, #0xFF0000": "=r"(x6));

#if (NUMBER==3)
LOOP_SAD3:
#elif (NUMBER==2)
LOOP_SAD2:
#elif (NUMBER==1)
LOOP_SAD1:
#endif
__asm__ volatile("BIC  %0, %0, #3": "=r"(ref));
    /****** process 8 pixels ******/
    x11 = *((int32*)(ref + 12));
    x12 = *((int32*)(ref + 16));
    x10 = *((int32*)(ref + 8));
    x14 = *((int32*)(blk + 12));

#if (SHIFT==8)
__asm__ volatile("MVN   %0, %0, lsr #8\n\tBIC   %0, %0, %1,lsl #24\n\tMVN   %1, %1,lsr #8\n\tBIC   %1, %1, %2,lsl #24": "=&r"(x10), "=&r"(x11): "r"(x12));
#elif (SHIFT==16)
__asm__ volatile("MVN   %0, %0, lsr #16\n\tBIC   %0, %0, %1,lsl #16\n\tMVN   %1, %1,lsr #16\n\tBIC   %1, %1, %2,lsl #16": "=&r"(x10), "=&r"(x11): "r"(x12));
#elif (SHIFT==24)
__asm__ volatile("MVN   %0, %0, lsr #24\n\tBIC   %0, %0, %1,lsl #8\n\tMVN   %1, %1,lsr #24\n\tBIC   %1, %1, %2,lsl #8": "=&r"(x10), "=&r"(x11): "r"(x12));
#endif

    x12 = *((int32*)(blk + 8));

    /* process x11 & x14 */
    x11 = sad_4pixelN(x11, x14, x9);

    /* process x12 & x10 */
    x10 = sad_4pixelN(x10, x12, x9);

    sum_accumulate;

    /****** process 8 pixels ******/
    x11 = *((int32*)(ref + 4));
    x12 = *((int32*)(ref + 8));
    x10 = *((int32*)ref); ref += lx;
    x14 = *((int32*)(blk + 4));

#if (SHIFT==8)
__asm__ volatile("MVN   %0, %0, lsr #8\n\tBIC   %0, %0, %1,lsl #24\n\tMVN   %1, %1,lsr #8\n\tBIC   %1, %1, %2,lsl #24": "=&r"(x10), "=&r"(x11): "r"(x12));
#elif (SHIFT==16)
__asm__ volatile("MVN   %0, %0, lsr #16\n\tBIC   %0, %0, %1,lsl #16\n\tMVN   %1, %1,lsr #16\n\tBIC   %1, %1, %2,lsl #16": "=&r"(x10), "=&r"(x11): "r"(x12));
#elif (SHIFT==24)
__asm__ volatile("MVN   %0, %0, lsr #24\n\tBIC   %0, %0, %1,lsl #8\n\tMVN   %1, %1,lsr #24\n\tBIC   %1, %1, %2,lsl #8": "=&r"(x10), "=&r"(x11): "r"(x12));
#endif
__asm__ volatile("LDR   %0, [%1], #16": "=&r"(x12), "=r"(blk));

    /* process x11 & x14 */
    x11 = sad_4pixelN(x11, x14, x9);

    /* process x12 & x10 */
    x10 = sad_4pixelN(x10, x12, x9);

    sum_accumulate;

    /****************/
    x10 = x5 - (x4 << 8); /* extract low bytes */
    x10 = x10 + x4;     /* add with high bytes */
    x10 = x10 + (x10 << 16); /* add with lower half word */

    if (((uint32)x10 >> 16) <= (uint32)dmin) /* compare with dmin */
    {
        if (--x8)
        {
#if (NUMBER==3)
            goto         LOOP_SAD3;
#elif (NUMBER==2)
goto         LOOP_SAD2;
#elif (NUMBER==1)
goto         LOOP_SAD1;
#endif
        }

    }

    return ((uint32)x10 >> 16);
}

#endif

