#!/bin/sh
#
# Script that measures statsd's PSS under an increasing number of metrics.

# Globals.
pss=""
pid=""

# Starts the loadtest.
start_loadtest() {
    echo "Starting loadtest"
    adb shell am start -n com.android.statsd.loadtest/.LoadtestActivity --es "type" "start"
}

# Stops the loadtest.
stop_loadtest() {
    echo "Stopping loadtest"
    adb shell am start -n com.android.statsd.loadtest/.LoadtestActivity --es "type" "stop"
}

# Sets the metrics replication.
# Arguments:
#   $1: The replication factor.
set_replication() {
    adb shell am start -n com.android.statsd.loadtest/.LoadtestActivity --es "type" "set_replication" --ei "replication" "${1}"
    echo "Replication set to ${1}"
}

# Reads statsd's pid and PSS.
update_pid_and_pss() {
    # Command that reads the PSS for statsd. This also gives us its pid.
    get_mem=$(adb shell dumpsys meminfo |grep statsd)
    # Looks for statsd's pid.
    regex="([0-9,]+)K: statsd \(pid ([0-9]+)\).*"
    if [[ $get_mem =~ $regex ]]; then
        pss=$(echo "${BASH_REMATCH[1]}" | tr -d , | sed 's/\.//g')
        pid=$(echo "${BASH_REMATCH[2]}")
    else
        echo $cmd doesnt match $regex
    fi
}

# Kills statsd.
# Assumes the pid has been set.
kill_statsd() {
    echo "Killing statsd (pid ${pid})"
    adb shell kill -9 "${pid}"
}

# Main loop.
main() {
    start_time=$(date +%s)
    values=()
    stop_loadtest

    echo ""
    echo "********************* NEW LOADTEST ************************"
    update_pid_and_pss
    for replication in 1 2 4 8 16 32 64 128 256 512 1024 2048 4096
    do
        echo "**** Starting test at replication ${replication} ****"

        # (1) Restart statsd. This will ensure its state is empty.
        kill_statsd
        sleep 3 # wait a bit for it to restart
        update_pid_and_pss
        echo "Before the test, statsd's PSS is ${pss}"

        # (2) Set the replication.
        set_replication "${replication}"
        sleep 1 # wait a bit

        # (3) Start the loadtest.
        start_loadtest

        # (4) Wait several seconds, then read the PSS.
        sleep 100 && update_pid_and_pss
        echo "During the test, statsd's PSS is ${pss}"
        values+=(${pss})

        echo "Values: ${values[@]}"

        # (5) Stop loadtest.
        stop_loadtest
        sleep 2

        echo ""
    done

    end_time=$(date +%s)
    echo "Completed loadtest in $((${end_time} - ${start_time})) seconds."

    values_as_str=$(IFS=$'\n'; echo "${values[*]}")
    echo "The PSS values are:"
    echo "${values_as_str}"
    echo ""
}

main
