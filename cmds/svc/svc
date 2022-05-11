#!/system/bin/sh

# `svc wifi` has been migrated to WifiShellCommand,
# simply perform translation to `cmd wifi set-wifi-enabled` here.
if [ "x$1" == "xwifi" ]; then
    # `cmd wifi` by convention uses enabled/disabled
    # instead of enable/disable
    if [ "x$2" == "xenable" ]; then
        exec cmd wifi set-wifi-enabled enabled
    elif [ "x$2" == "xdisable" ]; then
        exec cmd wifi set-wifi-enabled disabled
    else
        echo "Control the Wi-Fi manager"
        echo ""
        echo "usage: svc wifi [enable|disable]"
        echo "         Turn Wi-Fi on or off."
        echo ""
    fi
    exit 1
fi

if [ "x$1" == "xdata" ]; then
    if [ "x$2" == "xenable" ]; then
        exec cmd phone data enable
    elif [ "x$2" == "xdisable" ]; then
        exec cmd phone data disable
    else
        echo "Enable/Disable Mobile Data Connectivity"
        echo ""
        echo "usage: svc data [enable|disable]"
        echo ""
    fi
    exit 1
fi

# `svc bluetooth` has been migrated to BluetoothShellCommand,
# simply perform translation to `cmd bluetooth set-bluetooth-enabled` here.
if [ "x$1" == "xbluetooth" ]; then
    # `cmd wifi` by convention uses enabled/disabled
    # instead of enable/disable
    if [ "x$2" == "xenable" ]; then
        exec cmd bluetooth_manager enable
    elif [ "x$2" == "xdisable" ]; then
        exec cmd bluetooth_manager disable
    else
        echo "Control the Bluetooth manager"
        echo ""
        echo "usage: svc bluetooth [enable|disable]"
        echo "         Turn Bluetooth on or off."
        echo ""
    fi
    exit 1
fi

export CLASSPATH=/system/framework/svc.jar
exec app_process /system/bin com.android.commands.svc.Svc "$@"

