;//
;// 
;// File Name:  omxVCM4P2_MCReconBlock_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//
;// Description:
;//
;//

;// Include standard headers
    INCLUDE omxtypes_s.h
    INCLUDE armCOMM_s.h

;// Import symbols required from other files

    M_VARIANTS ARM1136JS

;// ***************************************************************************
;// ARM1136JS implementation
;// ***************************************************************************
    IF  ARM1136JS
    
;// ***************************************************************************
;// MACRO DEFINITIONS
;// ***************************************************************************
    ;// Description:
    ;//
    ;//   dest[j] = (x[j] + y[j] + round) >> 1,   j=0..3
    ;//
    ;// Similar to UHADD8 instruction, but with a rounding value of 1 added to
    ;// each sum before dividing by two, if round is 1
    ;//
    ;// Syntax:
    ;// M_UHADD8R   $dest, $x, $y, $round, $mask
    ;//
    ;// Inputs:
    ;// $x        four packed bytes,   x[3] :  x[2]  :  x[1]  :  x[0]
    ;// $y        four packed bytes,   y[3] :  y[2]  :  y[1]  :  y[0]
    ;// $round    0 if no rounding to be added, 1 if rounding to be done
    ;// $mask     some register set to 0x80808080
    ;//
    ;// Outputs:
    ;// $dest     four packed bytes,   z[3] :  z[2]  :  z[1]  :  z[0]

    MACRO
    M_UHADD8R   $dest, $x, $y, $round, $mask
    IF $round = 1
        IF  $dest /= $y
            MVN         $dest, $x
            UHSUB8      $dest, $y, $dest
            EOR         $dest, $dest, $mask
        ELSE
            MVN         $dest, $y
            UHSUB8      $dest, $x, $dest
            EOR         $dest, $dest, $mask
        ENDIF
    ELSE
        UHADD8      $dest, $x, $y
    ENDIF
    MEND
;// ***************************************************************************
    ;// Description:
    ;// Load 8 bytes from $pSrc (aligned or unaligned locations)
    ;//
    ;// Syntax:
    ;// M_LOAD_X    $pSrc, $srcStep, $out0, $out1, $scratch, $offset
    ;// 
    ;// Inputs:
    ;// $pSrc       4 byte aligned source pointer to an address just less than 
    ;//             or equal to the data location
    ;// $srcStep    The stride on source
    ;// $scratch    A scratch register, used internally for temp calculations
    ;// $offset     Difference of source data location to the source pointer
    ;//             Use when $offset != 0 (unaligned load)
    ;//
    ;// Outputs:
    ;// $pSrc       In case the macro accepts stride, it increments the pSrc by 
    ;//             that value, else unchanged
    ;// $out0       four packed bytes,   z[3] :  z[2]  :  z[1]  :  z[0]
    ;// $out1       four packed bytes,   z[7] :  z[6]  :  z[5]  :  z[4]
    ;//
    ;// Note: {$out0, $out1, $scratch} should be registers with ascending
    ;// register numbering. In case offset is 0, $scratch is not modified.

    MACRO
    M_LOAD_X    $pSrc, $srcStep, $out0, $out1, $scratch, $offset
        IF $offset = 0
            LDM         $pSrc, {$out0, $out1}
            ADD         $pSrc, $pSrc, $srcStep
        ELSE
            LDM         $pSrc, {$out0, $out1, $scratch} 
            ADD         $pSrc, $pSrc, $srcStep
            
            MOV         $out0, $out0, LSR #8 * $offset
            ORR         $out0, $out0, $out1, LSL #(32 - 8 * ($offset))
            MOV         $out1, $out1, LSR #8 * $offset
            ORR         $out1, $out1, $scratch, LSL #(32 - 8 * ($offset))
        ENDIF
    MEND

