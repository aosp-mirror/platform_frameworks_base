LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	analysis_sub_band.cpp \
 	apply_ms_synt.cpp \
 	apply_tns.cpp \
 	buf_getbits.cpp \
 	byte_align.cpp \
 	calc_auto_corr.cpp \
 	calc_gsfb_table.cpp \
 	calc_sbr_anafilterbank.cpp \
 	calc_sbr_envelope.cpp \
 	calc_sbr_synfilterbank.cpp \
 	check_crc.cpp \
 	dct16.cpp \
 	dct64.cpp \
 	decode_huff_cw_binary.cpp \
 	decode_noise_floorlevels.cpp \
 	deinterleave.cpp \
 	digit_reversal_tables.cpp \
 	dst16.cpp \
 	dst32.cpp \
 	dst8.cpp \
 	esc_iquant_scaling.cpp \
 	extractframeinfo.cpp \
 	fft_rx4_long.cpp \
 	fft_rx4_short.cpp \
 	fft_rx4_tables_fxp.cpp \
 	find_adts_syncword.cpp \
 	fwd_long_complex_rot.cpp \
 	fwd_short_complex_rot.cpp \
 	gen_rand_vector.cpp \
 	get_adif_header.cpp \
 	get_adts_header.cpp \
 	get_audio_specific_config.cpp \
 	get_dse.cpp \
 	get_ele_list.cpp \
 	get_ga_specific_config.cpp \
 	get_ics_info.cpp \
 	get_prog_config.cpp \
 	get_pulse_data.cpp \
 	get_sbr_bitstream.cpp \
 	get_sbr_startfreq.cpp \
 	get_sbr_stopfreq.cpp \
 	get_tns.cpp \
 	getfill.cpp \
 	getgroup.cpp \
 	getics.cpp \
 	getmask.cpp \
 	hcbtables_binary.cpp \
 	huffcb.cpp \
 	huffdecode.cpp \
 	hufffac.cpp \
 	huffspec_fxp.cpp \
 	idct16.cpp \
 	idct32.cpp \
 	idct8.cpp \
 	imdct_fxp.cpp \
 	infoinit.cpp \
 	init_sbr_dec.cpp \
 	intensity_right.cpp \
 	inv_long_complex_rot.cpp \
 	inv_short_complex_rot.cpp \
 	iquant_table.cpp \
 	long_term_prediction.cpp \
 	long_term_synthesis.cpp \
 	lt_decode.cpp \
 	mdct_fxp.cpp \
 	mdct_tables_fxp.cpp \
 	mdst.cpp \
 	mix_radix_fft.cpp \
 	ms_synt.cpp \
 	pns_corr.cpp \
 	pns_intensity_right.cpp \
 	pns_left.cpp \
 	ps_all_pass_filter_coeff.cpp \
 	ps_all_pass_fract_delay_filter.cpp \
 	ps_allocate_decoder.cpp \
 	ps_applied.cpp \
 	ps_bstr_decoding.cpp \
 	ps_channel_filtering.cpp \
 	ps_decode_bs_utils.cpp \
 	ps_decorrelate.cpp \
 	ps_fft_rx8.cpp \
 	ps_hybrid_analysis.cpp \
 	ps_hybrid_filter_bank_allocation.cpp \
 	ps_hybrid_synthesis.cpp \
 	ps_init_stereo_mixing.cpp \
 	ps_pwr_transient_detection.cpp \
 	ps_read_data.cpp \
 	ps_stereo_processing.cpp \
 	pulse_nc.cpp \
 	pv_div.cpp \
 	pv_log2.cpp \
 	pv_normalize.cpp \
 	pv_pow2.cpp \
 	pv_sine.cpp \
 	pv_sqrt.cpp \
 	pvmp4audiodecoderconfig.cpp \
 	pvmp4audiodecoderframe.cpp \
 	pvmp4audiodecodergetmemrequirements.cpp \
 	pvmp4audiodecoderinitlibrary.cpp \
 	pvmp4audiodecoderresetbuffer.cpp \
 	q_normalize.cpp \
 	qmf_filterbank_coeff.cpp \
 	sbr_aliasing_reduction.cpp \
 	sbr_applied.cpp \
 	sbr_code_book_envlevel.cpp \
 	sbr_crc_check.cpp \
 	sbr_create_limiter_bands.cpp \
 	sbr_dec.cpp \
 	sbr_decode_envelope.cpp \
 	sbr_decode_huff_cw.cpp \
 	sbr_downsample_lo_res.cpp \
 	sbr_envelope_calc_tbl.cpp \
 	sbr_envelope_unmapping.cpp \
 	sbr_extract_extended_data.cpp \
 	sbr_find_start_andstop_band.cpp \
 	sbr_generate_high_freq.cpp \
 	sbr_get_additional_data.cpp \
 	sbr_get_cpe.cpp \
 	sbr_get_dir_control_data.cpp \
 	sbr_get_envelope.cpp \
 	sbr_get_header_data.cpp \
 	sbr_get_noise_floor_data.cpp \
 	sbr_get_sce.cpp \
 	sbr_inv_filt_levelemphasis.cpp \
 	sbr_open.cpp \
 	sbr_read_data.cpp \
 	sbr_requantize_envelope_data.cpp \
 	sbr_reset_dec.cpp \
 	sbr_update_freq_scale.cpp \
 	set_mc_info.cpp \
 	sfb.cpp \
 	shellsort.cpp \
 	synthesis_sub_band.cpp \
 	tns_ar_filter.cpp \
 	tns_decode_coef.cpp \
 	tns_inv_filter.cpp \
 	trans4m_freq_2_time_fxp.cpp \
 	trans4m_time_2_freq_fxp.cpp \
 	unpack_idx.cpp \
 	window_tables_fxp.cpp \
 	pvmp4setaudioconfig.cpp \

LOCAL_CFLAGS := -DAAC_PLUS -DHQ_SBR -DPARAMETRICSTEREO -DOSCL_IMPORT_REF= -DOSCL_EXPORT_REF= -DOSCL_UNUSED_ARG=

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \

LOCAL_ARM_MODE := arm

LOCAL_MODULE := libstagefright_aacdec

include $(BUILD_STATIC_LIBRARY)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftAAC.cpp

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \

LOCAL_CFLAGS := -DOSCL_IMPORT_REF=

LOCAL_STATIC_LIBRARIES := \
        libstagefright_aacdec

LOCAL_SHARED_LIBRARIES := \
        libstagefright_omx libstagefright_foundation libutils

LOCAL_MODULE := libstagefright_soft_aacdec
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
