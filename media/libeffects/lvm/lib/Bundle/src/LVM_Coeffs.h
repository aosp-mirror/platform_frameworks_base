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

#ifndef __LVM_COEFFS_H__
#define __LVM_COEFFS_H__


/************************************************************************************/
/*                                                                                  */
/* High Pass Shelving Filter coefficients                                           */
/*                                                                                  */
/************************************************************************************/

#define TrebleBoostCorner                                  8000
#define TrebleBoostMinRate                                     4
#define TrebleBoostSteps                                    15


/* Coefficients for sample rate 22050Hz */
                                                                    /* Gain =  1.000000 dB */
#define HPF_Fs22050_Gain1_A0                             5383         /* Floating point value 0.164291 */
#define HPF_Fs22050_Gain1_A1                            16859         /* Floating point value 0.514492 */
#define HPF_Fs22050_Gain1_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain1_B1                            12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain1_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain1_Shift                             1         /* Shift value */
                                                                    /* Gain =  2.000000 dB */
#define HPF_Fs22050_Gain2_A0                             4683         /* Floating point value 0.142925 */
#define HPF_Fs22050_Gain2_A1                            17559         /* Floating point value 0.535858 */
#define HPF_Fs22050_Gain2_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain2_B1                            12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain2_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain2_Shift                             1         /* Shift value */
                                                                    /* Gain =  3.000000 dB */
#define HPF_Fs22050_Gain3_A0                             3898         /* Floating point value 0.118953 */
#define HPF_Fs22050_Gain3_A1                            18345         /* Floating point value 0.559830 */
#define HPF_Fs22050_Gain3_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain3_B1                            12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain3_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain3_Shift                             1         /* Shift value */
                                                                    /* Gain =  4.000000 dB */
#define HPF_Fs22050_Gain4_A0                             3016         /* Floating point value 0.092055 */
#define HPF_Fs22050_Gain4_A1                            19226         /* Floating point value 0.586728 */
#define HPF_Fs22050_Gain4_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain4_B1                            12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain4_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain4_Shift                             1         /* Shift value */
                                                                    /* Gain =  5.000000 dB */
#define HPF_Fs22050_Gain5_A0                             2028         /* Floating point value 0.061876 */
#define HPF_Fs22050_Gain5_A1                            20215         /* Floating point value 0.616907 */
#define HPF_Fs22050_Gain5_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain5_B1                            12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain5_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain5_Shift                             1         /* Shift value */
                                                                    /* Gain =  6.000000 dB */
#define HPF_Fs22050_Gain6_A0                              918         /* Floating point value 0.028013 */
#define HPF_Fs22050_Gain6_A1                            21324         /* Floating point value 0.650770 */
#define HPF_Fs22050_Gain6_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain6_B1                            12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain6_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain6_Shift                             1         /* Shift value */
                                                                    /* Gain =  7.000000 dB */
#define HPF_Fs22050_Gain7_A0                             -164         /* Floating point value -0.005002 */
#define HPF_Fs22050_Gain7_A1                            11311         /* Floating point value 0.345199 */
#define HPF_Fs22050_Gain7_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain7_B1                            12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain7_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain7_Shift                             2         /* Shift value */
                                                                    /* Gain =  8.000000 dB */
#define HPF_Fs22050_Gain8_A0                             -864         /* Floating point value -0.026368 */
#define HPF_Fs22050_Gain8_A1                            12012         /* Floating point value 0.366565 */
#define HPF_Fs22050_Gain8_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain8_B1                            12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain8_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain8_Shift                             2         /* Shift value */
                                                                    /* Gain =  9.000000 dB */
#define HPF_Fs22050_Gain9_A0                            -1650         /* Floating point value -0.050340 */
#define HPF_Fs22050_Gain9_A1                            12797         /* Floating point value 0.390537 */
#define HPF_Fs22050_Gain9_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain9_B1                            12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain9_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain9_Shift                             2         /* Shift value */
                                                                    /* Gain =  10.000000 dB */
#define HPF_Fs22050_Gain10_A0                           -2531         /* Floating point value -0.077238 */
#define HPF_Fs22050_Gain10_A1                           13679         /* Floating point value 0.417435 */
#define HPF_Fs22050_Gain10_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain10_B1                           12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain10_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain10_Shift                            2         /* Shift value */
                                                                    /* Gain =  11.000000 dB */
