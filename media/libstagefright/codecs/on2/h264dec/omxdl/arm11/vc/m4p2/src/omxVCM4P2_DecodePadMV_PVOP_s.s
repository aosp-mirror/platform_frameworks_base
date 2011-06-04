; **********
; * 
; * File Name:  omxVCM4P2_DecodePadMV_PVOP_s.s
; * OpenMAX DL: v1.0.2
; * Revision:   9641
; * Date:       Thursday, February 7, 2008
; * 
; * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
; * 
; * 
; * 
; **
; * Function: omxVCM4P2_DecodePadMV_PVOP
; *
; * Description:
; * Decodes and pads four motion vectors of the non-intra macroblock in P-VOP.
; * The motion vector padding process is specified in subclause 7.6.1.6 of
; * ISO/IEC 14496-2.
; *
; * Remarks:
; *
; *
; * Parameters:
; * [in]    ppBitStream        pointer to the pointer to the current byte in
; *                            the bit stream buffer
; * [in]    pBitOffset         pointer to the bit position in the byte pointed
; *                            to by *ppBitStream. *pBitOffset is valid within
; *                            [0-7].
; * [in]    pSrcMVLeftMB       pointers to the motion vector buffers of the
; *                           macroblocks specially at the left side of the current macroblock
; *                     respectively.
; * [in]    pSrcMVUpperMB      pointers to the motion vector buffers of the
; *                     macroblocks specially at the upper side of the current macroblock
; *                     respectively.
; * [in]    pSrcMVUpperRightMB pointers to the motion vector buffers of the
; *                     macroblocks specially at the upper-right side of the current macroblock
; *                     respectively.
; * [in]    fcodeForward       a code equal to vop_fcode_forward in MPEG-4
; *                     bit stream syntax
; * [in]    MBType         the type of the current macroblock. If MBType
; *                     is not equal to OMX_VC_INTER4V, the destination
; *                     motion vector buffer is still filled with the
; *                     same decoded vector.
; * [out]   ppBitStream         *ppBitStream is updated after the block is decoded,
; *                     so that it points to the current byte in the bit
; *                     stream buffer
; * [out]   pBitOffset         *pBitOffset is updated so that it points to the
; *                     current bit position in the byte pointed by
; *                     *ppBitStream
; * [out]   pDstMVCurMB         pointer to the motion vector buffer of the current
; *                     macroblock which contains four decoded motion vectors
; *
; * Return Value:
; * OMX_Sts_NoErr -no error
; * 
; *                     
; * OMX_Sts_Err - status error
; *
; *
     
        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        INCLUDE armCOMM_BitDec_s.h
        INCLUDE omxVC_s.h
        
       M_VARIANTS ARM1136JS
       
                


        IF ARM1136JS

;//Input Arguments

ppBitStream           RN 0
pBitOffset            RN 1
pSrcMVLeftMB          RN 2
pSrcMVUpperMB         RN 3
pSrcMVUpperRightMB    RN 4
pDstMVCurMB           RN 5
fcodeForward          RN 6
MBType                RN 7

;//Local Variables

zero                  RN 4
one                   RN 4
scaleFactor           RN 1


Return                RN 0

VlcMVD                RN 0
index                 RN 4
Count                 RN 7

mvHorData             RN 4
mvHorResidual         RN 0

mvVerData             RN 4             
mvVerResidual         RN 0

temp                  RN 1

temp1                 RN 3
High                  RN 4
Low                   RN 2
Range                 RN 1

BlkCount              RN 14

diffMVdx              RN 0
diffMVdy              RN 1

;// Scratch Registers

RBitStream            RN 8
RBitCount             RN 9
RBitBuffer            RN 10

