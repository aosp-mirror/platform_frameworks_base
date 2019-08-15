#!/system/bin/sh

AGENT_OPTIONS=
if [[ "$1" == --agent-options ]] ; then
  shift
  AGENT_OPTIONS="=$1"
  shift
fi

APP=$1
shift

$APP -Xplugin:libopenjdkjvmti.so "-agentpath:liblockagent.so$AGENT_OPTIONS" $@
