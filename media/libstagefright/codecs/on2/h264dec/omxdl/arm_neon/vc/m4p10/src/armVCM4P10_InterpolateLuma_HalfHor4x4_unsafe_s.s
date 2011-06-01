;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h

        M_VARIANTS CortexA8
        
        EXPORT armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe

DEBUG_ON    SETL {FALSE}

    IF CortexA8
        
        M_START armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe, r11

;// Declare input registers
pSrc            RN 0
srcStep         RN 1
pDst            RN 2
dstStep         RN 3

;// Declare Neon registers
dCoeff5         DN 30.S16
dCoeff20        DN 31.S16

qSrcA01         QN 11.U8
qSrcB01         QN 12.U8
qSrcC01         QN 13.U8
qSrcD01         QN 14.U8

dSrcA0          DN 22.U8
dSrcA1          DN 23.U8
dSrcB0          DN 24.U8
dSrcB1          DN 25.U8
dSrcC0          DN 26.U8
dSrcC1          DN 27.U8
dSrcD0          DN 28.U8
dSrcD1          DN 29.U8

dSrcb           DN 12.U8
dSrce           DN 13.U8
dSrcf           DN 10.U8

dSrc0c          DN 14.U8
dSrc1c          DN 16.U8
dSrc2c          DN 18.U8
dSrc3c          DN 20.U8
                   
dSrc0d          DN 15.U8
dSrc1d          DN 17.U8
dSrc2d          DN 19.U8
dSrc3d          DN 21.U8

qTemp01         QN 4.S16
qTemp23         QN 6.S16
dTemp0          DN 8.S16
dTemp2          DN 12.S16

qRes01          QN 11.S16
qRes23          QN 12.S16
qRes45          QN 13.S16
qRes67          QN 14.S16

dRes0           DN 22.S16
dRes2           DN 24.S16
dRes4           DN 26.S16
dRes6           DN 28.S16

dAcc0           DN 22.U8
dAcc2           DN 24.U8
dAcc4           DN 26.U8
dAcc6           DN 28.U8

dResult0        DN 22.U32
dResult2        DN 24.U32
dResult4        DN 26.U32
dResult6        DN 28.U32

        VLD1        qSrcA01, [pSrc], srcStep    ;// Load A register [a0 a1 a2 a3 ..]
        ;// One cycle stall
        VEXT        dSrcf, dSrcA0, dSrcA1, #5   ;// [f0 f1 f2 f3 ..]
        VEXT        dSrcb, dSrcA0, dSrcA1, #1   ;// [b0 b1 b2 b3 ..]
;        VLD1        qSrcB01, [pSrc], srcStep    ;// Load B register [a0 a1 a2 a3 ..]
        VEXT        dSrc0c, dSrcA0, dSrcA1, #2
        VEXT        dSrc0d, dSrcA0, dSrcA1, #3
        VEXT        dSrce, dSrcA0, dSrcA1, #4
        VADDL       qRes01, dSrcA0, dSrcf       ;// Acc=a+f
        VADDL       qTemp01, dSrc0c, dSrc0d     ;// c+d                
        VADDL       qTemp23, dSrcb, dSrce       ;// b+e
        
        VLD1        qSrcB01, [pSrc], srcStep    ;// Load B register [a0 a1 a2 a3 ..]
;        VLD1        qSrcC01, [pSrc], srcStep    ;// Load C register [a0 a1 a2 a3 ..]           
        VMLA        dRes0, dTemp0, dCoeff20     ;// Acc += 20*(c+d)
