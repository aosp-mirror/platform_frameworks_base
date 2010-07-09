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

#ifndef __LVDBE_COEFFS_H__
#define __LVDBE_COEFFS_H__


/************************************************************************************/
/*                                                                                  */
/* General                                                                          */
/*                                                                                  */
/************************************************************************************/

#define LVDBE_SCALESHIFT                                    10         /* As a power of 2 */


/************************************************************************************/
/*                                                                                  */
/* High Pass Filter coefficients                                                    */
/*                                                                                  */
/************************************************************************************/

 /* Coefficients for centre frequency 55Hz */
#define HPF_Fs8000_Fc55_A0                         1029556328         /* Floating point value 0.958849 */
#define HPF_Fs8000_Fc55_A1                        -2059112655         /* Floating point value -1.917698 */
#define HPF_Fs8000_Fc55_A2                         1029556328         /* Floating point value 0.958849 */
#define HPF_Fs8000_Fc55_B1                        -2081986375         /* Floating point value -1.939001 */
#define HPF_Fs8000_Fc55_B2                         1010183914         /* Floating point value 0.940807 */
#define HPF_Fs11025_Fc55_A0                        1038210831         /* Floating point value 0.966909 */
#define HPF_Fs11025_Fc55_A1                       -2076421662         /* Floating point value -1.933818 */
#define HPF_Fs11025_Fc55_A2                        1038210831         /* Floating point value 0.966909 */
#define HPF_Fs11025_Fc55_B1                       -2099950710         /* Floating point value -1.955732 */
#define HPF_Fs11025_Fc55_B2                        1027238450         /* Floating point value 0.956690 */
#define HPF_Fs12000_Fc55_A0                        1040079943         /* Floating point value 0.968650 */
#define HPF_Fs12000_Fc55_A1                       -2080159885         /* Floating point value -1.937300 */
#define HPF_Fs12000_Fc55_A2                        1040079943         /* Floating point value 0.968650 */
#define HPF_Fs12000_Fc55_B1                       -2103811702         /* Floating point value -1.959327 */
#define HPF_Fs12000_Fc55_B2                        1030940477         /* Floating point value 0.960138 */
#define HPF_Fs16000_Fc55_A0                        1045381988         /* Floating point value 0.973588 */
#define HPF_Fs16000_Fc55_A1                       -2090763976         /* Floating point value -1.947176 */
#define HPF_Fs16000_Fc55_A2                        1045381988         /* Floating point value 0.973588 */
#define HPF_Fs16000_Fc55_B1                       -2114727793         /* Floating point value -1.969494 */
#define HPF_Fs16000_Fc55_B2                        1041478147         /* Floating point value 0.969952 */
#define HPF_Fs22050_Fc55_A0                        1049766523         /* Floating point value 0.977671 */
#define HPF_Fs22050_Fc55_A1                       -2099533046         /* Floating point value -1.955343 */
#define HPF_Fs22050_Fc55_A2                        1049766523         /* Floating point value 0.977671 */
#define HPF_Fs22050_Fc55_B1                       -2123714381         /* Floating point value -1.977863 */
#define HPF_Fs22050_Fc55_B2                        1050232780         /* Floating point value 0.978105 */
#define HPF_Fs24000_Fc55_A0                        1050711051         /* Floating point value 0.978551 */
#define HPF_Fs24000_Fc55_A1                       -2101422103         /* Floating point value -1.957102 */
#define HPF_Fs24000_Fc55_A2                        1050711051         /* Floating point value 0.978551 */
#define HPF_Fs24000_Fc55_B1                       -2125645498         /* Floating point value -1.979662 */
#define HPF_Fs24000_Fc55_B2                        1052123526         /* Floating point value 0.979866 */
#define HPF_Fs32000_Fc55_A0                        1053385759         /* Floating point value 0.981042 */
#define HPF_Fs32000_Fc55_A1                       -2106771519         /* Floating point value -1.962084 */
#define HPF_Fs32000_Fc55_A2                        1053385759         /* Floating point value 0.981042 */
#define HPF_Fs32000_Fc55_B1                       -2131104794         /* Floating point value -1.984746 */
#define HPF_Fs32000_Fc55_B2                        1057486949         /* Floating point value 0.984861 */
#define HPF_Fs44100_Fc55_A0                        1055592498         /* Floating point value 0.983097 */
#define HPF_Fs44100_Fc55_A1                       -2111184995         /* Floating point value -1.966194 */
#define HPF_Fs44100_Fc55_A2                        1055592498         /* Floating point value 0.983097 */
#define HPF_Fs44100_Fc55_B1                       -2135598658         /* Floating point value -1.988931 */
#define HPF_Fs44100_Fc55_B2                        1061922249         /* Floating point value 0.988992 */
#define HPF_Fs48000_Fc55_A0                        1056067276         /* Floating point value 0.983539 */
#define HPF_Fs48000_Fc55_A1                       -2112134551         /* Floating point value -1.967079 */
#define HPF_Fs48000_Fc55_A2                        1056067276         /* Floating point value 0.983539 */
#define HPF_Fs48000_Fc55_B1                       -2136564296         /* Floating point value -1.989831 */
#define HPF_Fs48000_Fc55_B2                        1062877714         /* Floating point value 0.989882 */

 /* Coefficients for centre frequency 66Hz */
