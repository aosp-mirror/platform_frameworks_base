##
# Environment dependent variables
##

SHELL := /bin/bash
AAPT := aapt2
ZIP := zip -n .arsc:.png:AndroidManifest.xml
ZIPALIGN := zipalign 4
FRAMEWORK := ../../../../../out/target/common/obj/APPS/framework-res_intermediates/package-export.apk

##
# Project depenedent variables
##

LOCAL_PACKAGE := com.android.app
LOCAL_RESOURCE_DIR := res
LOCAL_OUT := out
LOCAL_GEN := out/gen

##
# AAPT2 custom rules.
##

PRIVATE_ARSC := $(LOCAL_OUT)/resources.arsc
PRIVATE_APK_UNALIGNED := $(LOCAL_OUT)/package-unaligned.apk
PRIVATE_APK_ALIGNED := $(LOCAL_OUT)/package.apk

# Eg: framework.apk, etc.
PRIVATE_LIBS := $(FRAMEWORK)
$(info PRIVATE_LIBS = $(PRIVATE_LIBS))

# Eg: gen/com/android/app/R.java
PRIVATE_R_JAVA := $(LOCAL_GEN)/$(subst .,/,$(LOCAL_PACKAGE))/R.java
$(info PRIVATE_R_JAVA = $(PRIVATE_R_JAVA))

# Eg: res/drawable/icon.png, res/values/styles.xml
PRIVATE_RESOURCES := $(shell find $(LOCAL_RESOURCE_DIR) -mindepth 1 -maxdepth 2 -type f)
$(info PRIVATE_RESOURCES = $(PRIVATE_RESOURCES))

# Eg: drawable, values, layouts
PRIVATE_RESOURCE_TYPES := \
	$(patsubst $(LOCAL_RESOURCE_DIR)/%/,%,$(sort $(dir $(PRIVATE_RESOURCES))))
$(info PRIVATE_RESOURCE_TYPES = $(PRIVATE_RESOURCE_TYPES))

# Eg: drawable, drawable-xhdpi, layout
PRIVATE_NON_VALUE_RESOURCE_TYPES := $(filter-out values%,$(PRIVATE_RESOURCE_TYPES))
$(info PRIVATE_NON_VALUE_RESOURCE_TYPES = $(PRIVATE_NON_VALUE_RESOURCE_TYPES))

# Eg: out/values-v4.table, out/drawable-xhdpi.table
PRIVATE_INTERMEDIATE_TABLES := $(patsubst %,$(LOCAL_OUT)/%.table,$(PRIVATE_RESOURCE_TYPES))
$(info PRIVATE_INTERMEDIATE_TABLES = $(PRIVATE_INTERMEDIATE_TABLES))

# Eg: out/res/layout/main.xml, out/res/drawable/icon.png
PRIVATE_INTERMEDIATE_FILES := $(patsubst $(LOCAL_RESOURCE_DIR)/%,$(LOCAL_OUT)/res/%,$(filter-out $(LOCAL_RESOURCE_DIR)/values%,$(PRIVATE_RESOURCES)))
$(info PRIVATE_INTERMEDIATE_FILES = $(PRIVATE_INTERMEDIATE_FILES))

# Generates rules for collect phase.
# $1: Resource type (values-v4)
# returns: out/values-v4.table: res/values-v4/styles.xml res/values-v4/colors.xml
define make-collect-rule
$(LOCAL_OUT)/$1.table: $(filter $(LOCAL_RESOURCE_DIR)/$1/%,$(PRIVATE_RESOURCES))
	$(AAPT) collect --package $(LOCAL_PACKAGE) -o $$@ $$^
endef

# Collect: out/values-v4.table <- res/values-v4/styles.xml res/values-v4/colors.xml
$(foreach d,$(PRIVATE_RESOURCE_TYPES),$(eval $(call make-collect-rule,$d)))

# Link: out/resources.arsc <- out/values-v4.table out/drawable-v4.table
$(PRIVATE_ARSC): $(PRIVATE_INTERMEDIATE_TABLES) $(PRIVATE_LIBS)
	$(AAPT) link --package $(LOCAL_PACKAGE) $(addprefix -I ,$(PRIVATE_LIBS)) --java $(LOCAL_GEN) -o $@ $(PRIVATE_INTERMEDIATE_TABLES)

# Compile Manifest: out/AndroidManifest.xml <- AndroidManifest.xml out/resources.arsc
$(LOCAL_OUT)/AndroidManifest.xml: AndroidManifest.xml $(PRIVATE_ARSC) $(PRIVATE_LIBS)
	$(AAPT) manifest -I $(PRIVATE_ARSC) $(addprefix -I ,$(PRIVATE_LIBS)) -o $(LOCAL_OUT) AndroidManifest.xml

# Generates rules for compile phase.
# $1: resource file (res/drawable/icon.png)
# returns: out/res/drawable/icon.png: res/drawable/icon.png out/resources.arsc
define make-compile-rule
$1: $(patsubst $(LOCAL_OUT)/res/%,$(LOCAL_RESOURCE_DIR)/%,$1) $(PRIVATE_ARSC) $(PRIVATE_LIBS)
	$(AAPT) compile --package $(LOCAL_PACKAGE) -I $(PRIVATE_ARSC) $(addprefix -I ,$(PRIVATE_LIBS)) -o $(LOCAL_OUT) $$<
endef

# Compile: out/res/drawable-xhdpi/icon.png <- res/drawable-xhdpi/icon.png
$(foreach f,$(PRIVATE_INTERMEDIATE_FILES),$(eval $(call make-compile-rule,$f)))

# R.java: gen/com/android/app/R.java <- out/resources.arsc
# No action since R.java is generated when out/resources.arsc is.
$(PRIVATE_R_JAVA): $(PRIVATE_ARSC)

# Assemble: zip out/resources.arsc AndroidManifest.xml and res/**/*
$(PRIVATE_APK_ALIGNED): $(PRIVATE_ARSC) $(PRIVATE_INTERMEDIATE_FILES) $(LOCAL_OUT)/AndroidManifest.xml
	cd $(LOCAL_OUT); $(ZIP) $(patsubst $(LOCAL_OUT)/%,%,$(PRIVATE_APK_UNALIGNED)) $(patsubst $(LOCAL_OUT)/%,%,$^)
	$(ZIPALIGN) $(PRIVATE_APK_UNALIGNED) $@

# Create the out directory if needed.
dummy := $(shell test -d $(LOCAL_OUT) || mkdir -p $(LOCAL_OUT))

.PHONY: java
java: $(PRIVATE_R_JAVA)

.PHONY: assemble
assemble: $(LOCAL_OUT)/package.apk

.PHONY: all
all: assemble java

.DEFAULT_GOAL := all
