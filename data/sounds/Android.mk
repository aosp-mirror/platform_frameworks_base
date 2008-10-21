LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# don't understand what's wrong here... bs
#
#copy_from := $(wildcard $(LOCAL_PATH)/*.mp3) 
#copy_to := $(addprefix $(TARGET_OUT)/sounds/,$(patsubst $(LOCAL_PATH)/%,%,$(copy_from)))
#
#$(copy_to) : PRIVATE_MODULE := sounds 
#$(copy_to) : $(TARGET_OUT)/sounds/% : $(LOCAL_PATH)/% | $(ACP)
#	$(transform-prebuilt-to-target)
#
#ALL_PREBUILT += $(copy_to)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/F1_MissedCall.ogg
$(TARGET_OUT)/media/audio/notifications/F1_MissedCall.ogg : $(LOCAL_PATH)/F1_MissedCall.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/F1_New_MMS.ogg
$(TARGET_OUT)/media/audio/notifications/F1_New_MMS.ogg : $(LOCAL_PATH)/F1_New_MMS.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/F1_New_SMS.ogg
$(TARGET_OUT)/media/audio/notifications/F1_New_SMS.ogg : $(LOCAL_PATH)/F1_New_SMS.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/alarms/Alarm_Buzzer.ogg
$(TARGET_OUT)/media/audio/alarms/Alarm_Buzzer.ogg : $(LOCAL_PATH)/Alarm_Buzzer.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/alarms/Alarm_Beep_01.ogg
$(TARGET_OUT)/media/audio/alarms/Alarm_Beep_01.ogg : $(LOCAL_PATH)/Alarm_Beep_01.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/alarms/Alarm_Beep_02.ogg
$(TARGET_OUT)/media/audio/alarms/Alarm_Beep_02.ogg : $(LOCAL_PATH)/Alarm_Beep_02.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/alarms/Alarm_Classic.ogg
$(TARGET_OUT)/media/audio/alarms/Alarm_Classic.ogg : $(LOCAL_PATH)/Alarm_Classic.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/alarms/Alarm_Beep_03.ogg
$(TARGET_OUT)/media/audio/alarms/Alarm_Beep_03.ogg : $(LOCAL_PATH)/Alarm_Beep_03.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/alarms/Alarm_Rooster_02.ogg
$(TARGET_OUT)/media/audio/alarms/Alarm_Rooster_02.ogg : $(LOCAL_PATH)/Alarm_Rooster_02.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/Ring_Classic_02.ogg
$(TARGET_OUT)/media/audio/ringtones/Ring_Classic_02.ogg : $(LOCAL_PATH)/Ring_Classic_02.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/Ring_Digital_02.ogg
$(TARGET_OUT)/media/audio/ringtones/Ring_Digital_02.ogg : $(LOCAL_PATH)/Ring_Digital_02.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/Ring_Synth_04.ogg
$(TARGET_OUT)/media/audio/ringtones/Ring_Synth_04.ogg : $(LOCAL_PATH)/Ring_Synth_04.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/Ring_Synth_02.ogg
$(TARGET_OUT)/media/audio/ringtones/Ring_Synth_02.ogg : $(LOCAL_PATH)/Ring_Synth_02.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/sounds/test.mid
$(TARGET_OUT)/sounds/test.mid : $(LOCAL_PATH)/test.mid | $(ACP)
	$(transform-prebuilt-to-target)
