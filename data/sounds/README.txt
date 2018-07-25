This README describes the audio assets, and how they relate to each other.

The product .mk references one of the AudioPackage*.mk,
which installs the appropriate assets into the destination directory.

For UI sound effects,
frameworks/base/media/java/android/media/AudioService.java array
SOUND_EFFECT_FILES contains a hard-coded list of asset filenames, stored
in directory SOUND_EFFECTS_PATH.

Touch sounds
------------

effects/Effect_Tick.ogg
  old, referenced by AudioPackage[2345].mk OriginalAudio.mk

effects/ogg/Effect_Tick.ogg
  new, referenced by AudioPackage[6789].mk AudioPackage7alt.mk AudioPackage10.mk

effects/ogg/Effect_Tick_48k.ogg
  oggdec -o temp.wav ogg/Effect_Tick.ogg
  sox temp.wav -r 48000 temp48k.wav
  oggenc -b 80 -o ogg/Effect_Tick_48k.ogg temp48k.wav

effects/wav/Effect_Tick.wav
  does not appear to be related to the other files in any obvious way

Video recording
---------------

./effects/ogg/VideoStop_48k.ogg
  unused

NFC
---

./effects/ogg/NFCFailure.ogg
./effects/ogg/NFCInitiated.ogg
./effects/ogg/NFCSuccess.ogg
./effects/ogg/NFCTransferComplete.ogg
./effects/ogg/NFCTransferInitiated.ogg

referenced in AudioPackage14.mk (= AudioPackage13.mk + NFC sounds).
