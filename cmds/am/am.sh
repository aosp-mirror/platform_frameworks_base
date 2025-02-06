#!/system/bin/sh

# set to top-app process group
settaskprofile $$ SCHED_SP_TOP_APP >/dev/null 2>&1 || true

if [ "$1" != "instrument" ] ; then
    cmd activity "$@"
else
    base=/system
    export CLASSPATH=$base/framework/am.jar
    exec app_process $base/bin com.android.commands.am.Am "$@"
fi
