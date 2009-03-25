#
# Audio Package 2
# 
# Include this file in a product makefile to include these audio files
#
# This is a larger package of sounds than the 1.0 release for devices
# that have larger internal flash.
# 

local_path:= frameworks/base/data/sounds

PRODUCT_COPY_FILES += \
	$(local_path)/Ring_Classic_02.ogg:system/media/audio/ringtones/Ring_Classic_02.ogg \
	$(local_path)/Ring_Digital_02.ogg:system/media/audio/ringtones/Ring_Digital_02.ogg \
	$(local_path)/Ring_Synth_04.ogg:system/media/audio/ringtones/Ring_Synth_04.ogg \
	$(local_path)/Ring_Synth_02.ogg:system/media/audio/ringtones/Ring_Synth_02.ogg \
	$(local_path)/newwavelabs/BeatPlucker.ogg:system/media/audio/ringtones/BeatPlucker.ogg \
	$(local_path)/newwavelabs/BentleyDubs.ogg:system/media/audio/ringtones/BentleyDubs.ogg \
	$(local_path)/newwavelabs/BirdLoop.ogg:system/media/audio/ringtones/BirdLoop.ogg \
	$(local_path)/newwavelabs/CaribbeanIce.ogg:system/media/audio/ringtones/CaribbeanIce.ogg \
	$(local_path)/newwavelabs/CurveBall.ogg:system/media/audio/ringtones/CurveBall.ogg \
	$(local_path)/newwavelabs/EtherShake.ogg:system/media/audio/ringtones/EtherShake.ogg \
	$(local_path)/newwavelabs/FriendlyGhost.ogg:system/media/audio/ringtones/FriendlyGhost.ogg \
	$(local_path)/newwavelabs/GameOverGuitar.ogg:system/media/audio/ringtones/GameOverGuitar.ogg \
	$(local_path)/newwavelabs/Growl.ogg:system/media/audio/ringtones/Growl.ogg \
	$(local_path)/newwavelabs/InsertCoin.ogg:system/media/audio/ringtones/InsertCoin.ogg \
	$(local_path)/newwavelabs/LoopyLounge.ogg:system/media/audio/ringtones/LoopyLounge.ogg \
	$(local_path)/newwavelabs/LoveFlute.ogg:system/media/audio/ringtones/LoveFlute.ogg \
	$(local_path)/newwavelabs/MidEvilJaunt.ogg:system/media/audio/ringtones/MidEvilJaunt.ogg \
	$(local_path)/newwavelabs/MildlyAlarming.ogg:system/media/audio/ringtones/MildlyAlarming.ogg \
	$(local_path)/newwavelabs/NewPlayer.ogg:system/media/audio/ringtones/NewPlayer.ogg \
	$(local_path)/newwavelabs/Noises1.ogg:system/media/audio/ringtones/Noises1.ogg \
	$(local_path)/newwavelabs/Noises2.ogg:system/media/audio/ringtones/Noises2.ogg \
	$(local_path)/newwavelabs/Noises3.ogg:system/media/audio/ringtones/Noises3.ogg \
	$(local_path)/newwavelabs/OrganDub.ogg:system/media/audio/ringtones/OrganDub.ogg \
	$(local_path)/newwavelabs/RomancingTheTone.ogg:system/media/audio/ringtones/RomancingTheTone.ogg \
	$(local_path)/newwavelabs/SitarVsSitar.ogg:system/media/audio/ringtones/SitarVsSitar.ogg \
	$(local_path)/newwavelabs/SpringyJalopy.ogg:system/media/audio/ringtones/SpringyJalopy.ogg \
	$(local_path)/newwavelabs/Terminated.ogg:system/media/audio/ringtones/Terminated.ogg \
	$(local_path)/newwavelabs/TwirlAway.ogg:system/media/audio/ringtones/TwirlAway.ogg \
	$(local_path)/newwavelabs/VeryAlarmed.ogg:system/media/audio/ringtones/VeryAlarmed.ogg \
	$(local_path)/newwavelabs/World.ogg:system/media/audio/ringtones/World.ogg \
	$(local_path)/effects/Effect_Tick.ogg:system/media/audio/ui/Effect_Tick.ogg \
	$(local_path)/effects/KeypressStandard.ogg:system/media/audio/ui/KeypressStandard.ogg \
	$(local_path)/effects/KeypressSpacebar.ogg:system/media/audio/ui/KeypressSpacebar.ogg \
	$(local_path)/effects/KeypressDelete.ogg:system/media/audio/ui/KeypressDelete.ogg \
	$(local_path)/effects/KeypressReturn.ogg:system/media/audio/ui/KeypressReturn.ogg \
	$(local_path)/newwavelabs/Big_Easy.ogg:system/media/audio/ringtones/Big_Easy.ogg \
	$(local_path)/newwavelabs/Bollywood.ogg:system/media/audio/ringtones/Bollywood.ogg \
	$(local_path)/newwavelabs/Cairo.ogg:system/media/audio/ringtones/Cairo.ogg \
	$(local_path)/newwavelabs/Calypso_Steel.ogg:system/media/audio/ringtones/Calypso_Steel.ogg \
	$(local_path)/newwavelabs/Champagne_Edition.ogg:system/media/audio/ringtones/Champagne_Edition.ogg \
	$(local_path)/newwavelabs/Club_Cubano.ogg:system/media/audio/ringtones/Club_Cubano.ogg \
	$(local_path)/newwavelabs/Eastern_Sky.ogg:system/media/audio/ringtones/Eastern_Sky.ogg \
	$(local_path)/newwavelabs/Funk_Yall.ogg:system/media/audio/ringtones/Funk_Yall.ogg \
	$(local_path)/newwavelabs/Savannah.ogg:system/media/audio/ringtones/Savannah.ogg \
	$(local_path)/newwavelabs/Gimme_Mo_Town.ogg:system/media/audio/ringtones/Gimme_Mo_Town.ogg \
	$(local_path)/newwavelabs/Glacial_Groove.ogg:system/media/audio/ringtones/Glacial_Groove.ogg \
	$(local_path)/newwavelabs/Seville.ogg:system/media/audio/ringtones/Seville.ogg \
	$(local_path)/newwavelabs/No_Limits.ogg:system/media/audio/ringtones/No_Limits.ogg \
	$(local_path)/newwavelabs/Revelation.ogg:system/media/audio/ringtones/Revelation.ogg \
	$(local_path)/newwavelabs/Paradise_Island.ogg:system/media/audio/ringtones/Paradise_Island.ogg \
	$(local_path)/newwavelabs/Road_Trip.ogg:system/media/audio/ringtones/Road_Trip.ogg \
	$(local_path)/newwavelabs/Shes_All_That.ogg:system/media/audio/ringtones/Shes_All_That.ogg \
	$(local_path)/newwavelabs/Steppin_Out.ogg:system/media/audio/ringtones/Steppin_Out.ogg \
	$(local_path)/newwavelabs/Third_Eye.ogg:system/media/audio/ringtones/Third_Eye.ogg \
	$(local_path)/newwavelabs/Thunderfoot.ogg:system/media/audio/ringtones/Thunderfoot.ogg