#define HPF_Fs8000_Fc66_A0                         1023293271         /* Floating point value 0.953016 */
#define HPF_Fs8000_Fc66_A1                        -2046586542         /* Floating point value -1.906032 */
#define HPF_Fs8000_Fc66_A2                         1023293271         /* Floating point value 0.953016 */
#define HPF_Fs8000_Fc66_B1                        -2068896860         /* Floating point value -1.926810 */
#define HPF_Fs8000_Fc66_B2                          997931110         /* Floating point value 0.929396 */
#define HPF_Fs11025_Fc66_A0                        1033624228         /* Floating point value 0.962638 */
#define HPF_Fs11025_Fc66_A1                       -2067248455         /* Floating point value -1.925275 */
#define HPF_Fs11025_Fc66_A2                        1033624228         /* Floating point value 0.962638 */
#define HPF_Fs11025_Fc66_B1                       -2090448000         /* Floating point value -1.946881 */
#define HPF_Fs11025_Fc66_B2                        1018182305         /* Floating point value 0.948256 */
#define HPF_Fs12000_Fc66_A0                        1035857662         /* Floating point value 0.964718 */
#define HPF_Fs12000_Fc66_A1                       -2071715325         /* Floating point value -1.929435 */
#define HPF_Fs12000_Fc66_A2                        1035857662         /* Floating point value 0.964718 */
#define HPF_Fs12000_Fc66_B1                       -2095080333         /* Floating point value -1.951196 */
#define HPF_Fs12000_Fc66_B2                        1022587158         /* Floating point value 0.952359 */
#define HPF_Fs16000_Fc66_A0                        1042197528         /* Floating point value 0.970622 */
#define HPF_Fs16000_Fc66_A1                       -2084395056         /* Floating point value -1.941244 */
#define HPF_Fs16000_Fc66_A2                        1042197528         /* Floating point value 0.970622 */
#define HPF_Fs16000_Fc66_B1                       -2108177912         /* Floating point value -1.963394 */
#define HPF_Fs16000_Fc66_B2                        1035142690         /* Floating point value 0.964052 */
#define HPF_Fs22050_Fc66_A0                        1047445145         /* Floating point value 0.975509 */
#define HPF_Fs22050_Fc66_A1                       -2094890289         /* Floating point value -1.951019 */
#define HPF_Fs22050_Fc66_A2                        1047445145         /* Floating point value 0.975509 */
#define HPF_Fs22050_Fc66_B1                       -2118961025         /* Floating point value -1.973436 */
#define HPF_Fs22050_Fc66_B2                        1045593102         /* Floating point value 0.973784 */
#define HPF_Fs24000_Fc66_A0                        1048576175         /* Floating point value 0.976563 */
#define HPF_Fs24000_Fc66_A1                       -2097152349         /* Floating point value -1.953125 */
#define HPF_Fs24000_Fc66_A2                        1048576175         /* Floating point value 0.976563 */
#define HPF_Fs24000_Fc66_B1                       -2121278255         /* Floating point value -1.975594 */
#define HPF_Fs24000_Fc66_B2                        1047852379         /* Floating point value 0.975889 */
#define HPF_Fs32000_Fc66_A0                        1051780119         /* Floating point value 0.979547 */
#define HPF_Fs32000_Fc66_A1                       -2103560237         /* Floating point value -1.959093 */
#define HPF_Fs32000_Fc66_A2                        1051780119         /* Floating point value 0.979547 */
#define HPF_Fs32000_Fc66_B1                       -2127829187         /* Floating point value -1.981695 */
#define HPF_Fs32000_Fc66_B2                        1054265623         /* Floating point value 0.981861 */
#define HPF_Fs44100_Fc66_A0                        1054424722         /* Floating point value 0.982010 */
#define HPF_Fs44100_Fc66_A1                       -2108849444         /* Floating point value -1.964019 */
#define HPF_Fs44100_Fc66_A2                        1054424722         /* Floating point value 0.982010 */
#define HPF_Fs44100_Fc66_B1                       -2133221723         /* Floating point value -1.986718 */
#define HPF_Fs44100_Fc66_B2                        1059573993         /* Floating point value 0.986805 */
#define HPF_Fs48000_Fc66_A0                        1054993851         /* Floating point value 0.982540 */
#define HPF_Fs48000_Fc66_A1                       -2109987702         /* Floating point value -1.965079 */
#define HPF_Fs48000_Fc66_A2                        1054993851         /* Floating point value 0.982540 */
#define HPF_Fs48000_Fc66_B1                       -2134380475         /* Floating point value -1.987797 */
#define HPF_Fs48000_Fc66_B2                        1060718118         /* Floating point value 0.987871 */

 /* Coefficients for centre frequency 78Hz */
