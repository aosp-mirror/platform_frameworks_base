# Copyright 2016 The Pure Experience Project
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

LOCAL_PATH := frameworks/base/data/sounds/

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)material/alarms/Argon.ogg:system/media/audio/alarms/Argon.ogg \
    $(LOCAL_PATH)material/alarms/Awaken.ogg:system/media/audio/alarms/Awaken.ogg \
    $(LOCAL_PATH)material/alarms/Bounce.ogg:system/media/audio/alarms/Bounce.ogg \
    $(LOCAL_PATH)material/alarms/Carbon.ogg:system/media/audio/alarms/Carbon.ogg \
    $(LOCAL_PATH)material/alarms/Drip.ogg:system/media/audio/alarms/Drip.ogg \
    $(LOCAL_PATH)material/alarms/Gallop.ogg:system/media/audio/alarms/Gallop.ogg \
    $(LOCAL_PATH)material/alarms/Helium.ogg:system/media/audio/alarms/Helium.ogg \
    $(LOCAL_PATH)material/alarms/Krypton.ogg:system/media/audio/alarms/Krypton.ogg \
    $(LOCAL_PATH)material/alarms/Neon.ogg:system/media/audio/alarms/Neon.ogg \
    $(LOCAL_PATH)material/alarms/Nudge.ogg:system/media/audio/alarms/Nudge.ogg \
    $(LOCAL_PATH)material/alarms/Orbit.ogg:system/media/audio/alarms/Orbit.ogg \
    $(LOCAL_PATH)material/alarms/Osmium.ogg:system/media/audio/alarms/Osmium.ogg \
    $(LOCAL_PATH)material/alarms/Oxygen.ogg:system/media/audio/alarms/Oxygen.ogg \
    $(LOCAL_PATH)material/alarms/Platinum.ogg:system/media/audio/alarms/Platinum.ogg \
    $(LOCAL_PATH)material/alarms/Rise.ogg:system/media/audio/alarms/Rise.ogg \
    $(LOCAL_PATH)material/alarms/Sway.ogg:system/media/audio/alarms/Sway.ogg \
    $(LOCAL_PATH)material/alarms/Timer.ogg:system/media/audio/alarms/Timer.ogg \
    $(LOCAL_PATH)material/alarms/Wag.ogg:system/media/audio/alarms/Wag.ogg \
    $(LOCAL_PATH)material/effects/audio_end.ogg:system/media/audio/ui/audio_end.ogg \
    $(LOCAL_PATH)material/effects/audio_initiate.ogg:system/media/audio/ui/audio_initiate.ogg \
    $(LOCAL_PATH)material/effects/camera_click.ogg:system/media/audio/ui/camera_click.ogg \
    $(LOCAL_PATH)material/effects/camera_focus.ogg:system/media/audio/ui/camera_focus.ogg \
    $(LOCAL_PATH)material/effects/Dock.ogg:system/media/audio/ui/Dock.ogg \
    $(LOCAL_PATH)material/effects/Effect_Tick.ogg:system/media/audio/ui/Effect_Tick.ogg \
    $(LOCAL_PATH)material/effects/KeypressDelete.ogg:system/media/audio/ui/KeypressDelete.ogg \
    $(LOCAL_PATH)material/effects/KeypressInvalid.ogg:system/media/audio/ui/KeypressInvalid.ogg \
    $(LOCAL_PATH)material/effects/KeypressReturn.ogg:system/media/audio/ui/KeypressReturn.ogg \
    $(LOCAL_PATH)material/effects/KeypressSpacebar.ogg:system/media/audio/ui/KeypressSpacebar.ogg \
    $(LOCAL_PATH)material/effects/KeypressStandard.ogg:system/media/audio/ui/KeypressStandard.ogg \
    $(LOCAL_PATH)material/effects/Lock.ogg:system/media/audio/ui/Lock.ogg \
    $(LOCAL_PATH)material/effects/LowBattery.ogg:system/media/audio/ui/LowBattery.ogg \
    $(LOCAL_PATH)material/effects/NFCFailure.ogg:system/media/audio/ui/NFCFailure.ogg \
    $(LOCAL_PATH)material/effects/NFCInitiated.ogg:system/media/audio/ui/NFCInitiated.ogg \
    $(LOCAL_PATH)material/effects/NFCSuccess.ogg:system/media/audio/ui/NFCSuccess.ogg \
    $(LOCAL_PATH)material/effects/NFCTransferComplete.ogg:system/media/audio/ui/NFCTransferComplete.ogg \
    $(LOCAL_PATH)material/effects/NFCTransferInitiated.ogg:system/media/audio/ui/NFCTransferInitiated.ogg \
    $(LOCAL_PATH)material/effects/Trusted.ogg:system/media/audio/ui/Trusted.ogg \
    $(LOCAL_PATH)material/effects/Undock.ogg:system/media/audio/ui/Undock.ogg \
    $(LOCAL_PATH)material/effects/Unlock.ogg:system/media/audio/ui/Unlock.ogg \
    $(LOCAL_PATH)material/effects/VideoRecord.ogg:system/media/audio/ui/VideoRecord.ogg \
    $(LOCAL_PATH)material/effects/VideoStop.ogg:system/media/audio/ui/VideoStop.ogg \
    $(LOCAL_PATH)material/effects/WirelessChargingStarted.ogg:system/media/audio/ui/WirelessChargingStarted.ogg \
    $(LOCAL_PATH)material/notifications/Ariel.ogg:system/media/audio/notifications/Ariel.ogg \
    $(LOCAL_PATH)material/notifications/Carme.ogg:system/media/audio/notifications/Carme.ogg \
    $(LOCAL_PATH)material/notifications/Ceres.ogg:system/media/audio/notifications/Ceres.ogg \
    $(LOCAL_PATH)material/notifications/Elara.ogg:system/media/audio/notifications/Elara.ogg \
    $(LOCAL_PATH)material/notifications/Europa.ogg:system/media/audio/notifications/Europa.ogg \
    $(LOCAL_PATH)material/notifications/Iapetus.ogg:system/media/audio/notifications/Iapetus.ogg \
    $(LOCAL_PATH)material/notifications/Io.ogg:system/media/audio/notifications/Io.ogg \
    $(LOCAL_PATH)material/notifications/Rhea.ogg:system/media/audio/notifications/Rhea.ogg \
    $(LOCAL_PATH)material/notifications/Salacia.ogg:system/media/audio/notifications/Salacia.ogg \
    $(LOCAL_PATH)material/notifications/Tethys.ogg:system/media/audio/notifications/Tethys.ogg \
    $(LOCAL_PATH)material/notifications/Titan.ogg:system/media/audio/notifications/Titan.ogg \
    $(LOCAL_PATH)material/ringtones/Atria.ogg:system/media/audio/ringtones/Atria.ogg \
    $(LOCAL_PATH)material/ringtones/Callisto.ogg:system/media/audio/ringtones/Callisto.ogg \
    $(LOCAL_PATH)material/ringtones/Dione.ogg:system/media/audio/ringtones/Dione.ogg \
    $(LOCAL_PATH)material/ringtones/Ganymede.ogg:system/media/audio/ringtones/Ganymede.ogg \
    $(LOCAL_PATH)material/ringtones/Luna.ogg:system/media/audio/ringtones/Luna.ogg \
    $(LOCAL_PATH)material/ringtones/Oberon.ogg:system/media/audio/ringtones/Oberon.ogg \
    $(LOCAL_PATH)material/ringtones/Phobos.ogg:system/media/audio/ringtones/Phobos.ogg \
    $(LOCAL_PATH)material/ringtones/Pyxis.ogg:system/media/audio/ringtones/Pyxis.ogg \
    $(LOCAL_PATH)material/ringtones/Sedna.ogg:system/media/audio/ringtones/Sedna.ogg \
    $(LOCAL_PATH)material/ringtones/Titania.ogg:system/media/audio/ringtones/Titania.ogg \
    $(LOCAL_PATH)material/ringtones/Triton.ogg:system/media/audio/ringtones/Triton.ogg \
    $(LOCAL_PATH)material/ringtones/Umbriel.ogg:system/media/audio/ringtones/Umbriel.ogg \
