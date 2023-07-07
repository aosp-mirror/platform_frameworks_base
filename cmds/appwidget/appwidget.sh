#!/system/bin/sh
export CLASSPATH=/system/framework/appwidget.jar
exec app_process /system/bin com.android.commands.appwidget.AppWidget "$@"
