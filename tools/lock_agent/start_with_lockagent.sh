#!/system/bin/sh
APP=$1
shift
$APP -Xplugin:libopenjdkjvmti.so -agentpath:liblockagent.so $@

