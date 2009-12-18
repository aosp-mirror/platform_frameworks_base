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
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    [input_variable_name] = [description of the input to module, its type
                 definition, and length (when applicable)]

 Local Stores/Buffers/Pointers Needed:
    [local_store_name] = [description of the local store, its type
                  definition, and length (when applicable)]
    [local_buffer_name] = [description of the local buffer, its type
                   definition, and length (when applicable)]
    [local_ptr_name] = [description of the local pointer, its type
                definition, and length (when applicable)]

 Global Stores/Buffers/Pointers Needed:
    [global_store_name] = [description of the global store, its type
                   definition, and length (when applicable)]
    [global_buffer_name] = [description of the global buffer, its type
                definition, and length (when applicable)]
    [global_ptr_name] = [description of the global pointer, its type
                 definition, and length (when applicable)]

 Outputs:
    [return_variable_name] = [description of data/pointer returned
                  by module, its type definition, and length
                  (when applicable)]

 Pointers and Buffers Modified:
    [variable_bfr_ptr] points to the [describe where the
      variable_bfr_ptr points to, its type definition, and length
      (when applicable)]
    [variable_bfr] contents are [describe the new contents of
      variable_bfr]

 Local Stores Modified:
    [local_store_name] = [describe new contents, its type
                  definition, and length (when applicable)]

 Global Stores Modified:
    [global_store_name] = [describe new contents, its type
                   definition, and length (when applicable)]

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

   For fast Deblock filtering
   Newer version (macroblock based processing)

------------------------------------------------------------------------------
 REQUIREMENTS

 [List requirements to be satisfied by this module.]

------------------------------------------------------------------------------
 REFERENCES

 [List all references used in designing this module.]

------------------------------------------------------------------------------
 PSEUDO-CODE

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
#include    "mp4dec_lib.h"
#include    "post_proc.h"

#define OSCL_DISABLE_WARNING_CONV_POSSIBLE_LOSS_OF_DATA

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/
//#define FILTER_LEN_8

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration

----------------------------------------------------------------------------
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
#ifdef PV_POSTPROC_ON