#define HPF_Fs22050_Gain11_A0                           -3520         /* Floating point value -0.107417 */
#define HPF_Fs22050_Gain11_A1                           14667         /* Floating point value 0.447615 */
#define HPF_Fs22050_Gain11_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain11_B1                           12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain11_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain11_Shift                            2         /* Shift value */
                                                                    /* Gain =  12.000000 dB */
#define HPF_Fs22050_Gain12_A0                           -4629         /* Floating point value -0.141279 */
#define HPF_Fs22050_Gain12_A1                           15777         /* Floating point value 0.481477 */
#define HPF_Fs22050_Gain12_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain12_B1                           12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain12_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain12_Shift                            2         /* Shift value */
                                                                    /* Gain =  13.000000 dB */
#define HPF_Fs22050_Gain13_A0                           -2944         /* Floating point value -0.089849 */
#define HPF_Fs22050_Gain13_A1                            8531         /* Floating point value 0.260352 */
#define HPF_Fs22050_Gain13_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain13_B1                           12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain13_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain13_Shift                            3         /* Shift value */
                                                                    /* Gain =  14.000000 dB */
#define HPF_Fs22050_Gain14_A0                           -3644         /* Floating point value -0.111215 */
#define HPF_Fs22050_Gain14_A1                            9231         /* Floating point value 0.281718 */
#define HPF_Fs22050_Gain14_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain14_B1                           12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain14_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain14_Shift                            3         /* Shift value */
                                                                    /* Gain =  15.000000 dB */
#define HPF_Fs22050_Gain15_A0                           -4430         /* Floating point value -0.135187 */
#define HPF_Fs22050_Gain15_A1                           10017         /* Floating point value 0.305690 */
#define HPF_Fs22050_Gain15_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain15_B1                           12125         /* Floating point value 0.370033 */
#define HPF_Fs22050_Gain15_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs22050_Gain15_Shift                            3         /* Shift value */


/* Coefficients for sample rate 24000Hz */
                                                                    /* Gain =  1.000000 dB */
#define HPF_Fs24000_Gain1_A0                             3625         /* Floating point value 0.110628 */
#define HPF_Fs24000_Gain1_A1                            16960         /* Floating point value 0.517578 */
#define HPF_Fs24000_Gain1_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain1_B1                             8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain1_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain1_Shift                             1         /* Shift value */
                                                                    /* Gain =  2.000000 dB */
#define HPF_Fs24000_Gain2_A0                             2811         /* Floating point value 0.085800 */
#define HPF_Fs24000_Gain2_A1                            17774         /* Floating point value 0.542406 */
#define HPF_Fs24000_Gain2_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain2_B1                             8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain2_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain2_Shift                             1         /* Shift value */
                                                                    /* Gain =  3.000000 dB */
#define HPF_Fs24000_Gain3_A0                             1899         /* Floating point value 0.057943 */
#define HPF_Fs24000_Gain3_A1                            18686         /* Floating point value 0.570263 */
#define HPF_Fs24000_Gain3_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain3_B1                             8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain3_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain3_Shift                             1         /* Shift value */
                                                                    /* Gain =  4.000000 dB */
#define HPF_Fs24000_Gain4_A0                              874         /* Floating point value 0.026687 */
#define HPF_Fs24000_Gain4_A1                            19711         /* Floating point value 0.601519 */
#define HPF_Fs24000_Gain4_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain4_B1                             8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain4_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain4_Shift                             1         /* Shift value */
                                                                    /* Gain =  5.000000 dB */
#define HPF_Fs24000_Gain5_A0                             -275         /* Floating point value -0.008383 */
#define HPF_Fs24000_Gain5_A1                            20860         /* Floating point value 0.636589 */
#define HPF_Fs24000_Gain5_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain5_B1                             8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain5_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain5_Shift                             1         /* Shift value */
                                                                    /* Gain =  6.000000 dB */
