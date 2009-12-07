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
/*

 Pathname: window_block_fxp.h


------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
    modified function definition: Time_data from Int to Int32
    change wnd_shape from structure to passing parameters
    delete definition of wnd_shape1, not needed.

 Description: Modified based on unit test comments

 Description: Change copyright, add () around constants.

 Description:
    changed Long_Window_fxp and Short _Window_fxp tables definition, from
    "const UInt16 *"  to "const UInt16 * const" to avoid global variable
    definition.

 Description: Updated function trans4m_freq_2_time_fxp definition

 Description:  Modified function interface to add output_buffer


 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Header file for window and block switch


------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 13818-7 Part 7: Advanced Audo Coding (AAC)


 (2) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC gives users of the MPEG-2 NBC/MPEG-4 Audio
   standards free license to this software module or modifications thereof
   for use in hardware or software products claiming conformance to the
   MPEG-2 NBC/MPEG-4 Audio  standards. Those intending to use this software
   module in hardware or software products are advised that this use may
   infringe existing patents. The original developer of this software
   module and his/her company, the subsequent editors and their companies,
   and ISO/IEC have no liability for use of this software module or
   modifications thereof in an implementation. Copyright is not released
   for non MPEG-2 NBC/MPEG-4 Audio conforming products.The original
   developer retains full right to use the code for his/her own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef WINDOW_BLOCK_FXP_H
#define WINDOW_BLOCK_FXP_H


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "e_window_shape.h"
#include "e_window_sequence.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/
#define LONG_WINDOW         (1024)
#define SHORT_WINDOW        (128)

#define HALF_LONG_WINDOW    (LONG_WINDOW>>1)
#define HALF_SHORT_WINDOW   (SHORT_WINDOW>>1)

#define NUM_SHORT_WINDOWS   (8)
#define LONG_WINDOW_m_1     (LONG_WINDOW-1)
#define SHORT_WINDOW_m_1    (SHORT_WINDOW-1)

    /*
     *  Limits for window sequences, they are used to build
     *  each long window, they are defined in the standards
     */
#define W_L_START_1         ((3*LONG_WINDOW - SHORT_WINDOW)>>1)
#define W_L_START_2         ((3*LONG_WINDOW + SHORT_WINDOW)>>1)
#define W_L_STOP_1          ((LONG_WINDOW - SHORT_WINDOW)>>1)
#define W_L_STOP_2          ((LONG_WINDOW + SHORT_WINDOW)>>1)


#define LONG_BLOCK1          (2*LONG_WINDOW)
#define SHORT_BLOCK1         (2*SHORT_WINDOW)


#define  SCALING    10
#define  ROUNDING     (1<<(SCALING-1))


    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/
    extern const Int16 Short_Window_KBD_fxp[ SHORT_WINDOW];
    extern const Int16 Long_Window_KBD_fxp[ LONG_WINDOW];
    extern const Int16 Short_Window_sine_fxp[ SHORT_WINDOW];
    extern const Int16 Long_Window_sine_fxp[ LONG_WINDOW];

    extern const Int16 * const Long_Window_fxp[];
    extern const Int16 * const Short_Window_fxp[];

    /*----------------------------------------------------------------------------
    ; SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; ENUMERATED TYPEDEF'S
    ----------------------------------------------------------------------------*/



    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

    void trans4m_freq_2_time_fxp(
        Int32   Frequency_data[],
        Int32   Time_data[],
#ifdef AAC_PLUS
        Int32   Output_buffer[],
#else
        Int16   Output_buffer[],
#endif
        WINDOW_SEQUENCE wnd_seq,
        Int     wnd_shape_prev_bk,
        Int     wnd_shape_this_bk,
        Int     Q_format,
        Int32   abs_max_per_window[],
        Int32   freq_2_time_buffer[] ,
        Int16   *Interleave_output
    );



    void trans4m_freq_2_time_fxp_1(
        Int32   Frequency_data[],
        Int32   Time_data[],
        Int16   Output_buffer[],
        WINDOW_SEQUENCE wnd_seq,
        Int     wnd_shape_prev_bk,
        Int     wnd_shape_this_bk,
        Int     Q_format,
        Int32   abs_max_per_window[],
        Int32   freq_2_time_buffer[]
    );


    void trans4m_freq_2_time_fxp_2(
        Int32   Frequency_data[],
        Int32   Time_data[],
        WINDOW_SEQUENCE wnd_seq,
        Int     wnd_shape_prev_bk,
        Int     wnd_shape_this_bk,
        Int     Q_format,
        Int32   abs_max_per_window[],
        Int32   freq_2_time_buffer[] ,
        Int16   *Interleave_output
    );

    void trans4m_time_2_freq_fxp(
        Int32   Time2Freq_data[],
        WINDOW_SEQUENCE wnd_seq,
        Int     wnd_shape_prev_bk,
        Int     wnd_shape_this_bk,
        Int     *pQ_format,
        Int32   mem_4_in_place_FFT[]);

    /*----------------------------------------------------------------------------
    ; END
    ----------------------------------------------------------------------------*/
#ifdef __cplusplus
}
#endif

#endif  /*  WINDOW_BLOCK_FXP_H */

