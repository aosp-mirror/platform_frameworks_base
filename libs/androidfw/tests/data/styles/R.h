#include <cstdint>

namespace com {
namespace android {
namespace app {

struct R {
  struct attr {
    enum : uint32_t {
      attr_one = 0x7f010000u,
      attr_two = 0x7f010001u,
      attr_three = 0x7f010002u,
      attr_four = 0x7f010003u,
      attr_five = 0x7f010004u,
      attr_indirect = 0x7f010005u,
    };
  };

  struct string {
      enum : uint32_t {
          string_one = 0x7f030000u,
      };
  };

  struct style {
    enum : uint32_t {
      StyleOne = 0x7f020000u,
      StyleTwo = 0x7f020001u,
    };
  };
};

}  // namespace app
}  // namespace android
}  // namespace com
