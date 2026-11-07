#!/bin/sh
# Build libleafiaaac.so: the AAC/Opus AudioConverter for wireless CarPlay audio
# (Leafia's own code; it uses the head unit's own Android codecs).
#   native/leafiaaac/build.sh [output]   (default: ../../files/libleafiaaac.so)
# No libc or NDK: the library only imports a few functions from the head unit's
# libc.so, libdl.so and liblog.so, so tiny stub libraries with those names are
# linked in to record them as dependencies. Any recent clang + lld.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/../../files/libleafiaaac.so}
case "$OUT" in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
CLANG=${CLANG:-clang}
T="--target=armv7a-linux-androideabi -mthumb -march=armv7-a -mfloat-abi=softfp"
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
echo 'void *calloc(unsigned a, unsigned b) { return 0; } void free(void *p) {} void *malloc(unsigned n) { return 0; }
void *memcpy(void *d, const void *s, unsigned n) { return d; } void *memset(void *d, int c, unsigned n) { return d; }' > "$W/c.c"
echo 'void *dlopen(const char *f, int m) { return 0; } void *dlsym(void *h, const char *s) { return 0; }' > "$W/dl.c"
echo 'int __android_log_print(int p, const char *t, const char *f, ...) { return 0; }' > "$W/log.c"
for s in c:libc dl:libdl log:liblog; do
    "$CLANG" $T -shared -nostdlib -fuse-ld=lld -Wl,-soname,${s#*:}.so -o "$W/${s#*:}.so" "$W/${s%%:*}.c"
done
cp "$HERE/leafiaaac.c" "$W/"
# -ffreestanding keeps memcpy/memset as plain calls (not __aeabi_memcpy/__aeabi_memclr)
(cd "$W" && "$CLANG" $T -Os -fPIC -ffreestanding -fno-exceptions -fno-asynchronous-unwind-tables \
    -shared -nostdlib -fuse-ld=lld -Wl,-z,now -Wl,--no-rosegment -Wl,--hash-style=sysv -Wl,--build-id=none \
    -Wl,-soname,libleafiaaac.so -o "$OUT" leafiaaac.c libc.so libdl.so liblog.so)
md5sum "$OUT"