#
# --- New Wave Labs ringtones
#
ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/BeatPlucker.ogg
$(TARGET_OUT)/media/audio/ringtones/BeatPlucker.ogg : $(LOCAL_PATH)/newwavelabs/BeatPlucker.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/BentleyDubs.ogg
$(TARGET_OUT)/media/audio/ringtones/BentleyDubs.ogg : $(LOCAL_PATH)/newwavelabs/BentleyDubs.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/BirdLoop.ogg
$(TARGET_OUT)/media/audio/ringtones/BirdLoop.ogg : $(LOCAL_PATH)/newwavelabs/BirdLoop.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/CaribbeanIce.ogg
$(TARGET_OUT)/media/audio/ringtones/CaribbeanIce.ogg : $(LOCAL_PATH)/newwavelabs/CaribbeanIce.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/CrazyDream.ogg
$(TARGET_OUT)/media/audio/ringtones/CrazyDream.ogg : $(LOCAL_PATH)/newwavelabs/CrazyDream.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/CurveBall.ogg
$(TARGET_OUT)/media/audio/ringtones/CurveBall.ogg : $(LOCAL_PATH)/newwavelabs/CurveBall.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/DreamTheme.ogg
$(TARGET_OUT)/media/audio/ringtones/DreamTheme.ogg : $(LOCAL_PATH)/newwavelabs/DreamTheme.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/EtherShake.ogg
$(TARGET_OUT)/media/audio/ringtones/EtherShake.ogg : $(LOCAL_PATH)/newwavelabs/EtherShake.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/FriendlyGhost.ogg
$(TARGET_OUT)/media/audio/ringtones/FriendlyGhost.ogg : $(LOCAL_PATH)/newwavelabs/FriendlyGhost.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/GameOverGuitar.ogg
$(TARGET_OUT)/media/audio/ringtones/GameOverGuitar.ogg : $(LOCAL_PATH)/newwavelabs/GameOverGuitar.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/Growl.ogg
$(TARGET_OUT)/media/audio/ringtones/Growl.ogg : $(LOCAL_PATH)/newwavelabs/Growl.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/InsertCoin.ogg
$(TARGET_OUT)/media/audio/ringtones/InsertCoin.ogg : $(LOCAL_PATH)/newwavelabs/InsertCoin.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/LoopyLounge.ogg
$(TARGET_OUT)/media/audio/ringtones/LoopyLounge.ogg : $(LOCAL_PATH)/newwavelabs/LoopyLounge.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/LoveFlute.ogg
$(TARGET_OUT)/media/audio/ringtones/LoveFlute.ogg : $(LOCAL_PATH)/newwavelabs/LoveFlute.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/MidEvilJaunt.ogg
$(TARGET_OUT)/media/audio/ringtones/MidEvilJaunt.ogg : $(LOCAL_PATH)/newwavelabs/MidEvilJaunt.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/MildlyAlarming.ogg
$(TARGET_OUT)/media/audio/ringtones/MildlyAlarming.ogg : $(LOCAL_PATH)/newwavelabs/MildlyAlarming.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/NewPlayer.ogg
$(TARGET_OUT)/media/audio/ringtones/NewPlayer.ogg : $(LOCAL_PATH)/newwavelabs/NewPlayer.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/Noises1.ogg
$(TARGET_OUT)/media/audio/ringtones/Noises1.ogg : $(LOCAL_PATH)/newwavelabs/Noises1.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/Noises2.ogg
$(TARGET_OUT)/media/audio/ringtones/Noises2.ogg : $(LOCAL_PATH)/newwavelabs/Noises2.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/Noises3.ogg
$(TARGET_OUT)/media/audio/ringtones/Noises3.ogg : $(LOCAL_PATH)/newwavelabs/Noises3.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/OrganDub.ogg
$(TARGET_OUT)/media/audio/ringtones/OrganDub.ogg : $(LOCAL_PATH)/newwavelabs/OrganDub.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/RomancingTheTone.ogg
$(TARGET_OUT)/media/audio/ringtones/RomancingTheTone.ogg : $(LOCAL_PATH)/newwavelabs/RomancingTheTone.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/SitarVsSitar.ogg
$(TARGET_OUT)/media/audio/ringtones/SitarVsSitar.ogg : $(LOCAL_PATH)/newwavelabs/SitarVsSitar.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/SpringyJalopy.ogg
$(TARGET_OUT)/media/audio/ringtones/SpringyJalopy.ogg : $(LOCAL_PATH)/newwavelabs/SpringyJalopy.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/Terminated.ogg
$(TARGET_OUT)/media/audio/ringtones/Terminated.ogg : $(LOCAL_PATH)/newwavelabs/Terminated.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/TwirlAway.ogg
$(TARGET_OUT)/media/audio/ringtones/TwirlAway.ogg : $(LOCAL_PATH)/newwavelabs/TwirlAway.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/VeryAlarmed.ogg
$(TARGET_OUT)/media/audio/ringtones/VeryAlarmed.ogg : $(LOCAL_PATH)/newwavelabs/VeryAlarmed.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ringtones/World.ogg
$(TARGET_OUT)/media/audio/ringtones/World.ogg : $(LOCAL_PATH)/newwavelabs/World.ogg | $(ACP)
	$(transform-prebuilt-to-target)
#
# --- New Wave Labs notifications
#
ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/CaffeineSnake.ogg
$(TARGET_OUT)/media/audio/notifications/CaffeineSnake.ogg : $(LOCAL_PATH)/newwavelabs/CaffeineSnake.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/DearDeer.ogg
$(TARGET_OUT)/media/audio/notifications/DearDeer.ogg : $(LOCAL_PATH)/newwavelabs/DearDeer.ogg | $(ACP)
	$(transform-prebuilt-to-target)
		
ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/DontPanic.ogg
$(TARGET_OUT)/media/audio/notifications/DontPanic.ogg : $(LOCAL_PATH)/newwavelabs/DontPanic.ogg | $(ACP)
	$(transform-prebuilt-to-target)
	
ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/Highwire.ogg
$(TARGET_OUT)/media/audio/notifications/Highwire.ogg : $(LOCAL_PATH)/newwavelabs/Highwire.ogg | $(ACP)
	$(transform-prebuilt-to-target)
	
ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/KzurbSonar.ogg
$(TARGET_OUT)/media/audio/notifications/KzurbSonar.ogg : $(LOCAL_PATH)/newwavelabs/KzurbSonar.ogg | $(ACP)
	$(transform-prebuilt-to-target)
	
ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/OnTheHunt.ogg
$(TARGET_OUT)/media/audio/notifications/OnTheHunt.ogg : $(LOCAL_PATH)/newwavelabs/OnTheHunt.ogg | $(ACP)
	$(transform-prebuilt-to-target)
	
ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/Voila.ogg
$(TARGET_OUT)/media/audio/notifications/Voila.ogg : $(LOCAL_PATH)/newwavelabs/Voila.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/ui/Effect_Tick.ogg
$(TARGET_OUT)/media/audio/ui/Effect_Tick.ogg : $(LOCAL_PATH)/Effect_Tick.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/Beat_Box_Android.ogg
$(TARGET_OUT)/media/audio/notifications/Beat_Box_Android.ogg : $(LOCAL_PATH)/notifications/Beat_Box_Android.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/Heaven.ogg
$(TARGET_OUT)/media/audio/notifications/Heaven.ogg : $(LOCAL_PATH)/notifications/Heaven.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/TaDa.ogg
$(TARGET_OUT)/media/audio/notifications/TaDa.ogg : $(LOCAL_PATH)/notifications/TaDa.ogg | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(TARGET_OUT)/media/audio/notifications/Tinkerbell.ogg
$(TARGET_OUT)/media/audio/notifications/Tinkerbell.ogg : $(LOCAL_PATH)/notifications/Tinkerbell.ogg | $(ACP)
	$(transform-prebuilt-to-target)
