set -e
make TextClassifierPerfTests
adb shell cmd package compile -m speed -f com.android.perftests.textclassifier
adb shell am instrument -w -e class android.view.textclassifier.TextClassifierPerfTest com.android.perftests.textclassifier/androidx.test.runner.AndroidJUnitRunner