#define HPF_Fs24000_Gain6_A0                            -1564         /* Floating point value -0.047733 */
#define HPF_Fs24000_Gain6_A1                            22149         /* Floating point value 0.675938 */
#define HPF_Fs24000_Gain6_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain6_B1                             8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain6_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain6_Shift                             1         /* Shift value */
                                                                    /* Gain =  7.000000 dB */
#define HPF_Fs24000_Gain7_A0                            -1509         /* Floating point value -0.046051 */
#define HPF_Fs24000_Gain7_A1                            11826         /* Floating point value 0.360899 */
#define HPF_Fs24000_Gain7_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain7_B1                             8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain7_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain7_Shift                             2         /* Shift value */
                                                                    /* Gain =  8.000000 dB */
#define HPF_Fs24000_Gain8_A0                            -2323         /* Floating point value -0.070878 */
#define HPF_Fs24000_Gain8_A1                            12640         /* Floating point value 0.385727 */
#define HPF_Fs24000_Gain8_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain8_B1                             8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain8_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain8_Shift                             2         /* Shift value */
                                                                    /* Gain =  9.000000 dB */
#define HPF_Fs24000_Gain9_A0                            -3235         /* Floating point value -0.098736 */
#define HPF_Fs24000_Gain9_A1                            13552         /* Floating point value 0.413584 */
#define HPF_Fs24000_Gain9_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain9_B1                             8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain9_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain9_Shift                             2         /* Shift value */
                                                                    /* Gain =  10.000000 dB */
#define HPF_Fs24000_Gain10_A0                           -4260         /* Floating point value -0.129992 */
#define HPF_Fs24000_Gain10_A1                           14577         /* Floating point value 0.444841 */
#define HPF_Fs24000_Gain10_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain10_B1                            8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain10_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain10_Shift                            2         /* Shift value */
                                                                    /* Gain =  11.000000 dB */
#define HPF_Fs24000_Gain11_A0                           -5409         /* Floating point value -0.165062 */
#define HPF_Fs24000_Gain11_A1                           15726         /* Floating point value 0.479911 */
#define HPF_Fs24000_Gain11_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain11_B1                            8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain11_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain11_Shift                            2         /* Shift value */
                                                                    /* Gain =  12.000000 dB */
#define HPF_Fs24000_Gain12_A0                           -6698         /* Floating point value -0.204411 */
#define HPF_Fs24000_Gain12_A1                           17015         /* Floating point value 0.519260 */
#define HPF_Fs24000_Gain12_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain12_B1                            8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain12_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain12_Shift                            2         /* Shift value */
                                                                    /* Gain =  13.000000 dB */
#define HPF_Fs24000_Gain13_A0                           -4082         /* Floating point value -0.124576 */
#define HPF_Fs24000_Gain13_A1                            9253         /* Floating point value 0.282374 */
#define HPF_Fs24000_Gain13_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain13_B1                            8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain13_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain13_Shift                            3         /* Shift value */
                                                                    /* Gain =  14.000000 dB */
#define HPF_Fs24000_Gain14_A0                           -4896         /* Floating point value -0.149404 */
#define HPF_Fs24000_Gain14_A1                           10066         /* Floating point value 0.307202 */
#define HPF_Fs24000_Gain14_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain14_B1                            8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain14_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain14_Shift                            3         /* Shift value */
                                                                    /* Gain =  15.000000 dB */
#define HPF_Fs24000_Gain15_A0                           -5808         /* Floating point value -0.177261 */
#define HPF_Fs24000_Gain15_A1                           10979         /* Floating point value 0.335059 */
#define HPF_Fs24000_Gain15_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain15_B1                            8780         /* Floating point value 0.267949 */
#define HPF_Fs24000_Gain15_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs24000_Gain15_Shift                            3         /* Shift value */


/* Coefficients for sample rate 32000Hz */
                                                                    /* Gain =  1.000000 dB */
#define HPF_Fs32000_Gain1_A0                            17225         /* Floating point value 0.525677 */
#define HPF_Fs32000_Gain1_A1                             -990         /* Floating point value -0.030227 */
#define HPF_Fs32000_Gain1_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain1_B1                                0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain1_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain1_Shift                             1         /* Shift value */
                                                                    /* Gain =  2.000000 dB */