#define HPF_Fs8000_Fc78_A0                         1016504203         /* Floating point value 0.946693 */
#define HPF_Fs8000_Fc78_A1                        -2033008405         /* Floating point value -1.893387 */
#define HPF_Fs8000_Fc78_A2                         1016504203         /* Floating point value 0.946693 */
#define HPF_Fs8000_Fc78_B1                        -2054623390         /* Floating point value -1.913517 */
#define HPF_Fs8000_Fc78_B2                          984733853         /* Floating point value 0.917105 */
#define HPF_Fs11025_Fc78_A0                        1028643741         /* Floating point value 0.957999 */
#define HPF_Fs11025_Fc78_A1                       -2057287482         /* Floating point value -1.915998 */
#define HPF_Fs11025_Fc78_A2                        1028643741         /* Floating point value 0.957999 */
#define HPF_Fs11025_Fc78_B1                       -2080083769         /* Floating point value -1.937229 */
#define HPF_Fs11025_Fc78_B2                        1008393904         /* Floating point value 0.939140 */
#define HPF_Fs12000_Fc78_A0                        1031271067         /* Floating point value 0.960446 */
#define HPF_Fs12000_Fc78_A1                       -2062542133         /* Floating point value -1.920892 */
#define HPF_Fs12000_Fc78_A2                        1031271067         /* Floating point value 0.960446 */
#define HPF_Fs12000_Fc78_B1                       -2085557048         /* Floating point value -1.942326 */
#define HPF_Fs12000_Fc78_B2                        1013551620         /* Floating point value 0.943944 */
#define HPF_Fs16000_Fc78_A0                        1038734628         /* Floating point value 0.967397 */
#define HPF_Fs16000_Fc78_A1                       -2077469256         /* Floating point value -1.934794 */
#define HPF_Fs16000_Fc78_A2                        1038734628         /* Floating point value 0.967397 */
#define HPF_Fs16000_Fc78_B1                       -2101033380         /* Floating point value -1.956740 */
#define HPF_Fs16000_Fc78_B2                        1028275228         /* Floating point value 0.957656 */
#define HPF_Fs22050_Fc78_A0                        1044918584         /* Floating point value 0.973156 */
#define HPF_Fs22050_Fc78_A1                       -2089837169         /* Floating point value -1.946313 */
#define HPF_Fs22050_Fc78_A2                        1044918584         /* Floating point value 0.973156 */
#define HPF_Fs22050_Fc78_B1                       -2113775854         /* Floating point value -1.968607 */
#define HPF_Fs22050_Fc78_B2                        1040555007         /* Floating point value 0.969092 */
#define HPF_Fs24000_Fc78_A0                        1046252164         /* Floating point value 0.974398 */
#define HPF_Fs24000_Fc78_A1                       -2092504328         /* Floating point value -1.948797 */
#define HPF_Fs24000_Fc78_A2                        1046252164         /* Floating point value 0.974398 */
#define HPF_Fs24000_Fc78_B1                       -2116514229         /* Floating point value -1.971157 */
#define HPF_Fs24000_Fc78_B2                        1043212719         /* Floating point value 0.971568 */
#define HPF_Fs32000_Fc78_A0                        1050031301         /* Floating point value 0.977918 */
#define HPF_Fs32000_Fc78_A1                       -2100062603         /* Floating point value -1.955836 */
#define HPF_Fs32000_Fc78_A2                        1050031301         /* Floating point value 0.977918 */
#define HPF_Fs32000_Fc78_B1                       -2124255900         /* Floating point value -1.978367 */
#define HPF_Fs32000_Fc78_B2                        1050762639         /* Floating point value 0.978599 */
#define HPF_Fs44100_Fc78_A0                        1053152258         /* Floating point value 0.980824 */
#define HPF_Fs44100_Fc78_A1                       -2106304516         /* Floating point value -1.961649 */
#define HPF_Fs44100_Fc78_A2                        1053152258         /* Floating point value 0.980824 */
#define HPF_Fs44100_Fc78_B1                       -2130628742         /* Floating point value -1.984303 */
#define HPF_Fs44100_Fc78_B2                        1057018180         /* Floating point value 0.984425 */
#define HPF_Fs48000_Fc78_A0                        1053824087         /* Floating point value 0.981450 */
#define HPF_Fs48000_Fc78_A1                       -2107648173         /* Floating point value -1.962900 */
#define HPF_Fs48000_Fc78_A2                        1053824087         /* Floating point value 0.981450 */
#define HPF_Fs48000_Fc78_B1                       -2131998154         /* Floating point value -1.985578 */
#define HPF_Fs48000_Fc78_B2                        1058367200         /* Floating point value 0.985681 */

 /* Coefficients for centre frequency 90Hz */
