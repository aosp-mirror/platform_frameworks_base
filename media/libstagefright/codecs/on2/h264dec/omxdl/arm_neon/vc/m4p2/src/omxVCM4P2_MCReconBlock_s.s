;//
;// 
;// File Name:  omxVCM4P2_MCReconBlock_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
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

    M_VARIANTS CortexA8

;// ***************************************************************************
;// ARM1136JS implementation
;// ***************************************************************************

;// ***************************************************************************
;// CortexA8 implementation
;// ***************************************************************************
    IF  CortexA8
;// ***************************************************************************
;// MACRO DEFINITIONS
;// ***************************************************************************
    ;// Description:
    ;// Does interpolation for the case of "IntegerPixel" predictType. Both 
    ;// rounding cases are handled. Just copies a block from pSrc to pDst
    ;//
    ;// Syntax:
    ;// M_MCRECONBLOCK_IntegerPixel
    ;// 
    ;// Inputs: None
    ;// Outputs: None

    MACRO 
    M_MCRECONBLOCK_IntegerPixel
CaseIntegerPixel_Rnd0
CaseIntegerPixel_Rnd1

    VLD1        dRow0, [pSrc], srcStep
    VLD1        dRow1, [pSrc], srcStep
    VLD1        dRow2, [pSrc], srcStep
    VLD1        dRow3, [pSrc], srcStep
    VLD1        dRow4, [pSrc], srcStep
    VLD1        dRow5, [pSrc], srcStep
    VLD1        dRow6, [pSrc], srcStep
    VLD1        dRow7, [pSrc], srcStep

    VST1        dRow0, [pDst@64], dstStep
    VST1        dRow1, [pDst@64], dstStep
    VST1        dRow2, [pDst@64], dstStep
    VST1        dRow3, [pDst@64], dstStep
    VST1        dRow4, [pDst@64], dstStep
    VST1        dRow5, [pDst@64], dstStep
    VST1        dRow6, [pDst@64], dstStep
    VST1        dRow7, [pDst@64], dstStep

    B           SwitchPredictTypeEnd
    MEND
;// ***************************************************************************
    ;// Description:
    ;// Does interpolation for the case of "HalfPixelX" predictType. The two 
    ;// rounding cases are handled by the parameter "$rndVal". Averages between
    ;// a pixel and pixel right to it, rounding it based on $rndVal. The 
    ;// rounding is implemented by using opCode switching between "VRHADD" and 
    ;// "VHADD" instructions.
    ;//
    ;// Syntax:
    ;// M_MCRECONBLOCK_HalfPixelX $rndVal
    ;// 
    ;// Inputs: 
    ;//     $rndVal: 0 for rounding and 1 for no rounding
    ;// Outputs: None

    MACRO 
    M_MCRECONBLOCK_HalfPixelX $rndVal

    LCLS M_VHADDR
    IF $rndVal = 0
M_VHADDR SETS "VRHADD"
    ELSE
M_VHADDR SETS "VHADD"
    ENDIF

