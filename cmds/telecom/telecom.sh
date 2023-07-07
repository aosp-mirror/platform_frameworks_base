#!/system/bin/sh
# Script to start "telecom" on the device
#
base=/system
export CLASSPATH=$base/framework/telecom.jar
exec app_process $base/bin com.android.commands.telecom.Telecom "$@"

