;//
;// 
;// File Name:  armVCM4P10_TransformResidual4x4_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//
;// Description:
;// Transform Residual 4x4 Coefficients
;// 
;// 

        
;// Include standard headers

        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
        M_VARIANTS ARM1136JS
        
;// Import symbols required from other files
;// (For example tables)
    
        
        
        
;// Set debugging level        
;//DEBUG_ON    SETL {TRUE}



;// Guarding implementation by the processor name
    
    IF  ARM1136JS 
    
;//Input Registers
pDst                RN  0
pSrc                RN  1

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
e0                  RN  4                   
e1                  RN  6
e2                  RN  8
e3                  RN  9
constZero           RN  1

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
g0                  RN  10
g1                  RN  11
g2                  RN  12
g3                  RN  14   

;// Coloumn operated pixels
colOp00             RN  2                   
colOp02             RN  3                   
colOp10             RN  4                   
colOp12             RN  5                   
colOp20             RN  6                   
colOp22             RN  7                   
colOp30             RN  8                   
colOp32             RN  9  


temp1               RN  10                  ;// Temporary scratch varaibles
const1              RN  11      
const2              RN  12
mask                RN  14

;// Output pixels
out00               RN  2                   
out02               RN  3                   
out10               RN  4                   
out12               RN  5                   
out20               RN  6                   
out22               RN  7                   
out30               RN  8                   
out32               RN  9  
      
       
       
    ;// Allocate stack memory required by the function
        

    ;// Write function header
        M_START armVCM4P10_TransformResidual4x4,r11
        
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
        
        LDMIA   pSrc,{in00,in02,in10,in12,in20,in22,in30,in32}
        
        MOV       constZero,#0                                     ;// Used to right shift by 1 
        ;LDR       constZero,=0x00000000  
        
        ;*****************************************************************
        ;//
        ;// Transpose the matrix inorder to perform row ops as coloumn ops
        ;// Input:   in[][] = original matrix
        ;// Output:  trRow[][]= transposed matrix
        ;// Step1: Obtain the LL part of the transposed matrix
        ;// Step2: Obtain the HL part
        ;// step3: Obtain the LH part
        ;// Step4: Obtain the HH part
        ;//
        ;*****************************************************************
        
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
        
        
        SADD16      e0, trRow00,trRow20                   ;//  e0 = d0 + d2 
        SSUB16    e1, trRow00,trRow20                   ;//  e1 = d0 - d2  
        SHADD16   e2, trRow10,constZero                 ;// (f1>>1) constZero is a register holding 0
        SHADD16   e3, trRow30,constZero                 ;//  avoid pipeline stalls for e2 and e3
        SSUB16    e2, e2, trRow30                       ;//  e2 = (d1>>1) - d3  
        SADD16    e3, e3, trRow10                       ;//  e3 = d1 + (d3>>1)  
        SADD16    rowOp00, e0, e3                       ;//  f0 = e0 + e3  
        SADD16    rowOp10, e1, e2                       ;//  f1 = e1 + e2  
        SSUB16    rowOp20, e1, e2                       ;//  f2 = e1 - e2  
        SSUB16    rowOp30, e0, e3                       ;//  f3 = e0 - e3
        
        ;// SIMD operations on next two columns(next two rows of the original matrix)
        
        SADD16      e0, trRow02,trRow22
        SSUB16    e1, trRow02,trRow22
        SHADD16   e2, trRow12,constZero                 ;//(f1>>1) constZero is a register holding 0
        SHADD16   e3, trRow32,constZero
        SSUB16    e2, e2, trRow32
        SADD16    e3, e3, trRow12
        SADD16    rowOp02, e0, e3
        SADD16    rowOp12, e1, e2
        SSUB16    rowOp22, e1, e2
        SSUB16    rowOp32, e0, e3
        
        
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
        
        
        ;// SIMD operations on first two columns
        
          
        SADD16      g0, trCol00,trCol20
        SSUB16    g1, trCol00,trCol20
        SHADD16   g2, trCol10,constZero                     ;// (f1>>1) constZero is a register holding 0
        SHADD16   g3, trCol30,constZero
        SSUB16    g2, g2, trCol30
        SADD16    g3, g3, trCol10
        SADD16    colOp00, g0, g3
        SADD16    colOp10, g1, g2
        SSUB16    colOp20, g1, g2
        SSUB16    colOp30, g0, g3
        
        ;// SIMD operations on next two columns
        
        SADD16      g0, trCol02,trCol22
        SSUB16    g1, trCol02,trCol22
        SHADD16   g2, trCol12,constZero                     ;// (f1>>1) constZero is a register holding 0
        SHADD16   g3, trCol32,constZero
        SSUB16    g2, g2, trCol32
        SADD16    g3, g3, trCol12
        SADD16    colOp02, g0, g3
        SADD16    colOp12, g1, g2
        SSUB16    colOp22, g1, g2
        SSUB16    colOp32, g0, g3
        
        
             
                  
             
        ;************************************************
        ;// Calculate final value (colOp[i][j] + 32)>>6
        ;************************************************
        
        ;// const1: Serves dual purpose 
        ;// (1) Add #32 to both the lower and higher 16bits of the SIMD result 
        ;// (2) Convert the lower 16 bit value to an unsigned number (Add 32768)
        
        LDR     const1, =0x00208020             
        
        LDR     mask, =0xffff03ff                       ;// Used to mask the down shifted 6 bits  
        
        ;// const2(#512): used to convert the lower 16bit number back to signed value 
      
        MOV     const2,#0x200                           ;// const2 = 2^9
        
        ;// First Row 
        
        SADD16    colOp00, colOp00, const1
        SADD16    colOp02, colOp02, const1
        AND     colOp00, mask, colOp00, ASR #6
        AND     colOp02, mask, colOp02, ASR #6
        SSUB16  out00,colOp00,const2
        SSUB16  out02,colOp02,const2    
        

        ;// Second Row
        
        SADD16    colOp10, colOp10, const1
        SADD16    colOp12, colOp12, const1
        AND     colOp10, mask, colOp10, ASR #6
        AND     colOp12, mask, colOp12, ASR #6
        SSUB16  out10,colOp10,const2
        SSUB16  out12,colOp12,const2    
        
        
        ;// Third Row
        
        SADD16    colOp20, colOp20, const1
        SADD16    colOp22, colOp22, const1
        AND     colOp20, mask, colOp20, ASR #6
        AND     colOp22, mask, colOp22, ASR #6
        SSUB16  out20,colOp20,const2
        SSUB16  out22,colOp22,const2
        
        
        ;// Fourth Row   
        
        SADD16    colOp30, colOp30, const1
        SADD16    colOp32, colOp32, const1
        AND     colOp30, mask, colOp30, ASR #6
        AND     colOp32, mask, colOp32, ASR #6
        SSUB16  out30,colOp30,const2
        SSUB16  out32,colOp32,const2
        
        
        
                
        ;***************************
        ;// Store all the 4x4 pixels
        ;***************************
        
        STMIA   pDst,{out00,out02,out10,out12,out20,out22,out30,out32}
        
                               
       
        ;// Set return value
        
End                

        
        ;// Write function tail
        M_END
        
    ENDIF                                                           ;//ARM1136JS    
    
    





;// Guarding implementation by the processor name
    
            
    END