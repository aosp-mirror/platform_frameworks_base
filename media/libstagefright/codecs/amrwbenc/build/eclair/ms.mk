#/*
# ** Copyright 2003-2010, VisualOn, Inc.
# **
# ** Licensed under the Apache License, Version 2.0 (the "License");
# ** you may not use this file except in compliance with the License.
# ** You may obtain a copy of the License at
# **
# **     http://www.apache.org/licenses/LICENSE-2.0
# **
# ** Unless required by applicable law or agreed to in writing, software
# ** distributed under the License is distributed on an "AS IS" BASIS,
# ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# ** See the License for the specific language governing permissions and
# ** limitations under the License.
# */
# please list all directories that all source files relative with your module(.h .c .cpp) locate
VOSRCDIR:=../../../inc \
          ../../../src \
	  ../../../../../Include

# please list all objects needed by your target here
OBJS:= autocorr.o az_isp.o bits.o c2t64fx.o c4t64fx.o convolve.o cor_h_x.o decim54.o \
       deemph.o dtx.o g_pitch.o gpclip.o homing.o hp400.o hp50.o hp6k.o hp_wsp.o \
       int_lpc.o isp_az.o isp_isf.o lag_wind.o levinson.o log2.o lp_dec2.o math_op.o mem_align.o \
       oper_32b.o p_med_ol.o pit_shrp.o pitch_f4.o pred_lt4.o preemph.o q_gain2.o q_pulse.o \
       qisf_ns.o qpisf_2s.o random.o residu.o scale.o stream.o syn_filt.o updt_tar.o util.o \
       voAMRWBEnc.o voicefac.o wb_vad.o weight_a.o


ifeq ($(VOTT), v5)
OBJS += cor_h_vec_opt.o Deemph_32_opt.o Dot_p_opt.o Filt_6k_7k_opt.o residu_asm_opt.o \
       scale_sig_opt.o Syn_filt_32_opt.o syn_filt_opt.o pred_lt4_1_opt.o convolve_opt.o \
       Norm_Corr_opt.o
VOSRCDIR+= ../../../src/asm/ARMV5E
endif

ifeq ($(VOTT), v7)
OBJS+= cor_h_vec_neon.o Deemph_32_neon.o Dot_p_neon.o Filt_6k_7k_neon.o residu_asm_neon.o \
       scale_sig_neon.o Syn_filt_32_neon.o syn_filt_neon.o pred_lt4_1_neon.o convolve_neon.o \
       Norm_Corr_neon.o
VOSRCDIR+= ../../../src/asm/ARMV7
endif