#define HPF_Fs32000_Gain2_A0                            18337         /* Floating point value 0.559593 */
#define HPF_Fs32000_Gain2_A1                            -2102         /* Floating point value -0.064142 */
#define HPF_Fs32000_Gain2_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain2_B1                                0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain2_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain2_Shift                             1         /* Shift value */
                                                                    /* Gain =  3.000000 dB */
#define HPF_Fs32000_Gain3_A0                            19584         /* Floating point value 0.597646 */
#define HPF_Fs32000_Gain3_A1                            -3349         /* Floating point value -0.102196 */
#define HPF_Fs32000_Gain3_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain3_B1                                0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain3_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain3_Shift                             1         /* Shift value */
                                                                    /* Gain =  4.000000 dB */
#define HPF_Fs32000_Gain4_A0                            20983         /* Floating point value 0.640343 */
#define HPF_Fs32000_Gain4_A1                            -4748         /* Floating point value -0.144893 */
#define HPF_Fs32000_Gain4_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain4_B1                                0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain4_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain4_Shift                             1         /* Shift value */
                                                                    /* Gain =  5.000000 dB */
#define HPF_Fs32000_Gain5_A0                            22553         /* Floating point value 0.688250 */
#define HPF_Fs32000_Gain5_A1                            -6318         /* Floating point value -0.192799 */
#define HPF_Fs32000_Gain5_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain5_B1                                0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain5_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain5_Shift                             1         /* Shift value */
                                                                    /* Gain =  6.000000 dB */
#define HPF_Fs32000_Gain6_A0                            24314         /* Floating point value 0.742002 */
#define HPF_Fs32000_Gain6_A1                            -8079         /* Floating point value -0.246551 */
#define HPF_Fs32000_Gain6_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain6_B1                                0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain6_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain6_Shift                             1         /* Shift value */
                                                                    /* Gain =  7.000000 dB */
#define HPF_Fs32000_Gain7_A0                            13176         /* Floating point value 0.402109 */
#define HPF_Fs32000_Gain7_A1                            -5040         /* Floating point value -0.153795 */
#define HPF_Fs32000_Gain7_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain7_B1                                0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain7_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain7_Shift                             2         /* Shift value */
                                                                    /* Gain =  8.000000 dB */
#define HPF_Fs32000_Gain8_A0                            14288         /* Floating point value 0.436024 */
#define HPF_Fs32000_Gain8_A1                            -6151         /* Floating point value -0.187711 */
#define HPF_Fs32000_Gain8_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain8_B1                                0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain8_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain8_Shift                             2         /* Shift value */
                                                                    /* Gain =  9.000000 dB */
#define HPF_Fs32000_Gain9_A0                            15535         /* Floating point value 0.474078 */
#define HPF_Fs32000_Gain9_A1                            -7398         /* Floating point value -0.225764 */
#define HPF_Fs32000_Gain9_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain9_B1                                0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain9_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain9_Shift                             2         /* Shift value */
                                                                    /* Gain =  10.000000 dB */
#define HPF_Fs32000_Gain10_A0                           16934         /* Floating point value 0.516774 */
#define HPF_Fs32000_Gain10_A1                           -8797         /* Floating point value -0.268461 */
#define HPF_Fs32000_Gain10_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain10_B1                               0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain10_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain10_Shift                            2         /* Shift value */
                                                                    /* Gain =  11.000000 dB */
#define HPF_Fs32000_Gain11_A0                           18503         /* Floating point value 0.564681 */
#define HPF_Fs32000_Gain11_A1                          -10367         /* Floating point value -0.316368 */
#define HPF_Fs32000_Gain11_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain11_B1                               0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain11_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain11_Shift                            2         /* Shift value */
                                                                    /* Gain =  12.000000 dB */
#define HPF_Fs32000_Gain12_A0                           20265         /* Floating point value 0.618433 */
#define HPF_Fs32000_Gain12_A1                          -12128         /* Floating point value -0.370120 */
#define HPF_Fs32000_Gain12_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain12_B1                               0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain12_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain12_Shift                            2         /* Shift value */
                                                                    /* Gain =  13.000000 dB */
#define HPF_Fs32000_Gain13_A0                           11147         /* Floating point value 0.340178 */
#define HPF_Fs32000_Gain13_A1                           -7069         /* Floating point value -0.215726 */
#define HPF_Fs32000_Gain13_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain13_B1                               0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain13_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain13_Shift                            3         /* Shift value */
                                                                    /* Gain =  14.000000 dB */
