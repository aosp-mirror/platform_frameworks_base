;//
;// 
;// File Name:  omxVCM4P10_TransformDequantLumaDCFromPair_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
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
        
        M_VARIANTS ARM1136JS

;// Set debugging level        
;//DEBUG_ON    SETL {TRUE}


;// Static Function: armVCM4P10_InvTransformDequantLumaDC4x4
    

;// Guarding implementation by the processor name
    
    IF  ARM1136JS 


;//Input Registers
pData               RN  0
QP                  RN  1

;//Output Registers


;//Local Scratch Registers

;// Packed Input pixels
in00                RN  2                   ;// Src[0] & Src[1] 
in02                RN  3                   ;// Src[2] & Src[3]
in10                RN  4                   ;// Src[4] & Src[5]
in12                RN  5                   ;// Src[6] & Src[7]
in20                RN  6                   ;// Src[8] & Src[9]
in22                RN  7                   ;// Src[10] & Src[11]
in30                RN  8                   ;// Src[12] & Src[13]
in32                RN  9                   ;// Src[14] & Src[15]

;// Transpose for Row operations (Rows to cols)
trRow00             RN  2
trRow10             RN  10
trRow02             RN  3
trRow12             RN  5
trRow20             RN  11
trRow30             RN  12
trRow32             RN  14
trRow22             RN  7

;// Intermediate calculations
rowSum1             RN  4
rowSum2             RN  6
rowDiff1            RN  8
rowDiff2            RN  9


;// Row operated pixels
rowOp00             RN  2
rowOp10             RN  10
rowOp20             RN  11
rowOp30             RN  12
rowOp02             RN  3
rowOp12             RN  5
rowOp22             RN  7
rowOp32             RN  14

;// Transpose for colulmn operations
trCol00             RN  2                   
trCol02             RN  3                   
trCol10             RN  4                   
trCol12             RN  5                   
trCol20             RN  6                   
trCol22             RN  7                   
trCol30             RN  8                   
trCol32             RN  9  

;// Intermediate calculations
colSum1             RN  10
colSum2             RN  11
colDiff1            RN  12
colDiff2            RN  14


;// Coloumn operated pixels
colOp00             RN  2                   
colOp02             RN  3                   
colOp10             RN  4                   
colOp12             RN  5                   
colOp20             RN  6                   
colOp22             RN  7                   
colOp30             RN  8                   
colOp32             RN  9  

;// Temporary scratch varaibles
pQPDivTable         RN  0
pQPModTable         RN  11
Shift               RN  10
Scale               RN  14
Round               RN  0

temp1               RN  10
temp2                RN  11
temp3               RN  12
temp4               RN  1



