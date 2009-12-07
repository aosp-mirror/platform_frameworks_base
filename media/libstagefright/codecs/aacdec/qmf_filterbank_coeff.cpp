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
/*

 Pathname: qmf_filterbank_coeff.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                              Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

 Local Stores/Buffers/Pointers Needed:

 Global Stores/Buffers/Pointers Needed:

 Outputs:

 Pointers and Buffers Modified:


 Local Stores Modified:

 Global Stores Modified:

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function defines the scalefactor bands for all sampling rates

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

SC 29 Software Copyright Licencing Disclaimer:

This software module was originally developed by
  Coding Technologies

and edited by
  -

in the course of development of the ISO/IEC 13818-7 and ISO/IEC 14496-3
standards for reference purposes and its performance may not have been
optimized. This software module is an implementation of one or more tools as
specified by the ISO/IEC 13818-7 and ISO/IEC 14496-3 standards.
ISO/IEC gives users free license to this software module or modifications
thereof for use in products claiming conformance to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International
Standards. ISO/IEC gives users the same free license to this software module or
modifications thereof for research purposes and further ISO/IEC standardisation.
Those intending to use this software module in products are advised that its
use may infringe existing patents. ISO/IEC have no liability for use of this
software module or modifications thereof. Copyright is not released for
products that do not conform to audiovisual and image-coding related ITU
Recommendations and/or ISO/IEC International Standards.
The original developer retains full right to modify and use the code for its
own purpose, assign or donate the code to a third party and to inhibit third
parties from using the code for products that do not conform to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International Standards.
This copyright notice must be included in all copies or derivative works.
Copyright (c) ISO/IEC 2002.

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE: [stack count for this module] + [variable to represent
          stack usage for each subroutine called]

     where: [stack usage variable] = stack usage for [subroutine
         name] (see [filename].ext)

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES: [cycle count equation for this module] + [variable
           used to represent cycle count for each subroutine
           called]

     where: [cycle count variable] = cycle count for [subroutine
        name] (see [filename].ext)

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS


#include    "qmf_filterbank_coeff.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/



const Int32 sbrDecoderFilterbankCoefficients[155] =
{
    /*  10/9*table */

    0xFFEA0066,  0x020C09CF,  0x34F67965,  0xCE380A2F,  0xFE43005A,
    0xFFEA006C,  0x02360998,  0x36907954,  0xCFCD0A57,  0xFE690054,
    0xFFEC0072,  0x0262095B,  0x382B7937,  0xD1600A7A,  0xFE8E004F,
    0xFFED0078,  0x028E0919,  0x39C6790F,  0xD2F00A98,  0xFEB20049,
    0xFFED007E,  0x02BB08D0,  0x3B6378DB,  0xD47D0AB1,  0xFED50043,
    0xFFEC0084,  0x02E90882,  0x3D00789B,  0xD6080AC6,  0xFEF6003E,
    0xFFEB0089,  0x0318082F,  0x3E9D7851,  0xD78F0AD6,  0xFF160039,
    0xFFEB008F,  0x034807D5,  0x403A77FB,  0xD9130AE2,  0xFF350033,
    0xFFEA0095,  0x03790775,  0x41D7779A,  0xDA930AEA,  0xFF53002E,
    0xFFE9009A,  0x03AB070E,  0x4373772D,  0xDC100AED,  0xFF6F0029,
    0xFFE800A0,  0x03DE06A2,  0x450D76B6,  0xDD890AED,  0xFF8A0024,
    0xFFE800A5,  0x0412062F,  0x46A77633,  0xDEFD0AE9,  0xFFA40020,
    0xFFE700AA,  0x044705B6,  0x483F75A6,  0xE06D0AE2,  0xFFBD001C,
    0xFFE600AF,  0x047B0537,  0x49D5750E,  0xE1D90AD7,  0xFFD40017,
    0xFFE500B3,  0x04B104B0,  0x4B69746B,  0xE3400AC8,  0xFFEB0013,
    0xFFE400B8,  0x04E70423,  0x4CFA73BE,  0xE4A20AB7,  0x0002000F,
    0xFFE400BC,  0x051E0390,  0x4E897306,  0xE5FF0AA2,  0x0016000B,
    0xFFE300BF,  0x055502F6,  0x50157244,  0xE7560A8A,  0x00280008,
    0xFFE300C3,  0x058D0254,  0x519D7178,  0xE8A80A6F,  0x003A0004,
    0xFFE300C6,  0x05C401AD,  0x532270A2,  0xE9F50A53,  0x004A0001,
    0xFFE200C8,  0x05FC00FE,  0x54A36FC3,  0xEB3C0A33,  0x005AFFFC,
    0xFFE200CA,  0x06340048,  0x56206EDA,  0xEC7D0A11,  0x0068FFF9,
    0xFFE200CC,  0x066CFF8A,  0x57986DE8,  0xEDB809EC,  0x0075FFF7,
    0xFFE200CD,  0x06A4FEC6,  0x590C6CEC,  0xEEED09C6,  0x0081FFF4,
    0xFFE200CE,  0x06DCFDFC,  0x5A7B6BE7,  0xF01C099E,  0x008DFFF2,
    0xFFE200CE,  0x0713FD2B,  0x5BE56ADA,  0xF1450973,  0x0097FFF0,
    0xFFE300CD,  0x074BFC52,  0x5D4869C4,  0xF2680947,  0x00A0FFEE,
    0xFFE300CC,  0x0781FB73,  0x5EA668A6,  0xF384091A,  0x00A8FFEC,
    0xFFE400CA,  0x07B7FA8D,  0x5FFF6780,  0xF49908EB,  0x00B0FFEA,
    0xFFE400C8,  0x07EDF9A0,  0x61506652,  0xF5A808BA,  0x00B6FFE9,
    0xFFE500C5,  0x0822F8AC,  0x629B651C,  0xF6B00888,  0x00BCFFE7
};