#define HPF_Fs32000_Gain14_A0                           12258         /* Floating point value 0.374093 */
#define HPF_Fs32000_Gain14_A1                           -8180         /* Floating point value -0.249642 */
#define HPF_Fs32000_Gain14_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain14_B1                               0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain14_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain14_Shift                            3         /* Shift value */
                                                                    /* Gain =  15.000000 dB */
#define HPF_Fs32000_Gain15_A0                           13505         /* Floating point value 0.412147 */
#define HPF_Fs32000_Gain15_A1                           -9427         /* Floating point value -0.287695 */
#define HPF_Fs32000_Gain15_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain15_B1                               0         /* Floating point value -0.000000 */
#define HPF_Fs32000_Gain15_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs32000_Gain15_Shift                            3         /* Shift value */


/* Coefficients for sample rate 44100Hz */
                                                                    /* Gain =  1.000000 dB */
#define HPF_Fs44100_Gain1_A0                            17442         /* Floating point value 0.532294 */
#define HPF_Fs44100_Gain1_A1                            -4761         /* Floating point value -0.145294 */
#define HPF_Fs44100_Gain1_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain1_B1                            -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain1_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain1_Shift                             1         /* Shift value */
                                                                    /* Gain =  2.000000 dB */
#define HPF_Fs44100_Gain2_A0                            18797         /* Floating point value 0.573633 */
#define HPF_Fs44100_Gain2_A1                            -6116         /* Floating point value -0.186634 */
#define HPF_Fs44100_Gain2_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain2_B1                            -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain2_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain2_Shift                             1         /* Shift value */
                                                                    /* Gain =  3.000000 dB */
#define HPF_Fs44100_Gain3_A0                            20317         /* Floating point value 0.620016 */
#define HPF_Fs44100_Gain3_A1                            -7635         /* Floating point value -0.233017 */
#define HPF_Fs44100_Gain3_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain3_B1                            -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain3_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain3_Shift                             1         /* Shift value */
                                                                    /* Gain =  4.000000 dB */
#define HPF_Fs44100_Gain4_A0                            22022         /* Floating point value 0.672059 */
#define HPF_Fs44100_Gain4_A1                            -9341         /* Floating point value -0.285060 */
#define HPF_Fs44100_Gain4_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain4_B1                            -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain4_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain4_Shift                             1         /* Shift value */
                                                                    /* Gain =  5.000000 dB */
#define HPF_Fs44100_Gain5_A0                            23935         /* Floating point value 0.730452 */
#define HPF_Fs44100_Gain5_A1                           -11254         /* Floating point value -0.343453 */
#define HPF_Fs44100_Gain5_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain5_B1                            -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain5_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain5_Shift                             1         /* Shift value */
                                                                    /* Gain =  6.000000 dB */
#define HPF_Fs44100_Gain6_A0                            26082         /* Floating point value 0.795970 */
#define HPF_Fs44100_Gain6_A1                           -13401         /* Floating point value -0.408971 */
#define HPF_Fs44100_Gain6_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain6_B1                            -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain6_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain6_Shift                             1         /* Shift value */
                                                                    /* Gain =  7.000000 dB */
#define HPF_Fs44100_Gain7_A0                            14279         /* Floating point value 0.435774 */
#define HPF_Fs44100_Gain7_A1                            -7924         /* Floating point value -0.241815 */
#define HPF_Fs44100_Gain7_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain7_B1                            -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain7_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain7_Shift                             2         /* Shift value */
                                                                    /* Gain =  8.000000 dB */
#define HPF_Fs44100_Gain8_A0                            15634         /* Floating point value 0.477113 */
#define HPF_Fs44100_Gain8_A1                            -9278         /* Floating point value -0.283154 */
#define HPF_Fs44100_Gain8_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain8_B1                            -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain8_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain8_Shift                             2         /* Shift value */
                                                                    /* Gain =  9.000000 dB */
