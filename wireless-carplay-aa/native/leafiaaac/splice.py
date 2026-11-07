# Splice an ELF's .t_* sections into a copy of a stock library, at their addresses
# (which are file offsets in libCarPlayUtils.so).
# usage: splice.py <elf> <stock lib> <out>
import struct, sys
elf, stock, out = sys.argv[1:4]
e = open(elf, 'rb').read()
shoff, = struct.unpack_from('<I', e, 0x20); shentsize, shnum, shstrndx = struct.unpack_from('<HHH', e, 0x2e)
secs = [struct.unpack_from('<IIIIIIIIII', e, shoff + i * shentsize) for i in range(shnum)]
strtab = secs[shstrndx]
name = lambda o: e[strtab[4] + o:e.index(b'\0', strtab[4] + o)].decode()
b = bytearray(open(stock, 'rb').read())
for s in secs:
    n = name(s[0])
    if n.startswith('.t_'):
        addr, off, size = s[3], s[4], s[5]
        b[addr:addr + size] = e[off:off + size]
        print('%-10s 0x%x %d bytes' % (n, addr, size))
open(out, 'wb').write(b)
