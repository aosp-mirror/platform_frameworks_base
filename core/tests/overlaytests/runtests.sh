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

function mkdir_if_needed()
{
	local path="$1"

	if [[ "${path:0:1}" != "/" ]]; then
		echo "mkdir_if_needed: error: path '$path' does not begin with /" | tee -a $log
		exit 1
	fi

	local basename=$(basename "$path")
	local dirname=$(dirname "$path")
	local t=$($adb shell ls -l $dirname | tr -d '\r' | grep -e "${basename}$" | grep -oe '^.')

	case "$t" in
		d) # File exists, and is a directory ...
			# do nothing
			;;
		l) # ... (or symbolic link possibly to a directory).
			# do nothing
			;;
		"") # File does not exist.
			mkdir_if_needed "$dirname"
			$adb shell mkdir "$path"
			;;
		*) # File exists, but is not a directory.
			echo "mkdir_if_needed: file '$path' exists, but is not a directory" | tee -a $log
			exit 1
			;;
	esac
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
	mkdir_if_needed "/system/vendor"
	mkdir_if_needed "/vendor/overlay/framework"
	$adb shell ln -s /data/app/com.android.overlaytest.overlay.apk /vendor/overlay/framework/framework-res.apk
}

function instrument()
{
	local class="$1"

	echo "Instrumenting $class"
	$adb shell am instrument -w -e class $class com.android.overlaytest/android.test.InstrumentationTestRunner | tee -a $log
}

function remount()
{
	echo "Remounting file system writable"
	$adb remount | tee -a $log
}

function sync()
{
	echo "Syncing to device"
	$adb sync data | tee -a $log
}

# some commands require write access, remount once and for all
remount

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
