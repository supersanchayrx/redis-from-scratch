package io.github.supersanchayrx.redis.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** Number parsing and formatting with Redis's exact rules, plus small byte helpers. */
public final class Bytes {

    private Bytes() {
    }

    /** Thrown (preallocated, no stack trace) when bytes aren't a number Redis would accept. */
    public static final class NotANumber extends RuntimeException {
        static final NotANumber INSTANCE = new NotANumber();

        private NotANumber() {
            super("not a number", null, false, false);
        }
    }

    public static long parseLong(byte[] b) {
        return parseLong(b, 0, b.length);
    }

    /**
     * Redis's {@code string2ll}: an optional '-', then digits with no leading zero (except "0" itself),
     * no '+', no spaces, and the value must fit in a signed 64-bit integer. So "-0", "01", "+1" and " 1" fail.
     */
    public static long parseLong(byte[] b, int off, int len) {
        if (len == 0 || len >= 21) {
            throw NotANumber.INSTANCE;
        }
        int p = off;
        int end = off + len;
        if (len == 1 && b[p] == '0') {
            return 0;
        }
        boolean negative = false;
        if (b[p] == '-') {
            negative = true;
            p++;
            if (p == end) {
                throw NotANumber.INSTANCE;
            }
        }
        if (b[p] < '1' || b[p] > '9') {
            throw NotANumber.INSTANCE;
        }
        // Accumulate as a negative number so Long.MIN_VALUE fits.
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multmin = limit / 10;
        long result = 0;
        for (; p < end; p++) {
            int d = b[p] - '0';
            if (d < 0 || d > 9 || result < multmin) {
                throw NotANumber.INSTANCE;
            }
            result *= 10;
            if (result < limit + d) {
                throw NotANumber.INSTANCE;
            }
            result -= d;
        }
        return negative ? result : -result;
    }