;// ***************************************************************************
    ;// Description:
    ;// Loads three words for X interpolation, update pointer to next row. For 
    ;// X interpolation, given a truncated-4byteAligned source pointer, 
    ;// invariably three continous words are required from there to get the
    ;// nine bytes from the source pointer for filtering. 
    ;//
    ;// Syntax:
    ;// M_LOAD_XINT $pSrc, $srcStep, $offset, $word0, $word1, $word2, $word3
    ;// 
    ;// Inputs:
    ;// $pSrc       4 byte aligned source pointer to an address just less than 
    ;//             or equal to the data location
    ;//
    ;// $srcStep    The stride on source
    ;//
    ;// $offset     Difference of source data location to the source pointer
    ;//             Use when $offset != 0 (unaligned load)
    ;//
    ;// Outputs:
    ;// $pSrc       Incremented by $srcStep
    ;//
    ;// $word0, $word1, $word2, $word3
    ;//             Three of these are outputs based on the $offset parameter. 
    ;//             The outputs are specifically generated to be processed by 
    ;//             the M_EXT_XINT macro. Following is the illustration to show 
    ;//             how the nine bytes are spanned for different offsets from 
    ;//             notTruncatedForAlignmentSourcePointer.
    ;//
    ;//              ------------------------------------------------------
    ;//             | Offset | Aligned Ptr | word0 | word1 | word2 | word3 |
    ;//             |------------------------------------------------------|
    ;//             |    0   |       0     | 0123  | 4567  | 8xxx  |       |
    ;//             |    1   |      -1     | x012  | 3456  | 78xx  |       |
    ;//             |    2   |      -2     | xx01  | 2345  | 678x  |       |
    ;//             |    3   |      -3     | xxx0  |       | 1234  | 5678  |
    ;//              ------------------------------------------------------
    ;// 
    ;//             where the numbering (0-8) is to designate the 9 bytes from
    ;//             start of a particular row. The illustration doesn't take in 
    ;//             account the positioning of bytes with in the word and the 
    ;//             macro combination with M_EXT_XINT will work only in little 
    ;//             endian environs
    ;// 
    ;// Note: {$word0, $word1, $word2, $word3} should be registers with ascending
    ;// register numbering

    MACRO
    M_LOAD_XINT $pSrc, $srcStep, $offset, $word0, $word1, $word2, $word3
        IF $offset /= 3
            LDM         $pSrc, {$word0, $word1, $word2}
        ELSE
            LDM         $pSrc, {$word0, $word2, $word3}
        ENDIF
        ADD         $pSrc, $pSrc, $srcStep
    MEND

;// ***************************************************************************
    ;// Description:
    ;// Extract four registers of four pixels for X interpolation 
    ;// 
    ;// Syntax:
    ;// M_EXT_XINT $offset, $word0, $word1, $word2, $word3
    ;// 
    ;// Inputs:
    ;// $offset     Difference of source data location to the source pointer
    ;//             Use when $offset != 0 (unaligned load)
    ;// 
    ;// $word0, $word1, $word2, $word3
    ;//             Three of these are inputs based on the $offset parameter. 
    ;//             The inputs are specifically selected to be processed by 
    ;//             the M_EXT_XINT macro.
    ;//
    ;//              ------------------------------------------------------
    ;//             | Offset | Aligned Ptr | word0 | word1 | word2 | word3 |
    ;//             |------------------------------------------------------|
    ;//             |    0   |       0     | 0123  | 4567  | 8xxx  | yyyy  |
    ;//             |    1   |      -1     | x012  | 3456  | 78xx  | yyyy  |
    ;//             |    2   |      -2     | xx01  | 2345  | 678x  | yyyy  |
    ;//             |    3   |      -3     | xxx0  | yyyy  | 1234  | 5678  |
    ;//              ------------------------------------------------------
    ;// 
    ;// Outputs:
    ;// $word0, $word1, $word2, $word3
    ;//             Bytes from the original source pointer (not truncated for
    ;//             4 byte alignment) as shown in the table. 
    ;//              -------------------------------
    ;//             | word0 | word1 | word2 | word3 |
    ;//             |-------------------------------|
    ;//             | 0123  | 4567  | 1234  | 5678  |
    ;//              -------------------------------
    ;//
    ;// Note: {$word0, $word1, $word2, $word3} should be registers with ascending
    ;// register numbering

    MACRO
    M_EXT_XINT $offset, $word0, $word1, $word2, $word3
        IF $offset = 0
            ; $word0 and $word1 are ok
            ; $word2, $word3 are just 8 shifted versions
            MOV         $word3, $word1, LSR #8
            ORR         $word3, $word3, $word2, LSL #24
            MOV         $word2, $word0, LSR #8
            ORR         $word2, $word2, $word1, LSL #24
        ELIF $offset = 3
            ; $word2 and $word3 are ok (taken care while loading itself)
            ; set $word0 & $word1
            MOV         $word0, $word0, LSR #24
            ORR         $word0, $word0, $word2, LSL #8
            MOV         $word1, $word2, LSR #24
            ORR         $word1, $word1, $word3, LSL #8
        ELSE
            MOV         $word0, $word0, LSR #8 * $offset
            ORR         $word0, $word0, $word1, LSL #(32 - 8 * ($offset))
            MOV         $word1, $word1, LSR #8 * $offset
            ORR         $word1, $word1, $word2, LSL #(32 - 8 * ($offset))

            MOV         $word3, $word1, LSR #8
            ORR         $word3, $word3, $word2, LSL #(32 - 8 * (($offset)+1))
            MOV         $word2, $word0, LSR #8
            ORR         $word2, $word2, $word1, LSL #24
        ENDIF
    MEND