T1                    RN 11
T2                    RN 12
LR                    RN 14

       IMPORT          armVCM4P2_aVlcMVD
       IMPORT          omxVCM4P2_FindMVpred

       ;// Allocate stack memory        
       
       M_ALLOC4        ppDstMVCurMB,4
       M_ALLOC4        pDstMVPredME,4
       M_ALLOC4        pBlkCount,4
       
       M_ALLOC4        pppBitStream,4
       M_ALLOC4        ppBitOffset,4
       M_ALLOC4        ppSrcMVLeftMB,4
       M_ALLOC4        ppSrcMVUpperMB,4
       
       M_ALLOC4        pdiffMVdx,4
       M_ALLOC4        pdiffMVdy,4
       M_ALLOC4        pHigh,4
       
              


       M_START   omxVCM4P2_DecodePadMV_PVOP,r11
       
       M_ARG           pSrcMVUpperRightMBonStack,4           ;// pointer to  pSrcMVUpperRightMB on stack
       M_ARG           pDstMVCurMBonStack,4                  ;// pointer to pDstMVCurMB on stack
       M_ARG           fcodeForwardonStack,4                 ;// pointer to fcodeForward on stack 
       M_ARG           MBTypeonStack,4                       ;// pointer to MBType on stack

      
       
       
       
       ;// Initializing the BitStream Macro

       M_BD_INIT0      ppBitStream, pBitOffset, RBitStream, RBitBuffer, RBitCount
       M_LDR           MBType,MBTypeonStack                  ;// Load MBType from stack
       M_LDR           pDstMVCurMB,pDstMVCurMBonStack        ;// Load pDstMVCurMB from stack
       MOV             zero,#0

       TEQ             MBType,#OMX_VC_INTRA                  ;// Check if MBType=OMX_VC_INTRA
       TEQNE           MBType,#OMX_VC_INTRA_Q                ;// check if MBType=OMX_VC_INTRA_Q
       STREQ           zero,[pDstMVCurMB]
       M_BD_INIT1      T1, T2, T2
       STREQ           zero,[pDstMVCurMB,#4]
       M_BD_INIT2      T1, T2, T2
       STREQ           zero,[pDstMVCurMB,#4]
       MOVEQ           Return,#OMX_Sts_NoErr
       MOV             BlkCount,#0
       STREQ           zero,[pDstMVCurMB,#4]
       
       BEQ             ExitOK

       TEQ             MBType,#OMX_VC_INTER4V                ;// Check if MBType=OMX_VC_INTER4V
       TEQNE           MBType,#OMX_VC_INTER4V_Q              ;// Check if MBType=OMX_VC_INTER4V_Q
       MOVEQ           Count,#4

       TEQ             MBType,#OMX_VC_INTER                  ;// Check if MBType=OMX_VC_INTER
       TEQNE           MBType,#OMX_VC_INTER_Q                ;// Check if MBType=OMX_VC_INTER_Q
       MOVEQ           Count,#1
       
       M_LDR           fcodeForward,fcodeForwardonStack      ;// Load fcodeForward  from stack

       ;// Storing the values temporarily on stack

       M_STR           ppBitStream,pppBitStream              
       M_STR           pBitOffset,ppBitOffset
            

       SUB             temp,fcodeForward,#1                  ;// temp=fcodeForward-1
       MOV             one,#1
       M_STR           pSrcMVLeftMB,ppSrcMVLeftMB
       LSL             scaleFactor,one,temp                  ;// scaleFactor=1<<(fcodeForward-1)
       M_STR           pSrcMVUpperMB,ppSrcMVUpperMB
       LSL             scaleFactor,scaleFactor,#5            
       M_STR           scaleFactor,pHigh                     ;// [pHigh]=32*scaleFactor
              
       ;// VLD Decoding


Loop

       LDR             VlcMVD, =armVCM4P2_aVlcMVD        ;// Load the optimized MVD VLC table

       ;// Horizontal Data and Residual calculation

       LDR             temp,=0xFFF                           
       M_BD_VLD        index,T1,T2,VlcMVD,3,2                ;// variable lenght decoding using the macro
      
       TEQ             index,temp
       BEQ             ExitError                             ;// Exit with an Error Message if the decoded symbol is an invalied symbol 
       
       SUB             mvHorData,index,#32                   ;// mvHorData=index-32             
       MOV             mvHorResidual,#1                      ;// mvHorResidual=1
       CMP             fcodeForward,#1
       TEQNE           mvHorData,#0
       MOVEQ           diffMVdx,mvHorData                    ;// if scaleFactor=1(fcodeForward=1) or mvHorData=0 diffMVdx=mvHorData         
       BEQ             VerticalData
       
       SUB             temp,fcodeForward,#1
       M_BD_VREAD8     mvHorResidual,temp,T1,T2              ;// get mvHorResidual from bitstream if fcodeForward>1 and mvHorData!=0              
       
       CMP             mvHorData,#0
       RSBLT           mvHorData,mvHorData,#0                ;// mvHorData=abs(mvHorData)
       SUB             mvHorResidual,mvHorResidual,fcodeForward
       SMLABB          diffMVdx,mvHorData,fcodeForward,mvHorResidual ;// diffMVdx=abs(mvHorData)*fcodeForward+mvHorResidual-fcodeForward
       ADD             diffMVdx,diffMVdx,#1
       RSBLT           diffMVdx,diffMVdx,#0
       
       ;// Vertical Data and Residual calculation

VerticalData

       M_STR           diffMVdx,pdiffMVdx                    ;// Store the diffMVdx on stack
       LDR             VlcMVD, =armVCM4P2_aVlcMVD        ;// Loading the address of optimized VLC tables

       LDR             temp,=0xFFF
       M_BD_VLD        index,T1,T2,VlcMVD,3,2                ;// VLC decoding using the macro
       
       TEQ             index,temp
       BEQ             ExitError                             ;// Exit with an Error Message if an Invalied Symbol occurs
       
       SUB             mvVerData,index,#32                   ;// mvVerData=index-32             
       MOV             mvVerResidual,#1     
       CMP             fcodeForward,#1
       TEQNE           mvVerData,#0
       MOVEQ           diffMVdy,mvVerData                    ;// diffMVdy = mvVerData if scaleFactor=1(fcodeForward=1) or mvVerData=0
       BEQ             FindMVPred

       SUB             temp,fcodeForward,#1
       M_BD_VREAD8     mvVerResidual,temp,T1,T2              ;// Get mvVerResidual from bit stream if fcodeForward>1 and mnVerData!=0
             

       CMP             mvVerData,#0
       RSBLT           mvVerData,mvVerData,#0
       SUB             mvVerResidual,mvVerResidual,fcodeForward
       SMLABB          diffMVdy,mvVerData,fcodeForward,mvVerResidual ;// diffMVdy=abs(mvVerData)*fcodeForward+mvVerResidual-fcodeForward
       ADD             diffMVdy,diffMVdy,#1
       RSBLT           diffMVdy,diffMVdy,#0

       ;//Calling the Function omxVCM4P2_FindMVpred
        
FindMVPred

       M_STR           diffMVdy,pdiffMVdy
       ADD             temp,pDstMVCurMB,BlkCount,LSL #2      ;// temp=pDstMVCurMB[BlkCount]
       M_STR           temp,ppDstMVCurMB                     ;// store temp on stack for passing as an argument to FindMVPred
       
       MOV             temp,#0
       M_STR           temp,pDstMVPredME                     ;// Pass pDstMVPredME=NULL as an argument         
       M_STR           BlkCount,pBlkCount                    ;// Passs BlkCount as Argument through stack

       MOV             temp,pSrcMVLeftMB                     ;// temp (RN 1)=pSrcMVLeftMB
       M_LDR           pSrcMVUpperRightMB,pSrcMVUpperRightMBonStack
       MOV             pSrcMVLeftMB,pSrcMVUpperMB            ;// pSrcMVLeftMB ( RN 2) = pSrcMVUpperMB
       MOV             ppBitStream,pDstMVCurMB               ;// ppBitStream  ( RN 0) = pDstMVCurMB
       MOV             pSrcMVUpperMB,pSrcMVUpperRightMB      ;// pSrcMVUpperMB( RN 3) = pSrcMVUpperRightMB      
       BL              omxVCM4P2_FindMVpred              ;// Branch to subroutine omxVCM4P2_FindMVpred

       ;// Store Horizontal Motion Vector
     
       M_LDR           BlkCount,pBlkCount                    ;// Load BlkCount from stack
       M_LDR           High,pHigh                            ;// High=32*scaleFactor
       LSL             temp1,BlkCount,#2                     ;// temp=BlkCount*4
       M_LDR           diffMVdx,pdiffMVdx                    ;// Laad diffMVdx
       
       LDRSH           temp,[pDstMVCurMB,temp1]              ;// temp=pDstMVCurMB[BlkCount]
       
       
       RSB             Low,High,#0                           ;// Low = -32*scaleFactor
       ADD             diffMVdx,temp,diffMVdx                ;// diffMVdx=pDstMVCurMB[BlkCount]+diffMVdx
       ADD             Range,High,High                       ;// Range=64*ScaleFactor
       SUB             High,High,#1                          ;// High= 32*scaleFactor-1

       CMP             diffMVdx,Low                          ;// If diffMVdx<Low          
       ADDLT           diffMVdx,diffMVdx,Range               ;// diffMVdx+=Range
        
       CMP             diffMVdx,High                         
       SUBGT           diffMVdx,diffMVdx,Range               ;// If diffMVdx > High diffMVdx-=Range
       STRH            diffMVdx,[pDstMVCurMB,temp1]

       ;// Store Vertical

       ADD             temp1,temp1,#2                        ;// temp1=4*BlkCount+2
       M_LDR           diffMVdx,pdiffMVdy                    ;// Laad diffMVdy
       LDRSH           temp,[pDstMVCurMB,temp1]              ;// temp=pDstMVCurMB[BlkCount].diffMVdy
       ADD             BlkCount,BlkCount,#1                  ;// BlkCount=BlkCount+1
       ADD             diffMVdx,temp,diffMVdx                
       CMP             diffMVdx,Low
       ADDLT           diffMVdx,diffMVdx,Range               ;// If diffMVdy<Low  diffMVdy+=Range                
       CMP             diffMVdx,High
       SUBGT           diffMVdx,diffMVdx,Range               ;// If diffMVdy > High diffMVdy-=Range
       STRH            diffMVdx,[pDstMVCurMB,temp1]    
       
       CMP             BlkCount,Count
       M_LDR           pSrcMVLeftMB,ppSrcMVLeftMB
       M_LDR           pSrcMVUpperMB,ppSrcMVUpperMB

       BLT             Loop                                  ;// If BlkCount<Count Continue the Loop


       ;// If MBType=OMX_VC_INTER or MBtype=OMX_VC_INTER_Q copy pDstMVCurMB[0] to
       ;// pDstMVCurMB[1], pDstMVCurMB[2], pDstMVCurMB[3] 

       M_LDR           MBType,MBTypeonStack

       TEQ             MBType,#OMX_VC_INTER                                       
       TEQNE           MBType,#OMX_VC_INTER_Q                            
       LDREQ           temp,[pDstMVCurMB]
       M_LDR           ppBitStream,pppBitStream
       STREQ           temp,[pDstMVCurMB,#4]
       
       STREQ           temp,[pDstMVCurMB,#8]
       STREQ           temp,[pDstMVCurMB,#12]
       
       
       M_LDR           pBitOffset,ppBitOffset
       ;//Ending the macro
       M_BD_FINI       ppBitStream,pBitOffset                 ;// Finishing the Macro       

       
       MOV             Return,#OMX_Sts_NoErr
       B               ExitOK
 
ExitError

       M_LDR           ppBitStream,pppBitStream
       M_LDR           pBitOffset,ppBitOffset
       ;//Ending the macro
       M_BD_FINI       ppBitStream,pBitOffset
       
       MOV             Return,#OMX_Sts_Err

ExitOK             

       M_END
       ENDIF
       END


   
