package com.github.guoyyit.genomeassemblyjava.engine;

/**
 * DNA 碱基的 2-bit 编码工具：A=0, C=1, G=2, T=3。
 */
public final class DnaEncoding {

    public static final int BASE_BITS = 2;
    public static final long BASE_MASK = 3L;

    private DnaEncoding() {
    }

    public static int encodeBase(char base) {
        return switch (Character.toUpperCase(base)) {
            case 'A' -> 0;
            case 'C' -> 1;
            case 'G' -> 2;
            case 'T' -> 3;
            default -> throw new IllegalArgumentException("not an unambiguous DNA base: " + base);
        };
    }

    public static boolean isAmbiguous(char base) {
        return switch (Character.toUpperCase(base)) {
            case 'A', 'C', 'G', 'T' -> false;
            default -> true;
        };
    }

    public static char decodeBase(int code) {
        return switch (code & 3) {
            case 0 -> 'A';
            case 1 -> 'C';
            case 2 -> 'G';
            default -> 'T';
        };
    }

    /**
     * 将一段只含 ACGT 的序列从左到右编码为 2-bit 整数；首个碱基位于高位。
     */
    public static long encode(String segment) {
        long code = 0L;
        for (int i = 0; i < segment.length(); i++) {
            code = (code << BASE_BITS) | encodeBase(segment.charAt(i));
        }
        return code;
    }

    /**
     * 将 2-bit 整数解码为长度为 length 的序列。
     */
    public static String decode(long code, int length) {
        char[] chars = new char[length];
        for (int i = length - 1; i >= 0; i--) {
            chars[i] = decodeBase((int) (code & BASE_MASK));
            code >>>= BASE_BITS;
        }
        return new String(chars);
    }

    /**
     * 返回编码序列的反向互补编码：rc(code) 对应 reverseComplement(decode(code))。
     * 满足 rc(rc(code, L), L) == code，且与碱基逐位处理顺序无关。
     */
    public static long reverseComplement(long code, int length) {
        long result = 0L;
        for (int i = 0; i < length; i++) {
            int base = (int) (code & BASE_MASK);
            result = (result << BASE_BITS) | ((base ^ 3) & BASE_MASK);
            code >>>= BASE_BITS;
        }
        return result;
    }

    /**
     * 字符串形式的反向互补；输入只含 ACGT。
     */
    public static String reverseComplement(String sequence) {
        char[] chars = new char[sequence.length()];
        for (int i = 0; i < sequence.length(); i++) {
            int base = encodeBase(sequence.charAt(sequence.length() - 1 - i));
            chars[i] = decodeBase(base ^ 3);
        }
        return new String(chars);
    }
}
