#
# Audio Package 12 - K (48kHz)
#
# Include this file in a product makefile to include these audio files
#
#

LOCAL_PATH := frameworks/base/data/sounds

# Simple files that do not require renaming
ALARM_FILES := Argon Carbon Helium Krypton Neon Oxygen Osmium Platinum
NOTIFICATION_FILES := Ariel Ceres Carme Elara Europa Iapetus Io Rhea Salacia Titan Tethys
RINGTONE_FILES := Callisto Dione Ganymede Luna Oberon Phobos Sedna Titania Triton Umbriel Yafarkencaaaarnt
EFFECT_FILES := Effect_Tick KeypressReturn KeypressInvalid KeypressDelete KeypressSpacebar KeypressStandard \
	Lock Unlock Trusted
MATERIAL_EFFECT_FILES := camera_click VideoRecord LowBattery WirelessChargingStarted VideoStop

# Alarms not yet available in 48 kHz
PRODUCT_COPY_FILES += $(foreach fn,$(ALARM_FILES),\
	$(LOCAL_PATH)/alarms/ogg/$(fn).ogg:system/media/audio/alarms/$(fn).ogg)

PRODUCT_COPY_FILES += $(foreach fn,$(NOTIFICATION_FILES),\
	$(LOCAL_PATH)/notifications/ogg/$(fn)_48k.ogg:system/media/audio/notifications/$(fn).ogg)

PRODUCT_COPY_FILES += $(foreach fn,$(RINGTONE_FILES),\
	$(LOCAL_PATH)/ringtones/ogg/$(fn)_48k.ogg:system/media/audio/ringtones/$(fn).ogg)

PRODUCT_COPY_FILES += $(foreach fn,$(EFFECT_FILES),\
	$(LOCAL_PATH)/effects/ogg/$(fn)_48k.ogg:system/media/audio/ui/$(fn).ogg)
PRODUCT_COPY_FILES += $(foreach fn,$(MATERIAL_EFFECT_FILES),\
	$(LOCAL_PATH)/effects/material/ogg/$(fn)_48k.ogg:system/media/audio/ui/$(fn).ogg)

# no gold-plated version yet
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/effects/ogg/camera_focus.ogg:system/media/audio/ui/camera_focus.ogg \
    $(LOCAL_PATH)/effects/ogg/Dock.ogg:system/media/audio/ui/Dock.ogg \
    $(LOCAL_PATH)/effects/ogg/Undock.ogg:system/media/audio/ui/Undock.ogg
