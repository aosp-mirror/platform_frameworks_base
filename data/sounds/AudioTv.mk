# Copyright 2017 The Android Open Source Project
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
    $(LOCAL_PATH)/alarms/ogg/Argon.ogg:system/media/audio/alarms/Argon.ogg \
    $(LOCAL_PATH)/alarms/ogg/Barium.ogg:system/media/audio/alarms/Barium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Carbon.ogg:system/media/audio/alarms/Carbon.ogg \
    $(LOCAL_PATH)/alarms/ogg/Cesium.ogg:system/media/audio/alarms/Cesium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Fermium.ogg:system/media/audio/alarms/Fermium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Hassium.ogg:system/media/audio/alarms/Hassium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Helium.ogg:system/media/audio/alarms/Helium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Krypton.ogg:system/media/audio/alarms/Krypton.ogg \
    $(LOCAL_PATH)/alarms/ogg/Neon.ogg:system/media/audio/alarms/Neon.ogg \
    $(LOCAL_PATH)/alarms/ogg/Neptunium.ogg:system/media/audio/alarms/Neptunium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Nobelium.ogg:system/media/audio/alarms/Nobelium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Osmium.ogg:system/media/audio/alarms/Osmium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Oxygen.ogg:system/media/audio/alarms/Oxygen.ogg \
    $(LOCAL_PATH)/alarms/ogg/Platinum.ogg:system/media/audio/alarms/Platinum.ogg \
    $(LOCAL_PATH)/alarms/ogg/Plutonium.ogg:system/media/audio/alarms/Plutonium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Promethium.ogg:system/media/audio/alarms/Promethium.ogg \
    $(LOCAL_PATH)/alarms/ogg/Scandium.ogg:system/media/audio/alarms/Scandium.ogg \
    $(LOCAL_PATH)/effects/ogg/camera_click_48k.ogg:system/media/audio/ui/camera_click.ogg \
    $(LOCAL_PATH)/effects/ogg/camera_focus.ogg:system/media/audio/ui/camera_focus.ogg \
    $(LOCAL_PATH)/effects/ogg/Dock.ogg:system/media/audio/ui/Dock.ogg \
    $(LOCAL_PATH)/effects/ogg/Effect_Tick_48k.ogg:system/media/audio/ui/Effect_Tick.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressDelete_120_48k.ogg:system/media/audio/ui/KeypressDelete.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressInvalid_120_48k.ogg:system/media/audio/ui/KeypressInvalid.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressReturn_120_48k.ogg:system/media/audio/ui/KeypressReturn.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressSpacebar_120_48k.ogg:system/media/audio/ui/KeypressSpacebar.ogg \
    $(LOCAL_PATH)/effects/ogg/KeypressStandard_120_48k.ogg:system/media/audio/ui/KeypressStandard.ogg \
    $(LOCAL_PATH)/effects/ogg/Lock.ogg:system/media/audio/ui/Lock.ogg \
    $(LOCAL_PATH)/effects/ogg/LowBattery.ogg:system/media/audio/ui/LowBattery.ogg \
    $(LOCAL_PATH)/effects/ogg/Trusted_48k.ogg:system/media/audio/ui/Trusted.ogg \
    $(LOCAL_PATH)/effects/ogg/Undock.ogg:system/media/audio/ui/Undock.ogg \
    $(LOCAL_PATH)/effects/ogg/Unlock.ogg:system/media/audio/ui/Unlock.ogg \
    $(LOCAL_PATH)/effects/ogg/VideoRecord_48k.ogg:system/media/audio/ui/VideoRecord.ogg \
    $(LOCAL_PATH)/effects/ogg/VideoStop_48k.ogg:system/media/audio/ui/VideoStop.ogg \
    $(LOCAL_PATH)/effects/ogg/WirelessChargingStarted.ogg:system/media/audio/ui/WirelessChargingStarted.ogg \
    $(LOCAL_PATH)/F1_MissedCall.ogg:system/media/audio/notifications/F1_MissedCall.ogg \
    $(LOCAL_PATH)/F1_New_MMS.ogg:system/media/audio/notifications/F1_New_MMS.ogg \
    $(LOCAL_PATH)/F1_New_SMS.ogg:system/media/audio/notifications/F1_New_SMS.ogg \
    $(LOCAL_PATH)/notifications/Aldebaran.ogg:system/media/audio/notifications/Aldebaran.ogg \
    $(LOCAL_PATH)/notifications/Altair.ogg:system/media/audio/notifications/Altair.ogg \
    $(LOCAL_PATH)/notifications/Antares.ogg:system/media/audio/notifications/Antares.ogg \
    $(LOCAL_PATH)/notifications/arcturus.ogg:system/media/audio/notifications/arcturus.ogg \
    $(LOCAL_PATH)/notifications/Beat_Box_Android.ogg:system/media/audio/notifications/Beat_Box_Android.ogg \
    $(LOCAL_PATH)/notifications/Betelgeuse.ogg:system/media/audio/notifications/Betelgeuse.ogg \
    $(LOCAL_PATH)/notifications/Canopus.ogg:system/media/audio/notifications/Canopus.ogg \
    $(LOCAL_PATH)/notifications/Castor.ogg:system/media/audio/notifications/Castor.ogg \
    $(LOCAL_PATH)/notifications/Cricket.ogg:system/media/audio/notifications/Cricket.ogg \
    $(LOCAL_PATH)/notifications/Deneb.ogg:system/media/audio/notifications/Deneb.ogg \
    $(LOCAL_PATH)/notifications/Doink.ogg:system/media/audio/notifications/Doink.ogg \
    $(LOCAL_PATH)/notifications/Drip.ogg:system/media/audio/notifications/Drip.ogg \
    $(LOCAL_PATH)/notifications/Electra.ogg:system/media/audio/notifications/Electra.ogg \
    $(LOCAL_PATH)/notifications/Fomalhaut.ogg:system/media/audio/notifications/Fomalhaut.ogg \
    $(LOCAL_PATH)/notifications/Heaven.ogg:system/media/audio/notifications/Heaven.ogg \
    $(LOCAL_PATH)/notifications/Merope.ogg:system/media/audio/notifications/Merope.ogg \
    $(LOCAL_PATH)/notifications/moonbeam.ogg:system/media/audio/notifications/moonbeam.ogg \
    $(LOCAL_PATH)/notifications/ogg/Adara.ogg:system/media/audio/notifications/Adara.ogg \
    $(LOCAL_PATH)/notifications/ogg/Alya.ogg:system/media/audio/notifications/Alya.ogg \
    $(LOCAL_PATH)/notifications/ogg/Antimony.ogg:system/media/audio/notifications/Antimony.ogg \
    $(LOCAL_PATH)/notifications/ogg/Arcturus.ogg:system/media/audio/notifications/Arcturus.ogg \
    $(LOCAL_PATH)/notifications/ogg/Argon.ogg:system/media/audio/notifications/Argon.ogg \
    $(LOCAL_PATH)/notifications/ogg/Bellatrix.ogg:system/media/audio/notifications/Bellatrix.ogg \
    $(LOCAL_PATH)/notifications/ogg/Beryllium.ogg:system/media/audio/notifications/Beryllium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Capella.ogg:system/media/audio/notifications/Capella.ogg \
    $(LOCAL_PATH)/notifications/ogg/CetiAlpha.ogg:system/media/audio/notifications/CetiAlpha.ogg \
    $(LOCAL_PATH)/notifications/ogg/Cobalt.ogg:system/media/audio/notifications/Cobalt.ogg \
    $(LOCAL_PATH)/notifications/ogg/Fluorine.ogg:system/media/audio/notifications/Fluorine.ogg \
    $(LOCAL_PATH)/notifications/ogg/Gallium.ogg:system/media/audio/notifications/Gallium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Helium.ogg:system/media/audio/notifications/Helium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Hojus.ogg:system/media/audio/notifications/Hojus.ogg \
    $(LOCAL_PATH)/notifications/ogg/Iridium.ogg:system/media/audio/notifications/Iridium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Krypton.ogg:system/media/audio/notifications/Krypton.ogg \
    $(LOCAL_PATH)/notifications/ogg/Lalande.ogg:system/media/audio/notifications/Lalande.ogg \
    $(LOCAL_PATH)/notifications/ogg/Mira.ogg:system/media/audio/notifications/Mira.ogg \
    $(LOCAL_PATH)/notifications/ogg/Palladium.ogg:system/media/audio/notifications/Palladium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Polaris.ogg:system/media/audio/notifications/Polaris.ogg \
    $(LOCAL_PATH)/notifications/ogg/Pollux.ogg:system/media/audio/notifications/Pollux.ogg \
    $(LOCAL_PATH)/notifications/ogg/Procyon.ogg:system/media/audio/notifications/Procyon.ogg \
    $(LOCAL_PATH)/notifications/ogg/Proxima.ogg:system/media/audio/notifications/Proxima.ogg \
    $(LOCAL_PATH)/notifications/ogg/Radon.ogg:system/media/audio/notifications/Radon.ogg \
    $(LOCAL_PATH)/notifications/ogg/Rubidium.ogg:system/media/audio/notifications/Rubidium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Selenium.ogg:system/media/audio/notifications/Selenium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Shaula.ogg:system/media/audio/notifications/Shaula.ogg \
    $(LOCAL_PATH)/notifications/ogg/Spica.ogg:system/media/audio/notifications/Spica.ogg \
    $(LOCAL_PATH)/notifications/ogg/Strontium.ogg:system/media/audio/notifications/Strontium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Syrma.ogg:system/media/audio/notifications/Syrma.ogg \
    $(LOCAL_PATH)/notifications/ogg/Talitha.ogg:system/media/audio/notifications/Talitha.ogg \
    $(LOCAL_PATH)/notifications/ogg/Tejat.ogg:system/media/audio/notifications/Tejat.ogg \
    $(LOCAL_PATH)/notifications/ogg/Thallium.ogg:system/media/audio/notifications/Thallium.ogg \
    $(LOCAL_PATH)/notifications/ogg/Upsilon.ogg:system/media/audio/notifications/Upsilon.ogg \
    $(LOCAL_PATH)/notifications/ogg/Vega.ogg:system/media/audio/notifications/Vega.ogg \
    $(LOCAL_PATH)/notifications/ogg/Xenon.ogg:system/media/audio/notifications/Xenon.ogg \
    $(LOCAL_PATH)/notifications/ogg/Zirconium.ogg:system/media/audio/notifications/Zirconium.ogg \
    $(LOCAL_PATH)/notifications/pixiedust.ogg:system/media/audio/notifications/pixiedust.ogg \
    $(LOCAL_PATH)/notifications/pizzicato.ogg:system/media/audio/notifications/pizzicato.ogg \
    $(LOCAL_PATH)/notifications/Plastic_Pipe.ogg:system/media/audio/notifications/Plastic_Pipe.ogg \
    $(LOCAL_PATH)/notifications/regulus.ogg:system/media/audio/notifications/regulus.ogg \
    $(LOCAL_PATH)/notifications/sirius.ogg:system/media/audio/notifications/sirius.ogg \
    $(LOCAL_PATH)/notifications/Sirrah.ogg:system/media/audio/notifications/Sirrah.ogg \
    $(LOCAL_PATH)/notifications/SpaceSeed.ogg:system/media/audio/notifications/SpaceSeed.ogg \
    $(LOCAL_PATH)/notifications/TaDa.ogg:system/media/audio/notifications/TaDa.ogg \
    $(LOCAL_PATH)/notifications/Tinkerbell.ogg:system/media/audio/notifications/Tinkerbell.ogg \
    $(LOCAL_PATH)/notifications/tweeters.ogg:system/media/audio/notifications/tweeters.ogg \
    $(LOCAL_PATH)/notifications/vega.ogg:system/media/audio/notifications/vega.ogg
