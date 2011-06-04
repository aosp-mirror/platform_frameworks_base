;//
;// 
;// File Name:  omxVCCOMM_ExpandFrame_I_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//
;// Description:
;// This function will Expand Frame boundary pixels into Plane
;// 
;// 

;// Include standard headers

        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
        M_VARIANTS CortexA8

;// Import symbols required from other files
;// (For example tables)
    
  
;// Set debugging level        
DEBUG_ON    SETL {FALSE}


    
    IF CortexA8
    
        M_START omxVCCOMM_ExpandFrame_I,r11

;//Input registers

pSrcDstPlane    RN  0
iFrameWidth     RN  1
iFrameHeight    RN  2    
iExpandPels     RN  3
iPlaneStep      RN  4
pTop            RN  5
pBot            RN  6
pDstTop         RN  7
pDstBot         RN  8
pLeft           RN  5
pRight          RN  6
pDstLeft        RN  9
pDstRight       RN  10
Offset          RN  11
Temp            RN  14
Counter         RN  12
Tmp             RN  7
;//Output registers

result          RN  0
;// Neon registers
qData0          QN  0.U8
qData1          QN  1.U8
dData0          DN  0.U8
dData1          DN  1.U8
dData2          DN  2.U8
dData3          DN  3.U8

        ;// Define stack arguments
        M_ARG       pPlaneStep, 4
        
        ;// Load argument from the stack
        M_LDR       iPlaneStep, pPlaneStep
        
        SUB         pTop, pSrcDstPlane, #0              ;// Top row pointer of the frame
        MUL         Offset, iExpandPels, iPlaneStep     ;// E*Step        
        SUB         Temp, iFrameHeight, #1              ;// H-1
        MUL         Temp, iPlaneStep, Temp              ;// (H-1)*Step
        ADD         pBot, Temp, pSrcDstPlane            ;// BPtr = TPtr + (H-1)*Step
        MOV         Temp, iFrameWidth                   ;// Outer loop counter
        
        ;// Check if pSrcDstPlane and iPlaneStep are 16 byte aligned
        TST         pSrcDstPlane, #0xf
        TSTEQ       iPlaneStep, #0xf        
        BNE         Hor8Loop00
        
        ;//
        ;// Copy top and bottom region of the plane as follows
        ;// top region = top row elements from the frame
        ;// bottom region = last row elements from the frame
        ;//

        ;// Case for 16 byte alignment
Hor16Loop00
        SUB         pDstTop, pTop, Offset
        VLD1        qData0, [pTop @128]!
        MOV         Counter, iExpandPels                ;// Inner loop counter
        ADD         pDstBot, pBot, iPlaneStep
        VLD1        qData1, [pBot @128]!
Ver16Loop0
        VST1        qData0, [pDstTop @128], iPlaneStep
        VST1        qData0, [pDstTop @128], iPlaneStep
        VST1        qData0, [pDstTop @128], iPlaneStep
        VST1        qData0, [pDstTop @128], iPlaneStep
        VST1        qData0, [pDstTop @128], iPlaneStep
        VST1        qData0, [pDstTop @128], iPlaneStep
        VST1        qData0, [pDstTop @128], iPlaneStep
        VST1        qData0, [pDstTop @128], iPlaneStep
        SUBS        Counter, Counter, #8
        VST1        qData1, [pDstBot @128], iPlaneStep
        VST1        qData1, [pDstBot @128], iPlaneStep
        VST1        qData1, [pDstBot @128], iPlaneStep
        VST1        qData1, [pDstBot @128], iPlaneStep
        VST1        qData1, [pDstBot @128], iPlaneStep
        VST1        qData1, [pDstBot @128], iPlaneStep
        VST1        qData1, [pDstBot @128], iPlaneStep
        VST1        qData1, [pDstBot @128], iPlaneStep        
        BGT         Ver16Loop0

        SUBS        Temp, Temp, #16
        BGT         Hor16Loop00
        B           EndAlignedLoop
        
        ;// Case for 8 byte alignment
Hor8Loop00
        SUB         pDstTop, pTop, Offset
        VLD1        qData0, [pTop @64]!
        MOV         Counter, iExpandPels                ;// Inner loop counter
        ADD         pDstBot, pBot, iPlaneStep
        VLD1        qData1, [pBot @64]!
