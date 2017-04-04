###############################################################################
#
#
# This file contains the shared and static dependencies needed by any target
# that attempts to statically link HWUI (i.e. libhwui_static build target). This
# file should be included by any target that lists libhwui_static as a
# dependency.
#
# This is a workaround for the fact that the build system does not add these
# transitive dependencies when it attempts to link libhwui_static into another
# library.
#
###############################################################################

LOCAL_SHARED_LIBRARIES += \
    liblog \
    libcutils \
    libutils \
    libEGL \
    libGLESv2 \
    libvulkan \
    libskia \
    libui \
    libgui \
    libprotobuf-cpp-full \
    libharfbuzz_ng \
    libft2 \
    libminikin \
    libandroidfw \
    libRScpp

LOCAL_STATIC_LIBRARIES += \
    libplatformprotos
