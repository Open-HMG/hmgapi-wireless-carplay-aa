#!/bin/sh
# Make recipes/carplay-utils-converter.recipe from a stock libCarPlayUtils.so
# (maintainers, with a private stock copy; the recipe holds no stock bytes):
# assemble utils_tramp.s (the five AudioConverter* stubs forward to libleafiaaac.so),
# splice it in at its addresses, and diff against stock with tools/mkrecipe.py.
#   native/leafiaaac/make-recipe.sh <stock libCarPlayUtils.so>
# dlopen_plt / dlsym_plt: the stock library's own PLT entries for dlopen and dlsym.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
STOCK=${1:?usage: make-recipe.sh <stock libCarPlayUtils.so>}
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
clang --target=armv7a-linux-gnueabi -c -o "$W/tramp.o" "$HERE/utils_tramp.s"
ld.lld -T "$HERE/utils.ld" --defsym dlopen_plt=0x33f0 --defsym dlsym_plt=0x33fc -o "$W/tramp.elf" "$W/tramp.o" 2>/dev/null
python3 "$HERE/splice.py" "$W/tramp.elf" "$STOCK" "$W/patched.so" >/dev/null
python3 "$REPO/tools/mkrecipe.py" carplay-utils-converter /system/lib/libCarPlayUtils.so "$STOCK" "$W/patched.so" \
    --native e85385d70194efe82cc8a6d0f66f5115:built-in-aac-opus-converters \
    --desc "libCarPlayUtils: forward the stub AudioConverter* calls to libleafiaaac.so (AAC/Opus via the head unit's own codecs)" \
    > "$HERE/../../recipes/carplay-utils-converter.recipe"
md5sum "$W/patched.so"
