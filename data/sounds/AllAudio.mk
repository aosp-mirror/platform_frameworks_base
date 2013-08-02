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

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/Alarm_Beep_01.ogg:system/media/audio/alarms/Alarm_Beep_01.ogg \
    $(LOCAL_PATH)/Alarm_Beep_02.ogg:system/media/audio/alarms/Alarm_Beep_02.ogg \
    $(LOCAL_PATH)/Alarm_Beep_03.ogg:system/media/audio/alarms/Alarm_Beep_03.ogg \
    $(LOCAL_PATH)/Alarm_Buzzer.ogg:system/media/audio/alarms/Alarm_Buzzer.ogg \
    $(LOCAL_PATH)/Alarm_Classic.ogg:system/media/audio/alarms/Alarm_Classic.ogg \
    $(LOCAL_PATH)/Alarm_Rooster_02.ogg:system/media/audio/alarms/Alarm_Rooster_02.ogg \
    $(LOCAL_PATH)/alarms/ogg/Fermium.ogg:system/media/audio/alarms/Fermium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Cesium.ogg:system/media/audio/alarms/Cesium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Adara.ogg:system/media/audio/notifications/Adara.ogg \
    $(LOCAL_PATH)/notifications/ogg/Alya.ogg:system/media/audio/notifications/Alya.ogg \
    $(LOCAL_PATH)/notifications/ogg/Antimony.ogg:system/media/audio/notifications/Antimony.ogg \
    $(LOCAL_PATH)/notifications/ogg/Arcturus.ogg:system/media/audio/notifications/Arcturus.ogg \
    $(LOCAL_PATH)/notifications/ogg/Fluorine.ogg:system/media/audio/notifications/Fluorine.ogg \
    $(LOCAL_PATH)/notifications/ogg/Hojus.ogg:system/media/audio/notifications/Hojus.ogg \
    $(LOCAL_PATH)/notifications/ogg/Krypton.ogg:system/media/audio/notifications/Krypton.ogg \
    $(LOCAL_PATH)/notifications/ogg/Mira.ogg:system/media/audio/notifications/Mira.ogg \
    $(LOCAL_PATH)/notifications/ogg/Pollux.ogg:system/media/audio/notifications/Pollux.ogg \
    $(LOCAL_PATH)/notifications/ogg/Procyon.ogg:system/media/audio/notifications/Procyon.ogg \
    $(LOCAL_PATH)/notifications/ogg/Proxima.ogg:system/media/audio/notifications/Proxima.ogg \
    $(LOCAL_PATH)/notifications/ogg/Spica.ogg:system/media/audio/notifications/Spica.ogg \
    $(LOCAL_PATH)/notifications/ogg/Talitha.ogg:system/media/audio/notifications/Talitha.ogg \
    $(LOCAL_PATH)/notifications/ogg/Tejat.ogg:system/media/audio/notifications/Tejat.ogg \
    $(LOCAL_PATH)/notifications/ogg/Antares.ogg:system/media/audio/notifications/Antares.ogg \
    $(LOCAL_PATH)/notifications/Cricket.ogg:system/media/audio/notifications/Cricket.ogg \
    $(LOCAL_PATH)/notifications/moonbeam.ogg:system/media/audio/notifications/moonbeam.ogg \
    $(LOCAL_PATH)/newwavelabs/OnTheHunt.ogg:system/media/audio/notifications/OnTheHunt.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Aquila.ogg:system/media/audio/ringtones/Aquila.ogg \
    $(LOCAL_PATH)/ringtones/CANISMAJOR.ogg:system/media/audio/ringtones/CANISMAJOR.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Carina.ogg:system/media/audio/ringtones/Carina.ogg \
    $(LOCAL_PATH)/ringtones/FreeFlight.ogg:system/media/audio/ringtones/FreeFlight.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Girtab.ogg:system/media/audio/ringtones/Girtab.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Hydra.ogg:system/media/audio/ringtones/Hydra.ogg \
    $(LOCAL_PATH)/ringtones/Lyra.ogg:system/media/audio/ringtones/Lyra.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Orion.ogg:system/media/audio/ringtones/Orion.ogg \
    $(LOCAL_PATH)/newwavelabs/Playa.ogg:system/media/audio/ringtones/Playa.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Pyxis.ogg:system/media/audio/ringtones/Pyxis.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Rigel.ogg:system/media/audio/ringtones/Rigel.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Solarium.ogg:system/media/audio/ringtones/Solarium.ogg \
    $(LOCAL_PATH)/ringtones/ogg/Themos.ogg:system/media/audio/ringtones/Themos.ogg \
    $(LOCAL_PATH)/ringtones/Vespa.ogg:system/media/audio/ringtones/Vespa.ogg \
    $(LOCAL_PATH)/ringtones/Machina.ogg:system/media/audio/ringtones/Machina.ogg \
    $(LOCAL_PATH)/ringtones/Scarabaeus.ogg:system/media/audio/ringtones/Scarabaeus.ogg \
    $(LOCAL_PATH)/effects/ogg/Dock.ogg:system/media/audio/ui/Dock.ogg \
    $(LOCAL_PATH)/effects/ogg/Effect_Tick_48k.ogg:system/media/audio/ui/Effect_Tick.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressDelete_120_48k.ogg:system/media/audio/ui/KeypressDelete.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressReturn_120_48k.ogg:system/media/audio/ui/KeypressReturn.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressSpacebar_120_48k.ogg:system/media/audio/ui/KeypressSpacebar.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressStandard_120_48k.ogg:system/media/audio/ui/KeypressStandard.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressInvalid_120_48k.ogg:system/media/audio/ui/KeypressInvalid.ogg \
    $(LOCAL_PATH)/effects/ogg/Lock.ogg:system/media/audio/ui/Lock.ogg \
    $(LOCAL_PATH)/effects/ogg/LowBattery.ogg:system/media/audio/ui/LowBattery.ogg \
    $(LOCAL_PATH)/effects/ogg/Undock.ogg:system/media/audio/ui/Undock.ogg \
    $(LOCAL_PATH)/effects/ogg/Unlock.ogg:system/media/audio/ui/Unlock.ogg \
    $(LOCAL_PATH)/effects/ogg/VideoRecord_48k.ogg:system/media/audio/ui/VideoRecord.ogg \
    $(LOCAL_PATH)/effects/ogg/WirelessChargingStarted.ogg:system/media/audio/ui/WirelessChargingStarted.ogg \
    $(LOCAL_PATH)/effects/ogg/camera_click_48k.ogg:system/media/audio/ui/camera_click.ogg \
    $(LOCAL_PATH)/effects/ogg/camera_focus.ogg:system/media/audio/ui/camera_focus.ogg
