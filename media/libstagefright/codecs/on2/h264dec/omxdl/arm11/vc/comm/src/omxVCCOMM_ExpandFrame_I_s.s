;//
;// 
;// File Name:  omxVCCOMM_ExpandFrame_I_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
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
        
        M_VARIANTS ARM1136JS

;// Import symbols required from other files
;// (For example tables)
    
  
;// Set debugging level        
DEBUG_ON    SETL {FALSE}


    



        
;// Guarding implementation by the processor name
    
    IF  ARM1136JS
    
;//Input Registers

pSrcDstPlane    RN  0
iFrameWidth     RN  1
iFrameHeight    RN  2    
iExpandPels     RN  3


;//Output Registers

result          RN  0

;//Local Scratch Registers

iPlaneStep      RN  4
pTop            RN  5
pBottom         RN  6
pBottomIndex    RN  7
x               RN  8
y               RN  9
tempTop         RN  10
tempBot         RN  11
ColStep         RN  12
pLeft           RN  5
pRight          RN  6
pRightIndex     RN  7
tempLeft1       RN  10
tempRight1      RN  11
tempLeft2       RN  14
tempRight2      RN  2
indexY          RN  14
RowStep         RN  12
expandTo4bytes  RN  1                               ;// copy a byte to 4 bytes of a word
    
        ;// Allocate stack memory required by the function
        
        
        ;// Write function header
        M_START omxVCCOMM_ExpandFrame_I,r11
        
        ;// Define stack arguments
        M_ARG   iPlaneStepOnStack, 4
        
        ;// Load argument from the stack
        M_LDR   iPlaneStep, iPlaneStepOnStack
        
        MUL     pTop,iExpandPels,iPlaneStep
        MLA     pBottom,iFrameHeight,iPlaneStep,pSrcDstPlane
        SUB     x,iFrameWidth,#4
        MOV     indexY,pTop
        ADD     ColStep,indexY,#4
        SUB     pBottomIndex,pBottom,iPlaneStep
        SUB     pTop,pSrcDstPlane,pTop
        
        
        ADD     pTop,pTop,x
        ADD     pBottom,pBottom,x

        ;//------------------------------------------------------------------------
        ;// The following improves upon the C implmentation
        ;// The x and y loops are interchanged: This ensures that the values of
        ;// pSrcDstPlane [x] and pSrcDstPlane [(iFrameHeight - 1) * iPlaneStep + x] 
        ;// which depend only on loop variable 'x' are loaded once and used in 
        ;// multiple stores in the 'Y' loop
        ;//------------------------------------------------------------------------

        ;// xloop
ExpandFrameTopBotXloop
        
        LDR     tempTop,[pSrcDstPlane,x]
        ;//------------------------------------------------------------------------
        ;// pSrcDstPlane [(iFrameHeight - 1) * iPlaneStep + x] is simplified as:
        ;// pSrcDstPlane + (iFrameHeight * iPlaneStep) - iPlaneStep + x ==
        ;// pBottom - iPlaneStep + x == pBottomIndex [x]
        ;// The value of pBottomIndex is calculated above this 'x' loop
        ;//------------------------------------------------------------------------
        LDR     tempBot,[pBottomIndex,x]
        
        ;// yloop
        MOV     y,iExpandPels

ExpandFrameTopBotYloop        
        SUBS    y,y,#1
        M_STR   tempTop,[pTop],iPlaneStep
        M_STR   tempBot,[pBottom],iPlaneStep
        BGT     ExpandFrameTopBotYloop
        
        SUBS    x,x,#4
        SUB     pTop,pTop,ColStep
        SUB     pBottom,pBottom,ColStep
        BGE     ExpandFrameTopBotXloop
        
        
        ;// y loop
        ;// The product is already calculated above : Reuse
        ;//MUL     indexY,iExpandPels,iPlaneStep      
      
        SUB     pSrcDstPlane,pSrcDstPlane,indexY
        SUB     pLeft,pSrcDstPlane,iExpandPels                  ;// pLeft->points to the top left of the expanded block
        ADD     pRight,pSrcDstPlane,iFrameWidth
        SUB     pRightIndex,pRight,#1 
        
        ADD     y,iFrameHeight,iExpandPels,LSL #1
        LDR     expandTo4bytes,=0x01010101
        
        RSB     RowStep,iExpandPels,iPlaneStep,LSL #1

        ;// The Y Loop is unrolled twice
ExpandFrameLeftRightYloop  
        LDRB    tempLeft2,[pSrcDstPlane,iPlaneStep]             ;// PreLoad the values
        LDRB    tempRight2,[pRightIndex,iPlaneStep]
        M_LDRB  tempLeft1,[pSrcDstPlane],iPlaneStep,LSL #1      ;// PreLoad the values
        M_LDRB  tempRight1,[pRightIndex],iPlaneStep,LSL #1
              
        SUB     x,iExpandPels,#4
        MUL     tempLeft2,tempLeft2,expandTo4bytes              ;// Copy the single byte to 4 bytes
        MUL     tempRight2,tempRight2,expandTo4bytes
        MUL     tempLeft1,tempLeft1,expandTo4bytes              ;// Copy the single byte to 4 bytes
        MUL     tempRight1,tempRight1,expandTo4bytes
        
        
        ;// x loop
ExpandFrameLeftRightXloop        
        SUBS    x,x,#4
        STR     tempLeft2,[pLeft,iPlaneStep]                     ;// Store the 4 bytes at one go
        STR     tempRight2,[pRight,iPlaneStep]
        STR     tempLeft1,[pLeft],#4                             ;// Store the 4 bytes at one go
        STR     tempRight1,[pRight],#4
        BGE     ExpandFrameLeftRightXloop
        
        SUBS    y,y,#2
        ADD     pLeft,pLeft,RowStep
        ADD     pRight,pRight,RowStep
        BGT     ExpandFrameLeftRightYloop
        
                        
        ;// Set return value
          
        MOV         result,#OMX_Sts_NoErr  
End             
      
        ;// Write function tail
        
        M_END
        
    ENDIF                                                    ;//ARM1136JS        
 
            
    END