const Int32 sbrDecoderFilterbankCoefficients_down_smpl[160] =
{
    0x0000FFEE,  0xFFF0FFEF, 0xFFEEFFED, 0xFFEBFFEA,
    0xFFE9FFE8,  0xFFE7FFE6, 0xFFE6FFE7, 0xFFE7FFE8,
    0xFFEAFFED,  0xFFEFFFF3, 0xFFF7FFFB, 0x00000007,
    0x000D0014,  0x001C0025, 0x002E0037, 0x0041004B,
    0x00560061,  0x006B0076, 0x0080008A, 0x0094009D,
    0x00A500AC,  0x00B200B6, 0x00B800B9, 0x00B700B3,
    0x00AD00A3,  0x00970087, 0x0074005D, 0x00420024,
    0x0001FFDA,  0xFFAFFF7F, 0xFF4BFF12, 0xFED5FE93,
    0x01B301FD,  0x024C029E, 0x02F4034D, 0x03A90408,
    0x046904CC,  0x05300595, 0x05FA065E, 0x06C10722,
    0x078007DA,  0x08300881, 0x08CB090F, 0x094A097C,
    0x09A409C1,  0x09D209D5, 0x09CB09B2, 0x0988094D,
    0x090108A2,  0x082F07A8, 0x070C0659, 0x059104B1,
    0x03B902AA,  0x01810041, 0xFEE7FD74, 0xFBE9FA45,
    0xF887F6B2,  0xF4C4F2BF, 0xF0A4EE72, 0xEC2AE9CF,
    0xE760E4DE,  0xE24CDFA9, 0xDCF9DA3B, 0xD772D4A0,
    0x2E3A311B,  0x33FF36E7, 0x39CE3CB4, 0x3F964273,
    0x45484813,  0x4AD24D84, 0x502552B4, 0x55305795,
    0x59E35C17,  0x5E2F602B, 0x620863C4, 0x655F66D7,
    0x682B6959,  0x6A626B43, 0x6BFC6C8C, 0x6CF46D32,
    0x6D476D32,  0x6CF46C8C, 0x6BFC6B43, 0x6A626959,
    0x682B66D7,  0x655F63C4, 0x6208602B, 0x5E2F5C17,
    0x59E35795,  0x553052B4, 0x50254D84, 0x4AD24813,
    0x45484273,  0x3F963CB4, 0x39CE36E7, 0x33FF311B,
    0xD1C6D4A0,  0xD772DA3B, 0xDCF9DFA9, 0xE24CE4DE,
    0xE760E9CF,  0xEC2AEE72, 0xF0A4F2BF, 0xF4C4F6B2,
    0xF887FA45,  0xFBE9FD74, 0xFEE70041, 0x018102AA,
    0x03B904B1,  0x05910659, 0x070C07A8, 0x082F08A2,
    0x0901094D,  0x098809B2, 0x09CB09D5, 0x09D209C1,
    0x09A4097C,  0x094A090F, 0x08CB0881, 0x083007DA,
    0x07800722,  0x06C1065E, 0x05FA0595, 0x053004CC,
    0x04690408,  0x03A9034D, 0x02F4029E, 0x024C01FD,
    0xFE4DFE93,  0xFED5FF12, 0xFF4BFF7F, 0xFFAFFFDA,
    0x00010024,  0x0042005D, 0x00740087, 0x009700A3,
    0x00AD00B3,  0x00B700B9, 0x00B800B6, 0x00B200AC,
    0x00A5009D,  0x0094008A, 0x00800076, 0x006B0061,
    0x0056004B,  0x00410037, 0x002E0025, 0x001C0014,
    0x000D0007,  0x0000FFFB, 0xFFF7FFF3, 0xFFEFFFED,
    0xFFEAFFE8,  0xFFE7FFE7, 0xFFE6FFE6, 0xFFE7FFE8,
    0xFFE9FFEA,  0xFFEBFFED, 0xFFEEFFEF, 0xFFF0FFEE
};