CaseHalfPixelX_Rnd$rndVal

    VLD1        {dRow0, dRow0Shft}, [pSrc], srcStep
    VEXT        dRow0Shft, dRow0, dRow0Shft, #1
    VLD1        {dRow1, dRow1Shft}, [pSrc], srcStep
    VEXT        dRow1Shft, dRow1, dRow1Shft, #1
    VLD1        {dRow2, dRow2Shft}, [pSrc], srcStep
    VEXT        dRow2Shft, dRow2, dRow2Shft, #1
    VLD1        {dRow3, dRow3Shft}, [pSrc], srcStep
    VEXT        dRow3Shft, dRow3, dRow3Shft, #1
    VLD1        {dRow4, dRow4Shft}, [pSrc], srcStep
    VEXT        dRow4Shft, dRow4, dRow4Shft, #1
    VLD1        {dRow5, dRow5Shft}, [pSrc], srcStep
    VEXT        dRow5Shft, dRow5, dRow5Shft, #1
    VLD1        {dRow6, dRow6Shft}, [pSrc], srcStep
    VEXT        dRow6Shft, dRow6, dRow6Shft, #1
    VLD1        {dRow7, dRow7Shft}, [pSrc], srcStep
    VEXT        dRow7Shft, dRow7, dRow7Shft, #1
    $M_VHADDR   dRow0, dRow0, dRow0Shft
    $M_VHADDR   dRow1, dRow1, dRow1Shft
    VST1        dRow0, [pDst@64], dstStep
    $M_VHADDR   dRow2, dRow2, dRow2Shft
    VST1        dRow1, [pDst@64], dstStep
    $M_VHADDR   dRow3, dRow3, dRow3Shft
    VST1        dRow2, [pDst@64], dstStep
    $M_VHADDR   dRow4, dRow4, dRow4Shft
    VST1        dRow3, [pDst@64], dstStep
    $M_VHADDR   dRow5, dRow5, dRow5Shft
    VST1        dRow4, [pDst@64], dstStep
    $M_VHADDR   dRow6, dRow6, dRow6Shft
    VST1        dRow5, [pDst@64], dstStep
    $M_VHADDR   dRow7, dRow7, dRow7Shft
    VST1        dRow6, [pDst@64], dstStep
    VST1        dRow7, [pDst@64], dstStep
    
    B           SwitchPredictTypeEnd
    MEND
;// ***************************************************************************
    ;// Description:
    ;// Does interpolation for the case of "HalfPixelY" predictType. The two 
    ;// rounding cases are handled by the parameter "$rndVal". Averages between
    ;// a pixel and pixel below it, rounding it based on $rndVal. The 
    ;// rounding is implemented by using opCode switching between "VRHADD" and 
    ;// "VHADD" instructions.
    ;//
    ;// Syntax:
    ;// M_MCRECONBLOCK_HalfPixelY $rndVal
    ;// 
    ;// Inputs: 
    ;//     $rndVal: 0 for rounding and 1 for no rounding
    ;// Outputs: None

    MACRO 
    M_MCRECONBLOCK_HalfPixelY $rndVal

    LCLS M_VHADDR
    IF $rndVal = 0
M_VHADDR SETS "VRHADD"
    ELSE
M_VHADDR SETS "VHADD"
    ENDIF

CaseHalfPixelY_Rnd$rndVal
    VLD1        dRow0, [pSrc], srcStep
    VLD1        dRow1, [pSrc], srcStep
    VLD1        dRow2, [pSrc], srcStep
    VLD1        dRow3, [pSrc], srcStep
    VLD1        dRow4, [pSrc], srcStep
    VLD1        dRow5, [pSrc], srcStep
    VLD1        dRow6, [pSrc], srcStep
    VLD1        dRow7, [pSrc], srcStep
    $M_VHADDR   dRow0, dRow0, dRow1
    VLD1        dRow8, [pSrc], srcStep
    $M_VHADDR   dRow1, dRow1, dRow2
    VST1        dRow0, [pDst@64], dstStep
    $M_VHADDR   dRow2, dRow2, dRow3
    VST1        dRow1, [pDst@64], dstStep
    $M_VHADDR   dRow3, dRow3, dRow4
    VST1        dRow2, [pDst@64], dstStep
    $M_VHADDR   dRow4, dRow4, dRow5
    VST1        dRow3, [pDst@64], dstStep
    $M_VHADDR   dRow5, dRow5, dRow6
    VST1        dRow4, [pDst@64], dstStep
    $M_VHADDR   dRow6, dRow6, dRow7
    VST1        dRow5, [pDst@64], dstStep
    $M_VHADDR   dRow7, dRow7, dRow8
    VST1        dRow6, [pDst@64], dstStep
    VST1        dRow7, [pDst@64], dstStep

    B           SwitchPredictTypeEnd
    MEND
