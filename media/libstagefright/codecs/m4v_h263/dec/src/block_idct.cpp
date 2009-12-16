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

------------------------------------------------------------------------------
 REQUIREMENTS

------------------------------------------------------------------------------
 REFERENCES

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
#include "mp4dec_lib.h"
#include "idct.h"
#include "motion_comp.h"

#define OSCL_DISABLE_WARNING_CONV_POSSIBLE_LOSS_OF_DATA
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
/* private prototypes */
static void idctrow(int16 *blk, uint8 *pred, uint8 *dst, int width);
static void idctrow_intra(int16 *blk, PIXEL *, int width);
static void idctcol(int16 *blk);

#ifdef FAST_IDCT
// mapping from nz_coefs to functions to be used


// ARM4 does not allow global data when they are not constant hence
// an array of function pointers cannot be considered as array of constants
// (actual addresses are only known when the dll is loaded).
// So instead of arrays of function pointers, we'll store here
// arrays of rows or columns and then call the idct function
// corresponding to such the row/column number:


static void (*const idctcolVCA[10][4])(int16*) =
{
    {&idctcol1, &idctcol0, &idctcol0, &idctcol0},
    {&idctcol1, &idctcol1, &idctcol0, &idctcol0},
    {&idctcol2, &idctcol1, &idctcol0, &idctcol0},
    {&idctcol3, &idctcol1, &idctcol0, &idctcol0},
    {&idctcol3, &idctcol2, &idctcol0, &idctcol0},
    {&idctcol3, &idctcol2, &idctcol1, &idctcol0},
    {&idctcol3, &idctcol2, &idctcol1, &idctcol1},
    {&idctcol3, &idctcol2, &idctcol2, &idctcol1},
    {&idctcol3, &idctcol3, &idctcol2, &idctcol1},
    {&idctcol4, &idctcol3, &idctcol2, &idctcol1}
};


static void (*const idctrowVCA[10])(int16*, uint8*, uint8*, int) =
{
    &idctrow1,
    &idctrow2,
    &idctrow2,
    &idctrow2,
    &idctrow2,
    &idctrow3,
    &idctrow4,
    &idctrow4,
    &idctrow4,
    &idctrow4
};


static void (*const idctcolVCA2[16])(int16*) =
{
    &idctcol0, &idctcol4, &idctcol3, &idctcol4,
    &idctcol2, &idctcol4, &idctcol3, &idctcol4,
    &idctcol1, &idctcol4, &idctcol3, &idctcol4,
    &idctcol2, &idctcol4, &idctcol3, &idctcol4
};

static void (*const idctrowVCA2[8])(int16*, uint8*, uint8*, int) =
{
    &idctrow1, &idctrow4, &idctrow3, &idctrow4,
    &idctrow2, &idctrow4, &idctrow3, &idctrow4
};

static void (*const idctrowVCA_intra[10])(int16*, PIXEL *, int) =
{
    &idctrow1_intra,
    &idctrow2_intra,
    &idctrow2_intra,
    &idctrow2_intra,
    &idctrow2_intra,
    &idctrow3_intra,
    &idctrow4_intra,
    &idctrow4_intra,
    &idctrow4_intra,
    &idctrow4_intra
};

