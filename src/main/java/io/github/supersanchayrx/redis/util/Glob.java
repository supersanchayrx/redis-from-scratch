package io.github.supersanchayrx.redis.util;

/**
 * Redis's glob matcher ({@code stringmatchlen} in util.c), used by KEYS, CONFIG GET and PSUBSCRIBE.
 * Supports {@code *}, {@code ?}, {@code [abc]}, {@code [^abc]}, {@code [a-z]} and {@code \} escapes.
 */
public final class Glob {

    /** Redis caps the '*' recursion depth so a hostile pattern can't blow the stack. */
    private static final int MAX_NESTING = 1000;

    private Glob() {
    }

    public static boolean match(byte[] pattern, byte[] string) {
        return match(pattern, 0, pattern.length, string, 0, string.length, false, 0);
    }

    public static boolean matchNoCase(byte[] pattern, byte[] string) {
        return match(pattern, 0, pattern.length, string, 0, string.length, true, 0);
    }

    /** A pattern of just "*" matches everything; callers use this to skip the matcher. */
    public static boolean isMatchAll(byte[] pattern) {
        return pattern.length == 1 && pattern[0] == '*';
    }

    private static boolean match(byte[] p, int pi, int plen, byte[] s, int si, int slen, boolean nocase, int nesting) {
        if (nesting > MAX_NESTING) {
            return false;
        }
        while (plen > 0 && slen > 0) {
            switch (p[pi]) {
                case '*' -> {
                    while (plen > 1 && p[pi + 1] == '*') {
                        pi++;
                        plen--;
                    }
                    if (plen == 1) {
                        return true;
                    }
                    while (slen > 0) {
                        if (match(p, pi + 1, plen - 1, s, si, slen, nocase, nesting + 1)) {
                            return true;
                        }
                        si++;
                        slen--;
                    }
                    return false;
                }
                case '?' -> {
                    si++;
                    slen--;
                }
                case '[' -> {
                    pi++;
                    plen--;
                    boolean not = plen > 0 && p[pi] == '^';
                    if (not) {
                        pi++;
                        plen--;
                    }
                    boolean matched = false;
                    while (true) {
                        if (plen == 0) {
                            // Unterminated class: step back so the outer loop sees the end.
                            pi--;
                            plen++;
                            break;
                        } else if (p[pi] == '\\' && plen >= 2) {
                            pi++;
                            plen--;
                            if (eq(p[pi], s[si], nocase)) {
                                matched = true;
                            }
                        } else if (p[pi] == ']') {
                            break;
                        } else if (plen >= 3 && p[pi + 1] == '-') {
                            int start = p[pi] & 0xff;
                            int end = p[pi + 2] & 0xff;
                            int c = s[si] & 0xff;
                            if (start > end) {
                                int t = start;
                                start = end;
                                end = t;
                            }
                            if (nocase) {
                                start = lower(start);
                                end = lower(end);
                                c = lower(c);
                            }
                            pi += 2;
                            plen -= 2;
                            if (c >= start && c <= end) {
                                matched = true;
                            }
                        } else if (eq(p[pi], s[si], nocase)) {
                            matched = true;
                        }
                        pi++;
                        plen--;
                    }
                    if (not) {
                        matched = !matched;
                    }
                    if (!matched) {
                        return false;
                    }
                    si++;
                    slen--;
                }
                default -> {
                    if (p[pi] == '\\' && plen >= 2) {
                        pi++;
                        plen--;
                    }
                    if (!eq(p[pi], s[si], nocase)) {
                        return false;
                    }
                    si++;
                    slen--;
                }
            }
            pi++;
            plen--;
            if (slen == 0) {
                while (plen > 0 && p[pi] == '*') {
                    pi++;
                    plen--;
                }
                break;
            }
        }
        return plen == 0 && slen == 0;
    }

    private static boolean eq(byte a, byte b, boolean nocase) {
        return nocase ? lower(a & 0xff) == lower(b & 0xff) : a == b;
    }

    private static int lower(int c) {
        return c >= 'A' && c <= 'Z' ? c + 32 : c;
    }
}
