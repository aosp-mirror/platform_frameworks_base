;//
;// 
;// File Name:  armVCM4P10_TransformResidual4x4_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
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
        
        M_VARIANTS CortexA8
        
;// Import symbols required from other files
;// (For example tables)
    
        
        
        
;// Set debugging level        
;//DEBUG_ON    SETL {TRUE}



;// Guarding implementation by the processor name
    
    
    





;// Guarding implementation by the processor name
    
    IF  CortexA8

;// ARM Registers
    
;//Input Registers
pDst                RN  0
pSrc                RN  1


;// Neon Registers
      
;// Packed Input pixels
dIn0                DN  D0.S16       
dIn1                DN  D1.S16       
dIn2                DN  D2.S16       
dIn3                DN  D3.S16

;// Intermediate calculations       
dZero               DN  D4.S16
de0                 DN  D5.S16
de1                 DN  D6.S16
de2                 DN  D7.S16
de3                 DN  D8.S16
dIn1RS              DN  D7.S16
dIn3RS              DN  D8.S16
df0                 DN  D0.S16
df1                 DN  D1.S16
df2                 DN  D2.S16
df3                 DN  D3.S16
qf01                QN  Q0.32
qf23                QN  Q1.32
dg0                 DN  D5.S16
dg1                 DN  D6.S16
dg2                 DN  D7.S16
dg3                 DN  D8.S16
df1RS               DN  D7.S16
df3RS               DN  D8.S16

;// Output pixels
dh0                 DN  D0.S16
dh1                 DN  D1.S16
dh2                 DN  D2.S16
dh3                 DN  D3.S16

       
    ;// Allocate stack memory required by the function
        

    ;// Write function header
        M_START armVCM4P10_TransformResidual4x4, ,d8
        
        ;******************************************************************
        ;// The strategy used in implementing the transform is as follows:*
        ;// Load the 4x4 block into 8 registers                           *  
        ;// Transpose the 4x4 matrix                                      *  
        ;// Perform the row operations (on columns) using SIMD            *  
        ;// Transpose the 4x4 result matrix                               *  
        ;// Perform the coloumn operations                                *
        ;// Store the 4x4 block at one go                                 *  
        ;******************************************************************

        ;// Load all the 4x4 pixels in transposed form
        
        VLD4    {dIn0,dIn1,dIn2,dIn3},[pSrc]
        
        VMOV    dZero,#0                                    ;// Used to right shift by 1 
        
        
        ;**************************************** 
        ;// Row Operations (Performed on columns)
        ;**************************************** 
        
        
        VADD        de0,dIn0,dIn2                       ;//  e0 = d0 + d2 
        VSUB        de1,dIn0,dIn2                        ;//  e1 = d0 - d2 
        VHADD       dIn1RS,dIn1,dZero                   ;// (f1>>1) constZero is a register holding 0
        VHADD       dIn3RS,dIn3,dZero
        VSUB        de2,dIn1RS,dIn3                     ;//  e2 = (d1>>1) - d3 
        VADD        de3,dIn1,dIn3RS                        ;//  e3 = d1 + (d3>>1) 
        VADD        df0,de0,de3                         ;//  f0 = e0 + e3
        VADD        df1,de1,de2                            ;//  f1 = e1 + e2
        VSUB        df2,de1,de2                            ;//  f2 = e1 - e2
        VSUB        df3,de0,de3                            ;//  f3 = e0 - e3
        
        
        
        ;*****************************************************************
        ;// Transpose the resultant matrix
        ;*****************************************************************
        
        VTRN    df0,df1
        VTRN    df2,df3
        VTRN    qf01,qf23 
        
        
        ;******************************* 
        ;// Coloumn Operations 
        ;******************************* 
        
        
        VADD        dg0,df0,df2                         ;//  e0 = d0 + d2 
        VSUB        dg1,df0,df2                            ;//  e1 = d0 - d2 
        VHADD       df1RS,df1,dZero                     ;// (f1>>1) constZero is a register holding 0
        VHADD       df3RS,df3,dZero
        VSUB        dg2,df1RS,df3                       ;//  e2 = (d1>>1) - d3 
        VADD        dg3,df1,df3RS                        ;//  e3 = d1 + (d3>>1) 
        VADD        dh0,dg0,dg3                         ;//  f0 = e0 + e3
        VADD        dh1,dg1,dg2                            ;//  f1 = e1 + e2
        VSUB        dh2,dg1,dg2                            ;//  f2 = e1 - e2
        VSUB        dh3,dg0,dg3                            ;//  f3 = e0 - e3
        
             
        ;************************************************
        ;// Calculate final value (colOp[i][j] + 32)>>6
        ;************************************************
        
        VRSHR       dh0,#6
        VRSHR       dh1,#6
        VRSHR       dh2,#6
        VRSHR       dh3,#6
        
                
        ;***************************
        ;// Store all the 4x4 pixels
        ;***************************
        
        VST1   {dh0,dh1,dh2,dh3},[pDst]
            
        
        ;// Set return value
        
End                

        
        ;// Write function tail
        M_END
        
    ENDIF                                                           ;//CortexA8            
            
    END