#define HPF_Fs8000_Fc90_A0                         1009760053         /* Floating point value 0.940412 */
#define HPF_Fs8000_Fc90_A1                        -2019520105         /* Floating point value -1.880825 */
#define HPF_Fs8000_Fc90_A2                         1009760053         /* Floating point value 0.940412 */
#define HPF_Fs8000_Fc90_B1                        -2040357139         /* Floating point value -1.900231 */
#define HPF_Fs8000_Fc90_B2                          971711129         /* Floating point value 0.904977 */
#define HPF_Fs11025_Fc90_A0                        1023687217         /* Floating point value 0.953383 */
#define HPF_Fs11025_Fc90_A1                       -2047374434         /* Floating point value -1.906766 */
#define HPF_Fs11025_Fc90_A2                        1023687217         /* Floating point value 0.953383 */
#define HPF_Fs11025_Fc90_B1                       -2069722397         /* Floating point value -1.927579 */
#define HPF_Fs11025_Fc90_B2                         998699604         /* Floating point value 0.930111 */
#define HPF_Fs12000_Fc90_A0                        1026704754         /* Floating point value 0.956193 */
#define HPF_Fs12000_Fc90_A1                       -2053409508         /* Floating point value -1.912387 */
#define HPF_Fs12000_Fc90_A2                        1026704754         /* Floating point value 0.956193 */
#define HPF_Fs12000_Fc90_B1                       -2076035996         /* Floating point value -1.933459 */
#define HPF_Fs12000_Fc90_B2                        1004595918         /* Floating point value 0.935603 */
#define HPF_Fs16000_Fc90_A0                        1035283225         /* Floating point value 0.964183 */
#define HPF_Fs16000_Fc90_A1                       -2070566451         /* Floating point value -1.928365 */
#define HPF_Fs16000_Fc90_A2                        1035283225         /* Floating point value 0.964183 */
#define HPF_Fs16000_Fc90_B1                       -2093889811         /* Floating point value -1.950087 */
#define HPF_Fs16000_Fc90_B2                        1021453326         /* Floating point value 0.951303 */
#define HPF_Fs22050_Fc90_A0                        1042398116         /* Floating point value 0.970809 */
#define HPF_Fs22050_Fc90_A1                       -2084796232         /* Floating point value -1.941618 */
#define HPF_Fs22050_Fc90_A2                        1042398116         /* Floating point value 0.970809 */
#define HPF_Fs22050_Fc90_B1                       -2108591057         /* Floating point value -1.963778 */
#define HPF_Fs22050_Fc90_B2                        1035541188         /* Floating point value 0.964423 */
#define HPF_Fs24000_Fc90_A0                        1043933302         /* Floating point value 0.972239 */
#define HPF_Fs24000_Fc90_A1                       -2087866604         /* Floating point value -1.944477 */
#define HPF_Fs24000_Fc90_A2                        1043933302         /* Floating point value 0.972239 */
#define HPF_Fs24000_Fc90_B1                       -2111750495         /* Floating point value -1.966721 */
#define HPF_Fs24000_Fc90_B2                        1038593601         /* Floating point value 0.967266 */
#define HPF_Fs32000_Fc90_A0                        1048285391         /* Floating point value 0.976292 */
#define HPF_Fs32000_Fc90_A1                       -2096570783         /* Floating point value -1.952584 */
#define HPF_Fs32000_Fc90_A2                        1048285391         /* Floating point value 0.976292 */
#define HPF_Fs32000_Fc90_B1                       -2120682737         /* Floating point value -1.975040 */
#define HPF_Fs32000_Fc90_B2                        1047271295         /* Floating point value 0.975347 */
#define HPF_Fs44100_Fc90_A0                        1051881330         /* Floating point value 0.979641 */
#define HPF_Fs44100_Fc90_A1                       -2103762660         /* Floating point value -1.959282 */
#define HPF_Fs44100_Fc90_A2                        1051881330         /* Floating point value 0.979641 */
#define HPF_Fs44100_Fc90_B1                       -2128035809         /* Floating point value -1.981888 */
#define HPF_Fs44100_Fc90_B2                        1054468533         /* Floating point value 0.982050 */
#define HPF_Fs48000_Fc90_A0                        1052655619         /* Floating point value 0.980362 */
#define HPF_Fs48000_Fc90_A1                       -2105311238         /* Floating point value -1.960724 */
#define HPF_Fs48000_Fc90_A2                        1052655619         /* Floating point value 0.980362 */
#define HPF_Fs48000_Fc90_B1                       -2129615871         /* Floating point value -1.983359 */
#define HPF_Fs48000_Fc90_B2                        1056021492         /* Floating point value 0.983497 */


/************************************************************************************/
/*                                                                                  */
/* Band Pass Filter coefficients                                                    */
/*                                                                                  */
/************************************************************************************/

 /* Coefficients for centre frequency 55Hz */
