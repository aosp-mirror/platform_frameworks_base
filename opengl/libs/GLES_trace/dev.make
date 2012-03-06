## NOTE
## This file is used for development purposes only. It is not used by the build system.

# generate protocol buffer files
genproto: gltrace.proto
	aprotoc --cpp_out=src --java_out=java gltrace.proto
	mv src/gltrace.pb.cc src/gltrace.pb.cpp

sync:
	adb root
	adb remount
	adb shell stop
	adb sync
	adb shell start
