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
/****************************************************************************
SC 29 Software Copyright Licencing Disclaimer:

This software module was originally developed by
  Coding Technologies

and edited by
  -

in the course of development of the ISO/IEC 13818-7 and ISO/IEC 14496-3
standards for reference purposes and its performance may not have been
optimized. This software module is an implementation of one or more tools as
specified by the ISO/IEC 13818-7 and ISO/IEC 14496-3 standards.
ISO/IEC gives users free license to this software module or modifications
thereof for use in products claiming conformance to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International
Standards. ISO/IEC gives users the same free license to this software module or
modifications thereof for research purposes and further ISO/IEC standardisation.
Those intending to use this software module in products are advised that its
use may infringe existing patents. ISO/IEC have no liability for use of this
software module or modifications thereof. Copyright is not released for
products that do not conform to audiovisual and image-coding related ITU
Recommendations and/or ISO/IEC International Standards.
The original developer retains full right to modify and use the code for its
own purpose, assign or donate the code to a third party and to inhibit third
parties from using the code for products that do not conform to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International Standards.
This copyright notice must be included in all copies or derivative works.
Copyright (c) ISO/IEC 2003.
*******************************************************************************/
/*
*/
#ifndef PS_CONSTANTS_H
#define PS_CONSTANTS_H


#define NO_SUB_QMF_CHANNELS         12
#define NO_QMF_CHANNELS_IN_HYBRID   3
#define NO_QMF_CHANNELS             64
#define NO_ALLPASS_CHANNELS         23
#define NO_DELAY_CHANNELS           (NO_QMF_CHANNELS-NO_ALLPASS_CHANNELS)
#define DELAY_ALLPASS               2
#define SHORT_DELAY_START           12
#define SHORT_DELAY                 1
#define LONG_DELAY                  14
#define NO_QMF_ALLPASS_CHANNELS    (NO_ALLPASS_CHANNELS-NO_QMF_CHANNELS_IN_HYBRID)
#define NO_QMF_ICC_CHANNELS        (NO_QMF_ALLPASS_CHANNELS+NO_DELAY_CHANNELS)
#define HYBRIDGROUPS                8
#define DECAY_CUTOFF                3
#define NO_SERIAL_ALLPASS_LINKS     3
#define MAX_NO_PS_ENV               5
#define NEGATE_IPD_MASK                 ( 0x00001000 )
#define NO_BINS                         ( 20 )
#define NO_HI_RES_BINS                  ( 34 )
#define NO_LOW_RES_BINS                 ( NO_IID_BINS / 2 )
#define NO_IID_BINS                     ( NO_BINS )
#define NO_ICC_BINS                     ( NO_BINS )
#define NO_LOW_RES_IID_BINS             ( NO_LOW_RES_BINS )
#define NO_LOW_RES_ICC_BINS             ( NO_LOW_RES_BINS )
#define SUBQMF_GROUPS                   ( 10 )
#define QMF_GROUPS                      ( 12 )
#define NO_IID_GROUPS                   ( SUBQMF_GROUPS + QMF_GROUPS )
#define NO_IID_STEPS                    ( 7 )
#define NO_IID_STEPS_FINE               ( 15 )
#define NO_ICC_STEPS                    ( 8 )
#define NO_IID_LEVELS                   ( 2 * NO_IID_STEPS + 1 )
#define NO_IID_LEVELS_FINE              ( 2 * NO_IID_STEPS_FINE + 1 )
#define NO_ICC_LEVELS                   ( NO_ICC_STEPS )



#endif      /*  PS_CONSTANTS_H */