static void (*const idctrowVCA2_intra[8])(int16*, PIXEL *, int) =
{
    &idctrow1_intra, &idctrow4_intra, &idctrow3_intra, &idctrow4_intra,
    &idctrow2_intra, &idctrow4_intra, &idctrow3_intra, &idctrow4_intra
};
#endif

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
void MBlockIDCT(VideoDecData *video)
{
    Vop *currVop = video->currVop;
    MacroBlock *mblock = video->mblock;
    PIXEL *c_comp;
    PIXEL *cu_comp;
    PIXEL *cv_comp;
    int x_pos = video->mbnum_col;
    int y_pos = video->mbnum_row;
    int width, width_uv;
    int32 offset;
    width = video->width;
    width_uv = width >> 1;
    offset = (int32)(y_pos << 4) * width + (x_pos << 4);

    c_comp  = currVop->yChan + offset;
    cu_comp = currVop->uChan + (offset >> 2) + (x_pos << 2);
    cv_comp = currVop->vChan + (offset >> 2) + (x_pos << 2);

    BlockIDCT_intra(mblock, c_comp, 0, width);
    BlockIDCT_intra(mblock, c_comp + 8, 1, width);
    BlockIDCT_intra(mblock, c_comp + (width << 3), 2, width);
    BlockIDCT_intra(mblock, c_comp + (width << 3) + 8, 3, width);
    BlockIDCT_intra(mblock, cu_comp, 4, width_uv);
    BlockIDCT_intra(mblock, cv_comp, 5, width_uv);
}


void BlockIDCT_intra(
    MacroBlock *mblock, PIXEL *c_comp, int comp, int width)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int16 *coeff_in = mblock->block[comp];
#ifdef INTEGER_IDCT
#ifdef FAST_IDCT  /* VCA IDCT using nzcoefs and bitmaps*/
    int i, bmapr;
    int nz_coefs = mblock->no_coeff[comp];
    uint8 *bitmapcol = mblock->bitmapcol[comp];
    uint8 bitmaprow = mblock->bitmaprow[comp];

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    if (nz_coefs <= 10)
    {
        bmapr = (nz_coefs - 1);

        (*(idctcolVCA[bmapr]))(coeff_in);
        (*(idctcolVCA[bmapr][1]))(coeff_in + 1);
        (*(idctcolVCA[bmapr][2]))(coeff_in + 2);
        (*(idctcolVCA[bmapr][3]))(coeff_in + 3);

        (*idctrowVCA_intra[nz_coefs-1])(coeff_in, c_comp, width);
    }
    else
    {
        i = 8;
        while (i--)
        {
            bmapr = (int)bitmapcol[i];
            if (bmapr)
            {
                if ((bmapr&0xf) == 0)         /*  07/18/01 */
                {
                    (*(idctcolVCA2[bmapr>>4]))(coeff_in + i);
                }
                else
                {
                    idctcol(coeff_in + i);
                }
            }
        }
        if ((bitmapcol[4] | bitmapcol[5] | bitmapcol[6] | bitmapcol[7]) == 0)
        {
            bitmaprow >>= 4;
            (*(idctrowVCA2_intra[(int)bitmaprow]))(coeff_in, c_comp, width);
        }
        else
        {
            idctrow_intra(coeff_in, c_comp, width);
        }
    }
#else
    void idct_intra(int *block, uint8 *comp, int width);
    idct_intra(coeff_in, c_comp, width);
#endif
#else
    void idctref_intra(int *block, uint8 *comp, int width);
    idctref_intra(coeff_in, c_comp, width);
#endif


    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}

/*  08/04/05, no residue, just copy from pred to output */
void Copy_Blk_to_Vop(uint8 *dst, uint8 *pred, int width)
{
    /* copy 4 bytes at a time */
    width -= 4;
    *((uint32*)dst) = *((uint32*)pred);
    *((uint32*)(dst += 4)) = *((uint32*)(pred += 4));
    *((uint32*)(dst += width)) = *((uint32*)(pred += 12));
    *((uint32*)(dst += 4)) = *((uint32*)(pred += 4));
    *((uint32*)(dst += width)) = *((uint32*)(pred += 12));
    *((uint32*)(dst += 4)) = *((uint32*)(pred += 4));
    *((uint32*)(dst += width)) = *((uint32*)(pred += 12));
    *((uint32*)(dst += 4)) = *((uint32*)(pred += 4));
    *((uint32*)(dst += width)) = *((uint32*)(pred += 12));
    *((uint32*)(dst += 4)) = *((uint32*)(pred += 4));
    *((uint32*)(dst += width)) = *((uint32*)(pred += 12));
    *((uint32*)(dst += 4)) = *((uint32*)(pred += 4));
    *((uint32*)(dst += width)) = *((uint32*)(pred += 12));
    *((uint32*)(dst += 4)) = *((uint32*)(pred += 4));
    *((uint32*)(dst += width)) = *((uint32*)(pred += 12));
    *((uint32*)(dst += 4)) = *((uint32*)(pred += 4));

    return ;
}