Ver8Loop0
        VST1        qData0, [pDstTop @64], iPlaneStep
        VST1        qData0, [pDstTop @64], iPlaneStep
        VST1        qData0, [pDstTop @64], iPlaneStep
        VST1        qData0, [pDstTop @64], iPlaneStep
        VST1        qData0, [pDstTop @64], iPlaneStep
        VST1        qData0, [pDstTop @64], iPlaneStep
        VST1        qData0, [pDstTop @64], iPlaneStep
        VST1        qData0, [pDstTop @64], iPlaneStep
        SUBS        Counter, Counter, #8
        VST1        qData1, [pDstBot @64], iPlaneStep
        VST1        qData1, [pDstBot @64], iPlaneStep
        VST1        qData1, [pDstBot @64], iPlaneStep
        VST1        qData1, [pDstBot @64], iPlaneStep
        VST1        qData1, [pDstBot @64], iPlaneStep
        VST1        qData1, [pDstBot @64], iPlaneStep
        VST1        qData1, [pDstBot @64], iPlaneStep
        VST1        qData1, [pDstBot @64], iPlaneStep        
        BGT         Ver8Loop0

        SUBS        Temp, Temp, #16
        BGT         Hor8Loop00

EndAlignedLoop
        ADD         Temp, pSrcDstPlane, iFrameWidth
        SUB         pDstRight, Temp, Offset
        SUB         pRight, Temp, #1
        SUB         pDstLeft, pSrcDstPlane, Offset    
        SUB         pDstLeft, pDstLeft, iExpandPels    
        ADD         pLeft, pSrcDstPlane, #0
        
        VLD1        {dData0 []}, [pLeft], iPlaneStep        ;// Top-Left corner pixel from frame duplicated in dData0
        SUB         Offset, iPlaneStep, iExpandPels
        VLD1        {dData1 []}, [pRight], iPlaneStep       ;// Top-Right corner pixel from frame duplicated in dData1
        MOV         Temp, iExpandPels

        ;//
        ;// Copy top-left and top-right region of the plane as follows
        ;// top-left region = top-left corner pixel from the frame
        ;// top-right region = top-right corner pixel from the frame
        ;//
HorLoop11
        MOV         Counter, iExpandPels
VerLoop1
        VST1        dData0, [pDstLeft], #8
        SUBS        Counter, Counter, #8
        VST1        dData1, [pDstRight], #8        
        BGT         VerLoop1

        SUBS        Temp, Temp, #1
        ADD         pDstLeft, pDstLeft, Offset
        ADD         pDstRight, pDstRight, Offset
        BPL         HorLoop11

        SUB         iFrameHeight, iFrameHeight, #1
        ;//
        ;// Copy left and right region of the plane as follows
        ;// Left region = copy the row with left start pixel from the frame
        ;// Right region = copy the row with right end pixel from the frame
        ;//
HorLoop22
        VLD1        {dData0 []}, [pLeft], iPlaneStep
        MOV         Counter, iExpandPels
        VLD1        {dData1 []}, [pRight], iPlaneStep
VerLoop2
        VST1        dData0, [pDstLeft], #8
        SUBS        Counter, Counter, #8
        VST1        dData1, [pDstRight], #8        
        BGT         VerLoop2

        SUBS        iFrameHeight, iFrameHeight, #1
        ADD         pDstLeft, pDstLeft, Offset
        ADD         pDstRight, pDstRight, Offset
        BGT         HorLoop22
                
        MOV         Temp, iExpandPels
        ;//
        ;// Copy bottom-left and bottom-right region of the plane as follows
        ;// bottom-left region = bottom-left corner pixel from the frame
        ;// bottom-right region = bottom-right corner pixel from the frame
        ;//
HorLoop33
        MOV         Counter, iExpandPels
VerLoop3
        VST1        dData0, [pDstLeft], #8
        SUBS        Counter, Counter, #8
        VST1        dData1, [pDstRight], #8        
        BGT         VerLoop3

        SUBS        Temp, Temp, #1
        ADD         pDstLeft, pDstLeft, Offset
        ADD         pDstRight, pDstRight, Offset
        BGT         HorLoop33
End
        MOV         r0, #OMX_Sts_NoErr
        
        M_END    
    
    ENDIF



        
;// Guarding implementation by the processor name
    
 
            
    END