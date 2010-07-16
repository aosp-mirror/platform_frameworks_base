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
#ifndef _VLC_ENCODE_H_
#define _VLC_ENCODE_H_

#include "mp4def.h"
#include "mp4enc_api.h"

Int PutCoeff_Inter(Int run, Int level, Int last, BitstreamEncVideo *bitstream);
Int PutCoeff_Intra(Int run, Int level, Int last, BitstreamEncVideo *bitstream);
Int PutCBPY(Int cbpy, Char intra, BitstreamEncVideo *bitstream);
Int PutMCBPC_Inter(Int cbpc, Int mode, BitstreamEncVideo *bitstream);
Int PutMCBPC_Intra(Int cbpc, Int mode, BitstreamEncVideo *bitstream);
Int PutMV(Int mvint, BitstreamEncVideo *bitstream);
Int PutDCsize_chrom(Int size, BitstreamEncVideo *bitstream);
Int PutDCsize_lum(Int size, BitstreamEncVideo *bitstream);
Int PutDCsize_lum(Int size, BitstreamEncVideo *bitstream);
Int PutCoeff_Inter_RVLC(Int run, Int level, Int last, BitstreamEncVideo *bitstream);
Int PutCoeff_Intra_RVLC(Int run, Int level, Int last, BitstreamEncVideo *bitstream);
Int PutRunCoeff_Inter(Int run, Int level, Int last, BitstreamEncVideo *bitstream);
Int PutRunCoeff_Intra(Int run, Int level, Int last, BitstreamEncVideo *bitstream);
Int PutLevelCoeff_Inter(Int run, Int level, Int last, BitstreamEncVideo *bitstream);
Int PutLevelCoeff_Intra(Int run, Int level, Int last, BitstreamEncVideo *bitstream);

Void MB_CodeCoeff(VideoEncData *video, BitstreamEncVideo *bs);
Void BlockCodeCoeff(RunLevelBlock *RLB, BitstreamEncVideo *bs, Int j_start, UChar Mode, Int rvlc, Int shortVideoHeader);
#endif /* _VLC_ENCODE_H_ */
