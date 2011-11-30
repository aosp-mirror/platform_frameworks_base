## NOTE
## This file is used for development purposes only. It is not used by the build system.

# generate protocol buffer files
genproto: gltrace.proto
	aprotoc --cpp_out=src --java_out=java gltrace.proto
	mv src/gltrace.pb.cc src/gltrace.pb.cpp

# NOTE: $OUT should be defined in the shell by doing a "lunch <config>"
# push updated files to device
push:
	adb push $(OUT)/system/lib/libGLESv2.so /system/lib/
	adb push $(OUT)/system/lib/libGLESv1_CM.so /system/lib/
	adb push $(OUT)/system/lib/libGLES_trace.so /system/lib/
	adb push $(OUT)/system/lib/libEGL.so /system/lib/
