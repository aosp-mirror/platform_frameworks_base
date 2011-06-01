;//
;// 
;// File Name:  armVCM4P10_Interpolate_Chroma_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//


        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
        M_VARIANTS ARM1136JS
        
    IF ARM1136JS

;// input registers

pSrc                 RN 0
iSrcStep             RN 1
pDst                 RN 2
iDstStep             RN 3
iWidth               RN 4
iHeight              RN 5
dx                   RN 6
dy                   RN 7


;// local variable registers
temp                 RN 11
r0x20                RN 12
tmp0x20              RN 14
return               RN 0
dxPlusdy             RN 10
EightMinusdx         RN 8 
EightMinusdy         RN 9
dxEightMinusdx       RN 8
BACoeff              RN 6
DCCoeff              RN 7
                     
iDstStepx2MinusWidth RN 8
iSrcStepx2MinusWidth RN 9
iSrcStep1            RN 10

pSrc1                RN 1
pSrc2                RN 8
pDst1                RN 8
pDst2                RN 12
                     
pix00                RN 8
pix01                RN 9
pix10                RN 10
pix11                RN 11

Out0100              RN 8  
Out1110              RN 10 

x00                  RN 8
x01                  RN 10
x02                  RN 12
x10                  RN 9
x11                  RN 11
x12                  RN 14
x20                  RN 10
x21                  RN 12
x22                  RN 14
                     
x01x00               RN 8  
x02x01               RN 10 
x11x10               RN 9  
x12x11               RN 11 
x21x20               RN 10 
x22x21               RN 12 
                     
OutRow00             RN 12
OutRow01             RN 14
OutRow10             RN 10
OutRow11             RN 12
                     
OutRow0100           RN 12
OutRow1110           RN 12
                     
;//-----------------------------------------------------------------------------------------------
;// armVCM4P10_Interpolate_Chroma_asm starts
;//-----------------------------------------------------------------------------------------------
        
        ;// Write function header
        M_START armVCM4P10_Interpolate_Chroma, r11
        
        ;// Define stack arguments
        M_ARG   Width,      4
        M_ARG   Height,     4
        M_ARG   Dx,         4
        M_ARG   Dy,         4
        
        ;// Load argument from the stack
        ;// M_STALL ARM1136JS=4
        
        M_LDR   iWidth,  Width  
        M_LDR   iHeight, Height  
        M_LDR   dx,      Dx 
        M_LDR   dy,      Dy
        
        ;// EightMinusdx = 8 - dx
        ;// EightMinusdy = 8 - dy
        
        ;// ACoeff = EightMinusdx * EightMinusdy
        ;// BCoeff = dx * EightMinusdy
        ;// CCoeff = EightMinusdx * dy
        ;// DCoeff = dx * dy
        
        ADD     pSrc1, pSrc, iSrcStep
        SUB     temp, iWidth, #1
        RSB     EightMinusdx, dx, #8 
        RSB     EightMinusdy, dy, #8
        CMN     dx,dy
        ADD     dxEightMinusdx, EightMinusdx, dx, LSL #16
        ORR     iWidth, iWidth, temp, LSL #16
        
        ;// Packed Coeffs.
        
        MUL     BACoeff, dxEightMinusdx, EightMinusdy
        MUL     DCCoeff, dxEightMinusdx, dy        
        
        
        ;// Checking either of dx and dy being non-zero
        
        BEQ     MVIsZero
        
;// Pixel layout:
;//
;//   x00 x01 x02
;//   x10 x11 x12
;//   x20 x21 x22

;// If fractionl mv is not (0, 0)
        
OuterLoopMVIsNotZero

