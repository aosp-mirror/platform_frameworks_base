set -e
make TextClassifierPerfTests perf-setup.sh
adb install ${OUT}/testcases/TextClassifierPerfTests/arm64/TextClassifierPerfTests.apk
adb shell cmd package compile -m speed -f com.android.perftests.textclassifier
adb push ${OUT}/obj/EXECUTABLES/perf-setup.sh_intermediates/perf-setup.sh /data/local/tmp/
adb shell chmod +x /data/local/tmp/perf-setup.sh
adb shell /data/local/tmp/perf-setup.sh
adb shell am instrument -w -e package android.view.textclassifier com.android.perftests.textclassifier/androidx.test.runner.AndroidJUnitRunner