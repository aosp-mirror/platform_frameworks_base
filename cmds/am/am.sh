#!/system/bin/sh

if [ "$1" != "instrument" ] ; then
    cmd activity "$@"
else
    base=/system
    export CLASSPATH=$base/framework/am.jar
    exec app_process $base/bin com.android.commands.am.Am "$@"
fi