/*  08/04/05 compute IDCT and add prediction at the end  */
void BlockIDCT(
    uint8 *dst,  /* destination */
    uint8 *pred, /* prediction block, pitch 16 */
    int16   *coeff_in,  /* DCT data, size 64 */
    int width, /* width of dst */
    int nz_coefs,
    uint8 *bitmapcol,
    uint8 bitmaprow
)
{
#ifdef INTEGER_IDCT
#ifdef FAST_IDCT  /* VCA IDCT using nzcoefs and bitmaps*/
    int i, bmapr;
    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    if (nz_coefs <= 10)
    {
        bmapr = (nz_coefs - 1);
        (*(idctcolVCA[bmapr]))(coeff_in);
        (*(idctcolVCA[bmapr][1]))(coeff_in + 1);
        (*(idctcolVCA[bmapr][2]))(coeff_in + 2);
        (*(idctcolVCA[bmapr][3]))(coeff_in + 3);

        (*idctrowVCA[nz_coefs-1])(coeff_in, pred, dst, width);
        return ;
    }
    else
    {
        i = 8;

        while (i--)
        {
            bmapr = (int)bitmapcol[i];
            if (bmapr)
            {
                if ((bmapr&0xf) == 0)         /*  07/18/01 */
                {
                    (*(idctcolVCA2[bmapr>>4]))(coeff_in + i);
                }
                else
                {
                    idctcol(coeff_in + i);
                }
            }
        }
        if ((bitmapcol[4] | bitmapcol[5] | bitmapcol[6] | bitmapcol[7]) == 0)
        {
            (*(idctrowVCA2[bitmaprow>>4]))(coeff_in, pred, dst, width);
        }
        else
        {
            idctrow(coeff_in, pred, dst, width);
        }
        return ;
    }
#else // FAST_IDCT
    void idct(int *block, uint8 *pred, uint8 *dst, int width);
    idct(coeff_in, pred, dst, width);
    return;
#endif // FAST_IDCT
#else // INTEGER_IDCT
    void idctref(int *block, uint8 *pred, uint8 *dst, int width);
    idctref(coeff_in, pred, dst, width);
    return;
#endif // INTEGER_IDCT

}
/*----------------------------------------------------------------------------
;  End Function: block_idct
----------------------------------------------------------------------------*/


/****************************************************************************/

