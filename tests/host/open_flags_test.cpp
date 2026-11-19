#include <fcntl.h>

#include "check.h"
#include "zb/open_flags.h"

int main() {
    int host = -1;

    // Typical arm32 opendir() flags: O_DIRECTORY | O_CLOEXEC | O_LARGEFILE.
    CHECK(zb::translate_arm_open_flags(040000 | 02000000 | 0400000, host));
    CHECK((host & O_DIRECTORY) != 0);
    CHECK((host & O_CLOEXEC) != 0);
    CHECK((host & O_DIRECT) == 0);
    CHECK((host & O_NOFOLLOW) == 0);

    // Architecture-specific bits must be mapped, not copied to the host bitfield.
    CHECK(zb::translate_arm_open_flags(0100000, host));
    CHECK((host & O_NOFOLLOW) != 0);
    CHECK((host & O_DIRECTORY) == 0);

    CHECK(zb::translate_arm_open_flags(0200000, host));
    CHECK((host & O_DIRECT) != 0);

    CHECK(zb::translate_arm_open_flags(00000100 | 00000200 | 00002000, host));
    CHECK((host & O_CREAT) != 0);
    CHECK((host & O_EXCL) != 0);
    CHECK((host & O_APPEND) != 0);

    CHECK(!zb::translate_arm_open_flags(0x80000000u, host));

    std::puts("open_flags_test PASS");
    return 0;
}