;// ***************************************************************************
    ;// Description:
    ;// Computes half-sum and xor of two inputs and puts them in the input 
    ;// registers in that order
    ;//
    ;// Syntax:
    ;// M_HSUM_XOR      $v0, $v1, $tmp
    ;// 
    ;// Inputs:
    ;// $v0         a, first input
    ;// $v1         b, second input
    ;// $tmp        scratch register
    ;// 
    ;// Outputs:
    ;// $v0         (a + b)/2
    ;// $v1         a ^ b

    MACRO
    M_HSUM_XOR      $v0, $v1, $tmp
        UHADD8      $tmp, $v0, $v1     ;// s0 = a + b
        EOR         $v1, $v0, $v1      ;// l0 = a ^ b
        MOV         $v0, $tmp          ;// s0
    MEND
;// ***************************************************************************
    ;// Description:
    ;// Calculates average of 4 values (a,b,c,d) for HalfPixelXY predict type in 
    ;// mcReconBlock module. Very specific to the implementation of 
    ;// M_MCRECONBLOCK_HalfPixelXY done here. Uses "tmp" as scratch register and 
    ;// "yMask" for mask variable "0x1010101x" set in it. In yMask 4 lsbs are 
    ;// not significant and are used by the callee for row counter (y)
    ;//
    ;// Some points to note are:
    ;// 1. Input is pair of pair-averages and Xors
    ;// 2. $sum1 and $lsb1 are not modified and hence can be reused in another 
    ;//    running average
    ;// 3. Output is in the first argument
    ;//
    ;// Syntax:
    ;// M_AVG4         $sum0, $lsb0, $sum1, $lsb1, $rndVal
    ;// 
    ;// Inputs:
    ;// $sum0       (a + b) >> 1, where a and b are 1st and 2nd inputs to be averaged
    ;// $lsb0       (a ^ b)
    ;// $sum1       (c + d) >> 1. Not modified
    ;// $lsb1       (c ^ d)       Not modified
    ;// $rndVal     Assembler Variable. 0 for rounding, 1 for no rounding
    ;// 
    ;// Outputs:
    ;// $sum0       (a + b + c + d + 1) / 4 : If no rounding
    ;//             (a + b + c + d + 2) / 4 : If rounding

    MACRO
    M_AVG4          $sum0, $lsb0, $sum1, $lsb1, $rndVal
        LCLS OP1
        LCLS OP2
        IF $rndVal = 0 ;// rounding case
OP1 SETS "AND"
OP2 SETS "ORR"
        ELSE           ;// Not rounding case
