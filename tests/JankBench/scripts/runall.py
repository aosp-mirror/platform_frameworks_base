#!/usr/bin/python

import optparse
import sys
import time

import adbutil
from devices import DEVICES

def parse_options(argv):
    usage = 'Usage: %prog [options]'
    desc = 'Example: %prog'
    parser = optparse.OptionParser(usage=usage, description=desc)
    parser.add_option("-c", dest='clear', action="store_true")
    parser.add_option("-d", dest='device', action="store",)
    parser.add_option("-t", dest='trace', action="store_true")
    options, categories = parser.parse_args(argv[1:])
    return (options, categories)

def clear_data(device = None):
    if device != None:
        dev = DEVICES[device]
        adbutil.root(dev)
        adbutil.pm(dev, "clear", "com.android.benchmark")
    else:
        for name, dev in DEVICES.iteritems():
            print("Clearing " + name)
            adbutil.root(dev)
            adbutil.pm(dev, "clear", "com.android.benchmark")

def start_device(name, dev):
    print("Go " + name + "!")
    try:
        adbutil.am(dev, "force-stop", "com.android.benchmark")
        adbutil.wake(dev)
        adbutil.am(dev, "start",
            ["-n", "\"com.android.benchmark/.app.RunLocalBenchmarksActivity\"",
            "--eia", "\"com.android.benchmark.EXTRA_ENABLED_BENCHMARK_IDS\"", "\"0,1,2,3,4,5,6\"",
            "--ei", "\"com.android.benchmark.EXTRA_RUN_COUNT\"", "\"5\""])
    except adbutil.AdbError:
        print "Couldn't launch " + name + "."

def start_benchmark(device, trace):
    if device != None:
        start_device(device, DEVICES[device])
        if trace:
            time.sleep(3)
            adbutil.trace(DEVICES[device])
    else:
        if trace:
            print("Note: -t only valid with -d, can't trace")
        for name, dev in DEVICES.iteritems():
            start_device(name, dev)

def main():
    options, categories = parse_options(sys.argv)
    if options.clear:
        print options.device
        clear_data(options.device)
    else:
        start_benchmark(options.device, options.trace)


if __name__ == "__main__":
    main()
