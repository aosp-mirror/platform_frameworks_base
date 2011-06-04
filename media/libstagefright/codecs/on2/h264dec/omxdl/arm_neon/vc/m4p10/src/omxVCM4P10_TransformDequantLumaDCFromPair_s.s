;//
;// 
;// File Name:  omxVCM4P10_TransformDequantLumaDCFromPair_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//
;// Description:
;// H.264 inverse quantize and transform module
;// 
;// 

;// Include standard headers

        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
;// Import/Export symbols required from/to other files
;// (For example tables)
        
        IMPORT armVCM4P10_UnpackBlock4x4 
        IMPORT armVCM4P10_QPDivTable
        IMPORT armVCM4P10_VMatrixQPModTable
        
        M_VARIANTS CortexA8

;// Set debugging level        
;//DEBUG_ON    SETL {TRUE}


;// Static Function: armVCM4P10_InvTransformDequantLumaDC4x4
    

;// Guarding implementation by the processor name
    
    

;// Static Function: armVCM4P10_InvTransformDequantLumaDC4x4

;// Guarding implementation by the processor name
    
    IF  CortexA8
    
;//Input Registers
pData               RN  0
QP                  RN  1    


;//Local Scratch Registers

;// ARM Registers

pQPDivTable         RN  2
pQPModTable         RN  3
Shift               RN  4
Scale               RN  5

;// NEON Registers

;// Packed Input pixels
dIn0                DN  D0.S16
dIn1                DN  D1.S16
dIn2                DN  D2.S16
dIn3                DN  D3.S16   

;// Intermediate calculations
dRowSum1            DN  D4.S16
dRowSum2            DN  D5.S16
dRowDiff1           DN  D6.S16
dRowDiff2           DN  D7.S16

;// Row operated pixels
dRowOp0             DN  D0.S16
dRowOp1                DN  D1.S16
dRowOp2                DN  D2.S16
dRowOp3                DN  D3.S16
qRowOp01            QN  Q0.32
qRowOp23            QN  Q1.32

;// Intermediate calculations
dColSum1            DN  D4.S16
dColSum2            DN  D5.S16
dColDiff1           DN  D6.S16
dColDiff2           DN  D7.S16

;// Coloumn operated pixels
dColOp0             DN  D0.S16
dColOp1                DN  D1.S16
dColOp2                DN  D2.S16
dColOp3                DN  D3.S16

;// Temporary scratch varaibles

dScale              DN  D5.S16
qRound0             QN  Q3.S32
qRound1             QN  Q4.S32
qRound2             QN  Q5.S32
qRound3             QN  Q6.S32