;// ***************************************************************************
    ;// Description:
    ;// Does interpolation for the case of "IntegerPixel" predictType. Both 
    ;// rounding cases are handled. 
    ;// Typical computation for a row goes like this
    ;//     1. VLD1        {dRow0, dRow0Shft}, [pSrc], srcStep ;// Load the row and next 8 bytes
    ;//     2. VEXT        dRow0Shft, dRow0, dRow0Shft, #1     ;// Generate the shifted row
    ;//     3. VADDL       qSum0, dRow0, dRow0Shft             ;// Generate the sum of row and shifted row
    ;//     5. VADD        qSum0, qSum0, qSum1                 ;// Add to the sum of next row (odd row sum has rounding value added to it)
    ;//     6. VSHRN       dRow0, qSum0, #2                    ;// Divide by 4
    ;//     7. VST1        dRow0, [pDst@64], dstStep           ;// Store
    ;// Odd rows undergo following computation after step 3
    ;//     4. VADD        qSum1, qSum1, qRound
    ;// This saves for adding rounding value to each final sum (overall saves 4 
    ;// instructions).
    ;// There is reuse of registers for qSum6, qSum7 & qSum8. Overall scheduling takes 
    ;// care of this and also minimizes stalls. Rounding value was modified in 
    ;// ARM register rndVal (originally used for rounding flag) before the switch.
    ;// It is then populated into all lanes in this macro. No branching out to 
    ;// label "SwitchPredictTypeEnd" is required in the end of the macro as these 
    ;// are the last of switch cases.
    ;// 
    ;// Syntax:
    ;// M_MCRECONBLOCK_HalfPixelXY
    ;// 
    ;// Inputs: None
    ;// Outputs: None

    MACRO 
    M_MCRECONBLOCK_HalfPixelXY

CaseHalfPixelXY_Rnd0
CaseHalfPixelXY_Rnd1
    VLD1        {dRow0, dRow0Shft}, [pSrc], srcStep
    VDUP        qRound, rndVal
    VLD1        {dRow1, dRow1Shft}, [pSrc], srcStep
    VEXT        dRow0Shft, dRow0, dRow0Shft, #1
    VLD1        {dRow2, dRow2Shft}, [pSrc], srcStep
    VEXT        dRow1Shft, dRow1, dRow1Shft, #1
    VLD1        {dRow3, dRow3Shft}, [pSrc], srcStep
    VEXT        dRow2Shft, dRow2, dRow2Shft, #1
    VLD1        {dRow4, dRow4Shft}, [pSrc], srcStep
    VADDL       qSum0, dRow0, dRow0Shft
    VLD1        {dRow5, dRow5Shft}, [pSrc], srcStep
    VADDL       qSum1, dRow1, dRow1Shft
    VLD1        {dRow6, dRow6Shft}, [pSrc], srcStep
    VEXT        dRow3Shft, dRow3, dRow3Shft, #1
    VLD1        {dRow7, dRow7Shft}, [pSrc], srcStep
    VEXT        dRow4Shft, dRow4, dRow4Shft, #1
    VLD1        {dRow8, dRow8Shft}, [pSrc], srcStep
    VADD        qSum1, qSum1, qRound
    VADDL       qSum2, dRow2, dRow2Shft
    VEXT        dRow5Shft, dRow5, dRow5Shft, #1
    VADD        qSum0, qSum0, qSum1
    VADDL       qSum3, dRow3, dRow3Shft
    VEXT        dRow6Shft, dRow6, dRow6Shft, #1
    VADD        qSum1, qSum1, qSum2
    VSHRN       dRow0, qSum0, #2
    VADDL       qSum4, dRow4, dRow4Shft
    VSHRN       dRow1, qSum1, #2
    VADD        qSum3, qSum3, qRound
    VADDL       qSum5, dRow5, dRow5Shft
    VST1        dRow0, [pDst@64], dstStep
    VEXT        dRow7Shft, dRow7, dRow7Shft, #1
    VST1        dRow1, [pDst@64], dstStep
    VEXT        dRow8Shft, dRow8, dRow8Shft, #1
    VADD        qSum5, qSum5, qRound
    VADD        qSum2, qSum2, qSum3
    VADD        qSum3, qSum3, qSum4
    VADD        qSum4, qSum4, qSum5
    VSHRN       dRow2, qSum2, #2
    VSHRN       dRow3, qSum3, #2
    VSHRN       dRow4, qSum4, #2
    VADDL       qSum6, dRow6, dRow6Shft
    VADDL       qSum7, dRow7, dRow7Shft
    VST1        dRow2, [pDst@64], dstStep
    VADDL       qSum8, dRow8, dRow8Shft
    VADD        qSum7, qSum7, qRound
    VST1        dRow3, [pDst@64], dstStep
    VST1        dRow4, [pDst@64], dstStep
    VADD        qSum5, qSum5, qSum6
    VADD        qSum6, qSum6, qSum7
    VADD        qSum7, qSum7, qSum8
    VSHRN       dRow5, qSum5, #2
    VSHRN       dRow6, qSum6, #2
    VSHRN       dRow7, qSum7, #2
    VST1        dRow5, [pDst@64], dstStep
    VST1        dRow6, [pDst@64], dstStep
    VST1        dRow7, [pDst@64], dstStep

    MEND