OP1 SETS "ORR"
OP2 SETS "AND"
        ENDIF
        
        LCLS lsb2
        LCLS sum2
        LCLS dest
    
lsb2  SETS "tmp"
sum2  SETS "$lsb0"
dest  SETS "$sum0"

        $OP1        $lsb0, $lsb0, $lsb1          ;// e0 = e0 & e1
        EOR         $lsb2, $sum0, $sum1          ;// e2 = s0 ^ s1
        $OP2        $lsb2, $lsb2, $lsb0          ;// e2 = e2 | e0
        AND         $lsb2, $lsb2, yMask, LSR # 4 ;// e2 = e2 & mask
        UHADD8      $sum2, $sum0, $sum1          ;// s2 = (s0 + s1)/2
        UADD8       $dest, $sum2, $lsb2          ;// dest =  s2 + e2
    MEND
;// ***************************************************************************
;// Motion compensation handler macros
;// ***************************************************************************
    ;// Description:
    ;// Implement motion compensation routines using the named registers in 
    ;// callee function. Each of the following 4 implement the 4 predict type
    ;// Each handles 8 cases each ie all the combinations of 4 types of source 
    ;// alignment offsets and 2 types of rounding flag
    ;//
    ;// Syntax:
    ;// M_MCRECONBLOCK_IntegerPixel $rndVal, $offset
    ;// M_MCRECONBLOCK_HalfPixelX   $rndVal, $offset
    ;// M_MCRECONBLOCK_HalfPixelY   $rndVal, $offset
    ;// M_MCRECONBLOCK_HalfPixelXY  $rndVal, $offset
    ;// 
    ;// Inputs:
    ;// $rndVal     Assembler Variable. 0 for rounding, 1 for no rounding
    ;// $offset     $pSrc MOD 4 value. Offset from 4 byte aligned location.
    ;// 
    ;// Outputs:
    ;// Outputs come in the named registers of the callee functions
    ;// The macro loads the data from the source pointer, processes it and 
    ;// stores in the destination pointer. Does the whole prediction cycle
    ;// of Motion Compensation routine for a particular predictType
    ;// After this only residue addition to the predicted values remain

    MACRO
    M_MCRECONBLOCK_IntegerPixel $rndVal, $offset
    ;// Algorithmic Description:
    ;// This handles motion compensation for IntegerPixel predictType. Both
    ;// rounding cases are handled by the same code base. It is just a copy
    ;// from source to destination. Two lines are done per loop to reduce 
    ;// stalls. Loop has been software pipelined as well for that purpose.
    ;// 
    ;// M_LOAD_X loads a whole row in two registers and then they are stored
    
CaseIntegerPixelRnd0Offset$offset
CaseIntegerPixelRnd1Offset$offset
    M_LOAD_X    pSrc, srcStep, tmp1, tmp2, tmp3, $offset
    M_LOAD_X    pSrc, srcStep, tmp3, tmp4, tmp5, $offset
YloopIntegerPixelOffset$offset
    SUBS        y, y, #2
    STRD        tmp1, tmp2, [pDst], dstStep
    STRD        tmp3, tmp4, [pDst], dstStep
    M_LOAD_X    pSrc, srcStep, tmp1, tmp2, tmp3, $offset
    M_LOAD_X    pSrc, srcStep, tmp3, tmp4, tmp5, $offset
    BGT         YloopIntegerPixelOffset$offset

    B           SwitchPredictTypeEnd
    MEND
;// ***************************************************************************
    MACRO
    M_MCRECONBLOCK_HalfPixelX $rndVal, $offset
    ;// Algorithmic Description:
    ;// This handles motion compensation for HalfPixelX predictType. The two
    ;// rounding cases are handled by the different code base and spanned by 
    ;// different macro calls. Loop has been software pipelined to reduce 
    ;// stalls.
    ;// 
    ;// Filtering involves averaging a pixel with the next horizontal pixel.
    ;// M_LOAD_XINT and M_EXT_XINT combination generate 4 registers, 2 with 
    ;// all pixels in a row with 4 pixel in each register and another 2
    ;// registers with pixels corresponding to one horizontally shifted pixel
    ;// corresponding to the initial row pixels. These are set of packed 
    ;// registers appropriate to do 4 lane SIMD.
    ;// After that M_UHADD8R macro does the averaging taking care of the 
    ;// rounding as required
    