#define BPF_Fs8000_Fc55_A0                            9875247         /* Floating point value 0.009197 */
#define BPF_Fs8000_Fc55_A1                                  0         /* Floating point value 0.000000 */
#define BPF_Fs8000_Fc55_A2                           -9875247         /* Floating point value -0.009197 */
#define BPF_Fs8000_Fc55_B1                        -2125519830         /* Floating point value -1.979545 */
#define BPF_Fs8000_Fc55_B2                         1053762629         /* Floating point value 0.981393 */
#define BPF_Fs11025_Fc55_A0                           7183952         /* Floating point value 0.006691 */
#define BPF_Fs11025_Fc55_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs11025_Fc55_A2                          -7183952         /* Floating point value -0.006691 */
#define BPF_Fs11025_Fc55_B1                       -2131901658         /* Floating point value -1.985488 */
#define BPF_Fs11025_Fc55_B2                        1059207548         /* Floating point value 0.986464 */
#define BPF_Fs12000_Fc55_A0                           6603871         /* Floating point value 0.006150 */
#define BPF_Fs12000_Fc55_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs12000_Fc55_A2                          -6603871         /* Floating point value -0.006150 */
#define BPF_Fs12000_Fc55_B1                       -2133238092         /* Floating point value -1.986733 */
#define BPF_Fs12000_Fc55_B2                        1060381143         /* Floating point value 0.987557 */
#define BPF_Fs16000_Fc55_A0                           4960591         /* Floating point value 0.004620 */
#define BPF_Fs16000_Fc55_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs16000_Fc55_A2                          -4960591         /* Floating point value -0.004620 */
#define BPF_Fs16000_Fc55_B1                       -2136949052         /* Floating point value -1.990189 */
#define BPF_Fs16000_Fc55_B2                        1063705760         /* Floating point value 0.990653 */
#define BPF_Fs22050_Fc55_A0                           3604131         /* Floating point value 0.003357 */
#define BPF_Fs22050_Fc55_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs22050_Fc55_A2                          -3604131         /* Floating point value -0.003357 */
#define BPF_Fs22050_Fc55_B1                       -2139929085         /* Floating point value -1.992964 */
#define BPF_Fs22050_Fc55_B2                        1066450095         /* Floating point value 0.993209 */
#define BPF_Fs24000_Fc55_A0                           3312207         /* Floating point value 0.003085 */
#define BPF_Fs24000_Fc55_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs24000_Fc55_A2                          -3312207         /* Floating point value -0.003085 */
#define BPF_Fs24000_Fc55_B1                       -2140560606         /* Floating point value -1.993552 */
#define BPF_Fs24000_Fc55_B2                        1067040703         /* Floating point value 0.993759 */
#define BPF_Fs32000_Fc55_A0                           2486091         /* Floating point value 0.002315 */
#define BPF_Fs32000_Fc55_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs32000_Fc55_A2                          -2486091         /* Floating point value -0.002315 */
#define BPF_Fs32000_Fc55_B1                       -2142328962         /* Floating point value -1.995199 */
#define BPF_Fs32000_Fc55_B2                        1068712067         /* Floating point value 0.995316 */
#define BPF_Fs44100_Fc55_A0                           1805125         /* Floating point value 0.001681 */
#define BPF_Fs44100_Fc55_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs44100_Fc55_A2                          -1805125         /* Floating point value -0.001681 */
#define BPF_Fs44100_Fc55_B1                       -2143765772         /* Floating point value -1.996537 */
#define BPF_Fs44100_Fc55_B2                        1070089770         /* Floating point value 0.996599 */
#define BPF_Fs48000_Fc55_A0                           1658687         /* Floating point value 0.001545 */
#define BPF_Fs48000_Fc55_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs48000_Fc55_A2                          -1658687         /* Floating point value -0.001545 */
#define BPF_Fs48000_Fc55_B1                       -2144072292         /* Floating point value -1.996823 */
#define BPF_Fs48000_Fc55_B2                        1070386036         /* Floating point value 0.996875 */

 /* Coefficients for centre frequency 66Hz */
#define BPF_Fs8000_Fc66_A0                           13580189         /* Floating point value 0.012648 */
#define BPF_Fs8000_Fc66_A1                                  0         /* Floating point value 0.000000 */
#define BPF_Fs8000_Fc66_A2                          -13580189         /* Floating point value -0.012648 */
#define BPF_Fs8000_Fc66_B1                        -2117161175         /* Floating point value -1.971760 */
#define BPF_Fs8000_Fc66_B2                         1046266945         /* Floating point value 0.974412 */
#define BPF_Fs11025_Fc66_A0                           9888559         /* Floating point value 0.009209 */
#define BPF_Fs11025_Fc66_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs11025_Fc66_A2                          -9888559         /* Floating point value -0.009209 */
#define BPF_Fs11025_Fc66_B1                       -2125972738         /* Floating point value -1.979966 */
#define BPF_Fs11025_Fc66_B2                        1053735698         /* Floating point value 0.981368 */
#define BPF_Fs12000_Fc66_A0                           9091954         /* Floating point value 0.008468 */
#define BPF_Fs12000_Fc66_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs12000_Fc66_A2                          -9091954         /* Floating point value -0.008468 */
#define BPF_Fs12000_Fc66_B1                       -2127818004         /* Floating point value -1.981685 */
#define BPF_Fs12000_Fc66_B2                        1055347356         /* Floating point value 0.982869 */
#define BPF_Fs16000_Fc66_A0                           6833525         /* Floating point value 0.006364 */
#define BPF_Fs16000_Fc66_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs16000_Fc66_A2                          -6833525         /* Floating point value -0.006364 */
#define BPF_Fs16000_Fc66_B1                       -2132941739         /* Floating point value -1.986457 */
#define BPF_Fs16000_Fc66_B2                        1059916517         /* Floating point value 0.987124 */
#define BPF_Fs22050_Fc66_A0                           4967309         /* Floating point value 0.004626 */
#define BPF_Fs22050_Fc66_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs22050_Fc66_A2                          -4967309         /* Floating point value -0.004626 */
#define BPF_Fs22050_Fc66_B1                       -2137056003         /* Floating point value -1.990288 */
#define BPF_Fs22050_Fc66_B2                        1063692170         /* Floating point value 0.990641 */
#define BPF_Fs24000_Fc66_A0                           4565445         /* Floating point value 0.004252 */
#define BPF_Fs24000_Fc66_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs24000_Fc66_A2                          -4565445         /* Floating point value -0.004252 */
#define BPF_Fs24000_Fc66_B1                       -2137927842         /* Floating point value -1.991100 */
#define BPF_Fs24000_Fc66_B2                        1064505202         /* Floating point value 0.991398 */
#define BPF_Fs32000_Fc66_A0                           3427761         /* Floating point value 0.003192 */
#define BPF_Fs32000_Fc66_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs32000_Fc66_A2                          -3427761         /* Floating point value -0.003192 */
#define BPF_Fs32000_Fc66_B1                       -2140369007         /* Floating point value -1.993374 */
#define BPF_Fs32000_Fc66_B2                        1066806920         /* Floating point value 0.993541 */
#define BPF_Fs44100_Fc66_A0                           2489466         /* Floating point value 0.002318 */
#define BPF_Fs44100_Fc66_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs44100_Fc66_A2                          -2489466         /* Floating point value -0.002318 */
#define BPF_Fs44100_Fc66_B1                       -2142352342         /* Floating point value -1.995221 */
#define BPF_Fs44100_Fc66_B2                        1068705240         /* Floating point value 0.995309 */
#define BPF_Fs48000_Fc66_A0                           2287632         /* Floating point value 0.002131 */
#define BPF_Fs48000_Fc66_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs48000_Fc66_A2                          -2287632         /* Floating point value -0.002131 */
#define BPF_Fs48000_Fc66_B1                       -2142775436         /* Floating point value -1.995615 */
#define BPF_Fs48000_Fc66_B2                        1069113581         /* Floating point value 0.995690 */

 /* Coefficients for centre frequency 78Hz */
