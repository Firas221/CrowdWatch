package crowdwatch.net;

import java.nio.charset.StandardCharsets;

/**
 * A small QR code encoder (ISO/IEC 18004): byte mode, error correction level M (about 15 % of the
 * code may be damaged), versions 1 to 10 (up to 213 bytes, plenty for a web address). Used to
 * print the help-page codes that visitors scan. Drawn as SVG, so it prints sharply at any size.
 * The construction follows the standard step by step: data bits, Reed-Solomon error correction
 * in blocks, function patterns, zig-zag placement, the mask with the lowest penalty, format and
 * version information.
 */
public final class QrCode {
    // error correction codewords per block and number of blocks, level M, versions 0 (unused) to 10
    private static final int[] ECC_PER_BLOCK = { -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26 };
    private static final int[] BLOCKS = { -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5 };
    /** Largest version made. */
    public static final int MAX_VERSION = 10;

    /** Version (1 to 10) and side length in modules. */
    public final int version, size;
    /** The mask pattern chosen (0 to 7). */
    public final int mask;
    private final boolean[][] modules, function;

    /** @return true if the module at column x, row y is dark */
    public boolean get(int x, int y) { return modules[y][x]; }

    /**
     * Encodes text (as UTF-8) in the smallest version that holds it.
     *
     * @param forcedMask a mask 0 to 7, or -1 to choose the best (tests compare with other encoders)
     * @throws IllegalArgumentException if the text is too long for version 10
     */
    public static QrCode encode(String text, int forcedMask) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= MAX_VERSION; v++) {
            int capacityBits = dataCodewords(v) * 8;
            int used = 4 + (v <= 9 ? 8 : 16) + data.length * 8;
            if (used <= capacityBits) return new QrCode(v, data, forcedMask);
        }
        throw new IllegalArgumentException("text too long for a QR code of version " + MAX_VERSION);
    }

    /** @return a QR code for the text with the best mask */
    public static QrCode encode(String text) { return encode(text, -1); }

    private QrCode(int ver, byte[] data, int forcedMask) {
        version = ver;
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
        // data bits: mode, count, bytes, terminator, padding
        int capacity = dataCodewords(ver) * 8;
        BitBuffer bb = new BitBuffer();
        bb.append(4, 4);
        bb.append(data.length, ver <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacity; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] codewords = new byte[bb.length / 8];
        for (int i = 0; i < bb.length; i++) codewords[i >>> 3] |= (byte) (bb.bit(i) << (7 - (i & 7)));
        byte[] all = addEccAndInterleave(codewords);
        drawFunctionPatterns();
        drawCodewords(all);
        int best = forcedMask;
        if (best < 0) {
            int bestPenalty = Integer.MAX_VALUE;
            for (int k = 0; k < 8; k++) {
                applyMask(k);
                drawFormatBits(k);
                int p = penalty();
                if (p < bestPenalty) { bestPenalty = p; best = k; }
                applyMask(k);   // undo
            }
        }
        mask = best;
        applyMask(mask);
        drawFormatBits(mask);
    }

    // ------------------------------------------------------------------ sizes

    private static int rawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int ver) { return rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]; }

    // ------------------------------------------------------------------ error correction

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = BLOCKS[version], blockEccLen = ECC_PER_BLOCK[version];
        int rawCodewords = rawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;
        byte[][] blocks = new byte[numBlocks][];
        byte[] divisor = rsDivisor(blockEccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortBlockLen + 1];
            System.arraycopy(dat, 0, block, 0, datLen);
            // short blocks get a placeholder byte so every block has the same layout; it is skipped below
            System.arraycopy(ecc, 0, block, block.length - blockEccLen, blockEccLen);
            blocks[i] = block;
        }
        byte[] result = new byte[rawCodewords];
        int n = 0;
        for (int i = 0; i < blocks[0].length; i++)
            for (int j = 0; j < blocks.length; j++)
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) result[n++] = blocks[j][i];
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) multiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    // multiplication in GF(2^8) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ------------------------------------------------------------------ drawing

    private void set(int x, int y, boolean dark) { modules[y][x] = dark; function[y][x] = true; }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
        int[] pos = alignmentPositions();
        int n = pos.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) alignment(pos[i], pos[j]);
        drawFormatBits(0);   // reserves the format areas; redrawn with the real mask
        drawVersion();
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, dist != 2 && dist != 4);
            }
    }

    private void alignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int numAlign = version / 7 + 2;
        int step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = result.length - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private void drawFormatBits(int msk) {
        int data = (0 << 3) | msk;   // level M has format bits 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3, c = i / 3;
            set(a, c, b);
            set(c, a, b);
        }
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
        }
    }

    private void applyMask(int msk) {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (msk) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                modules[y][x] ^= invert & !function[y][x];
            }
    }

    // a simplified penalty (runs of five or more, 2x2 blocks, dark/light balance): enough to avoid poor masks
    private int penalty() {
        int result = 0;
        for (int y = 0; y < size; y++) {
            int runX = 1, runY = 1;
            for (int x = 1; x < size; x++) {
                if (modules[y][x] == modules[y][x - 1]) { runX++; if (runX == 5) result += 3; else if (runX > 5) result++; } else runX = 1;
                if (modules[x][y] == modules[x - 1][y]) { runY++; if (runY == 5) result += 3; else if (runY > 5) result++; } else runY = 1;
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x > 0 && y > 0 && modules[y][x] == modules[y][x - 1] && modules[y][x] == modules[y - 1][x] && modules[y][x] == modules[y - 1][x - 1]) result += 3;
            }
        int total = size * size;
        result += (Math.abs(dark * 20 - total * 10) + total - 1) / total * 10;
        return result;
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    /** @return the code as an SVG image with a 4-module quiet zone */
    public String svg(int pixelsPerModule) {
        int border = 4, dim = size + border * 2;
        StringBuilder sb = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ").append(dim).append(' ').append(dim)
            .append("' width='").append(dim * pixelsPerModule).append("' height='").append(dim * pixelsPerModule).append("' shape-rendering='crispEdges'><rect width='100%' height='100%' fill='#fff'/><path d='");
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) if (modules[y][x]) sb.append('M').append(x + border).append(',').append(y + border).append("h1v1h-1z");
        return sb.append("' fill='#000'/></svg>").toString();
    }

    // a growing list of bits
    private static final class BitBuffer {
        private boolean[] bits = new boolean[64];
        int length;
        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                if (length == bits.length) bits = java.util.Arrays.copyOf(bits, length * 2);
                bits[length++] = ((value >>> i) & 1) != 0;
            }
        }
        int bit(int i) { return bits[i] ? 1 : 0; }
    }
}
