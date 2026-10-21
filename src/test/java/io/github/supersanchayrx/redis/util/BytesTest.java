package io.github.supersanchayrx.redis.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BytesTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void longsRedisAccepts() {
        assertEquals(0, Bytes.parseLong(b("0")));
        assertEquals(-1, Bytes.parseLong(b("-1")));
        assertEquals(Long.MAX_VALUE, Bytes.parseLong(b("9223372036854775807")));
        assertEquals(Long.MIN_VALUE, Bytes.parseLong(b("-9223372036854775808")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-", "-0", "01", "+1", " 1", "1 ", "1.0", "abc", "9223372036854775808",
            "-9223372036854775809", "123456789012345678901"})
    void longsRedisRejects(String s) {
        assertThrows(Bytes.NotANumber.class, () -> Bytes.parseLong(b(s)));
    }

    @ParameterizedTest
    @CsvSource({"1,1.0", "-2.5,-2.5", ".5,0.5", "5.,5.0", "1e3,1000.0", "0x10,16.0", "0x1p3,8.0", "-0,-0.0"})
    void doublesRedisAccepts(String in, double expected) {
        assertEquals(expected, Bytes.parseDouble(b(in)));
    }

    @Test
    void infinities() {
        for (String s : new String[] {"inf", "+inf", "INF", "Infinity", "+infinity"}) {
            assertEquals(Double.POSITIVE_INFINITY, Bytes.parseDouble(b(s)), s);
        }
        assertEquals(Double.NEGATIVE_INFINITY, Bytes.parseDouble(b("-inf")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nan", "NaN", " 1", "1 ", "abc", "1e400", "1e-400", "1d", "1f", "1e", ".", "0x", "infx"})
    void doublesRedisRejects(String s) {
        assertThrows(Bytes.NotANumber.class, () -> Bytes.parseDouble(b(s)));
    }

    /** Expected text captured from redis:8.10.2 (ZADD then ZSCORE). */
    @ParameterizedTest
    @CsvSource({
            "1,1", "1.5,1.5", "0.1,0.1", "-2.5,-2.5", "1000,1000", "123456789,123456789",
            "1e15,1000000000000000", "1e16,10000000000000000", "1e17,100000000000000000",
            "1e18,1000000000000000000", "1e19,1e+19", "1.5e19,1.5e+19", "123e18,1.23e+20", "1e20,1e+20",
            "12345678901234567890,12345678901234567000", "1e300,1e+300",
            "0.0001,0.0001", "1.5e-4,0.00015", "1e-5,0.00001", "0.000123,0.000123", "0.00001234,1.234e-5",
            "0.0000123,1.23e-5", "1.23e-7,1.23e-7", "2.5e-6,2.5e-6", "1e-300,1e-300",
            "3.14159265358979,3.14159265358979", "1234567.125,1234567.125", "-0,0", "0.30000000000000004,0.30000000000000004"})
    void formatMatchesRedis(String in, String expected) {
        assertEquals(expected, Bytes.formatDouble(Bytes.parseDouble(b(in))));
    }

    @Test
    void formatInfinity() {
        assertEquals("inf", Bytes.formatDouble(Double.POSITIVE_INFINITY));
        assertEquals("-inf", Bytes.formatDouble(Double.NEGATIVE_INFINITY));
    }
}
