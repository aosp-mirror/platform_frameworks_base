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
#ifndef PVAVCDECODER_FACTORY_H_INCLUDED
#define PVAVCDECODER_FACTORY_H_INCLUDED

class PVAVCDecoderInterface;

class PVAVCDecoderFactory
{
    public:
        /**
         * Creates an instance of a PVAVCDecoder. If the creation fails, this function will leave.
         *
         * @returns A pointer to an instance of PVAVCDecoder as PVAVCDecoderInterface reference or leaves if instantiation fails
         **/
        OSCL_IMPORT_REF static PVAVCDecoderInterface* CreatePVAVCDecoder(void);

        /**
         * Deletes an instance of PVAVCDecoder and reclaims all allocated resources.
         *
         * @param aVideoDec The PVAVCDecoder instance to be deleted
         * @returns A status code indicating success or failure of deletion
         **/
        OSCL_IMPORT_REF static bool DeletePVAVCDecoder(PVAVCDecoderInterface* aVideoDec);
};

#endif

