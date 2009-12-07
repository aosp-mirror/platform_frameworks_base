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

 Pathname: fft_rx8.c
 Funtions: ps_fft_rx8

------------------------------------------------------------------------------
 REVISION HISTORY



 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    Real     Vector of Real components size 8

    Imag     Vector of Imag components size 8
             type Int32


 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
     scratch_mem size 32

 Outputs:
    In-place calculation of a 8-point FFT (radix-8)

 Pointers and Buffers Modified:
    calculation are done in-place and returned in Data

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    8-point DFT, radix 8 with Decimation in Frequency

------------------------------------------------------------------------------
 REQUIREMENTS

    This function should provide a fixed point FFT for any input array
    of size power of 8.

------------------------------------------------------------------------------
 REFERENCES

    [1] Advance Digital Signal Processing, J. Proakis, C. Rader, F. Ling,
        C. Nikias, Macmillan Pub. Co.

------------------------------------------------------------------------------
 PSEUDO-CODE


   MODIFY( x[] )
   RETURN( exponent )

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

#ifdef AAC_PLUS


#ifdef PARAMETRICSTEREO


#include "pv_audio_type_defs.h"
#include "ps_fft_rx8.h"

#include    "fxp_mul32.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define R_SHIFT     29
#define Q29_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL VARIABLE DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void ps_fft_rx8(Int32 Re[], Int32 Im[], Int32 scratch_mem[])

