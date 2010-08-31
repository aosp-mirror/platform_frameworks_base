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


/************************************************************************************/
/*                                                                                  */
/*  Includes                                                                        */
/*                                                                                  */
/************************************************************************************/

#include "LVPSA.h"
#include "LVPSA_QPD.h"
/************************************************************************************/
/*                                                                                  */
/*  Sample rate table                                                               */
/*                                                                                  */
/************************************************************************************/

/*
 * Sample rate table for converting between the enumerated type and the actual
 * frequency
 */
const LVM_UINT16    LVPSA_SampleRateTab[] = {   8000,                    /* 8kS/s  */
                                                11025,
                                                12000,
                                                16000,
                                                22050,
                                                24000,
                                                32000,
                                                44100,
                                                48000};                  /* 48kS/s */

/************************************************************************************/
/*                                                                                  */
/*  Sample rate inverse table                                                       */
/*                                                                                  */
/************************************************************************************/

/*
 * Sample rate table for converting between the enumerated type and the actual
 * frequency
 */
const LVM_UINT32    LVPSA_SampleRateInvTab[] = {    268435,                    /* 8kS/s  */
                                                    194783,
                                                    178957,
                                                    134218,
                                                    97391,
                                                    89478,
                                                    67109,
                                                    48696,
                                                    44739};                  /* 48kS/s */



/************************************************************************************/
/*                                                                                  */
/*  Number of samples in 20ms                                                       */
/*                                                                                  */
/************************************************************************************/

/*
 * Table for converting between the enumerated type and the number of samples
 * during 20ms
 */
const LVM_UINT16    LVPSA_nSamplesBufferUpdate[]  = {   160,                   /* 8kS/s  */
                                                        220,
                                                        240,
                                                        320,
                                                        441,
                                                        480,
                                                        640,
                                                        882,
                                                        960};                  /* 48kS/s */
/************************************************************************************/
/*                                                                                  */
/*  Down sampling factors                                                           */
/*                                                                                  */
/************************************************************************************/

/*
 * Table for converting between the enumerated type and the down sampling factor
 */
const LVM_UINT16    LVPSA_DownSamplingFactor[]  = {     5,                    /* 8000  S/s  */
                                                        7,                    /* 11025 S/s  */
                                                        8,                    /* 12000 S/s  */
                                                        10,                   /* 16000 S/s  */
                                                        15,                   /* 22050 S/s  */
                                                        16,                   /* 24000 S/s  */
                                                        21,                   /* 32000 S/s  */
                                                        30,                   /* 44100 S/s  */
                                                        32};                  /* 48000 S/s  */


/************************************************************************************/
/*                                                                                  */
/*  Coefficient calculation tables                                                  */
/*                                                                                  */
/************************************************************************************/

/*
 * Table for 2 * Pi / Fs
 */
const LVM_INT16     LVPSA_TwoPiOnFsTable[] = {  26354,      /* 8kS/s */
                                                19123,
                                                17569,
                                                13177,
                                                 9561,
                                                 8785,
                                                 6588,
                                                 4781,
                                                 4392};    /* 48kS/s */

/*
 * Gain table
 */
const LVM_INT16     LVPSA_GainTable[] = {   364,          /* -15dB gain */
                                            408,
                                            458,
                                            514,
                                            577,
                                            647,
                                            726,
                                            815,
                                            914,
                                            1026,
                                            1151,
                                            1292,
                                            1449,
                                            1626,
                                            1825,
                                            2048,         /* 0dB gain */
                                            2297,
                                            2578,
                                            2892,
                                            3245,
                                            3641,
                                            4096,
                                            4584,
                                            5144,
                                            5772,
                                            6476,
                                            7266,
                                            8153,
                                            9148,
                                            10264,
                                            11576};        /* +15dB gain */

/************************************************************************************/
/*                                                                                  */
/*  Cosone polynomial coefficients                                                  */
/*                                                                                  */
/************************************************************************************/

/*
 * Coefficients for calculating the cosine with the equation:
 *
 *  Cos(x) = (2^Shifts)*(a0 + a1*x + a2*x^2 + a3*x^3 + a4*x^4 + a5*x^5)
 *
 * These coefficients expect the input, x, to be in the range 0 to 32768 respresenting
 * a range of 0 to Pi. The output is in the range 32767 to -32768 representing the range
 * +1.0 to -1.0
 */
const LVM_INT16     LVPSA_CosCoef[] = { 3,                             /* Shifts */
                                        4096,                          /* a0 */
                                        -36,                           /* a1 */
                                        -19725,                        /* a2 */
                                        -2671,                         /* a3 */
                                        23730,                         /* a4 */
                                        -9490};                        /* a5 */

/*
 * Coefficients for calculating the cosine error with the equation:
 *
 *  CosErr(x) = (2^Shifts)*(a0 + a1*x + a2*x^2 + a3*x^3)
 *
 * These coefficients expect the input, x, to be in the range 0 to 32768 respresenting
 * a range of 0 to Pi/25. The output is in the range 0 to 32767 representing the range
 * 0.0 to 0.0078852986
 *
 * This is used to give a double precision cosine over the range 0 to Pi/25 using the
 * the equation:
 *
 * Cos(x) = 1.0 - CosErr(x)
 */
const LVM_INT16     LVPSA_DPCosCoef[] = {   1,                           /* Shifts */
                                            0,                           /* a0 */
                                            -6,                          /* a1 */
                                            16586,                       /* a2 */
                                            -44};                        /* a3 */

/************************************************************************************/
/*                                                                                  */
/*  Quasi peak filter coefficients table                                            */
/*                                                                                  */
/************************************************************************************/
const QPD_C32_Coefs     LVPSA_QPD_Coefs[] = {

                                         {0x80CEFD2B,0x00CB9B17},  /* 8kS/s  */    /* LVPSA_SPEED_LOW   */
                                         {0x80D242E7,0x00CED11D},
                                         {0x80DCBAF5,0x00D91679},
                                         {0x80CEFD2B,0x00CB9B17},
                                         {0x80E13739,0x00DD7CD3},
                                         {0x80DCBAF5,0x00D91679},
                                         {0x80D94BAF,0x00D5B7E7},
                                         {0x80E13739,0x00DD7CD3},
                                         {0x80DCBAF5,0x00D91679},  /* 48kS/s */

                                         {0x8587513D,0x055C22CF},  /* 8kS/s  */    /* LVPSA_SPEED_MEDIUM      */
                                         {0x859D2967,0x0570F007},
                                         {0x85E2EFAC,0x05B34D79},
                                         {0x8587513D,0x055C22CF},
                                         {0x8600C7B9,0x05CFA6CF},
                                         {0x85E2EFAC,0x05B34D79},
                                         {0x85CC1018,0x059D8F69},
                                         {0x8600C7B9,0x05CFA6CF},//{0x8600C7B9,0x05CFA6CF},
                                         {0x85E2EFAC,0x05B34D79},  /* 48kS/s */

                                         {0xA115EA7A,0x1CDB3F5C},  /* 8kS/s  */   /* LVPSA_SPEED_HIGH      */
                                         {0xA18475F0,0x1D2C83A2},
                                         {0xA2E1E950,0x1E2A532E},
                                         {0xA115EA7A,0x1CDB3F5C},
                                         {0xA375B2C6,0x1E943BBC},
                                         {0xA2E1E950,0x1E2A532E},
                                         {0xA26FF6BD,0x1DD81530},
                                         {0xA375B2C6,0x1E943BBC},
                                         {0xA2E1E950,0x1E2A532E}}; /* 48kS/s */

