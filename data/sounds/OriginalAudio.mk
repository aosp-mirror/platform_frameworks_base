#
# Original audio package that shipped on G1
#
# This file is included from core.mk so that all devices will have these sounds
#
# TODO: Clean up for future releases
#

LOCAL_PATH:= frameworks/base/data/sounds

PRODUCT_COPY_FILES += \
	$(LOCAL_PATH)/F1_MissedCall.ogg:system/media/audio/notifications/F1_MissedCall.ogg \
	$(LOCAL_PATH)/F1_New_MMS.ogg:system/media/audio/notifications/F1_New_MMS.ogg \
	$(LOCAL_PATH)/F1_New_SMS.ogg:system/media/audio/notifications/F1_New_SMS.ogg \
	$(LOCAL_PATH)/Alarm_Buzzer.ogg:system/media/audio/alarms/Alarm_Buzzer.ogg \
	$(LOCAL_PATH)/Alarm_Beep_01.ogg:system/media/audio/alarms/Alarm_Beep_01.ogg \
	$(LOCAL_PATH)/Alarm_Beep_02.ogg:system/media/audio/alarms/Alarm_Beep_02.ogg \
	$(LOCAL_PATH)/Alarm_Classic.ogg:system/media/audio/alarms/Alarm_Classic.ogg \
	$(LOCAL_PATH)/Alarm_Beep_03.ogg:system/media/audio/alarms/Alarm_Beep_03.ogg \
	$(LOCAL_PATH)/Alarm_Rooster_02.ogg:system/media/audio/alarms/Alarm_Rooster_02.ogg \
	$(LOCAL_PATH)/Ring_Classic_02.ogg:system/media/audio/ringtones/Ring_Classic_02.ogg \
	$(LOCAL_PATH)/Ring_Digital_02.ogg:system/media/audio/ringtones/Ring_Digital_02.ogg \
	$(LOCAL_PATH)/Ring_Synth_04.ogg:system/media/audio/ringtones/Ring_Synth_04.ogg \
	$(LOCAL_PATH)/Ring_Synth_02.ogg:system/media/audio/ringtones/Ring_Synth_02.ogg \
	$(LOCAL_PATH)/notifications/Beat_Box_Android.ogg:system/media/audio/notifications/Beat_Box_Android.ogg \
	$(LOCAL_PATH)/notifications/Heaven.ogg:system/media/audio/notifications/Heaven.ogg \
	$(LOCAL_PATH)/notifications/TaDa.ogg:system/media/audio/notifications/TaDa.ogg \
	$(LOCAL_PATH)/notifications/Tinkerbell.ogg:system/media/audio/notifications/Tinkerbell.ogg \
	$(LOCAL_PATH)/effects/Effect_Tick.ogg:system/media/audio/ui/Effect_Tick.ogg \
	$(LOCAL_PATH)/effects/KeypressStandard.ogg:system/media/audio/ui/KeypressStandard.ogg \
	$(LOCAL_PATH)/effects/KeypressSpacebar.ogg:system/media/audio/ui/KeypressSpacebar.ogg \
	$(LOCAL_PATH)/effects/KeypressDelete.ogg:system/media/audio/ui/KeypressDelete.ogg \
	$(LOCAL_PATH)/effects/KeypressReturn.ogg:system/media/audio/ui/KeypressReturn.ogg \
	$(LOCAL_PATH)/effects/VideoRecord.ogg:system/media/audio/ui/VideoRecord.ogg \
	$(LOCAL_PATH)/effects/VideoStop.ogg:system/media/audio/ui/VideoStop.ogg \
	$(LOCAL_PATH)/effects/camera_click.ogg:system/media/audio/ui/camera_click.ogg \
	$(LOCAL_PATH)/newwavelabs/BeatPlucker.ogg:system/media/audio/ringtones/BeatPlucker.ogg \
	$(LOCAL_PATH)/newwavelabs/CaffeineSnake.ogg:system/media/audio/notifications/CaffeineSnake.ogg