;// ***************************************************************************

;// Input/Output Registers
pSrc                  RN 0
srcStep               RN 1
pSrcResidue           RN 2
pDst                  RN 3
dstStep               RN 4
predictType           RN 5
rndVal                RN 6

;// Local Scratch Registers
pDstCopy              RN 0
return                RN 0

;// Neon Registers
dRow0                 DN D0.U8
dRow0Shft             DN D1.U8
dRow1                 DN D2.U8
dRow1Shft             DN D3.U8
dRow2                 DN D4.U8
dRow2Shft             DN D5.U8
dRow3                 DN D6.U8
dRow3Shft             DN D7.U8
dRow4                 DN D8.U8
dRow4Shft             DN D9.U8
dRow5                 DN D10.U8
dRow5Shft             DN D11.U8
dRow6                 DN D12.U8
dRow6Shft             DN D13.U8
dRow7                 DN D14.U8
dRow7Shft             DN D15.U8
dRow8                 DN D16.U8
dRow8Shft             DN D17.U8


qSum0                 QN Q9.U16
qSum1                 QN Q10.U16
qSum2                 QN Q11.U16
qSum3                 QN Q12.U16
qSum4                 QN Q13.U16
qSum5                 QN Q14.U16
qSum6                 QN Q0.U16
qSum7                 QN Q1.U16
qSum8                 QN Q2.U16

qRound                QN Q15.U16

dDst0                 DN D0.U8
dDst1                 DN D1.U8
dDst2                 DN D2.U8
dDst3                 DN D3.U8
dDst4                 DN D4.U8
dDst5                 DN D5.U8
dDst6                 DN D6.U8
dDst7                 DN D7.U8

qRes0                 QN Q4.S16
qRes1                 QN Q5.S16
qRes2                 QN Q6.S16
qRes3                 QN Q7.S16
qRes4                 QN Q8.S16
qRes5                 QN Q9.S16
qRes6                 QN Q10.S16
qRes7                 QN Q11.S16

    ;// Function header
    M_START     omxVCM4P2_MCReconBlock, r6, d15
    ;// Define stack arguments
    M_ARG       Arg_dstStep,        4
    M_ARG       Arg_predictType,    4
    M_ARG       Arg_rndVal,         4
    ;// Load argument from the stack
    M_LDR       dstStep, Arg_dstStep
    M_LDR       predictType, Arg_predictType
    M_LDR       rndVal, Arg_rndVal
    ADD         predictType, rndVal, predictType, LSL #1
    RSB         rndVal, rndVal, #2              ;// preparing rndVal for HalfPixelXY
    
    ;// The following is implementation of switching to different code segments
    ;// based on different predictType and rndVal flags. The corresponding 
    ;// labels (e.g. CaseIntegerPixel_Rnd0) are embedded in the macros following
    ;// M_ENDSWITCH (e.g. M_MCRECONBLOCK_IntegerPixel). While "M_MCRECONBLOCK_IntegerPixel" 
    ;// and "M_MCRECONBLOCK_HalfPixelXY" handle for both rounding cases; 
    ;// "M_MCRECONBLOCK_HalfPixelX" and "M_MCRECONBLOCK_HalfPixelY" macros handle 
    ;// the two rounding cases in separate code bases.
    ;// All these together implement the interpolation functionality
    
    M_SWITCH    predictType
        M_CASE      CaseIntegerPixel_Rnd0
        M_CASE      CaseIntegerPixel_Rnd1
        M_CASE      CaseHalfPixelX_Rnd0
        M_CASE      CaseHalfPixelX_Rnd1
        M_CASE      CaseHalfPixelY_Rnd0
        M_CASE      CaseHalfPixelY_Rnd1
        M_CASE      CaseHalfPixelXY_Rnd0
        M_CASE      CaseHalfPixelXY_Rnd1
    M_ENDSWITCH

    M_MCRECONBLOCK_IntegerPixel
    M_MCRECONBLOCK_HalfPixelX 0
    M_MCRECONBLOCK_HalfPixelX 1
    M_MCRECONBLOCK_HalfPixelY 0
    M_MCRECONBLOCK_HalfPixelY 1
    M_MCRECONBLOCK_HalfPixelXY
