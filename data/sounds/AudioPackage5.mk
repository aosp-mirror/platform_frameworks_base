#
# Audio Package 5 - Crespo/Soju
#
# Include this file in a product makefile to include these audio files
#
#

PRODUCT_PACKAGES += audio_package5_frameworks_sounds
$(call soong_config_set_bool,frameworks_sounds,use_audio_package5_sounds,true)