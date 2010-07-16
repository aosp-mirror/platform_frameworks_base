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
#ifndef _VLC_ENCODE_INLINE_H_
#define _VLC_ENCODE_INLINE_H_

#if !defined(PV_ARM_GCC_V5) && !defined(PV_ARM_GCC_V4)

__inline  Int zero_run_search(UInt *bitmapzz, Short *dataBlock, RunLevelBlock *RLB, Int nc)
{
    Int idx, run, level, j;
    UInt end, match;

    idx = 0;
    j   = 0;
    run = 0;
    match = 1 << 31;
    if (nc > 32)
        end = 1;
    else
        end = 1 << (32 - nc);

    while (match >= end)
    {
        if ((match&bitmapzz[0]) == 0)
        {
            run++;
            j++;
            match >>= 1;
        }
        else
        {
            match >>= 1;
            level = dataBlock[j];
            dataBlock[j] = 0; /* reset output */
            j++;
            if (level < 0)
            {
                RLB->level[idx] = -level;
                RLB->s[idx] = 1;
                RLB->run[idx] = run;
                run = 0;
                idx++;
            }
            else
            {
                RLB->level[idx] = level;
                RLB->s[idx] = 0;
                RLB->run[idx] = run;
                run = 0;
                idx++;
            }
        }
    }
    nc -= 32;
    if (nc > 0)
    {
        match = 1 << 31;
        end = 1 << (32 - nc);
        while (match >= end)
        {
            if ((match&bitmapzz[1]) == 0)
            {
                run++;
                j++;
                match >>= 1;
            }
            else
            {
                match >>= 1;
                level = dataBlock[j];
                dataBlock[j] = 0; /* reset output */
                j++;
                if (level < 0)
                {
                    RLB->level[idx] = -level;
                    RLB->s[idx] = 1;
                    RLB->run[idx] = run;
                    run = 0;
                    idx++;
                }
                else
                {
                    RLB->level[idx] = level;
                    RLB->s[idx] = 0;
                    RLB->run[idx] = run;
                    run = 0;
                    idx++;
                }
            }
        }
    }

    return idx;
}

#elif defined(__CC_ARM)  /* only work with arm v5 */

__inline  Int zero_run_search(UInt *bitmapzz, Short *dataBlock, RunLevelBlock *RLB, Int nc)
{
    OSCL_UNUSED_ARG(nc);
    Int idx, run, level, j;
    UInt end, match;
    Int  zzorder;

    idx = 0;
    run = 0;
    j   = -1;
    __asm
    {
        ldr match, [bitmapzz]
        clz run, match
    }

    zzorder = 0;

    while (run < 32)
    {
        __asm
        {
            mov end, #0x80000000
            mov end, end, lsr run   /* mask*/
            bic match, match, end       /* remove it from bitmap */
            mov run, run, lsl #1  /* 05/09/02 */
            ldrsh level, [dataBlock, run] /*  load data */
            strh zzorder, [dataBlock, run] /* reset output */
            add j, j, #1
            rsb run, j, run, lsr #1 /* delta run */
            add j, j, run           /* current position */
        }
        if (level < 0)
        {
            RLB->level[idx] = -level;
            RLB->s[idx] = 1;
            RLB->run[idx] = run;
            run = 0;
            idx++;
        }
        else
        {
            RLB->level[idx] = level;
            RLB->s[idx] = 0;
            RLB->run[idx] = run;
            run = 0;
            idx++;
        }
        __asm
        {
            clz run, match
        }
    }
    __asm
    {
        ldr match, [bitmapzz, #4]
        clz run, match
    }

    while (run < 32)
    {
        __asm
        {
            mov end, #0x80000000
            mov end, end, lsr run   /* mask*/
            bic match, match, end       /* remove it from bitmap */
            add run, run, #32       /* current position */
            mov run, run, lsl #1    /* 09/02/05 */
            ldrsh level, [dataBlock, run] /*  load data */
            strh  zzorder, [dataBlock, run] /* reset output */
            add j, j, #1
            rsb run, j, run, lsr #1     /* delta run */
            add j, j, run           /* current position */
        }
        if (level < 0)
        {
            RLB->level[idx] = -level;
            RLB->s[idx] = 1;
            RLB->run[idx] = run;
            run = 0;
            idx++;
        }
        else
        {
            RLB->level[idx] = level;
            RLB->s[idx] = 0;
            RLB->run[idx] = run;
            run = 0;
            idx++;
        }
        __asm
        {
            clz run, match
        }
    }

    return idx;
}

#elif ( defined(PV_ARM_GCC_V4) || defined(PV_ARM_GCC_V5) ) /* ARM GNU COMPILER  */

__inline Int m4v_enc_clz(UInt temp)
{
    register Int rb;
    register UInt ra = (UInt)temp;

    asm volatile("clz   %0, %1"
             : "=&r"(rb)
                         : "r"(ra)
                        );

    return (rb);
}

__inline  Int zero_run_search(UInt *bitmapzz, Short *dataBlock, RunLevelBlock *RLB, Int nc)
{
    OSCL_UNUSED_ARG(nc);
    Int idx, run, level = 0, j;
    UInt end = 0, match;
    Int  zzorder;

    idx = 0;
    run = 0;
    j   = -1;
    match = *bitmapzz;
    run = m4v_enc_clz(match);

    zzorder = 0;

    while (run < 32)
    {
        asm volatile("mov   %0, #0x80000000\n\t"
                     "mov   %0, %0, lsr %1\n\t"
                     "bic   %2, %2, %0\n\t"
                     "mov   %1, %1, lsl #1\n\t"
                     "ldrsh %3, [%6, %1]\n\t"
                     "strh  %5, [%6, %1]\n\t"
                     "add   %4, %4, #1\n\t"
                     "rsb   %1, %4, %1, lsr #1\n\t"
                     "add   %4, %4, %1"
             : "+r"(end), "+r"(run), "+r"(match), "=r"(level), "+r"(j)
                             : "r"(zzorder), "r"(dataBlock));
        if (level < 0)
        {
            RLB->level[idx] = -level;
            RLB->s[idx] = 1;
            RLB->run[idx] = run;
            run = 0;
            idx++;
        }
        else
        {
            RLB->level[idx] = level;
            RLB->s[idx] = 0;
            RLB->run[idx] = run;
            run = 0;
            idx++;
        }
        run = m4v_enc_clz(match);
    }
    match = bitmapzz[1];
    run = m4v_enc_clz(match);

    while (run < 32)
    {
        asm volatile("mov   %0, #0x80000000\n\t"
                     "mov   %0, %0, lsr %1\n\t"
                     "bic   %2, %2, %0\n\t"
                     "add   %1, %1, #32\n\t"
                     "mov   %1, %1, lsl #1\n\t"
                     "ldrsh %3, [%6, %1]\n\t"
                     "strh  %5, [%6, %1]\n\t"
                     "add   %4, %4, #1\n\t"
                     "rsb   %1, %4, %1, lsr #1\n\t"
                     "add   %4, %4, %1"
             : "+r"(end), "+r"(run), "+r"(match), "+r"(level), "+r"(j)
                             : "r"(zzorder), "r"(dataBlock));
        if (level < 0)
        {
            RLB->level[idx] = -level;
            RLB->s[idx] = 1;
            RLB->run[idx] = run;
            run = 0;
            idx++;
        }
        else
        {
            RLB->level[idx] = level;
            RLB->s[idx] = 0;
            RLB->run[idx] = run;
            run = 0;
            idx++;
        }
        run = m4v_enc_clz(match);
    }

    return idx;
}

#endif

#endif // _VLC_ENCODE_INLINE_H_