SwitchPredictTypeEnd

    ;// After interpolation is done, residue needs to be added. This is done 
    ;// only in case "pSrcResidue" parameter to the function is not NULL.
    ;// Following is a completely unrolled code to do so. Each row and 
    ;// corresponding residue is loaded and residue is added and value 
    ;// stored
    
    CMP         pSrcResidue, #0
    SUBNE       pDst, pDst, dstStep, LSL #3     ;// Restoring pDst
    MOVNE       pDstCopy, pDst
    BEQ         pSrcResidueConditionEnd
pSrcResidueNotNull    
    VLD1        dDst0, [pDst@64], dstStep
    VLD1        qRes0, [pSrcResidue@128]!
    VLD1        dDst1, [pDst@64], dstStep
    VLD1        qRes1, [pSrcResidue@128]!
    VLD1        dDst2, [pDst@64], dstStep
    VLD1        qRes2, [pSrcResidue@128]!
    VADDW       qRes0, qRes0, dDst0
    VLD1        dDst3, [pDst@64], dstStep
    VADDW       qRes1, qRes1, dDst1
    VLD1        qRes3, [pSrcResidue@128]!
    VADDW       qRes2, qRes2, dDst2
    VLD1        dDst4, [pDst@64], dstStep
    VQMOVUN     dDst0, qRes0
    VLD1        qRes4, [pSrcResidue@128]!
    VADDW       qRes3, qRes3, dDst3
    VLD1        dDst5, [pDst@64], dstStep
    VQMOVUN     dDst1, qRes1
    VLD1        qRes5, [pSrcResidue@128]!
    VADDW       qRes4, qRes4, dDst4
    VLD1        dDst6, [pDst@64], dstStep
    VQMOVUN     dDst2, qRes2
    VLD1        qRes6, [pSrcResidue@128]!
    VADDW       qRes5, qRes5, dDst5
    VLD1        dDst7, [pDst@64], dstStep
    VQMOVUN     dDst3, qRes3
    VLD1        qRes7, [pSrcResidue@128]!
    VADDW       qRes6, qRes6, dDst6
    VST1        dDst0, [pDstCopy@64], dstStep
    VQMOVUN     dDst4, qRes4
    VST1        dDst1, [pDstCopy@64], dstStep
    VADDW       qRes7, qRes7, dDst7
    VST1        dDst2, [pDstCopy@64], dstStep
    VQMOVUN     dDst5, qRes5
    VST1        dDst3, [pDstCopy@64], dstStep
    VQMOVUN     dDst6, qRes6
    VST1        dDst4, [pDstCopy@64], dstStep
    VQMOVUN     dDst7, qRes7
    VST1        dDst5, [pDstCopy@64], dstStep
    VST1        dDst6, [pDstCopy@64], dstStep
    VST1        dDst7, [pDstCopy@64], dstStep
    
pSrcResidueConditionEnd
    MOV         return, #OMX_Sts_NoErr

    M_END
    ENDIF ;// CortexA8
    END
;// ***************************************************************************
;// omxVCM4P2_MCReconBlock ends
;// ***************************************************************************