ifneq ($(MINIMAL_NEWWAVELABS),true)
PRODUCT_COPY_FILES += \
	$(LOCAL_PATH)/newwavelabs/BentleyDubs.ogg:system/media/audio/ringtones/BentleyDubs.ogg \
	$(LOCAL_PATH)/newwavelabs/BirdLoop.ogg:system/media/audio/ringtones/BirdLoop.ogg \
	$(LOCAL_PATH)/newwavelabs/CaribbeanIce.ogg:system/media/audio/ringtones/CaribbeanIce.ogg \
	$(LOCAL_PATH)/newwavelabs/CurveBall.ogg:system/media/audio/ringtones/CurveBall.ogg \
	$(LOCAL_PATH)/newwavelabs/EtherShake.ogg:system/media/audio/ringtones/EtherShake.ogg \
	$(LOCAL_PATH)/newwavelabs/FriendlyGhost.ogg:system/media/audio/ringtones/FriendlyGhost.ogg \
	$(LOCAL_PATH)/newwavelabs/GameOverGuitar.ogg:system/media/audio/ringtones/GameOverGuitar.ogg \
	$(LOCAL_PATH)/newwavelabs/Growl.ogg:system/media/audio/ringtones/Growl.ogg \
	$(LOCAL_PATH)/newwavelabs/InsertCoin.ogg:system/media/audio/ringtones/InsertCoin.ogg \
	$(LOCAL_PATH)/newwavelabs/LoopyLounge.ogg:system/media/audio/ringtones/LoopyLounge.ogg \
	$(LOCAL_PATH)/newwavelabs/LoveFlute.ogg:system/media/audio/ringtones/LoveFlute.ogg \
	$(LOCAL_PATH)/newwavelabs/MidEvilJaunt.ogg:system/media/audio/ringtones/MidEvilJaunt.ogg \
	$(LOCAL_PATH)/newwavelabs/MildlyAlarming.ogg:system/media/audio/ringtones/MildlyAlarming.ogg \
	$(LOCAL_PATH)/newwavelabs/NewPlayer.ogg:system/media/audio/ringtones/NewPlayer.ogg \
	$(LOCAL_PATH)/newwavelabs/Noises1.ogg:system/media/audio/ringtones/Noises1.ogg \
	$(LOCAL_PATH)/newwavelabs/Noises2.ogg:system/media/audio/ringtones/Noises2.ogg \
	$(LOCAL_PATH)/newwavelabs/Noises3.ogg:system/media/audio/ringtones/Noises3.ogg \
	$(LOCAL_PATH)/newwavelabs/OrganDub.ogg:system/media/audio/ringtones/OrganDub.ogg \
	$(LOCAL_PATH)/newwavelabs/RomancingTheTone.ogg:system/media/audio/ringtones/RomancingTheTone.ogg \
	$(LOCAL_PATH)/newwavelabs/SitarVsSitar.ogg:system/media/audio/ringtones/SitarVsSitar.ogg \
	$(LOCAL_PATH)/newwavelabs/SpringyJalopy.ogg:system/media/audio/ringtones/SpringyJalopy.ogg \
	$(LOCAL_PATH)/newwavelabs/Terminated.ogg:system/media/audio/ringtones/Terminated.ogg \
	$(LOCAL_PATH)/newwavelabs/TwirlAway.ogg:system/media/audio/ringtones/TwirlAway.ogg \
	$(LOCAL_PATH)/newwavelabs/VeryAlarmed.ogg:system/media/audio/ringtones/VeryAlarmed.ogg \
	$(LOCAL_PATH)/newwavelabs/World.ogg:system/media/audio/ringtones/World.ogg \
	$(LOCAL_PATH)/newwavelabs/DearDeer.ogg:system/media/audio/notifications/DearDeer.ogg \
	$(LOCAL_PATH)/newwavelabs/DontPanic.ogg:system/media/audio/notifications/DontPanic.ogg \
	$(LOCAL_PATH)/newwavelabs/Highwire.ogg:system/media/audio/notifications/Highwire.ogg \
	$(LOCAL_PATH)/newwavelabs/KzurbSonar.ogg:system/media/audio/notifications/KzurbSonar.ogg \
	$(LOCAL_PATH)/newwavelabs/OnTheHunt.ogg:system/media/audio/notifications/OnTheHunt.ogg \
	$(LOCAL_PATH)/newwavelabs/Voila.ogg:system/media/audio/notifications/Voila.ogg \
	$(LOCAL_PATH)/newwavelabs/CrazyDream.ogg:system/media/audio/ringtones/CrazyDream.ogg \
	$(LOCAL_PATH)/newwavelabs/DreamTheme.ogg:system/media/audio/ringtones/DreamTheme.ogg
endif
