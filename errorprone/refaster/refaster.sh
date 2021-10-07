
paths=(
    $ANDROID_BUILD_TOP/out/soong/.intermediates/frameworks/base/framework/android_common/turbine-combined/framework.jar
    $ANDROID_BUILD_TOP/out/soong/.intermediates/libcore/core-all/android_common/turbine-combined/core-all.jar
    $ANDROID_BUILD_TOP/external/error_prone/error_prone/error_prone_refaster-2.4.0.jar
)

javac -cp "$(IFS=:; echo "${paths[*]}")" \
    "-Xplugin:RefasterRuleCompiler --out $1.refaster" $1

rm *.class
