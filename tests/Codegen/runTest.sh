#!/usr/bin/env bash

if [[ "$0" = *"/Codegen/runTest.sh" ]]; then
	#running in subshell - print code to eval and exit
	echo "source $0"
else
    function header_and_eval() {
        printf "\n[ $* ]\n" 1>&2
        eval "$@"
        return $?
    }

    header_and_eval m -j16 codegen_cli && \
        header_and_eval codegen $ANDROID_BUILD_TOP/frameworks/base/tests/Codegen/src/com/android/codegentest/SampleDataClass.java && \
        header_and_eval codegen $ANDROID_BUILD_TOP/frameworks/base/tests/Codegen/src/com/android/codegentest/SampleWithCustomBuilder.java && \
        header_and_eval codegen $ANDROID_BUILD_TOP/frameworks/base/tests/Codegen/src/com/android/codegentest/ParcelAllTheThingsDataClass.java && \
        header_and_eval codegen $ANDROID_BUILD_TOP/frameworks/base/tests/Codegen/src/com/android/codegentest/HierrarchicalDataClassBase.java && \
        header_and_eval codegen $ANDROID_BUILD_TOP/frameworks/base/tests/Codegen/src/com/android/codegentest/HierrarchicalDataClassChild.java && \
        header_and_eval codegen $ANDROID_BUILD_TOP/frameworks/base/tests/Codegen/src/com/android/codegentest/StaleDataclassDetectorFalsePositivesTest.java && \
        header_and_eval codegen $ANDROID_BUILD_TOP/frameworks/base/tests/Codegen/src/com/android/codegentest/SampleWithNestedDataClasses.java && \
        (
            cd $ANDROID_BUILD_TOP &&
            header_and_eval mmma -j16 frameworks/base/tests/Codegen && \
            header_and_eval adb install -r -t "$(find $ANDROID_TARGET_OUT_TESTCASES -name 'CodegenTests.apk')" && \
            # header_and_eval adb shell am set-debug-app -w com.android.codegentest && \
            header_and_eval adb shell am instrument -w -e package com.android.codegentest com.android.codegentest/androidx.test.runner.AndroidJUnitRunner
        )

        exitCode=$?

        # header_and_eval adb shell am clear-debug-app

        return $exitCode
fi