/*
------------------------------------------------------------------------------
 FUNCTION NAME: idctrow
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS FOR idctrow

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
 FUNCTION DESCRIPTION FOR idctrow

------------------------------------------------------------------------------
 REQUIREMENTS FOR idctrow

------------------------------------------------------------------------------
 REFERENCES FOR idctrow

------------------------------------------------------------------------------
 PSEUDO-CODE FOR idctrow

------------------------------------------------------------------------------
 RESOURCES USED FOR idctrow
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
; Function Code FOR idctrow
----------------------------------------------------------------------------*/
void idctrow(
    int16 *blk, uint8 *pred, uint8 *dst, int width
)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* row (horizontal) IDCT
    *
    * 7                       pi         1 dst[k] = sum c[l] * src[l] * cos( -- *
    * ( k + - ) * l ) l=0                      8          2
    *
    * where: c[0]    = 128 c[1..7] = 128*sqrt(2) */

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    width -= 4;
    dst -= width;
    pred -= 12;
    blk -= 8;

    while (i--)
    {
        x1 = (int32)blk[12] << 8;
        blk[12] = 0;
        x2 = blk[14];
        blk[14] = 0;
        x3 = blk[10];
        blk[10] = 0;
        x4 = blk[9];
        blk[9] = 0;
        x5 = blk[15];
        blk[15] = 0;
        x6 = blk[13];
        blk[13] = 0;
        x7 = blk[11];
        blk[11] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        blk[0] = 0;   /* for proper rounding in the fourth stage */

        /* first stage */
        x8 = W7 * (x4 + x5) + 4;
        x4 = (x8 + (W1 - W7) * x4) >> 3;
        x5 = (x8 - (W1 + W7) * x5) >> 3;
        x8 = W3 * (x6 + x7) + 4;
        x6 = (x8 - (W3 - W5) * x6) >> 3;
        x7 = (x8 - (W3 + W5) * x7) >> 3;

        /* second stage */
        x8 = x0 + x1;
        x0 -= x1;
        x1 = W6 * (x3 + x2) + 4;
        x2 = (x1 - (W2 + W6) * x2) >> 3;
        x3 = (x1 + (W2 - W6) * x3) >> 3;
        x1 = x4 + x6;
        x4 -= x6;
        x6 = x5 + x7;
        x5 -= x7;

        /* third stage */
        x7 = x8 + x3;
        x8 -= x3;
        x3 = x0 + x2;
        x0 -= x2;
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x4 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        pred_word = *((uint32*)(pred += 12)); /* read 4 bytes from pred */

        res = (x7 + x1) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x3 + x2) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x0 + x4) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x8 + x6) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(dst += width)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred += 4)); /* read 4 bytes from pred */

        res = (x8 - x6) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x0 - x4) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x3 - x2) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x7 - x1) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(dst += 4)) = dst_word; /* save 4 bytes to dst */
    }
    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}

void idctrow_intra(
    int16 *blk, PIXEL *comp, int width
)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8, temp;
    int i = 8;
    int offset = width;
    int32 word;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* row (horizontal) IDCT
    *
    * 7                       pi         1 dst[k] = sum c[l] * src[l] * cos( -- *
    * ( k + - ) * l ) l=0                      8          2
    *
    * where: c[0]    = 128 c[1..7] = 128*sqrt(2) */
    while (i--)
    {
        x1 = (int32)blk[4] << 8;
        blk[4] = 0;
        x2 = blk[6];
        blk[6] = 0;
        x3 = blk[2];
        blk[2] = 0;
        x4 = blk[1];
        blk[1] = 0;
        x5 = blk[7];
        blk[7] = 0;
        x6 = blk[5];
        blk[5] = 0;
        x7 = blk[3];
        blk[3] = 0;
#ifndef FAST_IDCT
        /* shortcut */  /* covered by idctrow1  01/9/2001 */
        if (!(x1 | x2 | x3 | x4 | x5 | x6 | x7))
        {
            blk[0] = blk[1] = blk[2] = blk[3] = blk[4] = blk[5] = blk[6] = blk[7] = (blk[0] + 32) >> 6;
            return;
        }
#endif
        x0 = ((int32)blk[0] << 8) + 8192;
        blk[0] = 0;  /* for proper rounding in the fourth stage */

        /* first stage */
        x8 = W7 * (x4 + x5) + 4;
        x4 = (x8 + (W1 - W7) * x4) >> 3;
        x5 = (x8 - (W1 + W7) * x5) >> 3;
        x8 = W3 * (x6 + x7) + 4;
        x6 = (x8 - (W3 - W5) * x6) >> 3;
        x7 = (x8 - (W3 + W5) * x7) >> 3;

        /* second stage */
        x8 = x0 + x1;
        x0 -= x1;
        x1 = W6 * (x3 + x2) + 4;
        x2 = (x1 - (W2 + W6) * x2) >> 3;
        x3 = (x1 + (W2 - W6) * x3) >> 3;
        x1 = x4 + x6;
        x4 -= x6;
        x6 = x5 + x7;
        x5 -= x7;

        /* third stage */
        x7 = x8 + x3;
        x8 -= x3;
        x3 = x0 + x2;
        x0 -= x2;
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x4 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        word = ((x7 + x1) >> 14);
        CLIP_RESULT(word)

        temp = ((x3 + x2) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 8);

        temp = ((x0 + x4) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 16);

        temp = ((x8 + x6) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 24);
        *((int32*)(comp)) = word;

        word = ((x8 - x6) >> 14);
        CLIP_RESULT(word)

        temp = ((x0 - x4) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 8);

        temp = ((x3 - x2) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 16);

        temp = ((x7 - x1) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 24);
        *((int32*)(comp + 4)) = word;
        comp += offset;

        blk += B_SIZE;
    }
    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}

