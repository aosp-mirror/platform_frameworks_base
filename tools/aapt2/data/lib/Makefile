##
# Environment dependent variables
##

AAPT := aapt2
ZIPALIGN := zipalign -f 4
FRAMEWORK := ../../../../../../out/target/common/obj/APPS/framework-res_intermediates/package-export.apk

##
# Project depenedent variables
##

LOCAL_PACKAGE := android.appcompat
LOCAL_RESOURCE_DIR := res
LOCAL_OUT := out
LOCAL_GEN := out/gen

##
# AAPT2 custom rules.
##

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

# Eg: out/values-v4.apk, out/drawable-xhdpi.apk
PRIVATE_INTERMEDIATE_TABLES := $(patsubst %,$(LOCAL_OUT)/%.apk,$(PRIVATE_RESOURCE_TYPES))
$(info PRIVATE_INTERMEDIATE_TABLES = $(PRIVATE_INTERMEDIATE_TABLES))

# Generates rules for collect phase.
# $1: Resource type (values-v4)
# returns: out/values-v4.apk: res/values-v4/styles.xml res/values-v4/colors.xml
define make-collect-rule
$(LOCAL_OUT)/$1.apk: $(filter $(LOCAL_RESOURCE_DIR)/$1/%,$(PRIVATE_RESOURCES))
	$(AAPT) compile -o $$@ $$^
endef

# Collect: out/values-v4.apk <- res/values-v4/styles.xml res/values-v4/colors.xml
$(foreach d,$(PRIVATE_RESOURCE_TYPES),$(eval $(call make-collect-rule,$d)))

# Link: out/package-unaligned.apk <- out/values-v4.apk out/drawable-v4.apk
$(PRIVATE_APK_UNALIGNED): $(PRIVATE_INTERMEDIATE_TABLES) $(PRIVATE_LIBS) AndroidManifest.xml
	$(AAPT) link --manifest AndroidManifest.xml $(addprefix -I ,$(PRIVATE_LIBS)) --java $(LOCAL_GEN) -o $@ $(PRIVATE_INTERMEDIATE_TABLES) --static-lib

# R.java: gen/com/android/app/R.java <- out/resources.arsc
# No action since R.java is generated when out/resources.arsc is.
$(PRIVATE_R_JAVA): $(PRIVATE_APK_UNALIGNED)

# Assemble: zip out/resources.arsc AndroidManifest.xml and res/**/*
$(PRIVATE_APK_ALIGNED): $(PRIVATE_APK_UNALIGNED)
	$(ZIPALIGN) $< $@

# Create the out directory if needed.
dummy := $(shell test -d $(LOCAL_OUT) || mkdir -p $(LOCAL_OUT))

.PHONY: java
java: $(PRIVATE_R_JAVA)

.PHONY: assemble
assemble: $(PRIVATE_APK_ALIGNED)

.PHONY: all
all: assemble java

.DEFAULT_GOAL := all