#define BPF_Fs8000_Fc78_A0                           19941180         /* Floating point value 0.018572 */
#define BPF_Fs8000_Fc78_A1                                  0         /* Floating point value 0.000000 */
#define BPF_Fs8000_Fc78_A2                          -19941180         /* Floating point value -0.018572 */
#define BPF_Fs8000_Fc78_B1                        -2103186749         /* Floating point value -1.958745 */
#define BPF_Fs8000_Fc78_B2                         1033397648         /* Floating point value 0.962427 */
#define BPF_Fs11025_Fc78_A0                          14543934         /* Floating point value 0.013545 */
#define BPF_Fs11025_Fc78_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs11025_Fc78_A2                         -14543934         /* Floating point value -0.013545 */
#define BPF_Fs11025_Fc78_B1                       -2115966638         /* Floating point value -1.970647 */
#define BPF_Fs11025_Fc78_B2                        1044317135         /* Floating point value 0.972596 */
#define BPF_Fs12000_Fc78_A0                          13376999         /* Floating point value 0.012458 */
#define BPF_Fs12000_Fc78_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs12000_Fc78_A2                         -13376999         /* Floating point value -0.012458 */
#define BPF_Fs12000_Fc78_B1                       -2118651708         /* Floating point value -1.973148 */
#define BPF_Fs12000_Fc78_B2                        1046678029         /* Floating point value 0.974795 */
#define BPF_Fs16000_Fc78_A0                          10064222         /* Floating point value 0.009373 */
#define BPF_Fs16000_Fc78_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs16000_Fc78_A2                         -10064222         /* Floating point value -0.009373 */
#define BPF_Fs16000_Fc78_B1                       -2126124342         /* Floating point value -1.980108 */
#define BPF_Fs16000_Fc78_B2                        1053380304         /* Floating point value 0.981037 */
#define BPF_Fs22050_Fc78_A0                           7321780         /* Floating point value 0.006819 */
#define BPF_Fs22050_Fc78_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs22050_Fc78_A2                          -7321780         /* Floating point value -0.006819 */
#define BPF_Fs22050_Fc78_B1                       -2132143771         /* Floating point value -1.985714 */
#define BPF_Fs22050_Fc78_B2                        1058928700         /* Floating point value 0.986204 */
#define BPF_Fs24000_Fc78_A0                           6730640         /* Floating point value 0.006268 */
#define BPF_Fs24000_Fc78_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs24000_Fc78_A2                          -6730640         /* Floating point value -0.006268 */
#define BPF_Fs24000_Fc78_B1                       -2133421607         /* Floating point value -1.986904 */
#define BPF_Fs24000_Fc78_B2                        1060124669         /* Floating point value 0.987318 */
#define BPF_Fs32000_Fc78_A0                           5055965         /* Floating point value 0.004709 */
#define BPF_Fs32000_Fc78_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs32000_Fc78_A2                          -5055965         /* Floating point value -0.004709 */
#define BPF_Fs32000_Fc78_B1                       -2137003977         /* Floating point value -1.990240 */
#define BPF_Fs32000_Fc78_B2                        1063512802         /* Floating point value 0.990473 */
#define BPF_Fs44100_Fc78_A0                           3673516         /* Floating point value 0.003421 */
#define BPF_Fs44100_Fc78_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs44100_Fc78_A2                          -3673516         /* Floating point value -0.003421 */
#define BPF_Fs44100_Fc78_B1                       -2139919394         /* Floating point value -1.992955 */
#define BPF_Fs44100_Fc78_B2                        1066309718         /* Floating point value 0.993078 */
#define BPF_Fs48000_Fc78_A0                           3375990         /* Floating point value 0.003144 */
#define BPF_Fs48000_Fc78_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs48000_Fc78_A2                          -3375990         /* Floating point value -0.003144 */
#define BPF_Fs48000_Fc78_B1                       -2140541906         /* Floating point value -1.993535 */
#define BPF_Fs48000_Fc78_B2                        1066911660         /* Floating point value 0.993639 */

 /* Coefficients for centre frequency 90Hz */