;// InvTransformed and Dequantized pixels
dOut0               DN  D0.S16
dOut1                DN  D1.S16
dOut2                DN  D2.S16
dOut3                DN  D3.S16

       
    ;// Allocate stack memory required by the function
        

    ;// Write function header
    M_START armVCM4P10_InvTransformDequantLumaDC4x4,r5,d13
    
    ;******************************************************************
    ;// The strategy used in implementing the transform is as follows:*
    ;// Load the 4x4 block into 4 D-registers                         *  
    ;// Transpose the 4x4 matrix                                      *  
    ;// Perform the row operations (on columns) using SIMD            *  
    ;// Transpose the 4x4 result matrix                               *  
    ;// Perform the coloumn operations                                *
    ;******************************************************************

        ;// Load all the 4x4 pixels in Transposed form
        
        VLD4    {dIn0,dIn1,dIn2,dIn3},[pData]
        LDR     pQPDivTable, =armVCM4P10_QPDivTable        ;// QP Division look-up-table base pointer
        LDR     pQPModTable, =armVCM4P10_VMatrixQPModTable ;// QP Modulo look-up-table base pointer
        
        ;**************************************** 
        ;// Row Operations (Performed on columns)
        ;**************************************** 
        ;// Scale factor calculation is done using ARM instructions
        ;// Interleaved with NEON instructions inorder to Dual issue
        
        VADD    dRowSum1,dIn0,dIn1
        VADD    dRowSum2,dIn2,dIn3
        VSUB    dRowDiff1,dIn0,dIn1
        LDRSB   Shift, [pQPDivTable, QP]               ;// ARM CODE: Shift = pQPDivTable[QP]
        VSUB    dRowDiff2,dIn2,dIn3
        LDRSB   Scale, [pQPModTable, QP]               ;// ARM CODE: Scale = pQPModTable[QP] 
        VADD    dRowOp0,dRowSum1,dRowSum2
        VSUB    dRowOp1,dRowSum1,dRowSum2
        VSUB    dRowOp2,dRowDiff1,dRowDiff2
        LSL     Scale, Scale, Shift                    ;// ARM CODE: Scale = Scale << Shift
        VADD    dRowOp3,dRowDiff1,dRowDiff2
        
        ;****************************************
        ;// Transpose the resultant matrix
        ;****************************************
        
        VTRN    dRowOp0,dRowOp1
        VTRN    dRowOp2,dRowOp3
        VTRN    qRowOp01,qRowOp23 
        
        ;**************************************** 
        ;// Coloumn Operations 
        ;**************************************** 
        
        VADD    dColSum1,dRowOp0,dRowOp1
        VADD    dColSum2,dRowOp2,dRowOp3
        VSUB    dColDiff1,dRowOp0,dRowOp1
        VSUB    dColDiff2,dRowOp2,dRowOp3
        VADD    dColOp0,dColSum1,dColSum2
        VSUB    dColOp1,dColSum1,dColSum2
        VSUB    dColOp2,dColDiff1,dColDiff2
        VADD    dColOp3,dColDiff1,dColDiff2
        
        ;//----------------------------------------------------------------------
        ;//
        ;// <Dequantize> improves on the c-reference code
        ;// Both the  cases i.e., Shift>=0 and Shift<0 cases are covered together
        ;// We do not subtract 2 from Shift as in C reference, instead perform a
        ;// Scale << Shift once in the beginning and do a right shift by a 
        ;// constant 2 after the Multiplication. The value of Round would be 2 
        ;// 
        ;// By doing this we aviod the Branches required and also 
        ;// reduce the code size substantially
        ;// 
        ;//----------------------------------------------------------------------
        
        
        VDUP    dScale, Scale                            ;// ARM -> NEON  copy 'scale' to vector
               
                
        VMOV    qRound0,#2                               ;// Set the Round Value 
        VMOV    qRound1,#2
        VMOV    qRound2,#2
        VMOV    qRound3,#2
        
        VMLAL   qRound0,dColOp0,dScale                   ;// pDst[i] * Scale + Round 
        VMLAL   qRound1,dColOp1,dScale
        VMLAL   qRound2,dColOp2,dScale
        VMLAL   qRound3,dColOp3,dScale
        
        VSHRN   dOut0,qRound0,#2                          ;// Right shift by 2 & (OMX_S16)Value
        VSHRN   dOut1,qRound1,#2
        VSHRN   dOut2,qRound2,#2
        VSHRN   dOut3,qRound3,#2
        
        ;***************************
        ;// Store all the 4x4 pixels
        ;***************************
        
        VST1  {dOut0,dOut1,dOut2,dOut3}, [pData]

        
        ;// Set return value
        
        ;// Write function tail
        M_END        
        
    ENDIF                                                           ;//CORTEXA8   
        


;// Function: omxVCM4P10_TransformDequantLumaDCFromPair
    
;//Input Registers
ppSrc               RN  0
pDst                RN  1
QPR2                RN  2

;//Output Registers
result              RN  0

;//Local Scratch Registers
pDstR4              RN  4
pDstR0              RN  0
QPR1                RN  1
QPR5                RN  5

;// Guarding implementation by the processor name
    
    IF CortexA8
       
    ;// Allocate stack memory required by the function
        

    ;// Write function header
        M_START omxVCM4P10_TransformDequantLumaDCFromPair,r5
        
        MOV     pDstR4,pDst                         ;// Saving register r1
        MOV     QPR5,QPR2                           ;// Saving register r2
        BL      armVCM4P10_UnpackBlock4x4
        
        MOV     pDstR0,pDstR4                       ;// Setting up register r0
        MOV     QPR1,QPR5                           ;// Setting up register r1
        BL      armVCM4P10_InvTransformDequantLumaDC4x4
                               
       
        ;// Set return value
        MOV     result,#OMX_Sts_NoErr        
       
        ;// Write function tail
        M_END
        
            
    ENDIF                                                           ;//ARM1136JS  
    

    END