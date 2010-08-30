/*
 * Copyright (C) 2004-2010 NXP Software
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _LVM_MACROS_H_
#define _LVM_MACROS_H_

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

/**********************************************************************************
   MUL32x32INTO32(A,B,C,ShiftR)
        C = (A * B) >> ShiftR

        A, B and C are all 32 bit SIGNED numbers and ShiftR can vary from 0 to 64

        The user has to take care that C does not overflow.  The result in case
        of overflow is undefined.

***********************************************************************************/
#ifndef MUL32x32INTO32
#define MUL32x32INTO32(A,B,C,ShiftR)   \
        {LVM_INT32 MUL32x32INTO32_temp,MUL32x32INTO32_temp2,MUL32x32INTO32_mask,MUL32x32INTO32_HH,MUL32x32INTO32_HL,MUL32x32INTO32_LH,MUL32x32INTO32_LL;\
         LVM_INT32  shiftValue;\
        shiftValue = (ShiftR);\
        MUL32x32INTO32_mask=0x0000FFFF;\
        MUL32x32INTO32_HH= ((LVM_INT32)((LVM_INT16)((A)>>16))*((LVM_INT16)((B)>>16)) );\
        MUL32x32INTO32_HL= ((LVM_INT32)((B)&MUL32x32INTO32_mask)*((LVM_INT16)((A)>>16))) ;\
        MUL32x32INTO32_LH= ((LVM_INT32)((A)&MUL32x32INTO32_mask)*((LVM_INT16)((B)>>16)));\
        MUL32x32INTO32_LL= (LVM_INT32)((A)&MUL32x32INTO32_mask)*(LVM_INT32)((B)&MUL32x32INTO32_mask);\
        MUL32x32INTO32_temp= (LVM_INT32)(MUL32x32INTO32_HL&MUL32x32INTO32_mask)+(LVM_INT32)(MUL32x32INTO32_LH&MUL32x32INTO32_mask)+(LVM_INT32)((MUL32x32INTO32_LL>>16)&MUL32x32INTO32_mask);\
        MUL32x32INTO32_HH= MUL32x32INTO32_HH+(LVM_INT32)(MUL32x32INTO32_HL>>16)+(LVM_INT32)(MUL32x32INTO32_LH>>16)+(LVM_INT32)(MUL32x32INTO32_temp>>16);\
        MUL32x32INTO32_LL=MUL32x32INTO32_LL+(LVM_INT32)(MUL32x32INTO32_HL<<16)+(LVM_INT32)(MUL32x32INTO32_LH<<16);\
        if(shiftValue<32)\
        {\
        MUL32x32INTO32_HH=MUL32x32INTO32_HH<<(32-shiftValue);\
        MUL32x32INTO32_mask=((LVM_INT32)1<<(32-shiftValue))-1;\
        MUL32x32INTO32_LL=(MUL32x32INTO32_LL>>shiftValue)&MUL32x32INTO32_mask;\
        MUL32x32INTO32_temp2=MUL32x32INTO32_HH|MUL32x32INTO32_LL;\
        }\
        else\
       {\
        MUL32x32INTO32_temp2=(LVM_INT32)MUL32x32INTO32_HH>>(shiftValue-32);\
       }\
       (C) = MUL32x32INTO32_temp2;\
       }
#endif

/**********************************************************************************
   MUL32x16INTO32(A,B,C,ShiftR)
        C = (A * B) >> ShiftR

        A and C are 32 bit SIGNED numbers.  B is a 16 bit SIGNED number.
        ShiftR can vary from 0 to 48

        The user has to take care that C does not overflow.  The result in case
        of overflow is undefined.

***********************************************************************************/
#ifndef MUL32x16INTO32
#define MUL32x16INTO32(A,B,C,ShiftR)   \
        {LVM_INT32 MUL32x16INTO32_mask,MUL32x16INTO32_HH,MUL32x16INTO32_LL;\
         LVM_INT32  shiftValue;\
        shiftValue = (ShiftR);\
        MUL32x16INTO32_mask=0x0000FFFF;\
        MUL32x16INTO32_HH= ((LVM_INT32)(B)*((LVM_INT16)((A)>>16)));\
        MUL32x16INTO32_LL= ((LVM_INT32)((A)&MUL32x16INTO32_mask)*(B));\
        if(shiftValue<16)\
        {\
        MUL32x16INTO32_HH=(LVM_INT32)((LVM_UINT32)MUL32x16INTO32_HH<<(16-shiftValue));\
        (C)=MUL32x16INTO32_HH+(LVM_INT32)(MUL32x16INTO32_LL>>shiftValue);\
        }\
        else if(shiftValue<32) {\
        MUL32x16INTO32_HH=(LVM_INT32)(MUL32x16INTO32_HH>>(shiftValue-16));\
        (C)=MUL32x16INTO32_HH+(LVM_INT32)(MUL32x16INTO32_LL>>shiftValue);\
        }\
        else {\
        (C)=MUL32x16INTO32_HH>>(shiftValue-16);}\
        }
#endif

/**********************************************************************************
   ADD2_SAT_32x32(A,B,C)
        C = SAT(A + B)

        A,B and C are 32 bit SIGNED numbers.
***********************************************************************************/
#ifndef ADD2_SAT_32x32
#define ADD2_SAT_32x32(A,B,C)   \
        {(C)=(A)+(B);\
         if ((((C) ^ (A)) & ((C) ^ (B))) >> 31)\
            {\
                if((A)<0)\
                    (C)=0x80000000l;\
                else\
                    (C)=0x7FFFFFFFl;\
            }\
        }
#endif


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* _LVM_MACROS_H_ */

/*** End of file ******************************************************************/
