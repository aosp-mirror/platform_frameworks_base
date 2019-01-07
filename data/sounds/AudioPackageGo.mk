# Copyright 2013 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH := frameworks/base/data/sounds

# Ring_Classic_02 : Bell Phone
# Ring_Synth_02 : Chimey Phone
# Ring_Digital_02 : Digital Phone
# Ring_Synth_04 : Flutey Phone
# Alarm_Beep_03 : Beep Beep Beep
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/notifications/ogg/Alya.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/notifications/Alya.ogg \
    $(LOCAL_PATH)/notifications/ogg/Argon.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/notifications/Argon.ogg \
    $(LOCAL_PATH)/notifications/Canopus.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/notifications/Canopus.ogg \
    $(LOCAL_PATH)/notifications/Deneb.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/notifications/Deneb.ogg \
    $(LOCAL_PATH)/newwavelabs/Highwire.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/notifications/Highwire.ogg \
    $(LOCAL_PATH)/notifications/ogg/Iridium.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/notifications/Iridium.ogg \
    $(LOCAL_PATH)/notifications/pixiedust.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/notifications/pixiedust.ogg \
    $(LOCAL_PATH)/notifications/ogg/Talitha.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/notifications/Talitha.ogg \
    $(LOCAL_PATH)/Ring_Classic_02.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ringtones/Ring_Classic_02.ogg \
    $(LOCAL_PATH)/Ring_Synth_02.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ringtones/Ring_Synth_02.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Cygnus.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ringtones/Cygnus.ogg \
    $(LOCAL_PATH)/Ring_Digital_02.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ringtones/Ring_Digital_02.ogg \
    $(LOCAL_PATH)/Ring_Synth_04.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ringtones/Ring_Synth_04.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Kuma.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ringtones/Kuma.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Themos.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ringtones/Themos.ogg \
    $(LOCAL_PATH)/Alarm_Classic.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/alarms/Alarm_Classic.ogg \
    $(LOCAL_PATH)/alarms/ogg/Argon.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/alarms/Argon.ogg \
    $(LOCAL_PATH)/alarms/ogg/Platinum.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/alarms/Platinum.ogg \
    $(LOCAL_PATH)/Alarm_Beep_03.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/alarms/Alarm_Beep_03.ogg \
    $(LOCAL_PATH)/alarms/ogg/Helium.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/alarms/Helium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Oxygen.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/alarms/Oxygen.ogg \
    $(LOCAL_PATH)/effects/ogg/Effect_Tick.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ui/Effect_Tick.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressStandard.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ui/KeypressStandard.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressSpacebar.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ui/KeypressSpacebar.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressDelete.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ui/KeypressDelete.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressInvalid.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ui/KeypressInvalid.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressReturn.ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ui/KeypressReturn.ogg \
