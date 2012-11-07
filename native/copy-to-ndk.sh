# Take care of copying current header files over to the correct
# location in the NDK.

copyndkheaders() {
    local CURR_PLATFORM=android-9
    local ALL_PLATFORMS="$CURR_PLATFORM android-8 android-5 android-4 android-3"

    local SRC_HEADERS=$ANDROID_BUILD_TOP/frameworks/native/include/android
    local NDK_PLATFORMS=$ANDROID_BUILD_TOP/development/ndk/platforms
    local DST_HEADERS=$NDK_PLATFORMS/$CURR_PLATFORM

    local SRC_LIB_ANDROID=$ANDROID_PRODUCT_OUT/system/lib/libandroid.so
    local DST_LIB_ANDROID=$NDK_PLATFORMS/$CURR_PLATFORM/arch-arm/lib/libandroid.so

    local didsomething=""

    #echo "SRC_HEADERS: $SRC_HEADERS"

    for i in $(cd $SRC_HEADERS; ls *.h); do
        local src=$SRC_HEADERS/$i
        local changed=""
        for j in $ALL_PLATFORMS; do
            local dst=$NDK_PLATFORMS/$j/include/android/$i
            if [ "$changed" == "" -a -e $dst ]; then
                echo "Exists: $dst"
                if diff $src $dst >/dev/null; then
                    echo "$i: has not changed from $j" >/dev/null
                    changed="false"
                else
                    changed="true"
                    echo "$i: has changed from $j" >/dev/null
                fi
            fi
        done
        if [ "$changed" == "true" -o "$changed" == "" ]; then
            echo "Updating: $i"
            cp $src $NDK_PLATFORMS/$CURR_PLATFORM/include/android/$i
            didsomething="true"
        fi
    done

    if diff $SRC_LIB_ANDROID $DST_LIB_ANDROID >/dev/null; then
        echo "libandroid.so: has not changed"
    else
        echo "Updating: $DST_LIB_ANDROID"
        cp $SRC_LIB_ANDROID $DST_LIB_ANDROID
        didsomething="true"
    fi
    if [ "$didsomething" != "" ]; then
        echo "Headers changed...  rebuilding platforms."
        sh $ANDROID_BUILD_TOP/ndk/build/tools/build-platforms.sh
    fi
}

copyndkheaders