#define HPF_Fs44100_Gain9_A0                            17154         /* Floating point value 0.523496 */
#define HPF_Fs44100_Gain9_A1                           -10798         /* Floating point value -0.329537 */
#define HPF_Fs44100_Gain9_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain9_B1                            -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain9_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain9_Shift                             2         /* Shift value */
                                                                    /* Gain =  10.000000 dB */
#define HPF_Fs44100_Gain10_A0                           18859         /* Floating point value 0.575539 */
#define HPF_Fs44100_Gain10_A1                          -12504         /* Floating point value -0.381580 */
#define HPF_Fs44100_Gain10_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain10_B1                           -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain10_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain10_Shift                            2         /* Shift value */
                                                                    /* Gain =  11.000000 dB */
#define HPF_Fs44100_Gain11_A0                           20773         /* Floating point value 0.633932 */
#define HPF_Fs44100_Gain11_A1                          -14417         /* Floating point value -0.439973 */
#define HPF_Fs44100_Gain11_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain11_B1                           -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain11_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain11_Shift                            2         /* Shift value */
                                                                    /* Gain =  12.000000 dB */
#define HPF_Fs44100_Gain12_A0                           22920         /* Floating point value 0.699450 */
#define HPF_Fs44100_Gain12_A1                          -16564         /* Floating point value -0.505491 */
#define HPF_Fs44100_Gain12_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain12_B1                           -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain12_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain12_Shift                            2         /* Shift value */
                                                                    /* Gain =  13.000000 dB */
#define HPF_Fs44100_Gain13_A0                           12694         /* Floating point value 0.387399 */
#define HPF_Fs44100_Gain13_A1                           -9509         /* Floating point value -0.290189 */
#define HPF_Fs44100_Gain13_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain13_B1                           -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain13_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain13_Shift                            3         /* Shift value */
                                                                    /* Gain =  14.000000 dB */
#define HPF_Fs44100_Gain14_A0                           14049         /* Floating point value 0.428738 */
#define HPF_Fs44100_Gain14_A1                          -10864         /* Floating point value -0.331528 */
#define HPF_Fs44100_Gain14_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain14_B1                           -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain14_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain14_Shift                            3         /* Shift value */
                                                                    /* Gain =  15.000000 dB */
#define HPF_Fs44100_Gain15_A0                           15569         /* Floating point value 0.475121 */
#define HPF_Fs44100_Gain15_A1                          -12383         /* Floating point value -0.377912 */
#define HPF_Fs44100_Gain15_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain15_B1                           -7173         /* Floating point value -0.218894 */
#define HPF_Fs44100_Gain15_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs44100_Gain15_Shift                            3         /* Shift value */


/* Coefficients for sample rate 48000Hz */
                                                                    /* Gain =  1.000000 dB */
#define HPF_Fs48000_Gain1_A0                            17491         /* Floating point value 0.533777 */
#define HPF_Fs48000_Gain1_A1                            -5606         /* Floating point value -0.171082 */
#define HPF_Fs48000_Gain1_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain1_B1                            -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain1_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain1_Shift                             1         /* Shift value */
                                                                    /* Gain =  2.000000 dB */
#define HPF_Fs48000_Gain2_A0                            18900         /* Floating point value 0.576779 */
#define HPF_Fs48000_Gain2_A1                            -7015         /* Floating point value -0.214085 */
#define HPF_Fs48000_Gain2_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain2_B1                            -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain2_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain2_Shift                             1         /* Shift value */
                                                                    /* Gain =  3.000000 dB */
#define HPF_Fs48000_Gain3_A0                            20481         /* Floating point value 0.625029 */
#define HPF_Fs48000_Gain3_A1                            -8596         /* Floating point value -0.262335 */
#define HPF_Fs48000_Gain3_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain3_B1                            -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain3_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain3_Shift                             1         /* Shift value */
                                                                    /* Gain =  4.000000 dB */
#define HPF_Fs48000_Gain4_A0                            22255         /* Floating point value 0.679167 */
#define HPF_Fs48000_Gain4_A1                           -10370         /* Floating point value -0.316472 */
#define HPF_Fs48000_Gain4_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain4_B1                            -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain4_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain4_Shift                             1         /* Shift value */
                                                                    /* Gain =  5.000000 dB */
