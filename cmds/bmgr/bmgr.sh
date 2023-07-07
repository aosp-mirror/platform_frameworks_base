#!/system/bin/sh
export CLASSPATH=/system/framework/bmgr.jar
exec app_process /system/bin com.android.commands.bmgr.Bmgr "$@"
