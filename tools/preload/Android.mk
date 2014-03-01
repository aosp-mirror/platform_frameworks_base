LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	Compile.java  \
	LoadedClass.java \
	MemoryUsage.java \
	Operation.java \
	Policy.java \
	PrintCsv.java \
	PrintHtmlDiff.java \
	PrintPsTree.java \
	Proc.java \
	Record.java \
	Root.java \
	WritePreloadedClassFile.java

LOCAL_MODULE:= preload

include $(BUILD_HOST_JAVA_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