CaseHalfPixelXRnd$rndVal.Offset$offset
    IF $rndVal = 0
        LDR mask, =0x80808080
    ENDIF

    M_LOAD_XINT pSrc, srcStep, $offset, tmp1, tmp2, tmp3, tmp4
YloopHalfPixelXRnd$rndVal.Offset$offset
    SUBS        y, y, #1
    M_EXT_XINT  $offset, tmp1, tmp2, tmp3, tmp4
    M_UHADD8R   tmp5, tmp1, tmp3, (1-$rndVal), mask
    M_UHADD8R   tmp6, tmp2, tmp4, (1-$rndVal), mask
    STRD        tmp5, tmp6, [pDst], dstStep
    M_LOAD_XINT pSrc, srcStep, $offset, tmp1, tmp2, tmp3, tmp4
    BGT         YloopHalfPixelXRnd$rndVal.Offset$offset

    B           SwitchPredictTypeEnd
    MEND
;// ***************************************************************************
    MACRO
    M_MCRECONBLOCK_HalfPixelY $rndVal, $offset
    ;// Algorithmic Description:
    ;// This handles motion compensation for HalfPixelY predictType. The two
    ;// rounding cases are handled by the different code base and spanned by 
    ;// different macro calls. PreLoading is used to avoid reload of same data. 
    ;// 
    ;// Filtering involves averaging a pixel with the next vertical pixel.
    ;// M_LOAD_X generates 2 registers with all pixels in a row with 4 pixel in 
    ;// each register. These are set of packed registers appropriate to do 
    ;// 4 lane SIMD. After that M_UHADD8R macro does the averaging taking care 
    ;// of the rounding as required
    
CaseHalfPixelYRnd$rndVal.Offset$offset
    IF $rndVal = 0
        LDR mask, =0x80808080
    ENDIF

    M_LOAD_X    pSrc, srcStep, tmp1, tmp2, tmp5, $offset ;// Pre-load
YloopHalfPixelYRnd$rndVal.Offset$offset
    SUBS        y, y, #2
    ;// Processing one line
    M_LOAD_X    pSrc, srcStep, tmp3, tmp4, tmp5, $offset
    M_UHADD8R   tmp1, tmp1, tmp3, (1-$rndVal), mask
    M_UHADD8R   tmp2, tmp2, tmp4, (1-$rndVal), mask
    STRD        tmp1, tmp2, [pDst], dstStep
    ;// Processing another line
    M_LOAD_X    pSrc, srcStep, tmp1, tmp2, tmp5, $offset
    M_UHADD8R   tmp3, tmp3, tmp1, (1-$rndVal), mask
    M_UHADD8R   tmp4, tmp4, tmp2, (1-$rndVal), mask
    STRD        tmp3, tmp4, [pDst], dstStep

    BGT         YloopHalfPixelYRnd$rndVal.Offset$offset

    B           SwitchPredictTypeEnd
    MEND
;// ***************************************************************************
    MACRO
    M_MCRECONBLOCK_HalfPixelXY $rndVal, $offset
    ;// Algorithmic Description:
    ;// This handles motion compensation for HalfPixelXY predictType. The two
    ;// rounding cases are handled by the different code base and spanned by 
    ;// different macro calls. PreLoading is used to avoid reload of same data. 
    ;// 
    ;// Filtering involves averaging a pixel with the next vertical, horizontal 
    ;// and right-down diagonal pixels. Just as in HalfPixelX case, M_LOAD_XINT
    ;// and M_EXT_XINT combination generates 4 registers with a row and its
    ;// 1 pixel right shifted version, with 4 pixels in one register. Another 
    ;// call of that macro-combination gets another row. Then M_HSUM_XOR is 
    ;// called to get mutual half-sum and xor combinations of a row with its
    ;// shifted version as they are inputs to the M_AVG4 macro which computes
    ;// the 4 element average with rounding. Note that it is the half-sum/xor 
    ;// values that are preserved for next row as they can be re-used in the 
    ;// next call to the M_AVG4 and saves recomputation.
    ;// Due to lack of register, the row counter and a masking value required 
    ;// in M_AVG4 are packed into a single register yMask where the last nibble
    ;// holds the row counter values and rest holds the masking variable left 
    ;// shifted by 4
    