/*************************************************************************
    Function prototype : void CombinedHorzVertFilter(   uint8 *rec,
                                                        int width,
                                                        int height,
                                                        int *QP_store,
                                                        int chr,
                                                        uint8 *pp_mod)
    Parameters  :
        rec     :   pointer to the decoded frame buffer.
        width   :   width of decoded frame.
        height  :   height of decoded frame
        QP_store:   pointer to the array of QP corresponding to the decoded frame.
                    It had only one value for each MB.
        chr     :   luma or color indication
                    == 0 luma
                    == 1 color
        pp_mod  :   The semphore used for deblocking

    Remark      :   The function do the deblocking on decoded frames.
                    First based on the semaphore info., it is divided into hard and soft filtering.
                    To differentiate real and fake edge, it then check the difference with QP to
                    decide whether to do the filtering or not.

*************************************************************************/


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
void CombinedHorzVertFilter(
    uint8 *rec,
    int width,
    int height,
    int16 *QP_store,
    int chr,
    uint8 *pp_mod)
{

    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int br, bc, mbr, mbc;
    int QP = 1;
    uint8 *ptr, *ptr_e;
    int pp_w, pp_h;
    int brwidth;

    int jVal0, jVal1, jVal2;
    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    pp_w = (width >> 3);
    pp_h = (height >> 3);

    for (mbr = 0; mbr < pp_h; mbr += 2)         /* row of blocks */
    {
        brwidth = mbr * pp_w;               /* number of blocks above current block row */
        for (mbc = 0; mbc < pp_w; mbc += 2)     /* col of blocks */
        {
            if (!chr)
                QP = QP_store[(brwidth>>2) + (mbc>>1)]; /* QP is per MB based value */

            /********* for each block **************/
            /****************** Horiz. Filtering ********************/
            for (br = mbr + 1; br < mbr + 3; br++)  /* 2x2 blocks */
            {
                brwidth += pp_w;                    /* number of blocks above & left current block row */
                /* the profile on ARM920T shows separate these two boundary check is faster than combine them */
                if (br < pp_h)                  /* boundary : don't do it on the lowest row block */
                    for (bc = mbc; bc < mbc + 2; bc++)
                    {
                        /****** check boundary for deblocking ************/
                        if (bc < pp_w)              /* boundary : don't do it on the most right col block */
                        {
                            ptr = rec + (brwidth << 6) + (bc << 3);
                            jVal0 = brwidth + bc;
                            if (chr)    QP = QP_store[jVal0];

                            ptr_e = ptr + 8;        /* pointer to where the loop ends */

                            if (((pp_mod[jVal0]&0x02)) && ((pp_mod[jVal0-pp_w]&0x02)))
                            {
                                /* Horiz Hard filter */
                                do
                                {
                                    jVal0 = *(ptr - width);     /* C */
                                    jVal1 = *ptr;               /* D */
                                    jVal2 = jVal1 - jVal0;

                                    if (((jVal2 > 0) && (jVal2 < (QP << 1)))
                                            || ((jVal2 < 0) && (jVal2 > -(QP << 1)))) /* (D-C) compared with 2QP */
                                    {
                                        /* differentiate between real and fake edge */
                                        jVal0 = ((jVal0 + jVal1) >> 1);     /* (D+C)/2 */
                                        *(ptr - width) = (uint8)(jVal0);    /*  C */
                                        *ptr = (uint8)(jVal0);          /*  D */

                                        jVal0 = *(ptr - (width << 1));      /* B */
                                        jVal1 = *(ptr + width);         /* E */
                                        jVal2 = jVal1 - jVal0;      /* E-B */

                                        if (jVal2 > 0)
                                        {
                                            jVal0 += ((jVal2 + 3) >> 2);
                                            jVal1 -= ((jVal2 + 3) >> 2);
                                            *(ptr - (width << 1)) = (uint8)jVal0;       /*  store B */
                                            *(ptr + width) = (uint8)jVal1;          /* store E */
                                        }
                                        else if (jVal2)
                                        {
                                            jVal0 -= ((3 - jVal2) >> 2);
                                            jVal1 += ((3 - jVal2) >> 2);
                                            *(ptr - (width << 1)) = (uint8)jVal0;       /*  store B */
                                            *(ptr + width) = (uint8)jVal1;          /* store E */
                                        }

                                        jVal0 = *(ptr - (width << 1) - width);  /* A */
                                        jVal1 = *(ptr + (width << 1));      /* F */
                                        jVal2 = jVal1 - jVal0;              /* (F-A) */

                                        if (jVal2 > 0)
                                        {
                                            jVal0 += ((jVal2 + 7) >> 3);
                                            jVal1 -= ((jVal2 + 7) >> 3);
                                            *(ptr - (width << 1) - width) = (uint8)(jVal0);
                                            *(ptr + (width << 1)) = (uint8)(jVal1);
                                        }
                                        else if (jVal2)
                                        {
                                            jVal0 -= ((7 - jVal2) >> 3);
                                            jVal1 += ((7 - jVal2) >> 3);
                                            *(ptr - (width << 1) - width) = (uint8)(jVal0);
                                            *(ptr + (width << 1)) = (uint8)(jVal1);
                                        }
                                    }/* a3_0 > 2QP */
                                }
                                while (++ptr < ptr_e);
                            }
                            else   /* Horiz soft filter*/
                            {
                                do
                                {
                                    jVal0 = *(ptr - width); /* B */
                                    jVal1 = *ptr;           /* C */
                                    jVal2 = jVal1 - jVal0;  /* C-B */

                                    if (((jVal2 > 0) && (jVal2 < (QP)))
                                            || ((jVal2 < 0) && (jVal2 > -(QP)))) /* (C-B) compared with QP */
                                    {

                                        jVal0 = ((jVal0 + jVal1) >> 1);     /* (B+C)/2 cannot overflow; ceil() */
                                        *(ptr - width) = (uint8)(jVal0);    /* B = (B+C)/2 */
                                        *ptr = (uint8)jVal0;            /* C = (B+C)/2 */

                                        jVal0 = *(ptr - (width << 1));      /* A */
                                        jVal1 = *(ptr + width);         /* D */
                                        jVal2 = jVal1 - jVal0;          /* D-A */


                                        if (jVal2 > 0)
                                        {
                                            jVal1 -= ((jVal2 + 7) >> 3);
                                            jVal0 += ((jVal2 + 7) >> 3);
                                            *(ptr - (width << 1)) = (uint8)jVal0;       /* A */
                                            *(ptr + width) = (uint8)jVal1;          /* D */
                                        }
                                        else if (jVal2)
                                        {
                                            jVal1 += ((7 - jVal2) >> 3);
                                            jVal0 -= ((7 - jVal2) >> 3);
                                            *(ptr - (width << 1)) = (uint8)jVal0;       /* A */
                                            *(ptr + width) = (uint8)jVal1;          /* D */
                                        }
                                    }
                                }
                                while (++ptr < ptr_e);
                            } /* Soft filter*/
                        }/* boundary checking*/
                    }/*bc*/
            }/*br*/
            brwidth -= (pp_w << 1);
            /****************** Vert. Filtering ********************/
            for (br = mbr; br < mbr + 2; br++)
            {
                if (br < pp_h)
                    for (bc = mbc + 1; bc < mbc + 3; bc++)
                    {
                        /****** check boundary for deblocking ************/
                        if (bc < pp_w)
                        {
                            ptr = rec + (brwidth << 6) + (bc << 3);
                            jVal0 = brwidth + bc;
                            if (chr)    QP = QP_store[jVal0];

                            ptr_e = ptr + (width << 3);

                            if (((pp_mod[jVal0-1]&0x01)) && ((pp_mod[jVal0]&0x01)))
                            {
                                /* Vert Hard filter */
                                do
                                {
                                    jVal1 = *ptr;       /* D */
                                    jVal0 = *(ptr - 1); /* C */
                                    jVal2 = jVal1 - jVal0;  /* D-C */

                                    if (((jVal2 > 0) && (jVal2 < (QP << 1)))
                                            || ((jVal2 < 0) && (jVal2 > -(QP << 1))))
                                    {
                                        jVal1 = (jVal0 + jVal1) >> 1;   /* (C+D)/2 */
                                        *ptr        =   jVal1;
                                        *(ptr - 1)  =   jVal1;

                                        jVal1 = *(ptr + 1);     /* E */
                                        jVal0 = *(ptr - 2);     /* B */
                                        jVal2 = jVal1 - jVal0;      /* E-B */

                                        if (jVal2 > 0)
                                        {
                                            jVal1 -= ((jVal2 + 3) >> 2);        /* E = E -(E-B)/4 */
                                            jVal0 += ((jVal2 + 3) >> 2);        /* B = B +(E-B)/4 */
                                            *(ptr + 1) = jVal1;
                                            *(ptr - 2) = jVal0;
                                        }
                                        else if (jVal2)
                                        {
                                            jVal1 += ((3 - jVal2) >> 2);        /* E = E -(E-B)/4 */
                                            jVal0 -= ((3 - jVal2) >> 2);        /* B = B +(E-B)/4 */
                                            *(ptr + 1) = jVal1;
                                            *(ptr - 2) = jVal0;
                                        }

                                        jVal1 = *(ptr + 2);     /* F */
                                        jVal0 = *(ptr - 3);     /* A */

                                        jVal2 = jVal1 - jVal0;          /* (F-A) */

                                        if (jVal2 > 0)
                                        {
                                            jVal1 -= ((jVal2 + 7) >> 3);    /* F -= (F-A)/8 */
                                            jVal0 += ((jVal2 + 7) >> 3);    /* A += (F-A)/8 */
                                            *(ptr + 2) = jVal1;
                                            *(ptr - 3) = jVal0;
                                        }
                                        else if (jVal2)
                                        {
                                            jVal1 -= ((jVal2 - 7) >> 3);    /* F -= (F-A)/8 */
                                            jVal0 += ((jVal2 - 7) >> 3);    /* A += (F-A)/8 */
                                            *(ptr + 2) = jVal1;
                                            *(ptr - 3) = jVal0;
                                        }
                                    }   /* end of ver hard filetering */
                                }
                                while ((ptr += width) < ptr_e);
                            }
                            else   /* Vert soft filter*/
                            {
                                do
                                {
                                    jVal1 = *ptr;               /* C */
                                    jVal0 = *(ptr - 1);         /* B */
                                    jVal2 = jVal1 - jVal0;

                                    if (((jVal2 > 0) && (jVal2 < (QP)))
                                            || ((jVal2 < 0) && (jVal2 > -(QP))))
                                    {

                                        jVal1 = (jVal0 + jVal1 + 1) >> 1;
                                        *ptr = jVal1;           /* C */
                                        *(ptr - 1) = jVal1;     /* B */

                                        jVal1 = *(ptr + 1);     /* D */
                                        jVal0 = *(ptr - 2);     /* A */
                                        jVal2 = (jVal1 - jVal0);        /* D- A */

                                        if (jVal2 > 0)
                                        {
                                            jVal1 -= (((jVal2) + 7) >> 3);      /* D -= (D-A)/8 */
                                            jVal0 += (((jVal2) + 7) >> 3);      /* A += (D-A)/8 */
                                            *(ptr + 1) = jVal1;
                                            *(ptr - 2) = jVal0;

                                        }
                                        else if (jVal2)
                                        {
                                            jVal1 += ((7 - (jVal2)) >> 3);      /* D -= (D-A)/8 */
                                            jVal0 -= ((7 - (jVal2)) >> 3);      /* A += (D-A)/8 */
                                            *(ptr + 1) = jVal1;
                                            *(ptr - 2) = jVal0;
                                        }
                                    }
                                }
                                while ((ptr += width) < ptr_e);
                            } /* Soft filter*/
                        } /* boundary*/
                    } /*bc*/
                brwidth += pp_w;
            }/*br*/
            brwidth -= (pp_w << 1);
        }/*mbc*/
        brwidth += (pp_w << 1);
    }/*mbr*/
    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
void CombinedHorzVertFilter_NoSoftDeblocking(
    uint8 *rec,
    int width,
    int height,
    int16 *QP_store,
    int chr,
    uint8 *pp_mod)
{

    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int br, bc, mbr, mbc;
    int QP = 1;
    uint8 *ptr, *ptr_e;
    int pp_w, pp_h;
    int brwidth;

    int jVal0, jVal1, jVal2;
    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    pp_w = (width >> 3);
    pp_h = (height >> 3);

    for (mbr = 0; mbr < pp_h; mbr += 2)         /* row of blocks */
    {
        brwidth = mbr * pp_w;               /* number of blocks above current block row */
        for (mbc = 0; mbc < pp_w; mbc += 2)     /* col of blocks */
        {
            if (!chr)
                QP = QP_store[(brwidth>>2) + (mbc>>1)]; /* QP is per MB based value */

            /********* for each block **************/
            /****************** Horiz. Filtering ********************/
            for (br = mbr + 1; br < mbr + 3; br++)  /* 2x2 blocks */
            {
                brwidth += pp_w;                    /* number of blocks above & left current block row */
                /* the profile on ARM920T shows separate these two boundary check is faster than combine them */
                if (br < pp_h)                  /* boundary : don't do it on the lowest row block */
                    for (bc = mbc; bc < mbc + 2; bc++)
                    {
                        /****** check boundary for deblocking ************/
                        if (bc < pp_w)              /* boundary : don't do it on the most right col block */
                        {
                            ptr = rec + (brwidth << 6) + (bc << 3);
                            jVal0 = brwidth + bc;
                            if (chr)    QP = QP_store[jVal0];

                            ptr_e = ptr + 8;        /* pointer to where the loop ends */

                            if (((pp_mod[jVal0]&0x02)) && ((pp_mod[jVal0-pp_w]&0x02)))
                            {
                                /* Horiz Hard filter */
                                do
                                {
                                    jVal0 = *(ptr - width);     /* C */
                                    jVal1 = *ptr;               /* D */
                                    jVal2 = jVal1 - jVal0;

                                    if (((jVal2 > 0) && (jVal2 < (QP << 1)))
                                            || ((jVal2 < 0) && (jVal2 > -(QP << 1)))) /* (D-C) compared with 2QP */
                                    {
                                        /* differentiate between real and fake edge */
                                        jVal0 = ((jVal0 + jVal1) >> 1);     /* (D+C)/2 */
                                        *(ptr - width) = (uint8)(jVal0);    /*  C */
                                        *ptr = (uint8)(jVal0);          /*  D */

                                        jVal0 = *(ptr - (width << 1));      /* B */
                                        jVal1 = *(ptr + width);         /* E */
                                        jVal2 = jVal1 - jVal0;      /* E-B */

                                        if (jVal2 > 0)
                                        {
                                            jVal0 += ((jVal2 + 3) >> 2);
                                            jVal1 -= ((jVal2 + 3) >> 2);
                                            *(ptr - (width << 1)) = (uint8)jVal0;       /*  store B */
                                            *(ptr + width) = (uint8)jVal1;          /* store E */
                                        }
                                        else if (jVal2)
                                        {
                                            jVal0 -= ((3 - jVal2) >> 2);
                                            jVal1 += ((3 - jVal2) >> 2);
                                            *(ptr - (width << 1)) = (uint8)jVal0;       /*  store B */
                                            *(ptr + width) = (uint8)jVal1;          /* store E */
                                        }

                                        jVal0 = *(ptr - (width << 1) - width);  /* A */
                                        jVal1 = *(ptr + (width << 1));      /* F */
                                        jVal2 = jVal1 - jVal0;              /* (F-A) */

                                        if (jVal2 > 0)
                                        {
                                            jVal0 += ((jVal2 + 7) >> 3);
                                            jVal1 -= ((jVal2 + 7) >> 3);
                                            *(ptr - (width << 1) - width) = (uint8)(jVal0);
                                            *(ptr + (width << 1)) = (uint8)(jVal1);
                                        }
                                        else if (jVal2)
                                        {
                                            jVal0 -= ((7 - jVal2) >> 3);
                                            jVal1 += ((7 - jVal2) >> 3);
                                            *(ptr - (width << 1) - width) = (uint8)(jVal0);
                                            *(ptr + (width << 1)) = (uint8)(jVal1);
                                        }
                                    }/* a3_0 > 2QP */
                                }
                                while (++ptr < ptr_e);
                            }

                        }/* boundary checking*/
                    }/*bc*/
            }/*br*/
            brwidth -= (pp_w << 1);
            /****************** Vert. Filtering ********************/
            for (br = mbr; br < mbr + 2; br++)
            {
                if (br < pp_h)
                    for (bc = mbc + 1; bc < mbc + 3; bc++)
                    {
                        /****** check boundary for deblocking ************/
                        if (bc < pp_w)
                        {
                            ptr = rec + (brwidth << 6) + (bc << 3);
                            jVal0 = brwidth + bc;
                            if (chr)    QP = QP_store[jVal0];

                            ptr_e = ptr + (width << 3);

                            if (((pp_mod[jVal0-1]&0x01)) && ((pp_mod[jVal0]&0x01)))
                            {
                                /* Vert Hard filter */
                                do
                                {
                                    jVal1 = *ptr;       /* D */
                                    jVal0 = *(ptr - 1); /* C */
                                    jVal2 = jVal1 - jVal0;  /* D-C */

                                    if (((jVal2 > 0) && (jVal2 < (QP << 1)))
                                            || ((jVal2 < 0) && (jVal2 > -(QP << 1))))
                                    {
                                        jVal1 = (jVal0 + jVal1) >> 1;   /* (C+D)/2 */
                                        *ptr        =   jVal1;
                                        *(ptr - 1)  =   jVal1;

                                        jVal1 = *(ptr + 1);     /* E */
                                        jVal0 = *(ptr - 2);     /* B */
                                        jVal2 = jVal1 - jVal0;      /* E-B */

                                        if (jVal2 > 0)
                                        {
                                            jVal1 -= ((jVal2 + 3) >> 2);        /* E = E -(E-B)/4 */
                                            jVal0 += ((jVal2 + 3) >> 2);        /* B = B +(E-B)/4 */
                                            *(ptr + 1) = jVal1;
                                            *(ptr - 2) = jVal0;
                                        }
                                        else if (jVal2)
                                        {
                                            jVal1 += ((3 - jVal2) >> 2);        /* E = E -(E-B)/4 */
                                            jVal0 -= ((3 - jVal2) >> 2);        /* B = B +(E-B)/4 */
                                            *(ptr + 1) = jVal1;
                                            *(ptr - 2) = jVal0;
                                        }

                                        jVal1 = *(ptr + 2);     /* F */
                                        jVal0 = *(ptr - 3);     /* A */

                                        jVal2 = jVal1 - jVal0;          /* (F-A) */

                                        if (jVal2 > 0)
                                        {
                                            jVal1 -= ((jVal2 + 7) >> 3);    /* F -= (F-A)/8 */
                                            jVal0 += ((jVal2 + 7) >> 3);    /* A += (F-A)/8 */
                                            *(ptr + 2) = jVal1;
                                            *(ptr - 3) = jVal0;
                                        }
                                        else if (jVal2)
                                        {
                                            jVal1 -= ((jVal2 - 7) >> 3);    /* F -= (F-A)/8 */
                                            jVal0 += ((jVal2 - 7) >> 3);    /* A += (F-A)/8 */
                                            *(ptr + 2) = jVal1;
                                            *(ptr - 3) = jVal0;
                                        }
                                    }   /* end of ver hard filetering */
                                }
                                while ((ptr += width) < ptr_e);
                            }

                        } /* boundary*/
                    } /*bc*/
                brwidth += pp_w;
            }/*br*/
            brwidth -= (pp_w << 1);
        }/*mbc*/
        brwidth += (pp_w << 1);
    }/*mbr*/
    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
#endif
