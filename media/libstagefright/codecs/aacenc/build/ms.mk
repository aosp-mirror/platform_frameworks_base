#/*
#** Copyright 2003-2010, VisualOn, Inc.
#**
#** Licensed under the Apache License, Version 2.0 (the "License");
#** you may not use this file except in compliance with the License.
#** You may obtain a copy of the License at
#**
#**     http://www.apache.org/licenses/LICENSE-2.0
#**
#** Unless required by applicable law or agreed to in writing, software
#** distributed under the License is distributed on an "AS IS" BASIS,
#** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#** See the License for the specific language governing permissions and
#** limitations under the License.
#*/


# please list all objects needed by your target here
OBJS:=basicop2.o oper_32b.o aac_rom.o aacenc.o aacenc_core.o adj_thr.o \
			band_nrg.o bit_cnt.o bitbuffer.o bitenc.o block_switch.o channel_map.o \
			dyn_bits.o grp_data.o interface.o line_pe.o memalign.o ms_stereo.o \
			pre_echo_control.o psy_configuration.o psy_main.o qc_main.o quantize.o sf_estim.o \
			spreading.o stat_bits.o tns.o transform.o

# please list all directories that all source files relative with your module(.h .c .cpp) locate
VOSRCDIR:=../../../src \
					../../../inc \
					../../../basic_op\
					../../../../../Include

ifeq ($(VOTT), v5)
OBJS+= AutoCorrelation_v5.o band_nrg_v5.o CalcWindowEnergy_v5.o \
				PrePostMDCT_v5.o R4R8First_v5.o Radix4FFT_v5.o
VOSRCDIR+= ../../../src/asm/ARMV5E/
endif

ifeq ($(VOTT), v7)
OBJS+= AutoCorrelation_v5.o band_nrg_v5.o CalcWindowEnergy_v5.o \
			 PrePostMDCT_v7.o R4R8First_v7.o Radix4FFT_v7.o
VOSRCDIR+= ../../../src/asm/ARMV5E/
VOSRCDIR+= ../../../src/asm/ARMV7/
endif
