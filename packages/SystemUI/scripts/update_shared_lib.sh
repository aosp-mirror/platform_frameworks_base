#!/bin/sh

NUM_ARGS=$#
JAR_DESTINATION="$1/prebuilts/framework_intermediates/quickstep/libs/sysui_shared.jar"

has_croot() {
  declare -F croot > /dev/null
  return $?
}

check_environment() {
  if ! has_croot; then
    echo "Run script in a shell that has had envsetup run. Run '. update_shared_lib.sh' from scripts directory"
    return 1
  fi

  if [ $NUM_ARGS -ne 1 ]; then
    echo "Usage: . update_shared_lib PATH_TO_UNBUNDLED_LAUNCER     e.g. . update_shared_lib ~/src/ub-launcher3-master"
    return 1
  fi
  return 0
}

main() {
  if check_environment ; then
    pushd .
    croot
    mma -j16 SystemUISharedLib
    cp out/target/product/$TARGET_PRODUCT/obj/JAVA_LIBRARIES/SystemUISharedLib_intermediates/javalib.jar $JAR_DESTINATION
    popd
  fi
}

main

