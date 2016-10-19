#
# This Makefile will generate the JNI headers for the Aapt2 class.
#

AAPT2_PKG=com.android.tools.aapt2
AAPT2_DIR=$(shell echo -n com/android/tools/aapt2 | tr . /)
OUT=out
OUT_CLASSES=$(OUT)/classes
OUT_HEADERS=.

AAPT2_JAVA=Aapt2.java
AAPT2_CLASSES=$(OUT_CLASSES)/$(AAPT2_DIR)/Aapt2.class

AAPT2_HEADERS=$(OUT_HEADERS)/Aapt2.h

all: $(AAPT2_HEADERS)

$(AAPT2_HEADERS): $(AAPT2_JAVA) $(AAPT2_CLASSES)
	mkdir -p $(OUT_HEADERS)
	$(JAVA_HOME)/bin/javah -d $(OUT_HEADERS) -cp $(OUT_CLASSES) $(AAPT2_PKG).Aapt2

$(AAPT2_CLASSES): $(AAPT2_JAVA)
	mkdir -p $(OUT_CLASSES)
	javac -d $(OUT_CLASSES) $(AAPT2_JAVA)

