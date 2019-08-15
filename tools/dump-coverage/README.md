# dumpcoverage

libdumpcoverage.so is a JVMTI agent designed to dump coverage information for a process, where the binaries have been instrumented by JaCoCo. JaCoCo automatically starts recording data on process start, and we need a way to trigger the resetting or dumping of this data.

The JVMTI agent is used to make the calls to JaCoCo in its process.

# Usage

Note that these examples assume you have an instrumented build (userdebug_coverage). Here is, for example, how to dump coverage information regarding the default clock app. First some setup is necessary:

```
adb root # necessary to copy files in/out of the /data/data/{package} folder
adb shell 'mkdir /data/data/com.android.deskclock/folder-to-use'
```

Then we can run the command to dump the data:

```
adb shell 'am attach-agent com.android.deskclock /system/lib/libdumpcoverage.so=dump:/data/data/com.android.deskclock/folder-to-use/coverage-file.ec'
```

We can also reset the coverage information with

```
adb shell 'am attach-agent com.android.deskclock /system/lib/libdumpcoverage.so=reset'
```

then perform more actions, then dump the data again. To get the files, we can get

```
adb pull /data/data/com.android.deskclock/folder-to-use/coverage-file.ec ~/path-on-your-computer
```

And you should have `coverage-file.ec` on your machine under the folder `~/path-on-your-computer`

# Details

In dump mode, the agent makes JNI calls equivalent to

```
Agent.getInstance().getExecutionData(/*reset = */ false);
```

and then saves the result to a file specified by the passed in directory

In reset mode, it makes a JNI call equivalent to

```
Agent.getInstance().reset();
```
