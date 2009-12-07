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

 Pathname: ./src/huffcb.c
 Funtions:
    huffcb


------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description:  Change variable names for clarity,
               change variables 'base', 'sect_len_inc', and 'esc_val' to
               UChar type.

 Description:  Add "if ((pSect[-1] % sfb_per_win) > max_sfb)" statement to
               detect the error condition.
               add more white space.

 Description: eliminated "pSect[-1]%sfb_per_win" operation

 Description: eliminated "pSect[-1]%sfb_per_win" operation

 Description: (1) Pass in SectInfo pSect
              (2) put BITS *pInputStream as second parameter

 Description:  Fix a failure for thrid party AAC encoding.
               The problem came when the total and the
               maximun number of active scale factor bands do not coincide.
               This is a rare situation but produces a problem when decoding
               encoders that tolerate this.

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9 or less and get1bits
              when only 1 bit is read.

 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    UChar   *pSect  = pointer to array that contains the interleaved
                      information of huffman codebook index and section
                      length. Array contains:
                      [codebook index]
                      [section boundary]
                      [codebook index]
                      [section boundary]
                      ...

    Int     sectbits  =   array that defines the number of bits
                          used for expressing the escape value of
                          section length

    Int     tot_sfb     = total number of sfb in one Frame

    Int     sfb_per_win = number of sfb in each sub-block (window)

    UChar   max_sfb     = 1 + number of active sfbs - see reference (2) p56

    BITS    *pInputStream = pointer to input stream


 Local Stores/Buffers/Pointers Needed:

    UChar    base     = number of sfb in already detected sections

    UChar    sect_len_inc = section length increment in number of sfbs'

    UChar    esc_val  = escape value for section length

    Int     bits     = number of bits needed for expressing section length


 Global Stores/Buffers/Pointers Needed:


 Outputs:

    num_sect = total number of sections in one frame


 Pointers and Buffers Modified:

    UChar    *pSect = pointer to array where huffman codebook index and
                     section length are stored

 Local Stores Modified:

 Global Stores Modified:

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 Background knowledge: 1024(960) coef's are separated into several sections,
 each section is encoded with one single Huffman codebook, and each section
 has a length of multiples of sfb.

 max_sfb <= sfb_per_win <= tot_sfb
 tot_sfb = total number of scalefactor bands in one frame (1024 coefs)

 This function reads the codebook index and section boundaries (expressed
 in number of sfb) from the input bitstream, store these information in
 *pSect, and return the number of sections been detected. Returns 0 if there
 is an error.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function should fill the array *pSect with section Huffman codebook
 indexes and section boundaries

------------------------------------------------------------------------------
 REFERENCES

 (1) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC  gives users of the MPEG-2 NBC/MPEG-4 Audio
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

 (2) ISO/IEC 14496-3 1999(E)
   Subpart 4    p55     (Recovering section_data())
                p24-25  (Syntax of section_data())

 (3) M. Bosi, K. Brandenburg, etc., "ISO/IEC MPEG-2 Advanced Audio Coding,"
     J. Audio Eng. Soc., Vol.45, No.10, 1997 October

------------------------------------------------------------------------------
 PSEUDO-CODE

 bits_needed_for_ESC  = sectbits[0];
 ESC_value            = (1<<bits_needed_for_ESC) - 1;
 num_of_section       = 0;


 FOR (base = 0; base<total_sfb AND num_of_section<total_sfb)
 {
    *pSect++     = getbits(LEN_CB, pInputStream);   (read huffman_codebook_num)
    sect_length_incr  = getbits(bits_needed_for_ESC, pInputStream);

    WHILE (sect_length_incr == ESC_value AND base < total_sfb)
    {
        base              += ESC_value;
        sect_length_incr  =  getbits(bits_needed_for_ESC, ebits);
    }
    ENDWHILE

    base      += sect_length_incr;
    *pSect++   =  base;
    num_of_section++;

   IF (num_of_sfb_for_this_group==max_sfb)
   {
        *pSect++    = 0; (use huffman codebook 0)
        base       += sfb_per_win - max_sfb;
        *pSect++    = base;
        num_of_section++;
   }
   ENDIF

   IF (num_of_sfb_for_this_group > max_sfb)
        break;
   ENDIF

 }
 ENDFOR

 IF (base != total_sfb OR num_of_section>total_sfb)
      return 0;
 ENDIF

 return num_sect;

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE: [stack count for this module] + [variable to represent
          stack usage for each subroutine called]

     where: [stack usage variable] = stack usage for [subroutine
         name] (see [filename].ext)

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES: [cycle count equation for this module] + [variable
           used to represent cycle count for each subroutine
           called]

     where: [cycle count variable] = cycle count for [subroutine
        name] (see [filename].ext)

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "pv_audio_type_defs.h"
#include    "huffman.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
Int huffcb(
    SectInfo    *pSect,
    BITS        *pInputStream,
    Int         sectbits[],
    Int         tot_sfb,
    Int         sfb_per_win,
    Int         max_sfb)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/

    Int   base;        /* section boundary */
    Int   sect_len_incr;
    Int   esc_val;     /* ESC of section length = 31(long), =7 (short) */
    Int     bits;        /* # of bits used to express esc_val */
    Int     num_sect;
    Int     active_sfb;
    Int   group_base;


    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/

    bits       =  sectbits[0];     /* 3 for SHORT_WIN, 5 for LONG_WIN */
    esc_val    = (1 << bits) - 1;   /* ESC_value for section length */
    num_sect   =  0;
    base       =  0;
    group_base =  0;

    /* read until the end of one frame */
    while ((base < tot_sfb) && (num_sect < tot_sfb))
    {

        pSect->sect_cb  = get9_n_lessbits(
                              LEN_CB,
                              pInputStream); /* section codebook */

        sect_len_incr   = get9_n_lessbits(
                              bits,
                              pInputStream); /* length_incr */


        /* read until non-ESC value, see p55 reference 2 */
        while ((sect_len_incr == esc_val) && (base < tot_sfb))
        {
            base            +=  esc_val;

            sect_len_incr   = get9_n_lessbits(
                                  bits,
                                  pInputStream);
        }

        base      += sect_len_incr;
        pSect->sect_end  =  base; /* total # of sfb until current section */
        pSect++;
        num_sect++;

        /* active_sfb = base % sfb_per_win; */
        active_sfb = base - group_base;

        /*
         *  insert a zero section for regions above max_sfb for each group
         *  Make sure that active_sfb is also lesser than tot_sfb
         */

        if ((active_sfb == max_sfb) && (active_sfb < tot_sfb))
        {
            base      += (sfb_per_win - max_sfb);
            pSect->sect_cb   =   0; /* huffman codebook 0 */
            pSect->sect_end  =   base;
            num_sect++;
            pSect++;
            group_base = base;
        }
        else if (active_sfb > max_sfb)
        {
            /* within each group, the sections must delineate the sfb
             * from zero to max_sfb so that the 1st section within each
             * group starts at sfb0 and the last section ends at max_sfb
             * see p55 reference 2
             */
            break;
        }

    } /* while (base=0) */


    if (base != tot_sfb || num_sect > tot_sfb)
    {
        num_sect = 0;   /* error */
    }

    return num_sect;

} /* huffcb */