InnerLoopMVIsNotZero
            
                LDRB    x00, [pSrc,  #+0]                   
                LDRB    x10, [pSrc1, #+0]                   
                LDRB    x01, [pSrc,  #+1]                  
                LDRB    x11, [pSrc1, #+1]                  
                LDRB    x02, [pSrc,  #+2]!                   
                LDRB    x12, [pSrc1, #+2]!                   
                
                ORR     x01x00, x00, x01, LSL #16        
                ;// M_STALL ARM1136JS=1
                ORR     x02x01, x01, x02, LSL #16        
                MOV     r0x20,  #32
                ORR     x11x10, x10, x11, LSL #16    
                ORR     x12x11, x11, x12, LSL #16        
                
                SMLAD   x01x00, x01x00, BACoeff, r0x20
                SMLAD   x02x01, x02x01, BACoeff, r0x20                
                
                ;// iWidth packed with MSB (top 16 bits) 
                ;// as inner loop counter value i.e 
                ;// (iWidth -1) and LSB (lower 16 bits)
                ;// as original width
                
                SUBS    iWidth, iWidth, #1<<17
                
                SMLAD   OutRow00, x11x10, DCCoeff, x01x00            
                SMLAD   OutRow01, x12x11, DCCoeff, x02x01            
                
                RSB     pSrc2, pSrc, pSrc1, LSL #1
                
                MOV     OutRow00, OutRow00, LSR #6
                MOV     OutRow01, OutRow01, LSR #6
                
                LDRB    x20,[pSrc2, #-2]
                
                ORR     OutRow0100, OutRow00, OutRow01, LSL #8
                STRH    OutRow0100, [pDst], #2
                
                LDRB    x21,[pSrc2, #-1]
                LDRB    x22,[pSrc2, #+0]
                
                ADD     pDst1, pDst, iDstStep
                
                ;// M_STALL ARM1136JS=1
                                
                ORR     x21x20, x20, x21, LSL #16
                ORR     x22x21, x21, x22, LSL #16     
                
                MOV     tmp0x20, #32
                
                ;// Reusing the packed data x11x10 and x12x11
                
                SMLAD   x11x10,  x11x10,  BACoeff, tmp0x20
                SMLAD   x12x11,  x12x11,  BACoeff, tmp0x20
                SMLAD   OutRow10, x21x20, DCCoeff, x11x10            
                SMLAD   OutRow11, x22x21, DCCoeff, x12x11
                
                MOV     OutRow10, OutRow10, LSR #6
                MOV     OutRow11, OutRow11, LSR #6
                
                ;// M_STALL ARM1136JS=1
               
                ORR     OutRow1110, OutRow10, OutRow11, LSL #8
                
                STRH    OutRow1110, [pDst1, #-2]
                
                BGT     InnerLoopMVIsNotZero
                
                SUBS    iHeight, iHeight, #2
                ADD     iWidth, iWidth, #1<<16
                RSB     iDstStepx2MinusWidth, iWidth, iDstStep, LSL #1
                SUB     iSrcStep1, pSrc1, pSrc
                SUB     temp, iWidth, #1
                RSB     iSrcStepx2MinusWidth, iWidth, iSrcStep1, LSL #1
                ADD     pDst, pDst, iDstStepx2MinusWidth
                ADD     pSrc1, pSrc1, iSrcStepx2MinusWidth
                ADD     pSrc, pSrc, iSrcStepx2MinusWidth
                ORR     iWidth, iWidth, temp, LSL #16
                BGT     OuterLoopMVIsNotZero
                MOV     return,  #OMX_Sts_NoErr
                M_EXIT

;// If fractionl mv is (0, 0)

MVIsZero
                ;// M_STALL ARM1136JS=4
OuterLoopMVIsZero

InnerLoopMVIsZero
                                      
                LDRB    pix00, [pSrc],  #+1
                LDRB    pix01, [pSrc],  #+1
                LDRB    pix10, [pSrc1], #+1
                LDRB    pix11, [pSrc1], #+1
                
                ADD     pDst2,  pDst, iDstStep
                SUBS    iWidth, iWidth, #1<<17                
                
                ORR     Out0100, pix00, pix01, LSL #8 
                ORR     Out1110, pix10, pix11, LSL #8
                
                STRH    Out0100, [pDst],  #2
                STRH    Out1110, [pDst2], #2
                
                BGT     InnerLoopMVIsZero
                
                SUBS    iHeight, iHeight, #2
                ADD     iWidth, iWidth, #1<<16
                RSB     iDstStepx2MinusWidth, iWidth, iDstStep, LSL #1
                SUB     iSrcStep1, pSrc1, pSrc
                SUB     temp, iWidth, #1
                RSB     iSrcStepx2MinusWidth, iWidth, iSrcStep1, LSL #1
                ADD     pDst, pDst, iDstStepx2MinusWidth
                ADD     pSrc1, pSrc1, iSrcStepx2MinusWidth
                ADD     pSrc, pSrc, iSrcStepx2MinusWidth
                ORR     iWidth, iWidth, temp, LSL #16
                BGT     OuterLoopMVIsZero
                MOV     return,  #OMX_Sts_NoErr
                M_END

        ENDIF ;// ARM1136JS

        
        END

;//-----------------------------------------------------------------------------------------------
;// armVCM4P10_Interpolate_Chroma_asm ends
;//-----------------------------------------------------------------------------------------------

