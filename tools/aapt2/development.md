# AAPT2 development

## Building
All build targets can be found in `Android.bp` file. The main ones are `make -j aapt2` and `make -j aapt2_tests`

`make -j aapt2` will create an aapt2 executable in `out/host/linux-x86/bin/aapt2` (on Linux). This `aapt2` executable will then be used for all the apps in the platform.

Static version of the tool (without shared libraries) can be built with `make -j static_sdk_tools dist DIST_DIR=$OUTPUT_DIRECTORY BUILD_HOST_static=1`. Note, in addition to aapt2 this command will also output other statically built tools to the `$OUTPUT_DIRECTORY`.

## Running tests
Build `make -j aapt2_tests` and then (on Linux) execute `out/host/linux-x86/nativetest64/aapt2_tests/aapt2_tests`