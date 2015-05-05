#include "Flag.h"
#include "StringPiece.h"

#include <functional>
#include <iomanip>
#include <iostream>
#include <string>
#include <vector>

namespace aapt {
namespace flag {

struct Flag {
    std::string name;
    std::string description;
    std::function<void(const StringPiece&)> action;
    bool required;
    bool* flagResult;
    bool flagValueWhenSet;
    bool parsed;
};

static std::vector<Flag> sFlags;
static std::vector<std::string> sArgs;

void optionalFlag(const StringPiece& name, const StringPiece& description,
                  std::function<void(const StringPiece&)> action) {
    sFlags.push_back(
            Flag{ name.toString(), description.toString(), action, false, nullptr, false, false });
}

void requiredFlag(const StringPiece& name, const StringPiece& description,
                  std::function<void(const StringPiece&)> action) {
    sFlags.push_back(
            Flag{ name.toString(), description.toString(), action, true, nullptr, false, false });
}

void optionalSwitch(const StringPiece& name, const StringPiece& description, bool resultWhenSet,
                    bool* result) {
    sFlags.push_back(Flag{
            name.toString(), description.toString(), {}, false, result, resultWhenSet, false });
}

void usageAndDie(const StringPiece& command) {
    std::cerr << command << " [options]";
    for (const Flag& flag : sFlags) {
        if (flag.required) {
            std::cerr << " " << flag.name << " arg";
        }
    }
    std::cerr << " files..." << std::endl << std::endl << "Options:" << std::endl;

    for (const Flag& flag : sFlags) {
        std::string command = flag.name;
        if (!flag.flagResult) {
            command += " arg ";
        }
        std::cerr << "  " << std::setw(30) << std::left << command
                  << flag.description << std::endl;
    }
    exit(1);
}

void parse(int argc, char** argv, const StringPiece& command) {
    for (int i = 0; i < argc; i++) {
        const StringPiece arg(argv[i]);
        if (*arg.data() != '-') {
            sArgs.push_back(arg.toString());
            continue;
        }

        bool match = false;
        for (Flag& flag : sFlags) {
            if (arg == flag.name) {
                match = true;
                flag.parsed = true;
                if (flag.flagResult) {
                    *flag.flagResult = flag.flagValueWhenSet;
                } else {
                    i++;
                    if (i >= argc) {
                        std::cerr << flag.name << " missing argument." << std::endl
                                  << std::endl;
                        usageAndDie(command);
                    }
                    flag.action(argv[i]);
                }
                break;
            }
        }

        if (!match) {
            std::cerr << "unknown option '" << arg << "'." << std::endl << std::endl;
            usageAndDie(command);
        }
    }

    for (const Flag& flag : sFlags) {
        if (flag.required && !flag.parsed) {
            std::cerr << "missing required flag " << flag.name << std::endl << std::endl;
            usageAndDie(command);
        }
    }
}

const std::vector<std::string>& getArgs() {
    return sArgs;
}

} // namespace flag
} // namespace aapt
