;/**
; * 
; * File Name:  armVCM4P2_DecodeVLCZigzag_AC_unsafe_s.s
; * OpenMAX DL: v1.0.2
; * Revision:   9641
; * Date:       Thursday, February 7, 2008
; * 
; * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
; * 
; * 
; *
; * Description: 
; * Contains modules for zigzag scanning and VLC decoding
; * for inter, intra block.
; *
; *
; *
; * Function: omxVCM4P2_DecodeVLCZigzag_AC_unsafe
; *
; * Description:
; * Performs VLC decoding and inverse zigzag scan 
; *
; * 
; *
; * 
; */


      INCLUDE omxtypes_s.h
      INCLUDE armCOMM_s.h
      INCLUDE armCOMM_BitDec_s.h


      M_VARIANTS ARM1136JS

     



     IF ARM1136JS
     
        



;//Input Arguments

ppBitStream          RN 0
pBitOffset           RN 1
pDst                 RN 2
shortVideoHeader     RN 3


;//Local Variables

Return               RN 0

pVlcTableL0L1        RN 4
pLMAXTableL0L1       RN 4
pRMAXTableL0L1       RN 4
pZigzagTable         RN 4

ftype                RN 0
temp3                RN 4
temp                 RN 5
Count                RN 6
Escape               RN 5

;// armVCM4P2_FillVLDBuffer
zigzag               RN 0
storeLevel           RN 1
temp2                RN 4
temp1                RN 5
sign                 RN 5
Last                 RN 7
storeRun             RN 14


packRetIndex         RN 5


markerbit            RN 5

;// Scratch Registers

RBitStream           RN 8
RBitBuffer           RN 9
RBitCount            RN 10

T1                   RN 11
T2                   RN 12
LR                   RN 14        
        


        M_ALLOC4        pppBitStream,4
        M_ALLOC4        ppOffset,4
        M_ALLOC4        pLinkRegister,4       
        
        M_START armVCM4P2_DecodeVLCZigzag_AC_unsafe

        ;// get the table addresses from stack       
        M_ARG           ppVlcTableL0L1,4
        M_ARG           ppLMAXTableL0L1,4
        M_ARG           ppRMAXTableL0L1,4
        M_ARG           ppZigzagTable,4
        
        ;// Store ALL zeros at pDst
        
        MOV             temp1,#0                                        ;// Initialize Count to zero                                
        MOV             Last,#0
        M_STR           LR,pLinkRegister                                ;// Store Link Register on Stack
        MOV             temp2,#0
        MOV             LR,#0          
        
        ;// Initialize the Macro and Store all zeros to pDst 
  
        STM             pDst!,{temp2,temp1,Last,LR}                   
        M_BD_INIT0      ppBitStream, pBitOffset, RBitStream, RBitBuffer, RBitCount  
        STM             pDst!,{temp2,temp1,Last,LR}
        M_BD_INIT1      T1, T2, T2
        STM             pDst!,{temp2,temp1,Last,LR}
        M_BD_INIT2      T1, T2, T2
        STM             pDst!,{temp2,temp1,Last,LR}
        M_STR           ppBitStream,pppBitStream                        ;// Store ppBitstream on stack                         
        STM             pDst!,{temp2,temp1,Last,LR}
        M_STR           pBitOffset,ppOffset                             ;// Store pBitOffset on stack
        STM             pDst!,{temp2,temp1,Last,LR}
        
        STM             pDst!,{temp2,temp1,Last,LR}
        STM             pDst!,{temp2,temp1,Last,LR}
 
        
        SUB             pDst,pDst,#128                                  ;// Restore pDst

        ;// The armVCM4P2_GetVLCBits begins

getVLCbits
        
        M_BD_LOOK8      Escape,7                                        ;// Load Escape Value
        LSR             Escape,Escape,#25                                                  
        CMP             Escape,#3                                       ;// check for escape mode
        MOVNE           ftype,#0
        BNE             notEscapemode                                   ;// Branch if not in Escape mode 3

        M_BD_VSKIP8     #7,T1
        CMP             shortVideoHeader,#0                             ;// Check shortVideoHeader flag to know the type of Escape mode
        BEQ             endFillVLD                                       
        
        ;// Escape Mode 4

        M_BD_READ8      Last,1,T1
        M_BD_READ8      storeRun,6,T1
        M_BD_READ8      storeLevel,8,T1

           
        ;// Check whether the Reserved values for Level are used and Exit with an Error Message if it is so

        TEQ             storeLevel,#0
        TEQNE           storeLevel,#128                    
        BEQ             ExitError

        ADD             temp2,storeRun,Count
        CMP             temp2,#64
        BGE             ExitError                                       ;// error if Count+storeRun >= 64
        
        
        ;// Load address of zigzagTable
        
        M_LDR           pZigzagTable,ppZigzagTable                      ;// Loading the Address of Zigzag table
               
                
        ;// armVCM4P2_FillVLDBuffer
                
        SXTB            storeLevel,storeLevel                           ;// Sign Extend storeLevel to 32 bits
                              
        
        ;// To Reflect Runlength

        ADD             Count,Count,storeRun
        LDRB            zigzag,[pZigzagTable,Count]
        ADD             Count,Count,#1
        STRH            storeLevel,[pDst,zigzag]                        ;// store Level
              
        B               ExitOk
       
        

endFillVLD
        
               
        ;// Load Ftype( Escape Mode) value based on the two successive bits in the bitstream
     
        M_BD_READ8      temp1,1,T1           
        CMP             temp1,#0    
        MOVEQ           ftype,#1
        BEQ             notEscapemode
        M_BD_READ8      temp1,1,T1
        CMP             temp1,#1
        MOVEQ           ftype,#3
        MOVNE           ftype,#2
        

