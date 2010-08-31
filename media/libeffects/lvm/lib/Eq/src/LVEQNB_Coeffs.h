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


#ifndef __LVEQNB_COEFFS_H__
#define __LVEQNB_COEFFS_H__


/************************************************************************************/
/*                                                                                  */
/* Gain table for (10^(Gain/20) - 1)                                                */
/*                                                                                  */
/************************************************************************************/

#define LVEQNB_GAINSHIFT                                   11         /* As a power of 2 */
#define LVEQNB_Gain_Neg15_dB                            -1684         /* Floating point value -0.822172 */
#define LVEQNB_Gain_Neg14_dB                            -1639         /* Floating point value -0.800474 */
#define LVEQNB_Gain_Neg13_dB                            -1590         /* Floating point value -0.776128 */
#define LVEQNB_Gain_Neg12_dB                            -1534         /* Floating point value -0.748811 */
#define LVEQNB_Gain_Neg11_dB                            -1471         /* Floating point value -0.718162 */
#define LVEQNB_Gain_Neg10_dB                            -1400         /* Floating point value -0.683772 */
#define LVEQNB_Gain_Neg9_dB                             -1321         /* Floating point value -0.645187 */
#define LVEQNB_Gain_Neg8_dB                             -1233         /* Floating point value -0.601893 */
#define LVEQNB_Gain_Neg7_dB                             -1133         /* Floating point value -0.553316 */
#define LVEQNB_Gain_Neg6_dB                             -1022         /* Floating point value -0.498813 */
#define LVEQNB_Gain_Neg5_dB                              -896         /* Floating point value -0.437659 */
#define LVEQNB_Gain_Neg4_dB                              -756         /* Floating point value -0.369043 */
#define LVEQNB_Gain_Neg3_dB                              -598         /* Floating point value -0.292054 */
#define LVEQNB_Gain_Neg2_dB                              -421         /* Floating point value -0.205672 */
#define LVEQNB_Gain_Neg1_dB                              -223         /* Floating point value -0.108749 */
#define LVEQNB_Gain_0_dB                                    0         /* Floating point value 0.000000 */
#define LVEQNB_Gain_1_dB                                  250         /* Floating point value 0.122018 */
#define LVEQNB_Gain_2_dB                                  530         /* Floating point value 0.258925 */
#define LVEQNB_Gain_3_dB                                  845         /* Floating point value 0.412538 */
#define LVEQNB_Gain_4_dB                                 1198         /* Floating point value 0.584893 */
#define LVEQNB_Gain_5_dB                                 1594         /* Floating point value 0.778279 */
#define LVEQNB_Gain_6_dB                                 2038         /* Floating point value 0.995262 */
#define LVEQNB_Gain_7_dB                                 2537         /* Floating point value 1.238721 */
#define LVEQNB_Gain_8_dB                                 3096         /* Floating point value 1.511886 */
#define LVEQNB_Gain_9_dB                                 3724         /* Floating point value 1.818383 */
#define LVEQNB_Gain_10_dB                                4428         /* Floating point value 2.162278 */
#define LVEQNB_Gain_11_dB                                5219         /* Floating point value 2.548134 */
#define LVEQNB_Gain_12_dB                                6105         /* Floating point value 2.981072 */
#define LVEQNB_Gain_13_dB                                7100         /* Floating point value 3.466836 */
#define LVEQNB_Gain_14_dB                                8216         /* Floating point value 4.011872 */
#define LVEQNB_Gain_15_dB                                9469         /* Floating point value 4.623413 */


/************************************************************************************/
/*                                                                                  */
/* Frequency table for 2*Pi/Fs                                                      */
/*                                                                                  */
/************************************************************************************/

#define LVEQNB_FREQSHIFT                                   25         /* As a power of 2 */
#define LVEQNB_2PiOn_8000                               26354         /* Floating point value 0.000785 */
#define LVEQNB_2PiOn_11025                              19123         /* Floating point value 0.000570 */
#define LVEQNB_2PiOn_12000                              17569         /* Floating point value 0.000524 */
#define LVEQNB_2PiOn_16000                              13177         /* Floating point value 0.000393 */
#define LVEQNB_2PiOn_22050                               9561         /* Floating point value 0.000285 */
#define LVEQNB_2PiOn_24000                               8785         /* Floating point value 0.000262 */
#define LVEQNB_2PiOn_32000                               6588         /* Floating point value 0.000196 */
#define LVEQNB_2PiOn_44100                               4781         /* Floating point value 0.000142 */
#define LVEQNB_2PiOn_48000                               4392         /* Floating point value 0.000131 */


/************************************************************************************/
/*                                                                                  */
/* 50D table for 50 / ( 1 + Gain )                                                  */
/*                                                                                  */
/************************************************************************************/

#define LVEQNB_100DSHIFT                                    5         /* As a power of 2 */
#define LVEQNB_100D_Neg15_dB                            17995         /* Floating point value 5.623413 */
#define LVEQNB_100D_Neg14_dB                            16038         /* Floating point value 5.011872 */
#define LVEQNB_100D_Neg13_dB                            14294         /* Floating point value 4.466836 */
#define LVEQNB_100D_Neg12_dB                            12739         /* Floating point value 3.981072 */
#define LVEQNB_100D_Neg11_dB                            11354         /* Floating point value 3.548134 */
#define LVEQNB_100D_Neg10_dB                            10119         /* Floating point value 3.162278 */
#define LVEQNB_100D_Neg9_dB                              9019         /* Floating point value 2.818383 */
#define LVEQNB_100D_Neg8_dB                              8038         /* Floating point value 2.511886 */
#define LVEQNB_100D_Neg7_dB                              7164         /* Floating point value 2.238721 */
#define LVEQNB_100D_Neg6_dB                              6385         /* Floating point value 1.995262 */
#define LVEQNB_100D_Neg5_dB                              5690         /* Floating point value 1.778279 */
#define LVEQNB_100D_Neg4_dB                              5072         /* Floating point value 1.584893 */
#define LVEQNB_100D_Neg3_dB                              4520         /* Floating point value 1.412538 */
#define LVEQNB_100D_Neg2_dB                              4029         /* Floating point value 1.258925 */
#define LVEQNB_100D_Neg1_dB                              3590         /* Floating point value 1.122018 */
#define LVEQNB_100D_0_dB                                 3200         /* Floating point value 1.000000 */


#endif