/* scratch_mem size 32 */
{

    Int     i;
    Int32   *Q = &scratch_mem[0];
    Int32   *Z = &scratch_mem[16];
    Int32   temp1;
    Int32   temp2;
    Int32   temp3;
    Int32   temp4;
    Int32   aux_r[2];
    Int32   aux_i[2];
    Int32   *pt_r1 = &Re[0];
    Int32   *pt_r2 = &Re[4];
    Int32   *pt_i1 = &Im[0];
    Int32   *pt_i2 = &Im[4];

    Int32   *pt_Q = Q;
    Int32   *pt_Z = Z;


    temp1 = *(pt_r1++); /*  Real */
    temp2 = *(pt_r2++); /*  Real */
    temp3 = *(pt_i1++); /*  Imag */
    temp4 = *(pt_i2++); /*  Imag */
    /*
     *  Vector Q stores data as Real, Imag, Real, Imag,....
     */

    *(pt_Q++) = temp1 + temp2;  /* Q(0) =  v(0) + v(4) */
    *(pt_Q++) = temp3 + temp4;
    *(pt_Q++) = temp1 - temp2;  /* Q(1) =  v(0) - v(4) */
    *(pt_Q++) = temp3 - temp4;

    temp1 = *(pt_r1++);
    temp2 = *(pt_r2++);
    temp3 = *(pt_i1++);
    temp4 = *(pt_i2++);

    *(pt_Q++) = temp1 + temp2;  /*    Q(2) =  v(1) + v(5) */
    *(pt_Q++) = temp3 + temp4;
    aux_r[0]  = temp1 - temp2;  /* aux[0]  =  v(1) - v(5) */
    aux_i[0]  = temp3 - temp4;

    temp1 = *(pt_r1++);
    temp2 = *(pt_r2++);
    temp3 = *(pt_i1++);
    temp4 = *(pt_i2++);

    *(pt_Q++) = temp1 + temp2;  /*  Q(3) =  v(2) + v(6) */
    *(pt_Q++) = temp3 + temp4;
    *(pt_Q++) = temp4 - temp3;  /*  Q(4) = (v(2) - v(6))*j */
    *(pt_Q++) = temp1 - temp2;

    temp1 = *(pt_r1++);
    temp2 = *(pt_r2++);
    temp3 = *(pt_i1++);
    temp4 = *(pt_i2++);


    *(pt_Q++) = temp1 + temp2;  /*  Q(5)   = v(3) + v(7) */
    *(pt_Q++) = temp3 + temp4;
    aux_r[1]  = temp1 - temp2;  /*  aux[1] = v(3) - v(7) */
    aux_i[1]  = temp3 - temp4;
    /*  Q(6) =  (aux[0] - aux[1])/sqrt(2); */
    *(pt_Q++) = fxp_mul32_Q29((aux_r[0] - aux_r[1]), Q29_fmt(0.70710678118655f));
    *(pt_Q++) = fxp_mul32_Q29((aux_i[0] - aux_i[1]), Q29_fmt(0.70710678118655f));

    /*  Q(7) =  (aux[0] + aux[1])*j/sqrt(2); */
    *(pt_Q++) =  fxp_mul32_Q29((aux_i[0] + aux_i[1]), Q29_fmt(-0.70710678118655f));
    *(pt_Q) =  fxp_mul32_Q29((aux_r[0] + aux_r[1]), Q29_fmt(0.70710678118655f));

    pt_r1 = &Q[0];        /* reset pointer */
    pt_r2 = &Q[6];        /* reset pointer */

    temp1 = *(pt_r1++);
    temp2 = *(pt_r2++);
    temp3 = *(pt_r1++);
    temp4 = *(pt_r2++);

    /*
     *  Vector Z stores data as Real, Imag, Real, Imag,....
     */

    *(pt_Z++) = temp1 + temp2;  /* Q(0) + Q(3) */
    *(pt_Z++) = temp3 + temp4;
    aux_r[0]  = temp1 - temp2;
    aux_i[0]  = temp3 - temp4;

    temp1 = *(pt_r1++);
    temp2 = *(pt_r2++);
    temp3 = *(pt_r1++);
    temp4 = *(pt_r2++);

    *(pt_Z++) = temp1 + temp2;  /* Q(1) + Q(4) */
    *(pt_Z++) = temp3 + temp4;
    *(pt_Z++) = aux_r[0];       /* Q(0) - Q(3) */
    *(pt_Z++) = aux_i[0];
    *(pt_Z++) = temp1 - temp2;  /* Q(1) - Q(4) */
    *(pt_Z++) = temp3 - temp4;

    temp1 = *(pt_r1++);
    temp2 = *(pt_r2++);
    temp3 = *(pt_r1);
    temp4 = *(pt_r2++);

    *(pt_Z++) = temp1 + temp2;  /* Q(2) + Q(5) */
    *(pt_Z++) = temp3 + temp4;
    aux_r[0]  = temp1 - temp2;
    aux_i[0]  = temp3 - temp4;

    temp1 = *(pt_r2++);
    temp3 = *(pt_r2++);
    temp2 = *(pt_r2++);
    temp4 = *(pt_r2);

    *(pt_Z++) = temp1 + temp2;  /* Q(6) + Q(7) */
    *(pt_Z++) = temp3 + temp4;

    *(pt_Z++) = -aux_i[0];      /* (Q(2) - Q(5))*j */
    *(pt_Z++) =  aux_r[0];

    *(pt_Z++) =  temp2 - temp1;  /* -Q(6) + Q(7) */
    *(pt_Z) =  temp4 - temp3;

    pt_Z = &Z[0];        /* reset pointer */
    pt_Q = &Z[8];        /* reset pointer */

    pt_r1 = &Re[0];
    pt_r2 = &Re[4];
    pt_i1 = &Im[0];
    pt_i2 = &Im[4];


    for (i = 4; i != 0; i--)
    {
        temp1 = *(pt_Z++);
        temp2 = *(pt_Q++);
        temp3 = *(pt_Z++);
        temp4 = *(pt_Q++);

        *(pt_r1++) = temp1 + temp2;  /* Z(n) + Z(n+4) */
        *(pt_i1++) = temp3 + temp4;
        *(pt_r2++) = temp1 - temp2;  /* Z(n) - Z(n+4) */
        *(pt_i2++) = temp3 - temp4;
    }

}

#endif  /* PARAMETRICSTEREO */


#endif  /* AAC_PLUS */
