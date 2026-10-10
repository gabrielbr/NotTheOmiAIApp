// Host check of the fast-core picker GMind uses to keep llama.cpp off the little cores.
#include "cpu_tiers.h"
#include <cassert>
#include <iostream>
using gmind::fast_cores;
int main() {
    // Tensor G4: 4x A520, 3x A720, 1x X4 -> the 4 big cores.
    assert(fast_cores({1950000, 1950000, 1950000, 1950000, 2600000, 2600000, 2600000, 3100000})
           == std::vector<int>({4, 5, 6, 7}));
    assert(fast_cores({2000000, 2000000, 2000000, 2000000}).empty());   // all alike: no pinning
    assert(fast_cores({0, 0, 0}).empty());                               // unknown: no pinning
    assert(fast_cores({}).empty());
    assert(fast_cores({0, 1800000, 2400000}) == std::vector<int>({2})); // unknown cores are not "fast"
    std::cout << "cpu tiers checks passed\n";
}
