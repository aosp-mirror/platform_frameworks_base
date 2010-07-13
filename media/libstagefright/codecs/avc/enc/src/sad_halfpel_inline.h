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

#ifndef _SAD_HALFPEL_INLINE_H_
#define _SAD_HALFPEL_INLINE_H_

#ifdef __cplusplus
extern "C"
{
#endif

#if defined(__GNUC__) && defined(__arm__) /* ARM GNU COMPILER  */

    __inline int32 INTERP1_SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
        tmp = (tmp2 >> 1) - tmp;
        if (tmp > 0) sad += tmp;
        else sad -= tmp;

        return sad;
    }

    __inline int32 INTERP2_SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
        tmp = (tmp >> 2) - tmp2;
        if (tmp > 0) sad += tmp;
        else sad -= tmp;

        return sad;
    }

#elif defined(__CC_ARM)  /* only work with arm v5 */

    __inline int32 INTERP1_SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
        __asm
        {
            rsbs    tmp, tmp, tmp2, asr #1 ;
            rsbmi   tmp, tmp, #0 ;
            add     sad, sad, tmp ;
        }

        return sad;
    }

    __inline int32 INTERP2_SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
        __asm
        {
            rsbs    tmp, tmp2, tmp, asr #2 ;
            rsbmi   tmp, tmp, #0 ;
            add     sad, sad, tmp ;
        }

        return sad;
    }

#elif defined(__GNUC__) && defined(__arm__) /* ARM GNU COMPILER  */

    __inline int32 INTERP1_SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
__asm__ volatile("rsbs	%1, %1, %2, asr #1\n\trsbmi %1, %1, #0\n\tadd  %0, %0, %1": "=r"(sad), "=r"(tmp): "r"(tmp2));

        return sad;
    }

    __inline int32 INTERP2_SUB_SAD(int32 sad, int32 tmp, int32 tmp2)
    {
__asm__ volatile("rsbs	%1, %2, %1, asr #2\n\trsbmi %1, %1, #0\n\tadd	%0, %0, %1": "=r"(sad), "=r"(tmp): "r"(tmp2));

        return sad;
    }

#endif

#ifdef __cplusplus
}
#endif

#endif //_SAD_HALFPEL_INLINE_H_