;        VMLS        dRes0, dTemp2, dCoeff5      ;// Acc -= 5*(b+e)
        VMUL        dTemp0, dTemp2, dCoeff5 ;// TeRi
        
        VEXT        dSrcf, dSrcB0, dSrcB1, #5   ;// [f0 f1 f2 f3 ..]
        VEXT        dSrcb, dSrcB0, dSrcB1, #1   ;// [b0 b1 b2 b3 ..]
        VEXT        dSrc1c, dSrcB0, dSrcB1, #2
        VEXT        dSrc1d, dSrcB0, dSrcB1, #3
        VEXT        dSrce, dSrcB0, dSrcB1, #4
        VADDL       qRes23, dSrcB0, dSrcf       ;// Acc=a+f

        VSUB        dRes0, dRes0, dTemp0    ;// TeRi

        VADDL       qTemp01, dSrc1c, dSrc1d     ;// c+d                
        VADDL       qTemp23, dSrcb, dSrce       ;// b+e
        
        VLD1        qSrcC01, [pSrc], srcStep    ;// Load C register [a0 a1 a2 a3 ..]           
;        VLD1        qSrcD01, [pSrc], srcStep    ;// Load D register [a0 a1 a2 a3 ..]  
        
        VMLA        dRes2, dTemp0, dCoeff20     ;// Acc += 20*(c+d)
;        VMLS        dRes2, dTemp2, dCoeff5      ;// Acc -= 5*(b+e)
        VMUL        dTemp0, dTemp2, dCoeff5 ;// TeRi

        VEXT        dSrcf, dSrcC0, dSrcC1, #5   ;// [f0 f1 f2 f3 ..]
        VEXT        dSrcb, dSrcC0, dSrcC1, #1   ;// [b0 b1 b2 b3 ..]
        VEXT        dSrc2c, dSrcC0, dSrcC1, #2
        VEXT        dSrc2d, dSrcC0, dSrcC1, #3
        VEXT        dSrce, dSrcC0, dSrcC1, #4
        VADDL       qRes45, dSrcC0, dSrcf       ;// Acc=a+f
        
        VSUB        dRes2, dRes2, dTemp0  ;// TeRi
        
        VADDL       qTemp01, dSrc2c, dSrc2d     ;// c+d                
        VADDL       qTemp23, dSrcb, dSrce       ;// b+e

        VLD1        qSrcD01, [pSrc], srcStep    ;// Load D register [a0 a1 a2 a3 ..]  

        VMLA        dRes4, dTemp0, dCoeff20     ;// Acc += 20*(c+d)
;        VMLS        dRes4, dTemp2, dCoeff5      ;// Acc -= 5*(b+e)
        VMUL        dTemp0, dTemp2, dCoeff5      ;// Acc -= 5*(b+e) TeRi
        

        VEXT        dSrcf, dSrcD0, dSrcD1, #5   ;// [f0 f1 f2 f3 ..]
        VEXT        dSrcb, dSrcD0, dSrcD1, #1   ;// [b0 b1 b2 b3 ..]
        VEXT        dSrc3c, dSrcD0, dSrcD1, #2
        VEXT        dSrc3d, dSrcD0, dSrcD1, #3
        VEXT        dSrce, dSrcD0, dSrcD1, #4
        VADDL       qRes67, dSrcD0, dSrcf       ;// Acc=a+f

        VSUB        dRes4, dRes4, dTemp0 ;// TeRi

        VADDL       qTemp01, dSrc3c, dSrc3d     ;// c+d                
        VADDL       qTemp23, dSrcb, dSrce       ;// b+e
        VMLA        dRes6, dTemp0, dCoeff20     ;// Acc += 20*(c+d)
        VMLS        dRes6, dTemp2, dCoeff5      ;// Acc -= 5*(b+e)

        VQRSHRUN    dAcc0, qRes01, #5           ;// Acc = Sat ((Acc + 16) / 32)
        VQRSHRUN    dAcc2, qRes23, #5           ;// Acc = Sat ((Acc + 16) / 32)
        VQRSHRUN    dAcc4, qRes45, #5           ;// Acc = Sat ((Acc + 16) / 32)
        VQRSHRUN    dAcc6, qRes67, #5           ;// Acc = Sat ((Acc + 16) / 32)
        
        M_END
    
    ENDIF


    END
    


























