notEscapemode

        ;// Load optimized packed VLC table with last=0 and Last=1
        
        M_LDR           pVlcTableL0L1,ppVlcTableL0L1                    ;// Load Combined VLC Table
                
       
        CMP             ftype,#3                                        ;// If ftype >=3 get perform Fixed Length Decoding (Escape Mode 3)
        BGE             EscapeMode3                                     ;// Else continue normal VLC Decoding
        
        ;// Variable lengh decoding, "armUnPackVLC32" 
        
        
        M_BD_VLD        packRetIndex,T1,T2,pVlcTableL0L1,4,2
        
        
        LDR             temp3,=0xFFF
        
        CMP             packRetIndex,temp3                              ;// Check for invalid symbol
        BEQ             ExitError                                       ;// if invalid symbol occurs exit with an error message
        
        AND             Last,packRetIndex,#2                            ;// Get Last from packed Index
              
         
        

        LSR             storeRun,packRetIndex,#7                        ;// Get Run Value from Packed index
        AND             storeLevel,packRetIndex,#0x7c                   ;// storeLevel=packRetIndex[2-6],storeLevel[0-1]=0 
                                                                        
     
        M_LDR           pLMAXTableL0L1,ppLMAXTableL0L1                  ;// Load LMAX table
              
       
        LSR             storeLevel,storeLevel,#2                        ;// Level value

        CMP             ftype,#1                                    
        BNE             ftype2
        
        ;// ftype==1; Escape mode =1
          
        
        ADD            temp1, pLMAXTableL0L1, Last, LSL#4              ;// If the Last=1 add 32 to table address
        LDRB            temp1,[temp1,storeRun]

       
        ADD             storeLevel,temp1,storeLevel                     

ftype2

        ;// ftype =2; Escape mode =2
        
        M_LDR           pRMAXTableL0L1,ppRMAXTableL0L1                  ;// Load RMAX Table 
                
        CMP             ftype,#2
        BNE             FillVLDL1
                  
        ADD            temp1, pRMAXTableL0L1, Last, LSL#4               ;// If Last=1 add 32 to table address
        SUB             temp2,storeLevel,#1
        LDRB            temp1,[temp1,temp2]

       
        ADD             storeRun,storeRun,#1
        ADD             storeRun,temp1
        
FillVLDL1        
            
                
        ;// armVCM4P2_FillVLDBuffer

        M_LDR           pZigzagTable,ppZigzagTable                     ;// Load address of zigzagTable 
                
        M_BD_READ8      sign,1,T1

        CMP             sign,#1
        RSBEQ           storeLevel,storeLevel,#0
 
        ADD             temp1,storeRun,Count                           ;// Exit with an error message if Run + Count exceeds 63
        CMP             temp1,#64
        BGE             ExitError

      
        
        
              
        
        ;// To Reflect Runlenght

        ADD             Count,Count,storeRun
 
storeLevelL1
        
        LDRB            zigzag,[pZigzagTable,Count]
        CMP             Last,#2                                         ;// Check if the Level val is Last non zero val
        ADD             Count,Count,#1
        LSR             Last,Last,#1
        STRH            storeLevel,[pDst,zigzag]                  
           
        BNE             end
        
        B               ExitOk
 


        ;// Fixed Lengh Decoding Escape Mode 3

EscapeMode3

        M_BD_READ8      Last,1,T1
        M_BD_READ8      storeRun,6,T1
        
        ADD             temp2,storeRun,Count                            ;// Exit with an error message if Run + Count exceeds 63
        CMP             temp2,#64
        BGE             ExitError

        M_BD_READ8      markerbit,1,T1
        TEQ             markerbit,#0                                    ;// Exit with an error message if marker bit is zero
        BEQ             ExitError
        
        M_BD_READ16     storeLevel,12,T1

        TST             storeLevel,#0x800                               ;// test if the level is negative
        SUBNE           storeLevel,storeLevel,#4096
        CMP             storeLevel,#0
        CMPNE           storeLevel,#-2048
        BEQ             ExitError                                       ;// Exit with an error message if Level==0 or  -2048 

        M_LDR           pZigzagTable,ppZigzagTable                      ;// Load address of zigzagTable
              
        M_BD_READ8      markerbit,1,T1
           

        ;// armVCM4P2_FillVLDBuffer ( Sign not used as storeLevel is preprocessed)
            
               

        ;// To Reflect Run Length

        ADD             Count,Count,storeRun


 
storeLevelLast
        
        LDRB            zigzag,[pZigzagTable,Count]
        CMP             Last,#1
        ADD             Count,Count,#1
        STRH            storeLevel,[pDst,zigzag]                          
                
        BNE             end 
      
        B               ExitOk
        
end

        CMP             Count,#64                                       ;//Run the Loop untill Count reaches 64

        BLT             getVLCbits

        
ExitOk
        ;// Exit When VLC Decoding is done Successfully 
   
        ;// Loading ppBitStream and pBitOffset from stack
        
        CMP             Last,#1
        M_LDR           ppBitStream,pppBitStream
        M_LDR           pBitOffset,ppOffset

        ;//Ending the macro

        M_BD_FINI       ppBitStream,pBitOffset
             
        MOVEQ           Return,#OMX_Sts_NoErr
        MOVNE           Return,#OMX_Sts_Err
        M_LDR           LR,pLinkRegister                               ;// Load the Link Register Back
        B               exit2

ExitError
        ;// Exit When an Error occurs 

        M_LDR           ppBitStream,pppBitStream
        M_LDR           pBitOffset,ppOffset
        ;//Ending the macro

        M_BD_FINI       ppBitStream,pBitOffset
        M_LDR           LR,pLinkRegister
        MOV             Return,#OMX_Sts_Err

exit2
       

        M_END
        ENDIF
        
        END