#define BPF_Fs8000_Fc90_A0                           24438548         /* Floating point value 0.022760 */
#define BPF_Fs8000_Fc90_A1                                  0         /* Floating point value 0.000000 */
#define BPF_Fs8000_Fc90_A2                          -24438548         /* Floating point value -0.022760 */
#define BPF_Fs8000_Fc90_B1                        -2092801347         /* Floating point value -1.949073 */
#define BPF_Fs8000_Fc90_B2                         1024298757         /* Floating point value 0.953953 */
#define BPF_Fs11025_Fc90_A0                          17844385         /* Floating point value 0.016619 */
#define BPF_Fs11025_Fc90_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs11025_Fc90_A2                         -17844385         /* Floating point value -0.016619 */
#define BPF_Fs11025_Fc90_B1                       -2108604921         /* Floating point value -1.963791 */
#define BPF_Fs11025_Fc90_B2                        1037639797         /* Floating point value 0.966377 */
#define BPF_Fs12000_Fc90_A0                          16416707         /* Floating point value 0.015289 */
#define BPF_Fs12000_Fc90_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs12000_Fc90_A2                         -16416707         /* Floating point value -0.015289 */
#define BPF_Fs12000_Fc90_B1                       -2111922936         /* Floating point value -1.966882 */
#define BPF_Fs12000_Fc90_B2                        1040528216         /* Floating point value 0.969067 */
#define BPF_Fs16000_Fc90_A0                          12359883         /* Floating point value 0.011511 */
#define BPF_Fs16000_Fc90_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs16000_Fc90_A2                         -12359883         /* Floating point value -0.011511 */
#define BPF_Fs16000_Fc90_B1                       -2121152162         /* Floating point value -1.975477 */
#define BPF_Fs16000_Fc90_B2                        1048735817         /* Floating point value 0.976711 */
#define BPF_Fs22050_Fc90_A0                           8997173         /* Floating point value 0.008379 */
#define BPF_Fs22050_Fc90_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs22050_Fc90_A2                          -8997173         /* Floating point value -0.008379 */
#define BPF_Fs22050_Fc90_B1                       -2128580762         /* Floating point value -1.982395 */
#define BPF_Fs22050_Fc90_B2                        1055539113         /* Floating point value 0.983047 */
#define BPF_Fs24000_Fc90_A0                           8271818         /* Floating point value 0.007704 */
#define BPF_Fs24000_Fc90_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs24000_Fc90_A2                          -8271818         /* Floating point value -0.007704 */
#define BPF_Fs24000_Fc90_B1                       -2130157013         /* Floating point value -1.983863 */
#define BPF_Fs24000_Fc90_B2                        1057006621         /* Floating point value 0.984414 */
#define BPF_Fs32000_Fc90_A0                           6215918         /* Floating point value 0.005789 */
#define BPF_Fs32000_Fc90_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs32000_Fc90_A2                          -6215918         /* Floating point value -0.005789 */
#define BPF_Fs32000_Fc90_B1                       -2134574521         /* Floating point value -1.987977 */
#define BPF_Fs32000_Fc90_B2                        1061166033         /* Floating point value 0.988288 */
#define BPF_Fs44100_Fc90_A0                           4517651         /* Floating point value 0.004207 */
#define BPF_Fs44100_Fc90_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs44100_Fc90_A2                          -4517651         /* Floating point value -0.004207 */
#define BPF_Fs44100_Fc90_B1                       -2138167926         /* Floating point value -1.991324 */
#define BPF_Fs44100_Fc90_B2                        1064601898         /* Floating point value 0.991488 */
#define BPF_Fs48000_Fc90_A0                           4152024         /* Floating point value 0.003867 */
#define BPF_Fs48000_Fc90_A1                                 0         /* Floating point value 0.000000 */
#define BPF_Fs48000_Fc90_A2                          -4152024         /* Floating point value -0.003867 */
#define BPF_Fs48000_Fc90_B1                       -2138935002         /* Floating point value -1.992038 */
#define BPF_Fs48000_Fc90_B2                        1065341620         /* Floating point value 0.992177 */


/************************************************************************************/
/*                                                                                  */
/* Automatic Gain Control time constants and gain settings                          */
/*                                                                                  */
/************************************************************************************/

/* AGC Time constants */
#define AGC_ATTACK_Fs8000                               27571         /* Floating point value 0.841395 */
#define AGC_ATTACK_Fs11025                              28909         /* Floating point value 0.882223 */
#define AGC_ATTACK_Fs12000                              29205         /* Floating point value 0.891251 */
#define AGC_ATTACK_Fs16000                              30057         /* Floating point value 0.917276 */
#define AGC_ATTACK_Fs22050                              30778         /* Floating point value 0.939267 */
#define AGC_ATTACK_Fs24000                              30935         /* Floating point value 0.944061 */
#define AGC_ATTACK_Fs32000                              31383         /* Floating point value 0.957745 */
#define AGC_ATTACK_Fs44100                              31757         /* Floating point value 0.969158 */
#define AGC_ATTACK_Fs48000                              31838         /* Floating point value 0.971628 */
#define DECAY_SHIFT                                        10         /* As a power of 2 */
#define AGC_DECAY_Fs8000                                   44         /* Floating point value 0.000042 */
#define AGC_DECAY_Fs11025                                  32         /* Floating point value 0.000030 */
#define AGC_DECAY_Fs12000                                  29         /* Floating point value 0.000028 */
#define AGC_DECAY_Fs16000                                  22         /* Floating point value 0.000021 */
#define AGC_DECAY_Fs22050                                  16         /* Floating point value 0.000015 */
#define AGC_DECAY_Fs24000                                  15         /* Floating point value 0.000014 */
#define AGC_DECAY_Fs32000                                  11         /* Floating point value 0.000010 */
#define AGC_DECAY_Fs44100                                   8         /* Floating point value 0.000008 */
#define AGC_DECAY_Fs48000                                   7         /* Floating point value 0.000007 */