#define HPF_Fs48000_Gain5_A0                            24245         /* Floating point value 0.739910 */
#define HPF_Fs48000_Gain5_A1                           -12361         /* Floating point value -0.377215 */
#define HPF_Fs48000_Gain5_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain5_B1                            -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain5_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain5_Shift                             1         /* Shift value */
                                                                    /* Gain =  6.000000 dB */
#define HPF_Fs48000_Gain6_A0                            26479         /* Floating point value 0.808065 */
#define HPF_Fs48000_Gain6_A1                           -14594         /* Floating point value -0.445370 */
#define HPF_Fs48000_Gain6_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain6_B1                            -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain6_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain6_Shift                             1         /* Shift value */
                                                                    /* Gain =  7.000000 dB */
#define HPF_Fs48000_Gain7_A0                            14527         /* Floating point value 0.443318 */
#define HPF_Fs48000_Gain7_A1                            -8570         /* Floating point value -0.261540 */
#define HPF_Fs48000_Gain7_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain7_B1                            -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain7_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain7_Shift                             2         /* Shift value */
                                                                    /* Gain =  8.000000 dB */
#define HPF_Fs48000_Gain8_A0                            15936         /* Floating point value 0.486321 */
#define HPF_Fs48000_Gain8_A1                            -9979         /* Floating point value -0.304543 */
#define HPF_Fs48000_Gain8_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain8_B1                            -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain8_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain8_Shift                             2         /* Shift value */
                                                                    /* Gain =  9.000000 dB */
#define HPF_Fs48000_Gain9_A0                            17517         /* Floating point value 0.534571 */
#define HPF_Fs48000_Gain9_A1                           -11560         /* Floating point value -0.352793 */
#define HPF_Fs48000_Gain9_A2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain9_B1                            -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain9_B2                                0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain9_Shift                             2         /* Shift value */
                                                                    /* Gain =  10.000000 dB */
#define HPF_Fs48000_Gain10_A0                           19291         /* Floating point value 0.588708 */
#define HPF_Fs48000_Gain10_A1                          -13334         /* Floating point value -0.406930 */
#define HPF_Fs48000_Gain10_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain10_B1                           -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain10_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain10_Shift                            2         /* Shift value */
                                                                    /* Gain =  11.000000 dB */
#define HPF_Fs48000_Gain11_A0                           21281         /* Floating point value 0.649452 */
#define HPF_Fs48000_Gain11_A1                          -15325         /* Floating point value -0.467674 */
#define HPF_Fs48000_Gain11_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain11_B1                           -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain11_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain11_Shift                            2         /* Shift value */
                                                                    /* Gain =  12.000000 dB */
#define HPF_Fs48000_Gain12_A0                           23515         /* Floating point value 0.717607 */
#define HPF_Fs48000_Gain12_A1                          -17558         /* Floating point value -0.535829 */
#define HPF_Fs48000_Gain12_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain12_B1                           -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain12_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain12_Shift                            2         /* Shift value */
                                                                    /* Gain =  13.000000 dB */
#define HPF_Fs48000_Gain13_A0                           13041         /* Floating point value 0.397982 */
#define HPF_Fs48000_Gain13_A1                          -10056         /* Floating point value -0.306877 */
#define HPF_Fs48000_Gain13_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain13_B1                           -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain13_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain13_Shift                            3         /* Shift value */
                                                                    /* Gain =  14.000000 dB */
#define HPF_Fs48000_Gain14_A0                           14450         /* Floating point value 0.440984 */
#define HPF_Fs48000_Gain14_A1                          -11465         /* Floating point value -0.349880 */
#define HPF_Fs48000_Gain14_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain14_B1                           -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain14_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain14_Shift                            3         /* Shift value */
                                                                    /* Gain =  15.000000 dB */
#define HPF_Fs48000_Gain15_A0                           16031         /* Floating point value 0.489234 */
#define HPF_Fs48000_Gain15_A1                          -13046         /* Floating point value -0.398130 */
#define HPF_Fs48000_Gain15_A2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain15_B1                           -8780         /* Floating point value -0.267949 */
#define HPF_Fs48000_Gain15_B2                               0         /* Floating point value 0.000000 */
#define HPF_Fs48000_Gain15_Shift                            3         /* Shift value */


#endif
