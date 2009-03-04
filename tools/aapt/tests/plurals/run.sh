TEST_DIR=tools/aapt/tests/plurals
TEST_OUT_DIR=out/plurals_test

rm -rf $TEST_OUT_DIR
mkdir -p $TEST_OUT_DIR
mkdir -p $TEST_OUT_DIR/java

#gdb --args \
aapt package -v -x -m -z  -J $TEST_OUT_DIR/java -M $TEST_DIR/AndroidManifest.xml \
             -I out/target/common/obj/APPS/framework-res_intermediates/package-export.apk \
             -P $TEST_OUT_DIR/public_resources.xml \
             -S $TEST_DIR/res

echo
echo "==================== FILES CREATED ==================== "
find $TEST_OUT_DIR -type f
