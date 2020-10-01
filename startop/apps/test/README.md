This directory contains a simple Android app that is meant to help in doing
controlled startup performance experiments.

This app is structured as a number of activities that each are useful for a
different aspect of startup testing.

# Activities

## EmptyActivity

This is the simplest possible Android activity. Starting this exercises only the
system parts of startup without any app-specific behavior.

    adb shell am start -n com.android.startop.test/.EmptyActivity

## LayoutInflation

This activity inflates a reasonably complex layout to see the impact of layout
inflation. The layout is supported by the viewcompiler, so this can be used for
testing precompiled layout performance.

The activity adds an `inflate#activity_main` slice to atrace around the time
spent in view inflation to make it easier to focus on the time spent in view
inflation.

    adb shell am start -n com.android.startop.test/.ComplexLayoutInflationActivity

## NonInteractiveSystemServerBenchmark

This activity is for running microbenchmarks from the command line. Run as follows:

   adb shell am start -W -n com.android.startop.test .NonInteractiveSystemServerBenchmarkActivity

It takes awhile (and there's currently no automated way to make sure it's done),
but when it finishes, you can get the results like this:

    adb shell cat /sdcard/Android/data/com.android.startop.test/files/benchmark.csv