const Int32 sbrDecoderFilterbankCoefficients_an_filt_LC[155] =
{

    Qfmt27(-0.00079446133872F), Qfmt27(0.02197766364781F), Qfmt27(0.54254182141522F), Qfmt27(-0.47923775873194F),
    Qfmt27(-0.01574239605130F), Qfmt27(-0.00068946163857F), Qfmt27(0.02537571195384F), Qfmt27(0.57449847577240F),
    Qfmt27(-0.44806230039026F), Qfmt27(-0.01291535202742F), Qfmt27(-0.00071286404460F), Qfmt27(0.02892516313544F),
    Qfmt27(0.60657315615086F), Qfmt27(-0.41729436041451F), Qfmt27(-0.01026942774868F), Qfmt27(-0.00077308974337F),
    Qfmt27(0.03262310249845F), Qfmt27(0.63865835544980F), Qfmt27(-0.38701849746199F), Qfmt27(-0.00782586328859F),
    Qfmt27(-0.00083027488297F), Qfmt27(0.03646915244785F), Qfmt27(0.67068416485018F), Qfmt27(-0.35729827194706F),
    Qfmt27(-0.00557215982767F), Qfmt27(-0.00089272089703F), Qfmt27(0.04045671426315F), Qfmt27(0.70254003810627F),
    Qfmt27(-0.32819525024294F), Qfmt27(-0.00351102841332F), Qfmt27(-0.00095851011196F), Qfmt27(0.04455021764484F),
    Qfmt27(0.73415149000395F), Qfmt27(-0.29977591877185F), Qfmt27(-0.00163598204794F), Qfmt27(-0.00101225729839F),
    Qfmt27(0.04873676213679F), Qfmt27(0.76545064960593F), Qfmt27(-0.27208998714049F), Qfmt27(0.00003903936539F),
    Qfmt27(-0.00105230782648F), Qfmt27(0.05300654158217F), Qfmt27(0.79631383686511F), Qfmt27(-0.24519750285673F),
    Qfmt27(0.00154182229475F), Qfmt27(-0.00108630976316F), Qfmt27(0.05732502937107F), Qfmt27(0.82666485395476F),
    Qfmt27(-0.21914753347432F), Qfmt27(0.00286720203220F), Qfmt27(-0.00110794157381F), Qfmt27(0.06167350555855F),
    Qfmt27(0.85641712130638F), Qfmt27(-0.19396671004887F), Qfmt27(0.00402297937976F), Qfmt27(-0.00110360418081F),
    Qfmt27(0.06602157445253F), Qfmt27(0.88547343436495F), Qfmt27(-0.16971665552213F), Qfmt27(0.00500649278750F),
    Qfmt27(-0.00109714405326F), Qfmt27(0.07034096875232F), Qfmt27(0.91376152398903F), Qfmt27(-0.14641770628514F),
    Qfmt27(0.00583386287581F), Qfmt27(-0.00106490281247F), Qfmt27(0.07461825625751F), Qfmt27(0.94117890777861F),
    Qfmt27(-0.12410396326951F), Qfmt27(0.00651097277313F), Qfmt27(-0.00102041023958F), Qfmt27(0.07879625324269F),
    Qfmt27(0.96765488212662F), Qfmt27(-0.10280530739363F), Qfmt27(0.00704839655425F), Qfmt27(-0.00094051141595F),
    Qfmt27(0.08286099010631F), Qfmt27(0.99311573680798F), Qfmt27(-0.08254839941155F), Qfmt27(0.00745513427428F),
    Qfmt27(-0.00084090835475F), Qfmt27(0.08675566213219F), Qfmt27(1.01745066253324F), Qfmt27(-0.06332944781672F),
    Qfmt27(0.00774335382672F), Qfmt27(-0.00072769348801F), Qfmt27(0.09046949018457F), Qfmt27(1.04060828658052F),
    Qfmt27(-0.04518854556363F), Qfmt27(0.00790787636150F), Qfmt27(-0.00057913742435F), Qfmt27(0.09395575430420F),
    Qfmt27(1.06251808919053F), Qfmt27(-0.02811939233087F), Qfmt27(0.00797463714114F), Qfmt27(-0.00040969484059F),
    Qfmt27(0.09716267023308F), Qfmt27(1.08310018709600F), Qfmt27(-0.01212147193047F), Qfmt27(0.00795079915733F),
    Qfmt27(-0.00020454902123F), Qfmt27(0.10007381188066F), Qfmt27(1.10227871198194F), Qfmt27(0.00279527795884F),
    Qfmt27(0.00784545014643F), Qfmt27(0.00001908481202F), Qfmt27(0.10262701466139F), Qfmt27(1.12001978353403F),
    Qfmt27(0.01663452156443F), Qfmt27(0.00766458213130F), Qfmt27(0.00028892665922F), Qfmt27(0.10479373974558F),
    Qfmt27(1.13624787143434F), Qfmt27(0.02941522773279F), Qfmt27(0.00741912981120F), Qfmt27(0.00056943874774F),
    Qfmt27(0.10650970405576F), Qfmt27(1.15091404672203F), Qfmt27(0.04112872592057F), Qfmt27(0.00712664923329F),
    Qfmt27(0.00088238158168F), Qfmt27(0.10776200996423F), Qfmt27(1.16395714324633F), Qfmt27(0.05181934748033F),
    Qfmt27(0.00677868764313F), Qfmt27(0.00121741725989F), Qfmt27(0.10848340171661F), Qfmt27(1.17535833075364F),
    Qfmt27(0.06148559051724F), Qfmt27(0.00639363830229F), Qfmt27(0.00159101288509F), Qfmt27(0.10864412991640F),
    Qfmt27(1.18507099110810F), Qfmt27(0.07014197759039F), Qfmt27(0.00597707038378F), Qfmt27(0.00196610899088F),
    Qfmt27(0.10819451041273F), Qfmt27(1.19306425909871F), Qfmt27(0.07784680399703F), Qfmt27(0.00554476792518F),
    Qfmt27(0.00238550675072F), Qfmt27(0.10709920766553F), Qfmt27(1.19929775892826F), Qfmt27(0.08459352758522F),
    Qfmt27(0.00509233837916F), Qfmt27(0.00280596092809F), Qfmt27(0.10531144797543F), Qfmt27(1.20377455661175F),
    Qfmt27(0.09043115226911F), Qfmt27(0.00463008004888F), Qfmt27(0.00325513071185F), Qfmt27(0.10278145526768F),
    Qfmt27(1.20646855283790F), Qfmt27(0.09539224314440F), Qfmt27(0.00416760958657F)
};