CaseHalfPixelXYRnd$rndVal.Offset$offset
    LDR         yMask, =((0x01010101 << 4) + 8)

    M_LOAD_XINT pSrc, srcStep, $offset, t00, t01, t10, t11 ;// Load a, a', b, b'
    M_EXT_XINT  $offset, t00, t01, t10, t11
    M_HSUM_XOR  t00, t10, tmp               ;// s0, l0
    M_HSUM_XOR  t01, t11, tmp               ;// s0', l0'

YloopHalfPixelXYRnd$rndVal.Offset$offset
    ;// Processsing one line
    ;// t00, t01, t10, t11 required from previous loop
    M_LOAD_XINT pSrc, srcStep, $offset, t20, t21, t30, t31 ;// Load c, c', d, d'
    SUB         yMask, yMask, #2
    M_EXT_XINT  $offset, t20, t21, t30, t31
    M_HSUM_XOR  t20, t30, tmp               ;// s1, l1
    M_HSUM_XOR  t21, t31, tmp               ;// s1', l1'
    M_AVG4      t00, t10, t20, t30, $rndVal ;// s0, l0, s1, l1
    M_AVG4      t01, t11, t21, t31, $rndVal ;// s0', l0', s1', l1'
    STRD        t00, t01, [pDst], dstStep   ;// store the average
    
    ;// Processsing another line
    ;// t20, t21, t30, t31 required from above
    M_LOAD_XINT pSrc, srcStep, $offset, t00, t01, t10, t11 ;// Load a, a', b, b'
    TST         yMask, #7
    M_EXT_XINT  $offset, t00, t01, t10, t11
    M_HSUM_XOR  t00, t10, tmp
    M_HSUM_XOR  t01, t11, tmp
    M_AVG4      t20, t30, t00, t10, $rndVal
    M_AVG4      t21, t31, t01, t11, $rndVal
    STRD        t20, t21, [pDst], dstStep

    BGT         YloopHalfPixelXYRnd$rndVal.Offset$offset

    IF $offset/=3 :LOR: $rndVal/=1
        B           SwitchPredictTypeEnd
    ENDIF
    MEND
;// ***************************************************************************
;// Motion compensation handler macros end here
;// ***************************************************************************
    ;// Description:
    ;// Populates all 4 kinds of offsets "cases" for each predictType and rndVal
    ;// combination in the "switch" to prediction processing code segment
    ;//
    ;// Syntax:
    ;// M_CASE_OFFSET $rnd, $predictType
    ;// 
    ;// Inputs:
    ;// $rnd            0 for rounding, 1 for no rounding
    ;// $predictType    The prediction mode
    ;// 
    ;// Outputs:
    ;// Populated list of "M_CASE"s for the "M_SWITCH" macro

    MACRO
    M_CASE_OFFSET $rnd, $predictType
        M_CASE      Case$predictType.Rnd$rnd.Offset0
        M_CASE      Case$predictType.Rnd$rnd.Offset1
        M_CASE      Case$predictType.Rnd$rnd.Offset2
        M_CASE      Case$predictType.Rnd$rnd.Offset3
    MEND
