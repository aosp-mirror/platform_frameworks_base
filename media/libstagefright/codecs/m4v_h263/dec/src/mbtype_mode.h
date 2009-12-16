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
const static int MBtype_mode[] =
{
    MODE_INTER,
    MODE_INTER_Q,
    MODE_INTER4V,
    MODE_INTRA,
    MODE_INTRA_Q,
#ifdef PV_ANNEX_IJKT_SUPPORT
    MODE_INTER4V_Q,
#endif
    MODE_SKIPPED
};
#ifdef PV_ANNEX_IJKT_SUPPORT
const static int16 DQ_tab_Annex_T_10[32] = {0, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3};
const static int16 DQ_tab_Annex_T_11[32] = {0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 2, 1, -5};
const static int16 MQ_chroma_QP_table[32] = {0, 1, 2, 3, 4, 5, 6, 6, 7, 8, 9, 9, 10, 10, 11, 11, 12, 12, 12, 13, 13, 13,
        14, 14, 14, 14, 14, 15, 15, 15, 15, 15
                                            };
#endif