/* AGC Gain settings */
#define AGC_GAIN_SCALE                                        31         /* As a power of 2 */
#define AGC_GAIN_SHIFT                                         4         /* As a power of 2 */
#define AGC_TARGETLEVEL                              33170337         /* Floating point value -0.100000dB */
#define AGC_HPFGAIN_0dB                             110739704         /* Floating point value 0.412538 */
#define AGC_GAIN_0dB                                        0         /* Floating point value 0.000000 */
#define AGC_HPFGAIN_1dB                             157006071         /* Floating point value 0.584893 */
#define AGC_GAIN_1dB                                 32754079         /* Floating point value 0.122018 */
#define AGC_HPFGAIN_2dB                             208917788         /* Floating point value 0.778279 */
#define AGC_GAIN_2dB                                 69504761         /* Floating point value 0.258925 */
#define AGC_HPFGAIN_3dB                             267163693         /* Floating point value 0.995262 */
#define AGC_GAIN_3dB                                110739704         /* Floating point value 0.412538 */
#define AGC_HPFGAIN_4dB                             332516674         /* Floating point value 1.238721 */
#define AGC_GAIN_4dB                                157006071         /* Floating point value 0.584893 */
#define AGC_HPFGAIN_5dB                             405843924         /* Floating point value 1.511886 */
#define AGC_GAIN_5dB                                208917788         /* Floating point value 0.778279 */
#define AGC_HPFGAIN_6dB                             488118451         /* Floating point value 1.818383 */
#define AGC_GAIN_6dB                                267163693         /* Floating point value 0.995262 */
#define AGC_HPFGAIN_7dB                             580431990         /* Floating point value 2.162278 */
#define AGC_GAIN_7dB                                332516674         /* Floating point value 1.238721 */
#define AGC_HPFGAIN_8dB                             684009483         /* Floating point value 2.548134 */
#define AGC_GAIN_8dB                                405843924         /* Floating point value 1.511886 */
#define AGC_HPFGAIN_9dB                             800225343         /* Floating point value 2.981072 */
#define AGC_GAIN_9dB                                488118451         /* Floating point value 1.818383 */
#define AGC_HPFGAIN_10dB                            930621681         /* Floating point value 3.466836 */
#define AGC_GAIN_10dB                               580431990         /* Floating point value 2.162278 */
#define AGC_HPFGAIN_11dB                           1076928780         /* Floating point value 4.011872 */
#define AGC_GAIN_11dB                               684009483         /* Floating point value 2.548134 */
#define AGC_HPFGAIN_12dB                           1241088045         /* Floating point value 4.623413 */
#define AGC_GAIN_12dB                               800225343         /* Floating point value 2.981072 */
#define AGC_HPFGAIN_13dB                           1425277769         /* Floating point value 5.309573 */
#define AGC_GAIN_13dB                               930621681         /* Floating point value 3.466836 */
#define AGC_HPFGAIN_14dB                           1631942039         /* Floating point value 6.079458 */
#define AGC_GAIN_14dB                              1076928780         /* Floating point value 4.011872 */
#define AGC_HPFGAIN_15dB                           1863823163         /* Floating point value 6.943282 */
#define AGC_GAIN_15dB                              1241088045         /* Floating point value 4.623413 */


/************************************************************************************/
/*                                                                                  */
/* Volume control                                                                   */
/*                                                                                  */
/************************************************************************************/

/* Volume control gain */
#define VOLUME_MAX                                          0         /* In dBs */
#define VOLUME_SHIFT                                        0         /* In dBs */

/* Volume control time constants */
#define VOL_TC_SHIFT                                       21         /* As a power of 2 */
#define VOL_TC_Fs8000                                   25889         /* Floating point value 0.024690 */
#define VOL_TC_Fs11025                                  18850         /* Floating point value 0.017977 */
#define VOL_TC_Fs12000                                  17331         /* Floating point value 0.016529 */
#define VOL_TC_Fs16000                                  13026         /* Floating point value 0.012422 */
#define VOL_TC_Fs22050                                   9468         /* Floating point value 0.009029 */
#define VOL_TC_Fs24000                                   8702         /* Floating point value 0.008299 */
#define VOL_TC_Fs32000                                   6533         /* Floating point value 0.006231 */
#define VOL_TC_Fs44100                                   4745         /* Floating point value 0.004525 */
#define VOL_TC_Fs48000                                   4360         /* Floating point value 0.004158 */
#define MIX_TC_Fs8000                                   29365         /* Floating point value 0.896151 */
#define MIX_TC_Fs11025                                  30230         /* Floating point value 0.922548 */
#define MIX_TC_Fs12000                                  30422         /* Floating point value 0.928415 */
#define MIX_TC_Fs16000                                  30978         /* Floating point value 0.945387 */
#define MIX_TC_Fs22050                                  31451         /* Floating point value 0.959804 */
#define MIX_TC_Fs24000                                  31554         /* Floating point value 0.962956 */
#define MIX_TC_Fs32000                                  31850         /* Floating point value 0.971973 */
#define MIX_TC_Fs44100                                  32097         /* Floating point value 0.979515 */
#define MIX_TC_Fs48000                                  32150         /* Floating point value 0.981150 */


#endif