/*----------------------------------------------------------------------------
; End Function: idctrow
----------------------------------------------------------------------------*/


/****************************************************************************/

/*
------------------------------------------------------------------------------
 FUNCTION NAME: idctcol
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS FOR idctcol

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
 FUNCTION DESCRIPTION FOR idctcol

------------------------------------------------------------------------------
 REQUIREMENTS FOR idctcol

------------------------------------------------------------------------------
 REFERENCES FOR idctcol

------------------------------------------------------------------------------
 PSEUDO-CODE FOR idctcol

------------------------------------------------------------------------------
 RESOURCES USED FOR idctcol
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
; Function Code FOR idctcol
----------------------------------------------------------------------------*/
void idctcol(
    int16 *blk
)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* column (vertical) IDCT
    *
    * 7                         pi         1 dst[8*k] = sum c[l] * src[8*l] *
    * cos( -- * ( k + - ) * l ) l=0                        8          2
    *
    * where: c[0]    = 1/1024 c[1..7] = (1/1024)*sqrt(2) */
    x1 = (int32)blk[32] << 11;
    x2 = blk[48];
    x3 = blk[16];
    x4 = blk[8];
    x5 = blk[56];
    x6 = blk[40];
    x7 = blk[24];
#ifndef FAST_IDCT
    /* shortcut */        /* covered by idctcolumn1  01/9/2001 */
    if (!(x1 | x2 | x3 | x4 | x5 | x6 | x7))
    {
        blk[0] = blk[8] = blk[16] = blk[24] = blk[32] = blk[40] = blk[48] = blk[56]
                                              = blk[0] << 3;
        return;
    }
#endif

    x0 = ((int32)blk[0] << 11) + 128;

    /* first stage */
    x8 = W7 * (x4 + x5);
    x4 = x8 + (W1 - W7) * x4;
    x5 = x8 - (W1 + W7) * x5;
    x8 = W3 * (x6 + x7);
    x6 = x8 - (W3 - W5) * x6;
    x7 = x8 - (W3 + W5) * x7;

    /* second stage */
    x8 = x0 + x1;
    x0 -= x1;
    x1 = W6 * (x3 + x2);
    x2 = x1 - (W2 + W6) * x2;
    x3 = x1 + (W2 - W6) * x3;
    x1 = x4 + x6;
    x4 -= x6;
    x6 = x5 + x7;
    x5 -= x7;

    /* third stage */
    x7 = x8 + x3;
    x8 -= x3;
    x3 = x0 + x2;
    x0 -= x2;
    x2 = (181 * (x4 + x5) + 128) >> 8;
    x4 = (181 * (x4 - x5) + 128) >> 8;

    /* fourth stage */
    blk[0]    = (x7 + x1) >> 8;
    blk[8] = (x3 + x2) >> 8;
    blk[16] = (x0 + x4) >> 8;
    blk[24] = (x8 + x6) >> 8;
    blk[32] = (x8 - x6) >> 8;
    blk[40] = (x0 - x4) >> 8;
    blk[48] = (x3 - x2) >> 8;
    blk[56] = (x7 - x1) >> 8;
    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
/*----------------------------------------------------------------------------
;  End Function: idctcol
----------------------------------------------------------------------------*/

