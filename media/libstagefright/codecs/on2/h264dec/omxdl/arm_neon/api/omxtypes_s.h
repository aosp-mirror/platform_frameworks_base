;//
;// 
;// File Name:  omxtypes_s.h
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

;// Mandatory return codes - use cases are explicitly described for each function 
OMX_Sts_NoErr                    EQU  0    ;// No error the function completed successfully 
OMX_Sts_Err                      EQU -2    ;// Unknown/unspecified error     
OMX_Sts_InvalidBitstreamValErr   EQU -182  ;// Invalid value detected during bitstream processing     
OMX_Sts_MemAllocErr              EQU -9    ;// Not enough memory allocated for the operation 
OMX_StsACAAC_GainCtrErr    	     EQU -159  ;// AAC: Unsupported gain control data detected 
OMX_StsACAAC_PrgNumErr           EQU -167  ;// AAC: Invalid number of elements for one program   
OMX_StsACAAC_CoefValErr          EQU -163  ;// AAC: Invalid quantized coefficient value               
OMX_StsACAAC_MaxSfbErr           EQU -162  ;// AAC: Invalid maxSfb value in relation to numSwb     
OMX_StsACAAC_PlsDataErr		     EQU -160  ;// AAC: pulse escape sequence data error 

;// Optional return codes - use cases are explicitly described for each function
OMX_Sts_BadArgErr                EQU -5    ;// Bad Arguments 

OMX_StsACAAC_TnsNumFiltErr       EQU -157  ;// AAC: Invalid number of TNS filters  
OMX_StsACAAC_TnsLenErr           EQU -156  ;// AAC: Invalid TNS region length     
OMX_StsACAAC_TnsOrderErr         EQU -155  ;// AAC: Invalid order of TNS filter                    
OMX_StsACAAC_TnsCoefResErr       EQU -154  ;// AAC: Invalid bit-resolution for TNS filter coefficients  
OMX_StsACAAC_TnsCoefErr          EQU -153  ;// AAC: Invalid TNS filter coefficients                    
OMX_StsACAAC_TnsDirectErr        EQU -152  ;// AAC: Invalid TNS filter direction    

OMX_StsICJP_JPEGMarkerErr        EQU -183  ;// JPEG marker encountered within an entropy-coded block; 
                                            ;// Huffman decoding operation terminated early.           
OMX_StsICJP_JPEGMarker           EQU -181  ;// JPEG marker encountered; Huffman decoding 
                                            ;// operation terminated early.                         
OMX_StsIPPP_ContextMatchErr      EQU -17   ;// Context parameter doesn't match to the operation 

OMX_StsSP_EvenMedianMaskSizeErr  EQU -180  ;// Even size of the Median Filter mask was replaced by the odd one 

OMX_Sts_MaximumEnumeration       EQU 0x7FFFFFFF



OMX_MIN_S8      EQU 	   	(-128)
OMX_MIN_U8  	EQU     	0
OMX_MIN_S16		EQU      	(-32768)
OMX_MIN_U16		EQU	        0


OMX_MIN_S32		EQU	(-2147483647-1)
OMX_MIN_U32		EQU	0

OMX_MAX_S8		EQU	(127)
OMX_MAX_U8		EQU	(255)
OMX_MAX_S16		EQU	(32767)
OMX_MAX_U16		EQU	(0xFFFF)
OMX_MAX_S32		EQU	(2147483647)
OMX_MAX_U32		EQU	(0xFFFFFFFF)

OMX_VC_UPPER    EQU 0x1                 ;// Used by the PredictIntra functions   
OMX_VC_LEFT     EQU 0x2                 ;// Used by the PredictIntra functions 
OMX_VC_UPPER_RIGHT    EQU 0x40          ;// Used by the PredictIntra functions   

NULL    EQU 0

;// Structures

    INCLUDE     armCOMM_s.h

    M_STRUCT    OMXPoint
    M_FIELD     x, 4
    M_FIELD     y, 4
    M_ENDSTRUCT

        END
