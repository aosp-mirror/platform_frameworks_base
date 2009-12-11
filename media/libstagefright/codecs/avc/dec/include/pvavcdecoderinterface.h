/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
#ifndef PVAVCDECODERINTERFACE_H_INCLUDED
#define PVAVCDECODERINTERFACE_H_INCLUDED

typedef void (*FunctionType_Unbind)(void *, int);
typedef int (*FunctionType_Alloc)(void *, int, uint8 **);
typedef int (*FunctionType_SPS)(void *, uint, uint);
typedef int (*FunctionType_Malloc)(void *, int32, int);
typedef void(*FunctionType_Free)(void *, int);


// PVAVCDecoderInterface pure virtual interface class
class PVAVCDecoderInterface
{
    public:
        virtual ~PVAVCDecoderInterface() {};
        virtual bool    InitAVCDecoder(FunctionType_SPS, FunctionType_Alloc, FunctionType_Unbind,
                                       FunctionType_Malloc, FunctionType_Free, void *) = 0;
        virtual void    CleanUpAVCDecoder(void) = 0;
        virtual void    ResetAVCDecoder(void) = 0;
        virtual int32   DecodeSPS(uint8 *bitstream, int32 buffer_size) = 0;
        virtual int32   DecodePPS(uint8 *bitstream, int32 buffer_size) = 0;
        virtual int32   DecodeAVCSlice(uint8 *bitstream, int32 *buffer_size) = 0;
        virtual bool    GetDecOutput(int *indx, int *release) = 0;
        virtual void    GetVideoDimensions(int32 *width, int32 *height, int32 *top, int32 *left, int32 *bottom, int32 *right) = 0;
//  virtual int     AVC_Malloc(int32 size, int attribute);
//  virtual void    AVC_Free(int mem);
};

#endif // PVAVCDECODERINTERFACE_H_INCLUDED