;// ***************************************************************************
    ;// Description:
    ;// Populates all 2 kinds of rounding "cases" for each predictType in the 
    ;// "switch" to prediction processing code segment
    ;//
    ;// Syntax:
    ;// M_CASE_OFFSET $predictType
    ;// 
    ;// Inputs:
    ;// $predictType    The prediction mode
    ;// 
    ;// Outputs:
    ;// Populated list of "M_CASE_OFFSET" macros

    MACRO
    M_CASE_MCRECONBLOCK $predictType
        M_CASE_OFFSET  0, $predictType ;// 0 for rounding
        M_CASE_OFFSET  1, $predictType ;// 1 for no rounding
    MEND
;// ***************************************************************************
    ;// Description:
    ;// Populates all 8 kinds of rounding and offset combinations handling macros 
    ;// for the specified predictType. In case of "IntegerPixel" predictType, 
    ;// rounding is not required so same code segment handles both cases
    ;//
    ;// Syntax:
    ;// M_MCRECONBLOCK    $predictType
    ;// 
    ;// Inputs:
    ;// $predictType    The prediction mode
    ;// 
    ;// Outputs:
    ;// Populated list of "M_MCRECONBLOCK_<predictType>" macros for specified 
    ;// predictType. Each 
    ;//                 M_MCRECONBLOCK_<predictType> $rnd, $offset 
    ;// is an code segment (starting with a label indicating the predictType, 
    ;// rounding and offset combination)
    ;// Four calls of this macro with the 4 prediction modes populate all the 32 
    ;// handlers

    MACRO
    M_MCRECONBLOCK $predictType
        M_MCRECONBLOCK_$predictType 0, 0
        M_MCRECONBLOCK_$predictType 0, 1
        M_MCRECONBLOCK_$predictType 0, 2
        M_MCRECONBLOCK_$predictType 0, 3
    IF "$predictType" /= "IntegerPixel" ;// If not IntegerPixel then rounding makes a difference
        M_MCRECONBLOCK_$predictType 1, 0
        M_MCRECONBLOCK_$predictType 1, 1
        M_MCRECONBLOCK_$predictType 1, 2
        M_MCRECONBLOCK_$predictType 1, 3
    ENDIF
    MEND
;// ***************************************************************************
;// Input/Output Registers
pSrc                  RN 0
srcStep               RN 1
arg_pSrcResidue       RN 2
pSrcResidue           RN 12
pDst                  RN 3
dstStep               RN 2
predictType           RN 10
rndVal                RN 11
mask                  RN 11

;// Local Scratch Registers
zero                  RN 12
y                     RN 14

tmp1                  RN 4
tmp2                  RN 5
tmp3                  RN 6
tmp4                  RN 7
tmp5                  RN 8
tmp6                  RN 9
tmp7                  RN 10
tmp8                  RN 11
tmp9                  RN 12

t00                   RN 4
t01                   RN 5
t10                   RN 6
t11                   RN 7
t20                   RN 8
t21                   RN 9
t30                   RN 10
t31                   RN 11
tmp                   RN 12

yMask                 RN 14

