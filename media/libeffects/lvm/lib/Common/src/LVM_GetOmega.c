/*
 * Copyright (C) 2004-2010 NXP Software
 * Copyright (C) 2010 The Android Open Source Project
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

#include "LVM_Types.h"
#include "Filter.h"
#include "LVM_Macros.h"

/************************************************************************************/
/*                                                                                  */
/* Defines and Tables for 2*Pi/Fs                                                   */
/*                                                                                  */
/************************************************************************************/

#define LVVDL_2PiBy_8000        1727108826  /* In Q41 format */
#define LVVDL_2PiBy_11025       1253230894  /* In Q41 format */
#define LVVDL_2PiBy_12000       1151405884  /* In Q41 format */

#define LVVDL_2PiByFs_SHIFT1    12          /* Qformat shift for 8kHz, 11.025kHz and 12kHz i.e. 12=41-29 */
#define LVVDL_2PiByFs_SHIFT2    13          /* Qformat shift for 16kHz, 22.050kHz and 24kHz i.e. 13=42-29 */
#define LVVDL_2PiByFs_SHIFT3    14          /* Qformat shift for 32kHz, 44.1kHz and 48kHz i.e. 14=43-29 */

const LVM_INT32     LVVDL_2PiOnFsTable[] =  {LVVDL_2PiBy_8000 , /* 8kHz in Q41, 16kHz in Q42, 32kHz in Q43 */
                                            LVVDL_2PiBy_11025,  /* 11025 Hz in Q41, 22050Hz in Q42, 44100 Hz in Q43*/
                                            LVVDL_2PiBy_12000}; /* 12kHz in Q41, 24kHz in Q42, 48kHz in Q43 */


const LVM_INT32     LVVDL_2PiOnFsShiftTable[]={LVVDL_2PiByFs_SHIFT1 ,         /* 8kHz, 11025Hz, 12kHz */
                                               LVVDL_2PiByFs_SHIFT2,          /* 16kHz, 22050Hz, 24kHz*/
                                               LVVDL_2PiByFs_SHIFT3};         /* 32kHz, 44100Hz, 48kHz */

/*-------------------------------------------------------------------------*/
/* FUNCTION:                                                               */
/*   LVM_GetOmega                                                          */
/*                                                                         */
/* LVM_INT32 LVM_GetOmega(LVM_UINT16                  Fc,                  */
/*                        LVM_Fs_en                   Fs)                  */
/*                                                                         */
/* DESCRIPTION:                                                            */
/*   This function calculates the value of w using Fc and Fs               */
/*                                                                         */
/* PARAMETERS:                                                             */
/*                                                                         */
/*  LVM_UINT16          Fc     The corner frequency in Hz Q16.0 format     */
/*  LVM_Fs_en           Fs     The SampleRate                              */
/* RETURNS:                                                                */
/*   w=2*pi*Fc/Fs in Q2.29 format                                          */
/*-------------------------------------------------------------------------*/

LVM_INT32 LVM_GetOmega(LVM_UINT16                  Fc,
                       LVM_Fs_en                   Fs)
{
    LVM_INT32   w;
    MUL32x32INTO32((LVM_INT32)Fc,LVVDL_2PiOnFsTable[Fs%3],w,LVVDL_2PiOnFsShiftTable[Fs/3])
    return w;
}

