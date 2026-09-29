package com.github.guoyyit.genomeassemblyjava.tests.engine;

import com.github.guoyyit.genomeassemblyjava.engine.DnaEncoding;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnaEncodingTest {

    @Test
    void encodingRoundTrip() {
        Random random = new Random(42);
        for (int length = 1; length <= 31; length++) {
            for (int trial = 0; trial < 50; trial++) {
                String sequence = randomSequence(random, length);
                long encoded = DnaEncoding.encode(sequence);
                assertEquals(sequence, DnaEncoding.decode(encoded, length));
            }
        }
    }

    @Test
    void reverseComplementMatchesStringForm() {
        assertEquals("CGTA", DnaEncoding.reverseComplement("TACG"));
        assertEquals("TTGGCCAA", DnaEncoding.reverseComplement("TTGGCCAA"));
        String sequence = "ACGTACGTACGT";
        long encoded = DnaEncoding.encode(sequence);
        long rc = DnaEncoding.reverseComplement(encoded, sequence.length());
        assertEquals(DnaEncoding.reverseComplement(sequence),
                DnaEncoding.decode(rc, sequence.length()));
    }

    @Test
    void reverseComplementIsInvolution() {
        Random random = new Random(7);
        for (int length = 1; length <= 31; length++) {
            for (int trial = 0; trial < 50; trial++) {
                String sequence = randomSequence(random, length);
                long encoded = DnaEncoding.encode(sequence);
                long twice = DnaEncoding.reverseComplement(
                        DnaEncoding.reverseComplement(encoded, length), length);
                assertEquals(encoded, twice);
            }
        }
    }

    @Test
    void detectsAmbiguousBases() {
        assertFalse(DnaEncoding.isAmbiguous('A'));
        assertFalse(DnaEncoding.isAmbiguous('c'));
        assertTrue(DnaEncoding.isAmbiguous('N'));
        assertThrows(IllegalArgumentException.class, () -> DnaEncoding.encodeBase('N'));
    }

    private static String randomSequence(Random random, int length) {
        char[] alphabet = {'A', 'C', 'G', 'T'};
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = alphabet[random.nextInt(alphabet.length)];
        }
        return new String(chars);
    }
}