dst                   RN 1
return                RN 0

    ;// Allocate memory on stack
    M_ALLOC4    Stk_pDst,           4
    M_ALLOC4    Stk_pSrcResidue,    4
    ;// Function header
    M_START     omxVCM4P2_MCReconBlock, r11
    ;// Define stack arguments
    M_ARG       Arg_dstStep,        4
    M_ARG       Arg_predictType,    4
    M_ARG       Arg_rndVal,         4
    ;// Save on stack
    M_STR       pDst, Stk_pDst
    M_STR       arg_pSrcResidue, Stk_pSrcResidue
    ;// Load argument from the stack
    M_LDR       dstStep, Arg_dstStep
    M_LDR       predictType, Arg_predictType
    M_LDR       rndVal, Arg_rndVal
    
    MOV         y, #8
    
    AND         tmp1, pSrc, #3
    ORR         predictType, tmp1, predictType, LSL #3
    ORR         predictType, predictType, rndVal, LSL #2
    ;// Truncating source pointer to align to 4 byte location
    BIC         pSrc, pSrc, #3

    ;// Implementation takes care of all combinations of different 
    ;// predictTypes, rounding cases and source pointer offsets to alignment 
    ;// of 4 bytes in different code bases unless one of these parameter wasn't 
    ;// making any difference to the implementation. Below M_CASE_MCRECONBLOCK
    ;// macros branch into 8 M_CASE macros for all combinations of the 2 
    ;// rounding cases and 4 offsets of the pSrc pointer to the 4 byte 
    ;// alignment. 
    M_SWITCH    predictType
        M_CASE_MCRECONBLOCK IntegerPixel
        M_CASE_MCRECONBLOCK HalfPixelX
        M_CASE_MCRECONBLOCK HalfPixelY
        M_CASE_MCRECONBLOCK HalfPixelXY
    M_ENDSWITCH

    ;// The M_MCRECONBLOCK macros populate the code bases by calling all 8 
    ;// particular macros (4 in case of IntegerPixel as rounding makes no 
    ;// difference there) to generate the code for all cases of rounding and 
    ;// offsets. LTORG is used to segment the code as code size bloated beyond 
    ;// 4KB.
    M_MCRECONBLOCK IntegerPixel
    M_MCRECONBLOCK HalfPixelX
    LTORG
    M_MCRECONBLOCK HalfPixelY
    M_MCRECONBLOCK HalfPixelXY
SwitchPredictTypeEnd

    ;// Residue Addition
    ;// This is done in 2 lane SIMD though loads are further optimized and
    ;// 4 bytes are loaded in case of destination buffer. Algorithmic 
    ;// details are in inlined comments
    M_LDR       pSrcResidue, Stk_pSrcResidue
    CMP         pSrcResidue, #0
    BEQ         pSrcResidueConditionEnd
pSrcResidueNotNull    
    M_LDR       pDst, Stk_pDst
    MOV         y, #8
    SUB         dstStep, dstStep, #4
Yloop_pSrcResidueNotNull
    SUBS        y, y, #1
    LDR         dst, [pDst]                ;// dst = [dcba]
    LDMIA       pSrcResidue!, {tmp1, tmp2} ;// tmp1=[DC] tmp2=[BA]
    PKHBT       tmp3, tmp1, tmp2, LSL #16  ;// Deltaval1 = [C A]
    PKHTB       tmp4, tmp2, tmp1, ASR #16  ;// DeltaVal2 = [D B]
    UXTB16      tmp1, dst                  ;// tmp1 = [0c0a]
    UXTB16      tmp2, dst, ROR #8          ;// tmp2 = [0d0b]
    QADD16      tmp1, tmp1, tmp3           ;// Add and saturate to 16 bits
    QADD16      tmp2, tmp2, tmp4
    USAT16      tmp1, #8, tmp1
    USAT16      tmp2, #8, tmp2             ;// armClip(0, 255, tmp2)
    ORR         tmp1, tmp1, tmp2, LSL #8   ;// tmp1 = [dcba]
    STR         tmp1, [pDst], #4
    
    LDR         dst, [pDst]
    LDMIA       pSrcResidue!, {tmp1, tmp2}
    PKHBT       tmp3, tmp1, tmp2, LSL #16
    PKHTB       tmp4, tmp2, tmp1, ASR #16
    UXTB16      tmp1, dst
    UXTB16      tmp2, dst, ROR #8
    QADD16      tmp1, tmp1, tmp3
    QADD16      tmp2, tmp2, tmp4
    USAT16      tmp1, #8, tmp1
    USAT16      tmp2, #8, tmp2
    ORR         tmp1, tmp1, tmp2, LSL #8
    STR         tmp1, [pDst], dstStep
    
    BGT         Yloop_pSrcResidueNotNull
pSrcResidueConditionEnd

    MOV         return, #OMX_Sts_NoErr

    M_END
    ENDIF ;// ARM1136JS

;// ***************************************************************************
;// CortexA8 implementation
;// ***************************************************************************
    END
;// ***************************************************************************
;// omxVCM4P2_MCReconBlock ends
;// ***************************************************************************