;// InvTransformed and Dequantized pixels
out00               RN  2                   
out02               RN  3                   
out10               RN  4                   
out12               RN  5                   
out20               RN  6                   
out22               RN  7                   
out30               RN  8                   
out32               RN  9  
      
        

       
    ;// Allocate stack memory required by the function
        M_ALLOC4    pDataOnStack, 4

    ;// Write function header
        M_START armVCM4P10_InvTransformDequantLumaDC4x4,r11
        
        ;******************************************************************
        ;// The strategy used in implementing the transform is as follows:*
        ;// Load the 4x4 block into 8 registers                           *  
        ;// Transpose the 4x4 matrix                                      *  
        ;// Perform the row operations (on columns) using SIMD            *  
        ;// Transpose the 4x4 result matrix                               *  
        ;// Perform the coloumn operations                                *
        ;// Store the 4x4 block at one go                                 *  
        ;******************************************************************

        ;// Load all the 4x4 pixels
        
        LDMIA   pData,{in00,in02,in10,in12,in20,in22,in30,in32}
        
        ;//*****************************************************************
        ;//
        ;// Transpose the matrix inorder to perform row ops as coloumn ops
        ;// Input:   in[][] = original matrix
        ;// Output:  trRow[][]= transposed matrix
        ;// Step1: Obtain the LL part of the transposed matrix
        ;// Step2: Obtain the HL part
        ;// step3: Obtain the LH part
        ;// Step4: Obtain the HH part
        ;//
        ;//*****************************************************************
        
        ;// LL 2x2 transposed matrix 
        ;//   d0 d1 - -
        ;//   d4 d5 - -
        ;//   -  -  - -
        ;//   -  -  - -
        
        PKHTB   trRow10,in10,in00,ASR #16               ;// [5 4] = [f5:f1]    
        PKHBT   trRow00,in00,in10,LSL #16               ;// [1 0] = [f4:f0]  
        
        ;// HL 2x2 transposed matrix  
        ;//    -   -   - -
        ;//    -   -   - -
        ;//    d8  d9  - -
        ;//   d12 d13  - -
        
         
         PKHTB   trRow30,in12,in02,ASR #16              ;// [13 12] = [7 3]
         PKHBT   trRow20,in02,in12,LSL #16              ;// [9 8] = [6 2] 
        
        ;// LH 2x2 transposed matrix 
        ;//   - - d2 d3 
        ;//   - - d6 d7 
        ;//   - - -  -
        ;//   - - -  -
        
        PKHBT   trRow02,in20,in30,LSL #16               ;// [3 2] = [f12:f8]  
        PKHTB   trRow12,in30,in20,ASR #16               ;// [7 6] = [f13:f9] 
        
        
        
         
        ;// HH 2x2 transposed matrix  
        ;//    - -   -   -
        ;//    - -   -   -
        ;//    - -  d10 d11
        ;//    - -  d14 d15
        
        PKHTB   trRow32,in32,in22,ASR #16               ;// [15 14] = [15 11]
        PKHBT   trRow22,in22,in32,LSL #16               ;// [11 10] = [14 10]
       
        
        ;**************************************** 
        ;// Row Operations (Performed on columns)
        ;**************************************** 
        
        
        ;// SIMD operations on first two columns(two rows of the original matrix)
        
        SADD16      rowSum1,trRow00,trRow10                ;// (c0+c1)
        SADD16      rowSum2,trRow20,trRow30                ;// (c2+c3)
        SSUB16      rowDiff1,trRow00,trRow10               ;// (c0-c1)
        SSUB16      rowDiff2,trRow20,trRow30               ;// (c2-c3)
        SADD16      rowOp00,rowSum1,rowSum2                ;// (c0+c1+c2+c3)
        SSUB16      rowOp10,rowSum1,rowSum2                ;// (c0+c1-c2-c3)
        SSUB16      rowOp20,rowDiff1,rowDiff2              ;// (c0-c1-c2+c3)
        SADD16      rowOp30,rowDiff1,rowDiff2              ;// (c0-c1+c2-c3)
        
                
        ;// SIMD operations on next two columns(next two rows of the original matrix)
        
        SADD16      rowSum1,trRow02,trRow12                ;// (c0+c1)
        SADD16      rowSum2,trRow22,trRow32                ;// (c2+c3)
        SSUB16      rowDiff1,trRow02,trRow12               ;// (c0-c1)
        SSUB16      rowDiff2,trRow22,trRow32               ;// (c2-c3)
        SADD16      rowOp02,rowSum1,rowSum2                ;// (c0+c1+c2+c3)
        SSUB16      rowOp12,rowSum1,rowSum2                ;// (c0+c1-c2-c3)
        SSUB16      rowOp22,rowDiff1,rowDiff2              ;// (c0-c1-c2+c3)
        SADD16      rowOp32,rowDiff1,rowDiff2              ;// (c0-c1+c2-c3)
        
        
        
        ;*****************************************************************
        ;// Transpose the resultant matrix
        ;// Input:  rowOp[][]
        ;// Output: trCol[][] 
        ;*****************************************************************
        
        ;// LL 2x2 transposed matrix 
        ;//   d0 d1 - -
        ;//   d4 d5 - -
        ;//   -  -  - -
        ;//   -  -  - -
        
        PKHTB   trCol10,rowOp10,rowOp00,ASR #16           ;// [5 4] = [f5:f1]
        PKHBT   trCol00,rowOp00,rowOp10,LSL #16           ;// [1 0] = [f4:f0]  
        
        ;// HL 2x2 transposed matrix  
        ;//    -   -   - -
        ;//    -   -   - -
        ;//    d8  d9  - -
        ;//   d12 d13  - -
        
         
         PKHTB   trCol30,rowOp12,rowOp02,ASR #16          ;// [13 12] = [7 3]
         PKHBT   trCol20,rowOp02,rowOp12,LSL #16          ;// [9 8] = [6 2] 
        
        ;// LH 2x2 transposed matrix 
        ;//   - - d2 d3 
        ;//   - - d6 d7 
        ;//   - - -  -
        ;//   - - -  -
        
        PKHBT   trCol02,rowOp20,rowOp30,LSL #16           ;// [3 2] = [f12:f8]  
        PKHTB   trCol12,rowOp30,rowOp20,ASR #16           ;// [7 6] = [f13:f9] 
        
        
        
         
        ;// HH 2x2 transposed matrix  
        ;//    - -   -   -
        ;//    - -   -   -
        ;//    - -  d10 d11
        ;//    - -  d14 d15
        
        PKHTB   trCol32,rowOp32,rowOp22,ASR #16            ;// [15 14] = [15 11]
        PKHBT   trCol22,rowOp22,rowOp32,LSL #16            ;// [11 10] = [14 10]
       
        
        ;******************************* 
        ;// Coloumn Operations 
        ;******************************* 
        
        ;//--------------------------------------------------------------------------------------
        ;// Store pData(RN0) on stack and restore it only at the final store back
        ;// This frees up a register (RN0) which is used to reduce number of intermediate stalls 
        ;//--------------------------------------------------------------------------------------
        M_STR       pData,pDataOnStack
        
        
        ;// SIMD operations on first two columns(two rows of the original matrix)
                
        SADD16      colSum1,trCol00,trCol10                ;// (c0+c1)
        SADD16      colSum2,trCol20,trCol30                ;// (c2+c3)
        SSUB16      colDiff1,trCol00,trCol10               ;// (c0-c1)
        SSUB16      colDiff2,trCol20,trCol30               ;// (c2-c3)
        SADD16      colOp00,colSum1,colSum2                ;// (c0+c1+c2+c3)
        SSUB16      colOp10,colSum1,colSum2                ;// (c0+c1-c2-c3)
        SSUB16      colOp20,colDiff1,colDiff2              ;// (c0-c1-c2+c3)
        SADD16      colOp30,colDiff1,colDiff2              ;// (c0-c1+c2-c3)
        
                
        ;// SIMD operations on next two columns(next two rows of the original matrix)
        
        LDR         pQPDivTable, =armVCM4P10_QPDivTable    ;// QP Division look-up-table base pointer
        SADD16      colSum1,trCol02,trCol12                ;// (c0+c1)
        SADD16      colSum2,trCol22,trCol32                ;// (c2+c3)
        SSUB16      colDiff1,trCol02,trCol12               ;// (c0-c1)
        SSUB16      colDiff2,trCol22,trCol32               ;// (c2-c3)
        SADD16      colOp02,colSum1,colSum2                ;// (c0+c1+c2+c3)
        SSUB16      colOp12,colSum1,colSum2                ;// (c0+c1-c2-c3)
        LDR         pQPModTable, =armVCM4P10_VMatrixQPModTable ;// QP Modulo look-up-table base pointer
        LDRSB       Shift, [pQPDivTable, QP]               ;// Shift = pQPDivTable[QP]
        SSUB16      colOp22,colDiff1,colDiff2              ;// (c0-c1-c2+c3)
        SADD16      colOp32,colDiff1,colDiff2              ;// (c0-c1+c2-c3)
        
               
        LDRSB       Scale, [pQPModTable, QP]               ;// Scale = pQPModTable[QP] 
        
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
        
        MOV         Round, #2                               ;// Round = 2
        LSL         Scale, Scale, Shift                     ;// Scale = Scale << Shift
                
        
        ;// Row 1
        SMLABB  temp1, colOp00, Scale, Round                ;// Temp1 = B(c0w0) * Scale + Round
        SMLABB  temp3, colOp02, Scale, Round                ;// Temp3 = B(c1w0) * Scale + Round
        SMLATB  temp2, colOp00, Scale, Round                ;// Temp2 = T(c0w0) * Scale + Round
        SMLATB  temp4, colOp02, Scale, Round                ;// Temp4 = T(c1w0) * Scale + Round
        
        ASR     temp1, temp1, #2                            ;// Temp1 = Temp1 >> 2
        ASR     temp3, temp3, #2                            ;// Temp3 = Temp3 >> 2
        PKHBT   out00,  temp1, temp2, LSL #14               ;// c0w0  = | Temp2 | Temp1 |
        PKHBT   out02,  temp3, temp4, LSL #14               ;// c1w0  = | Temp2 | Temp1 |
        
        
        ;// Row 2
        SMLABB  temp1, colOp10, Scale, Round                ;// Temp1 = B(c0w0) * Scale + Round
        SMLABB  temp3, colOp12, Scale, Round                ;// Temp3 = B(c1w0) * Scale + Round
        SMLATB  temp2, colOp10, Scale, Round                ;// Temp2 = T(c0w0) * Scale + Round
        SMLATB  temp4, colOp12, Scale, Round                ;// Temp4 = T(c1w0) * Scale + Round
        
        ASR     temp1, temp1, #2                            ;// Temp1 = Temp1 >> 2
        ASR     temp3, temp3, #2                            ;// Temp3 = Temp3 >> 2
        PKHBT   out10,  temp1, temp2, LSL #14               ;// c0w0  = | Temp2 | Temp1 |
        PKHBT   out12,  temp3, temp4, LSL #14               ;// c1w0  = | Temp2 | Temp1 |
        
        ;// Row 3
        SMLABB  temp1, colOp20, Scale, Round                ;// Temp1 = B(c0w0) * Scale + Round
        SMLABB  temp3, colOp22, Scale, Round                ;// Temp3 = B(c1w0) * Scale + Round
        SMLATB  temp2, colOp20, Scale, Round                ;// Temp2 = T(c0w0) * Scale + Round
        SMLATB  temp4, colOp22, Scale, Round                ;// Temp4 = T(c1w0) * Scale + Round
        
        ASR     temp1, temp1, #2                            ;// Temp1 = Temp1 >> 2 
        ASR     temp3, temp3, #2                            ;// Temp3 = Temp3 >> 2
        PKHBT   out20,  temp1, temp2, LSL #14               ;// c0w0  = | Temp2 | Temp1 |
        PKHBT   out22,  temp3, temp4, LSL #14               ;// c1w0  = | Temp2 | Temp1 |
        
        ;// Row 4
        SMLABB  temp1, colOp30, Scale, Round                ;// Temp1 = B(c0w0) * Scale + Round
        SMLABB  temp3, colOp32, Scale, Round                ;// Temp3 = B(c1w0) * Scale + Round
        SMLATB  temp2, colOp30, Scale, Round                ;// Temp2 = T(c0w0) * Scale + Round
        SMLATB  temp4, colOp32, Scale, Round                ;// Temp4 = T(c1w0) * Scale + Round
        
        M_LDR   pData,pDataOnStack                          ;// Restore pData pointer from stack
        ASR     temp1, temp1, #2                            ;// Temp1 = Temp1 >> 2
        ASR     temp3, temp3, #2                            ;// Temp3 = Temp3 >> 2
        PKHBT   out30,  temp1, temp2, LSL #14               ;// c0w0  = | Temp2 | Temp1 |
        PKHBT   out32,  temp3, temp4, LSL #14               ;// c1w0  = | Temp2 | Temp1 |
        
        
        
        ;***************************
        ;// Store all the 4x4 pixels
        ;***************************

store_coeff
        
        STMIA   pData,{out00,out02,out10,out12,out20,out22,out30,out32}
        
                               
       
        ;// Set return value
        
       
        ;// Write function tail
        M_END        
        
    ENDIF                                                           ;//ARM1136JS        
    

;// Static Function: armVCM4P10_InvTransformDequantLumaDC4x4

;// Guarding implementation by the processor name
    
        


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
    
    IF ARM1136JS
       
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