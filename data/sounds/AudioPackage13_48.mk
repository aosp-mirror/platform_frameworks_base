#
# Audio Package 13 - L (48kHz)
#
# Include this file in a product makefile to include these audio files
#
#

LOCAL_PATH := frameworks/base/data/sounds

# Simple files that do not require renaming
ALARM_FILES := Alarm1 Alarm2 Alarm3 Alarm4 Alarm5 Alarm6 Alarm7 Alarm8 Timer
NOTIFICATION_FILES := Notification1 Notification2 Notification3 Notification4 \
	Notification5 Notification6 Notification7 Notification8 Notification9 \
	Notification10 Notification11
RINGTONE_FILES := Ringtone1 Ringtone2 Ringtone3 Ringtone4 Ringtone5 Ringtone6 \
	Ringtone7 Ringtone8 Ringtone9 Ringtone10 Ringtone11 Ringtone12
EFFECT_FILES := Effect_Tick KeypressReturn KeypressInvalid KeypressDelete KeypressSpacebar KeypressStandard \
	camera_click Lock Unlock Trusted
MATERIAL_EFFECT_FILES := VideoRecord WirelessChargingStarted LowBattery

PRODUCT_COPY_FILES += $(foreach fn,$(ALARM_FILES),\
	$(LOCAL_PATH)/alarms/material/ogg/$(fn)_48k.ogg:system/media/audio/alarms/$(fn).ogg)

PRODUCT_COPY_FILES += $(foreach fn,$(NOTIFICATION_FILES),\
	$(LOCAL_PATH)/notifications/material/ogg/$(fn)_48k.ogg:system/media/audio/notifications/$(fn).ogg)

PRODUCT_COPY_FILES += $(foreach fn,$(RINGTONE_FILES),\
	$(LOCAL_PATH)/ringtones/material/ogg/$(fn)_48k.ogg:system/media/audio/ringtones/$(fn).ogg)

PRODUCT_COPY_FILES += $(foreach fn,$(EFFECT_FILES),\
	$(LOCAL_PATH)/effects/ogg/$(fn)_48k.ogg:system/media/audio/ui/$(fn).ogg)
PRODUCT_COPY_FILES += $(foreach fn,$(MATERIAL_EFFECT_FILES),\
	$(LOCAL_PATH)/effects/material/ogg/$(fn)_48k.ogg:system/media/audio/ui/$(fn).ogg)

# no gold-plated version yet
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/effects/ogg/camera_focus.ogg:system/media/audio/ui/camera_focus.ogg \
    $(LOCAL_PATH)/effects/ogg/Dock.ogg:system/media/audio/ui/Dock.ogg \
    $(LOCAL_PATH)/effects/ogg/Undock.ogg:system/media/audio/ui/Undock.ogg