#ifdef HQ_SBR


const Int32 sbrDecoderFilterbankCoefficients_an_filt[155] =
{
    Qfmt27(-0.000561769F),   Qfmt27(+ 0.015540555F),   Qfmt27(+ 0.383635001F),   Qfmt27(-0.338872269F),   Qfmt27(-0.011131555F),
    Qfmt27(-0.000487523F),   Qfmt27(+ 0.017943338F),   Qfmt27(+ 0.406231768F),   Qfmt27(-0.316827891F),   Qfmt27(-0.009132533F),
    Qfmt27(-0.000504071F),   Qfmt27(+ 0.020453179F),   Qfmt27(+ 0.428911992F),   Qfmt27(-0.295071672F),   Qfmt27(-0.007261582F),
    Qfmt27(-0.000546657F),   Qfmt27(+ 0.023068017F),   Qfmt27(+ 0.451599654F),   Qfmt27(-0.273663404F),   Qfmt27(-0.005533721F),
    Qfmt27(-0.000587093F),   Qfmt27(+ 0.025787585F),   Qfmt27(+ 0.474245321F),   Qfmt27(-0.252648031F),   Qfmt27(-0.003940112F),
    Qfmt27(-0.000631249F),   Qfmt27(+ 0.028607217F),   Qfmt27(+ 0.496770825F),   Qfmt27(-0.232069087F),   Qfmt27(-0.002482672F),
    Qfmt27(-0.000677769F),   Qfmt27(+ 0.031501761F),   Qfmt27(+ 0.519123497F),   Qfmt27(-0.211973585F),   Qfmt27(-0.001156814F),
    Qfmt27(-0.000715774F),   Qfmt27(+ 0.034462095F),   Qfmt27(+ 0.541255345F),   Qfmt27(-0.192396675F),   Qfmt27(+ 0.000027605F),
    Qfmt27(-0.000744094F),   Qfmt27(+ 0.037481285F),   Qfmt27(+ 0.563078914F),   Qfmt27(-0.173380817F),   Qfmt27(+ 0.001090233F),
    Qfmt27(-0.000768137F),   Qfmt27(+ 0.040534917F),   Qfmt27(+ 0.584540324F),   Qfmt27(-0.154960707F),   Qfmt27(+ 0.002027418F),
    Qfmt27(-0.000783433F),   Qfmt27(+ 0.043609754F),   Qfmt27(+ 0.605578354F),   Qfmt27(-0.137155176F),   Qfmt27(+ 0.002844676F),
    Qfmt27(-0.000780366F),   Qfmt27(+ 0.046684303F),   Qfmt27(+ 0.626124270F),   Qfmt27(-0.120007798F),   Qfmt27(+ 0.003540125F),
    Qfmt27(-0.000775798F),   Qfmt27(+ 0.049738576F),   Qfmt27(+ 0.646126970F),   Qfmt27(-0.103532953F),   Qfmt27(+ 0.004125164F),
    Qfmt27(-0.000753000F),   Qfmt27(+ 0.052763075F),   Qfmt27(+ 0.665513988F),   Qfmt27(-0.087754754F),   Qfmt27(+ 0.004603953F),
    Qfmt27(-0.000721539F),   Qfmt27(+ 0.055717365F),   Qfmt27(+ 0.684235329F),   Qfmt27(-0.072694330F),   Qfmt27(+ 0.004983969F),
    Qfmt27(-0.000665042F),   Qfmt27(+ 0.058591568F),   Qfmt27(+ 0.702238872F),   Qfmt27(-0.058370533F),   Qfmt27(+ 0.005271576F),
    Qfmt27(-0.000594612F),   Qfmt27(+ 0.061345517F),   Qfmt27(+ 0.719446263F),   Qfmt27(-0.044780682F),   Qfmt27(+ 0.005475378F),
    Qfmt27(-0.000514557F),   Qfmt27(+ 0.063971590F),   Qfmt27(+ 0.735821176F),   Qfmt27(-0.031953127F),   Qfmt27(+ 0.005591713F),
    Qfmt27(-0.000409512F),   Qfmt27(+ 0.066436751F),   Qfmt27(+ 0.751313746F),   Qfmt27(-0.019883413F),   Qfmt27(+ 0.005638920F),
    Qfmt27(-0.000289698F),   Qfmt27(+ 0.068704383F),   Qfmt27(+ 0.765867487F),   Qfmt27(-0.008571175F),   Qfmt27(+ 0.005622064F),
    Qfmt27(-0.000144638F),   Qfmt27(+ 0.070762871F),   Qfmt27(+ 0.779428752F),   Qfmt27(+ 0.001976560F),   Qfmt27(+ 0.005547571F),
    Qfmt27(+ 0.000013495F),   Qfmt27(+ 0.072568258F),   Qfmt27(+ 0.791973584F),   Qfmt27(+ 0.011762383F),   Qfmt27(+ 0.005419678F),
    Qfmt27(+ 0.000204302F),   Qfmt27(+ 0.074100364F),   Qfmt27(+ 0.803448575F),   Qfmt27(+ 0.020799707F),   Qfmt27(+ 0.005246117F),
    Qfmt27(+ 0.000402654F),   Qfmt27(+ 0.075313734F),   Qfmt27(+ 0.813819127F),   Qfmt27(+ 0.029082401F),   Qfmt27(+ 0.005039302F),
    Qfmt27(+ 0.000623938F),   Qfmt27(+ 0.076199248F),   Qfmt27(+ 0.823041989F),   Qfmt27(+ 0.036641812F),   Qfmt27(+ 0.004793256F),
    Qfmt27(+ 0.000860844F),   Qfmt27(+ 0.076709349F),   Qfmt27(+ 0.831103846F),   Qfmt27(+ 0.043476878F),   Qfmt27(+ 0.004520985F),
    Qfmt27(+ 0.001125016F),   Qfmt27(+ 0.076823001F),   Qfmt27(+ 0.837971734F),   Qfmt27(+ 0.049597868F),   Qfmt27(+ 0.004226427F),
    Qfmt27(+ 0.001390249F),   Qfmt27(+ 0.076505072F),   Qfmt27(+ 0.843623828F),   Qfmt27(+ 0.055046003F),   Qfmt27(+ 0.003920743F),
    Qfmt27(+ 0.001686808F),   Qfmt27(+ 0.075730576F),   Qfmt27(+ 0.848031578F),   Qfmt27(+ 0.059816657F),   Qfmt27(+ 0.003600827F),
    Qfmt27(+ 0.001984114F),   Qfmt27(+ 0.074466439F),   Qfmt27(+ 0.851197152F),   Qfmt27(+ 0.063944481F),   Qfmt27(+ 0.003273961F),
    Qfmt27(+ 0.002301725F),   Qfmt27(+ 0.072677464F),   Qfmt27(+ 0.853102095F),   Qfmt27(+ 0.067452502F),   Qfmt27(+ 0.002946945F)
};



#endif  /* HQ_SBR */


#endif  /* AAC_PLUS */
