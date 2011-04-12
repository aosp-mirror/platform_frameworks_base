#!/bin/bash

adb="adb"
if [[ $# -gt 0 ]]; then
	adb="adb $*" # for setting -e, -d or -s <serial>
fi

function atexit()
{
	local retval=$?

	if [[ $retval -eq 0 ]]; then
		rm $log
	else
		echo "There were errors, please check log at $log"
	fi
}

log=$(mktemp)
trap "atexit" EXIT
failures=0

function compile_module()
{
	local android_mk="$1"

	echo "Compiling .${android_mk:${#PWD}}"
	ONE_SHOT_MAKEFILE="$android_mk" make -C "../../../../../" files | tee -a $log
	if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
		exit 1
	fi
}

function wait_for_boot_completed()
{
	echo "Rebooting device"
	$adb wait-for-device logcat -c
	$adb wait-for-device logcat | grep -m 1 -e 'PowerManagerService.*bootCompleted' >/dev/null
}

function disable_overlay()
{
	echo "Disabling overlay"
	$adb shell rm /vendor/overlay/framework/framework-res.apk
	$adb shell rm /data/resource-cache/vendor@overlay@framework@framework-res.apk@idmap
}

function enable_overlay()
{
	echo "Enabling overlay"
	$adb shell ln -s /data/app/com.android.overlaytest.overlay.apk /vendor/overlay/framework/framework-res.apk
}

function instrument()
{
	local class="$1"

	echo "Instrumenting $class"
	$adb shell am instrument -w -e class $class com.android.overlaytest/android.test.InstrumentationTestRunner | tee -a $log
}

function sync()
{
	echo "Syncing to device"
	$adb remount | tee -a $log
	$adb sync data | tee -a $log
}

# build and sync
compile_module "$PWD/OverlayTest/Android.mk"
compile_module "$PWD/OverlayTestOverlay/Android.mk"
sync

# instrument test (without overlay)
$adb shell stop
disable_overlay
$adb shell start
wait_for_boot_completed
instrument "com.android.overlaytest.WithoutOverlayTest"

# instrument test (with overlay)
$adb shell stop
enable_overlay
$adb shell start
wait_for_boot_completed
instrument "com.android.overlaytest.WithOverlayTest"

# cleanup
exit $(grep -c -e '^FAILURES' $log)
