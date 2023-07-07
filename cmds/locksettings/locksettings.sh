#!/system/bin/sh
# Script to start "locksettings" on the device
#
base=/system
export CLASSPATH=$base/framework/locksettings.jar
exec app_process $base/bin com.android.commands.locksettings.LockSettingsCmd "$@"
