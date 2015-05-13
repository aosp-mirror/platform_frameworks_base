#ifndef AAPT_FLAG_H
#define AAPT_FLAG_H

#include "StringPiece.h"

#include <functional>
#include <string>
#include <vector>

namespace aapt {
namespace flag {

void requiredFlag(const StringPiece& name, const StringPiece& description,
                  std::function<void(const StringPiece&)> action);

void requiredFlag(const StringPiece& name, const StringPiece& description,
                  std::function<bool(const StringPiece&, std::string*)> action);

void optionalFlag(const StringPiece& name, const StringPiece& description,
                  std::function<void(const StringPiece&)> action);

void optionalSwitch(const StringPiece& name, const StringPiece& description, bool resultWhenSet,
                    bool* result);

void usageAndDie(const StringPiece& command);

void parse(int argc, char** argv, const StringPiece& command);

const std::vector<std::string>& getArgs();

} // namespace flag
} // namespace aapt

#endif // AAPT_FLAG_H
