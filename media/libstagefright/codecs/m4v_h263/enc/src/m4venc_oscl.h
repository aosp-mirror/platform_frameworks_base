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
/* Revision History                                                             */
/* Date: 11/04/05                                                               */
/* Description: Created for abstracting out OSCL such that the code can be used */
/*          by both V3 and V4 OSCL library. This file is for V4.                */
/*********************************************************************************/

#ifndef _M4VENC_OSCL_H_
#define _M4VENC_OSCL_H_

#include <stdlib.h>
#include <math.h>

#define M4VENC_MALLOC(size)             malloc(size)
#define M4VENC_FREE(ptr)                free(ptr)

#define M4VENC_MEMSET(ptr,val,size)     memset(ptr,val,size)
#define M4VENC_MEMCPY(dst,src,size)     memcpy(dst,src,size)

#define M4VENC_LOG(x)                   log(x)
#define M4VENC_SQRT(x)                  sqrt(x)
#define M4VENC_POW(x,y)                 pow(x,y)

#define M4VENC_HAS_SYMBIAN_SUPPORT  OSCL_HAS_SYMBIAN_SUPPORT

#endif //_M4VENC_OSCL_H_
