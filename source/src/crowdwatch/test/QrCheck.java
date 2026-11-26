package crowdwatch.test;

import java.nio.charset.StandardCharsets;
import crowdwatch.net.QrCode;

/**
 * Reads a QR code back without using the encoder's code, to check it as a scanner would: the
 * format information (with its BCH check), the mask, the codewords in zig-zag order, the
 * Reed-Solomon check of every block (all syndromes zero) and the byte-mode text.
 */
final class QrCheck {
    private QrCheck() { }

    private static final int[] ECC = { -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26 }, BLOCKS = { -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5 };

    /** @return the decoded text, or a message starting with "ERROR" */
    static String decode(QrCode q) {
        int size = q.size, ver = (size - 17) / 4;
        // format bits, first copy
        int bits = 0;
        int[][] at = { { 8, 0 }, { 8, 1 }, { 8, 2 }, { 8, 3 }, { 8, 4 }, { 8, 5 }, { 8, 7 }, { 8, 8 }, { 7, 8 }, { 5, 8 }, { 4, 8 }, { 3, 8 }, { 2, 8 }, { 1, 8 }, { 0, 8 } };
        for (int i = 0; i < 15; i++) if (q.get(at[i][0], at[i][1])) bits |= 1 << i;
        bits ^= 0x5412;
        int data = bits >>> 10, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        if ((data << 10 | (rem & 0x3FF)) != bits) return "ERROR format BCH";
        if ((data >>> 3) != 0) return "ERROR level is not M";
        int mask = data & 7;
        // which modules are function patterns
        boolean[][] fn = new boolean[size][size];
        for (int i = 0; i < size; i++) { fn[6][i] = true; fn[i][6] = true; }
        for (int y = 0; y < 9; y++) for (int x = 0; x < 9; x++) fn[y][x] = true;
        for (int y = 0; y < 9; y++) for (int x = size - 8; x < size; x++) fn[y][x] = true;
        for (int y = size - 8; y < size; y++) for (int x = 0; x < 9; x++) fn[y][x] = true;
        if (ver >= 2) {
            int n = ver / 7 + 2, step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
            int[] pos = new int[n];
            pos[0] = 6;
            for (int i = n - 1, p = size - 7; i >= 1; i--, p -= step) pos[i] = p;
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue;
                for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) fn[pos[i] + dy][pos[j] + dx] = true;
            }
        }
        if (ver >= 7) for (int i = 0; i < 18; i++) { fn[i / 3][size - 11 + i % 3] = true; fn[size - 11 + i % 3][i / 3] = true; }
        // read the codewords
        int raw = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) if (!fn[y][x]) raw++;
        byte[] cw = new byte[raw / 8];
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) for (int j = 0; j < 2; j++) {
                int x = right - j, y = ((right + 1) & 2) == 0 ? size - 1 - vert : vert;
                if (fn[y][x] || i >= cw.length * 8) continue;
                boolean b = q.get(x, y) ^ maskBit(mask, x, y);
                if (b) cw[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
                i++;
            }
        }
        // de-interleave the blocks and check them
        int nb = BLOCKS[ver], ecc = ECC[ver], total = cw.length, shortLen = total / nb, numShort = nb - total % nb;
        byte[][] blocks = new byte[nb][];
        for (int b = 0; b < nb; b++) blocks[b] = new byte[shortLen + (b < numShort ? 0 : 1)];
        int k = 0;
        for (int c = 0; c < shortLen + 1; c++)
            for (int b = 0; b < nb; b++) {
                int dataLen = blocks[b].length - ecc;
                if (c < dataLen) blocks[b][c] = cw[k++];
            }
        for (int c = 0; c < ecc; c++) for (int b = 0; b < nb; b++) blocks[b][blocks[b].length - ecc + c] = cw[k++];
        StringBuilder dataBits = new StringBuilder();
        for (byte[] bl : blocks) {
            for (int s = 0; s < ecc; s++) {
                int alpha = pow(s), acc = 0;
                for (byte v : bl) acc = mul(acc, alpha) ^ (v & 0xFF);
                if (acc != 0) return "ERROR Reed-Solomon syndrome " + s;
            }
            for (int d = 0; d < bl.length - ecc; d++) dataBits.append(String.format("%8s", Integer.toBinaryString(bl[d] & 0xFF)).replace(' ', '0'));
        }
        if (!dataBits.substring(0, 4).equals("0100")) return "ERROR not byte mode";
        int cbits = ver <= 9 ? 8 : 16, count = Integer.parseInt(dataBits.substring(4, 4 + cbits), 2);
        byte[] out = new byte[count];
        for (int c = 0; c < count; c++) out[c] = (byte) Integer.parseInt(dataBits.substring(4 + cbits + c * 8, 12 + cbits + c * 8), 2);
        return new String(out, StandardCharsets.UTF_8);
    }

    /** @return the format bits for level M and mask 0 as the standard's table gives them */
    static String formatM0(QrCode q) {
        StringBuilder sb = new StringBuilder();
        int[][] at = { { 8, 0 }, { 8, 1 }, { 8, 2 }, { 8, 3 }, { 8, 4 }, { 8, 5 }, { 8, 7 }, { 8, 8 }, { 7, 8 }, { 5, 8 }, { 4, 8 }, { 3, 8 }, { 2, 8 }, { 1, 8 }, { 0, 8 } };
        for (int i = 14; i >= 0; i--) sb.append(q.get(at[i][0], at[i][1]) ? '1' : '0');
        return sb.toString();
    }

    private static boolean maskBit(int m, int x, int y) {
        switch (m) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return x * y % 2 + x * y % 3 == 0;
            case 6: return (x * y % 2 + x * y % 3) % 2 == 0;
            default: return ((x + y) % 2 + x * y % 3) % 2 == 0;
        }
    }

    private static int mul(int x, int y) {
        int r = 0;
        while (y > 0) { if ((y & 1) != 0) r ^= x; x <<= 1; if ((x & 0x100) != 0) x ^= 0x11D; y >>>= 1; }
        return r;
    }

    private static int pow(int e) { int r = 1; for (int i = 0; i < e; i++) r = mul(r, 2); return r; }
}
