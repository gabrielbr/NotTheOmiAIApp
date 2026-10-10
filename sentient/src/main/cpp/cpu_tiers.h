#pragma once
#include <cstddef>
#include <vector>

namespace gmind {
// Cores above the slowest frequency tier (the big cores of a big.LITTLE phone), from each core's
// maximum frequency (0 when unknown). Empty when every core is alike or nothing is known.
// Same rule as GVoice's whisper_safety.h (the two apps build separately).
inline std::vector<int> fast_cores(const std::vector<long> &max_khz) {
    long lowest = 0;
    for (long khz : max_khz) if (khz > 0 && (lowest == 0 || khz < lowest)) lowest = khz;
    std::vector<int> fast;
    if (lowest == 0) return fast;
    for (size_t i = 0; i < max_khz.size(); ++i) if (max_khz[i] > lowest) fast.push_back(static_cast<int>(i));
    return fast;
}
}  // namespace gmind