    /**
     * Redis's {@code string2d} (strtod plus its checks): no leading space, nothing after the number,
     * no NaN, and no overflow to infinity or underflow to zero. "inf", "+inf", "-inf" and "infinity"
     * are accepted in any case.
     */
    public static double parseDouble(byte[] b) {
        int n = b.length;
        if (n == 0) {
            throw NotANumber.INSTANCE;
        }
        int p = 0;
        boolean negative = false;
        if (b[p] == '+' || b[p] == '-') {
            negative = b[p] == '-';
            p++;
        }
        if (p < n && (b[p] == 'i' || b[p] == 'I')) {
            int rest = n - p;
            if ((rest == 3 && matchesIgnoreCase(b, p, "inf")) || (rest == 8 && matchesIgnoreCase(b, p, "infinity"))) {
                return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            throw NotANumber.INSTANCE;
        }
        if (p + 1 < n && b[p] == '0' && (b[p + 1] == 'x' || b[p + 1] == 'X')) {
            return parseHexDouble(b, p + 2, negative);
        }
        // Validate the decimal shape ourselves: Double.parseDouble trims spaces, accepts "NaN",
        // "Infinity" and a trailing 'd' or 'f', none of which strtod would take.
        int digits = 0;
        boolean nonZeroDigit = false;
        while (p < n && isDigit(b[p])) {
            nonZeroDigit |= b[p] != '0';
            p++;
            digits++;
        }
        if (p < n && b[p] == '.') {
            p++;
            while (p < n && isDigit(b[p])) {
                nonZeroDigit |= b[p] != '0';
                p++;
                digits++;
            }
        }
        if (digits == 0) {
            throw NotANumber.INSTANCE;
        }
        if (p < n && (b[p] == 'e' || b[p] == 'E')) {
            p++;
            if (p < n && (b[p] == '+' || b[p] == '-')) {
                p++;
            }
            int expDigits = 0;
            while (p < n && isDigit(b[p])) {
                p++;
                expDigits++;
            }
            if (expDigits == 0) {
                throw NotANumber.INSTANCE;
            }
        }
        if (p != n) {
            throw NotANumber.INSTANCE;
        }
        double v = Double.parseDouble(new String(b, StandardCharsets.US_ASCII));
        if (Double.isInfinite(v) || (v == 0 && nonZeroDigit)) {
            throw NotANumber.INSTANCE; // ERANGE in strtod
        }
        return v;
    }

    /** strtod's hex form: 0x1A, 0x1.8p3. Java needs the 'p' exponent, so one is added when it's missing. */
    private static double parseHexDouble(byte[] b, int p, boolean negative) {
        int n = b.length;
        int digits = 0;
        int start = p;
        while (p < n && isHex(b[p])) {
            p++;
            digits++;
        }
        if (p < n && b[p] == '.') {
            p++;
            while (p < n && isHex(b[p])) {
                p++;
                digits++;
            }
        }
        if (digits == 0) {
            throw NotANumber.INSTANCE;
        }
        boolean hasExp = false;
        if (p < n && (b[p] == 'p' || b[p] == 'P')) {
            int q = p + 1;
            if (q < n && (b[q] == '+' || b[q] == '-')) {
                q++;
            }
            int expDigits = 0;
            while (q < n && isDigit(b[q])) {
                q++;
                expDigits++;
            }
            if (expDigits == 0) {
                throw NotANumber.INSTANCE;
            }
            hasExp = true;
            p = q;
        }
        if (p != n) {
            throw NotANumber.INSTANCE;
        }
        String s = (negative ? "-0x" : "0x") + new String(b, start, n - start, StandardCharsets.US_ASCII)
                + (hasExp ? "" : "p0");
        double v = Double.parseDouble(s);
        if (Double.isInfinite(v)) {
            throw NotANumber.INSTANCE;
        }
        return v;
    }

    private static boolean isHex(byte c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** LLONG_MAX / 2: Redis's double2ll only takes the integer path inside this range. */
    private static final double INT_PATH_LIMIT = 4611686018427387903.0;

    /**
     * A double as Redis 8 prints it in RESP2 replies ({@code d2string} in util.c): an integral value within
     * ±LLONG_MAX/2 prints as an integer; anything else uses fpconv's shortest round-trip digits laid out as
     * a plain integer if {@code K >= 0 && exp < ndigits + 7}, as a decimal if {@code K < 0 && (K > -7 || exp < 4)},
     * and otherwise in exponent form with an unpadded exponent (1.23e-7, 1e+20). K is the power of ten of
     * the last digit, exp the absolute power of ten of the first. Checked against redis:8.10.2.
     */
    public static String formatDouble(double d) {
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (d == 0) {
            return "0"; // real Redis prints -0 as 0 too
        }
        if (d >= -INT_PATH_LIMIT && d <= INT_PATH_LIMIT && d == (long) d) {
            return Long.toString((long) d);
        }
        // Java's Double.toString gives the shortest round-trip digits, like fpconv's grisu.
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int nd = digits.length();
        int k = -bd.scale();
        int exp = Math.abs(k + nd - 1);
        StringBuilder sb = new StringBuilder(26);
        if (d < 0) {
            sb.append('-');
        }
        if (k >= 0 && exp < nd + 7) {
            sb.append(digits).append("0".repeat(k));
        } else if (k < 0 && (k > -7 || exp < 4)) {
            int offset = nd + k; // digits before the point
            if (offset <= 0) {
                sb.append("0.").append("0".repeat(-offset)).append(digits);
            } else {
                sb.append(digits, 0, offset).append('.').append(digits, offset, nd);
            }
        } else {
            sb.append(digits.charAt(0));
            if (nd > 1) {
                sb.append('.').append(digits, 1, nd);
            }
            sb.append('e').append(k + nd - 1 < 0 ? '-' : '+').append(exp);
        }
        return sb.toString();
    }

    public static byte[] formatDoubleBytes(double d) {
        return formatDouble(d).getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] ascii(long v) {
        return Long.toString(v).getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** For logs and error messages only, never for logic. */
    public static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** True if {@code b} equals the ASCII word {@code upper} ignoring case. {@code upper} must be upper-case. */
    public static boolean eq(byte[] b, String upper) {
        return b.length == upper.length() && matchesIgnoreCase(b, 0, upper);
    }

    /** The ASCII lower-case form of a command name, for lookups and error messages. */
    public static String lower(byte[] b) {
        char[] c = new char[b.length];
        for (int i = 0; i < b.length; i++) {
            int x = b[i] & 0xff;
            c[i] = (char) (x >= 'A' && x <= 'Z' ? x + 32 : x);
        }
        return new String(c);
    }

    private static boolean matchesIgnoreCase(byte[] b, int off, String word) {
        for (int i = 0; i < word.length(); i++) {
            int x = b[off + i];
            if (x >= 'a' && x <= 'z') {
                x -= 32;
            }
            int w = word.charAt(i);
            if (w >= 'a' && w <= 'z') {
                w -= 32;
            }
            if (x != w) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }
}
