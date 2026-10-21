package io.github.supersanchayrx.redis.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobTest {

    @ParameterizedTest(name = "{0} ~ {1} = {2}")
    @CsvSource({
            "*, anything, true",
            "h?llo, hello, true",
            "h?llo, hllo, false",
            "h*llo, hllo, true",
            "h*llo, heeeello, true",
            "h[ae]llo, hallo, true",
            "h[ae]llo, hillo, false",
            "h[^e]llo, hallo, true",
            "h[^e]llo, hello, false",
            "h[a-b]llo, hbllo, true",
            "h[b-a]llo, hallo, true",
            "h[a-b]llo, hcllo, false",
            "h\\*llo, h*llo, true",
            "h\\*llo, hello, false",
            "user:*:name, user:42:name, true",
            "user:*:name, user:42:mail, false",
            "a*, a, true",
            "a**b, ab, true",
            "[abc, a, true",
            "abc, abcd, false",
    })
    void matches(String pattern, String s, boolean expected) {
        assertEquals(expected, Glob.match(pattern.getBytes(StandardCharsets.UTF_8), s.getBytes(StandardCharsets.UTF_8)));
    }
}
