package com.privacylogistics;

/*
 * Format-Preserving Encryption for FF3
 *
 * Copyright (c) 2021 Schoening Consulting LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FF3Cipher {
    /**
     * Class FF3Cipher implements the FF3 format-preserving encryption algorithm with default
     * radix of 10.
     *
     * @param key   encryption key used to initialize AES ECB
     * @param tweak used in each round and split into right and left sides
     */
    public FF3Cipher(String key, String tweak) {
        this(key, tweak, 10);
    }

    /**
     * Class FF3Cipher implements the FF3 format-preserving encryption algorithm
     *
     * @param key   encryption key used to initialize AES ECB
     * @param tweak used in each round and split into right and left sides
     * @param alphabet the cipher alphabet
     */
    public FF3Cipher(String key, String tweak, String alphabet) {
        this.alphabet = alphabet;
        this.radix = alphabet.length();

        byte[] keyBytes = hexStringToByteArray(key);

        // Calculate range of supported message lengths [minLen..maxLen]
        // radix 10: 6 ... 56, 26: 5 ... 40, 36: 4 .. 36

        // Per revised spec, radix^minLength >= 1,000,000
        this.minLen = (int) Math.ceil(Math.log(DOMAIN_MIN) / Math.log(radix));

        // We simplify the specs log[radix](2^96) to 96/log2(radix) using the log base change rule
        this.maxLen = (int) (2 * Math.floor(Math.log(Math.pow(2, 96)) / Math.log(radix)));

        int keyLen = keyBytes.length;
        // Check if the key is 128, 192, or 256 bits = 16, 24, or 32 bytes
        if (keyLen != 16 && keyLen != 24 && keyLen != 32) {
            throw new IllegalArgumentException("key length " + keyLen + " but must be 128, 192, or 256 bits");
        }

        // While FF3 allows radices in [2, 2^16], there is a practical limit to 36 (alphanumeric)
        // because Java BigInt only supports up to base 36.
        if ((radix < 2) || (radix > MAX_RADIX)) {
            throw new IllegalArgumentException("radix must be between 2 and 36, inclusive");
        }

        // Make sure 2 <= minLength <= maxLength < 2*floor(log base radix of 2^96) is satisfied
        if ((this.minLen < 2) || (this.maxLen < this.minLen)) {
            throw new IllegalArgumentException("minLen or maxLen invalid, adjust your radix");
        }

        this.tweakBytes = hexStringToByteArray(tweak);

        // AES block cipher in ECB mode with the block size derived based on the length of the key
        // Always use the reversed key since Encrypt and Decrypt call cipher expecting that
        // Feistel ciphers use the same func for encrypt/decrypt, so mode is always ENCRYPT_MODE

        try {
            reverseBytes(keyBytes);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            aesCipher = Cipher.getInstance("AES/ECB/NoPadding");
            aesCipher.init(Cipher.ENCRYPT_MODE, keySpec);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e) {
            // this could happen if the JRE doesn't have the ciphers
            throw new RuntimeException(e);
        }
    }

    /**
     * Class FF3Cipher implements the FF3 format-preserving encryption algorithm
     *
     * @param key   encryption key used to initialize AES ECB
     * @param tweak used in each round and split into right and left sides
     * @param radix the domain of the alphabet, 10, 26 or 36
     */
    public FF3Cipher(String key, String tweak, int radix) {
        this(key, tweak, alphabetForBase(radix));
    }

        /* convenience method to override tweak */
    @SuppressWarnings("unused")
    public String encrypt(String plaintext, String tweak) throws BadPaddingException, IllegalBlockSizeException {
        this.tweakBytes = hexStringToByteArray(tweak);
        return encrypt(plaintext);
    }

    public String encrypt(String plaintext) throws BadPaddingException, IllegalBlockSizeException {
        int n = plaintext.length();

        // Check if message length is within minLength and maxLength bounds
        if ((n < this.minLen) || (n > this.maxLen)) {
            throw new IllegalArgumentException(String.format("message length %d is not within min %d and max %d bounds",
                    n, this.minLen, this.maxLen));
        }

        // Check if the plaintext message is formatted in the current radix
        try {
            new BigInteger(plaintext, this.radix);
        } catch (NumberFormatException ex) {
            throw new NumberFormatException(String.format("The plaintext %s is not supported in the current radix %d", plaintext, this.radix));
        }

        // Calculate split point
        int u = (int) Math.ceil(n / 2.0);
        int v = n - u;

        // Split the message
        String A = plaintext.substring(0, u);
        String B = plaintext.substring(u);
        logger.info("r {} A {} B {}", this.radix, A, B);

        // Split the tweak
        logger.info("tweak: {}", byteArrayToHexString(this.tweakBytes));

        byte[] Tl;
        byte[] Tr;
        if (this.tweakBytes.length == TWEAK_LEN) {
            // FF3
            Tl = Arrays.copyOf(this.tweakBytes, HALF_TWEAK_LEN);
            Tr = Arrays.copyOfRange(this.tweakBytes, HALF_TWEAK_LEN, TWEAK_LEN);
        } else if (this.tweakBytes.length == TWEAK_LEN_NEW)  {
            // FF3-1
            // The tweak is partitioned into a 32-bit left tweak and a 32-bit right tweak
            // Tl is T[0..27] + 0000
            Tl = Arrays.copyOf(this.tweakBytes, HALF_TWEAK_LEN);
            Tl[3] &= 0xF0;

            // Tr is T[32..55] + T[28..31] + 0000
            Tr = new byte[HALF_TWEAK_LEN];
            Tr[0] = this.tweakBytes[HALF_TWEAK_LEN];
            Tr[1] = this.tweakBytes[HALF_TWEAK_LEN+1];
            Tr[2] = this.tweakBytes[HALF_TWEAK_LEN+2];
            Tr[3] = this.tweakBytes[3];
            Tr[3] &= 0x0F;
            Tr[3] = (byte) (Tr[3] << 4);
        } else {
            throw new IllegalArgumentException(String.format("tweak length %d is invalid: tweak must be 8 bytes, or 64 bits",
                    this.tweakBytes.length));
        }
        // P is always 16 bytes
        byte[] P;

        // Pre-calculate the modulus since it's only one of 2 values,
        // depending on whether i is even or odd

        BigInteger modU = BigInteger.valueOf(this.radix).pow(u);
        BigInteger modV = BigInteger.valueOf(this.radix).pow(v);
        logger.info("u {} v {} modU: {} modV: {}", u, v, modU, modV);
        logger.info("tL: {} tR: {}", byteArrayToHexString(Tl), byteArrayToHexString(Tr));

        for (byte i = 0; i < NUM_ROUNDS; ++i) {
            int m;
            BigInteger c;
            byte[] W;

            // Determine alternating Feistel round side, right or left
            if (i % 2 == 0) {
                m = u;
                W = Tr;
            } else {
                m = v;
                W = Tl;
            }

            // P is fixed-length 16 bytes
            P = calculateP(i, this.radix, W, B);
            reverseBytes(P);

            // Calculate S by operating on P in place
            byte[] S = this.aesCipher.doFinal(P);
            reverseBytes(S);
            logger.info("\tS: {}", byteArrayToHexString(S));

            BigInteger y = new BigInteger(byteArrayToHexString(S), 16);

            // Calculate c
            try {
                c = new BigInteger(reverseString(A), this.radix);
            } catch (NumberFormatException ex) {
                throw new RuntimeException("string A is not within base/radix");
            }

            c = c.add(y);

            if (i % 2 == 0) {
                c = c.mod(modU);
            } else {
                c = c.mod(modV);
            }

            logger.info("\tm: {} A: {} c: {} y: {}", m, A, c, y);

            // Convert c to sting using radix and length m
            String C = c.toString(this.radix);
            C = reverseString(C);
            C = C + "00000000".substring(0, m - C.length());

            // Final steps
            A = B;
            B = C;
            logger.info("A: {} B: {}", A, B);
        }
        return A + B;
    }

    /* convenience method to override tweak */
    @SuppressWarnings("unused")
    public String decrypt(String ciphertext, String tweak) throws BadPaddingException, IllegalBlockSizeException {
        this.tweakBytes = hexStringToByteArray(tweak);
        return decrypt(ciphertext);
    }

    public String decrypt(String ciphertext) throws BadPaddingException, IllegalBlockSizeException {
        int n = ciphertext.length();

        // Check if message length is within minLength and maxLength bounds
        if ((n < this.minLen) || (n > this.maxLen)) {
            throw new IllegalArgumentException(String.format("message length %d is not within min %d and max %d bounds",
                    n, this.minLen, this.maxLen));
        }

        // Check if the ciphertext message is formatted in the current radix
        try {
            new BigInteger(ciphertext, this.radix);
        } catch (NumberFormatException ex) {
            throw new NumberFormatException(String.format("The plaintext is not supported in the current radix %d", this.radix));
        }

        // Calculate split point
        int u = (int) Math.ceil(n / 2.0);
        int v = n - u;

        // Split the message
        String A = ciphertext.substring(0, u);
        String B = ciphertext.substring(u);

        // Split the tweak
        logger.info("tweak: {}", byteArrayToHexString(this.tweakBytes));

        byte[] Tl;
        byte[] Tr;
        if (this.tweakBytes.length == TWEAK_LEN) {
            // FF3
            Tl = Arrays.copyOf(this.tweakBytes, HALF_TWEAK_LEN);
            Tr = Arrays.copyOfRange(this.tweakBytes, HALF_TWEAK_LEN, TWEAK_LEN);
        } else if (this.tweakBytes.length == TWEAK_LEN_NEW)  {
            // FF3-1
            // The tweak is partitioned into a 32-bit left tweak and a 32-bit right tweak
            // Tl is T[0..27] + 0000
            Tl = Arrays.copyOf(this.tweakBytes, HALF_TWEAK_LEN);
            Tl[3] &= 0xF0;

            // Tr is T[32..55] + T[28..31] + 0000
            Tr = new byte[HALF_TWEAK_LEN];
            Tr[0] = this.tweakBytes[HALF_TWEAK_LEN];
            Tr[1] = this.tweakBytes[HALF_TWEAK_LEN+1];
            Tr[2] = this.tweakBytes[HALF_TWEAK_LEN+2];
            Tr[3] = this.tweakBytes[3];
            Tr[3] &= 0x0F;
            Tr[3] = (byte) (Tr[3] << 4);
        } else {
            throw new IllegalArgumentException(String.format("tweak length %d is invalid: tweak must be 8 bytes, or 64 bits",
                    this.tweakBytes.length));
        }

        // P is always 16 bytes
        byte[] P;

        // Pre-calculate the modulus since it's only one of 2 values,
        // depending on whether i is even or odd

        BigInteger modU = BigInteger.valueOf(this.radix).pow(u);
        BigInteger modV = BigInteger.valueOf(this.radix).pow(v);
        logger.info("modU: {} modV: {}", modU, modV);
        logger.info("tL: {} tR: {}", byteArrayToHexString(Tl), byteArrayToHexString(Tr));

        for (byte i = (byte) (NUM_ROUNDS - 1); i >= 0; --i) {
            int m;
            BigInteger c;
            byte[] W;

            // Determine alternating Feistel round side, right or left
            if (i % 2 == 0) {
                m = u;
                W = Tr;
            } else {
                m = v;
                W = Tl;
            }

            // P is fixed-length 16 bytes
            P = calculateP(i, this.radix, W, A);
            reverseBytes(P);

            // Calculate S by operating on P in place
            byte[] S = this.aesCipher.doFinal(P);
            reverseBytes(S);
            logger.info("\tS: {}", byteArrayToHexString(S));

            BigInteger y = new BigInteger(byteArrayToHexString(S), 16);

            // Calculate c
            try {
                c = new BigInteger(reverseString(B), this.radix);
            } catch (NumberFormatException ex) {
                throw new RuntimeException("string B is not within base/radix");
            }

            c = c.subtract(y);

            if (i % 2 == 0) {
                c = c.mod(modU);
            } else {
                c = c.mod(modV);
            }

            logger.info("\tm: {} B: {} c: {} y: {}", m, B, c, y);

            // Convert c to sting using radix and length m
            String C = c.toString(this.radix);
            C = reverseString(C);
            C = C + "00000000".substring(0, m - C.length());

            // Final steps
            B = A;
            A = C;
            logger.info("A: {} B: {}", A, B);
        }
        return A + B;
    }

    protected static byte[] calculateP(int i, int radix, byte[] W, String B) {

        byte[] P = new byte[BLOCK_SIZE];     // P is always 16 bytes, zero initialized

        // Calculate P by XORing W, i into the first 4 bytes of P
        // i only requires 1 byte, rest are 0 padding bytes
        // Anything XOR 0 is itself, so only need to XOR the last byte

        P[0] = W[0];
        P[1] = W[1];
        P[2] = W[2];
        P[3] = (byte) (W[3] ^ i);

        // The remaining 12 bytes of P are copied from reverse(B) with padding

        B = reverseString(B);
        byte[] bBytes = new BigInteger(B, radix).toByteArray();

        System.arraycopy(bBytes, 0, P, (BLOCK_SIZE - bBytes.length), bBytes.length);
        logger.info("round: {} W: {} P: {}", i, byteArrayToHexString(W), byteArrayToIntString(P));
        return P;
    }

    protected static String reverseString(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    /*
     * Reverse a byte array in-place
     */
    protected void reverseBytes(byte[] b) {
        for (int i = 0; i < b.length / 2; i++) {
            byte temp = b[i];
            b[i] = b[b.length - i - 1];
            b[b.length - i - 1] = temp;
        }
    }

    protected static byte[] hexStringToByteArray(String s) {
        byte[] data = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2) {
            data[i / 2] = (Integer.decode("0x" + s.charAt(i) + s.charAt(i + 1))).byteValue();
        }
        return data;
    }

    /*
     * used for debugging
     * Java 17 has java.util.HexFormat
     */
    protected static String byteArrayToHexString(byte[] byteArray) {

        StringBuilder sb = new StringBuilder();
        for (byte b : byteArray) {
            String aByte = String.format("%02X", b);
            sb.append(aByte);
        }
        return sb.toString();
    }

    protected static String byteArrayToIntString(byte[] byteArray) {

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (byte b : byteArray) {
            // cast signed byte to int and mask for last byte
            String aByte = String.format("%d ", ((int) b) & 0xFF);
            sb.append(aByte);
        }
        sb.append(']');
        return sb.toString();
    }

    /* Return a string representation of a number in the given base system for 2..62

            The string is left in a reversed order expected by the calling cryptographic function

            examples:
               encode_int_r(5)
                '101'
               encode_int_r(10, base=16)
                'A'
               encode_int_r(32, base=16)
                '20'
         */
    protected static String encode_int_r(int n, int base, int length) {

        if (base > MAX_RADIX) {
            throw new NumberFormatException(String.format("Base %d is not supported in the current radix 2..62", base));
        }

        String x = "";
        while (n >= base) {
            int b = n % base;
            n = n / base;
            x += BASE62[b];
        }
        x += BASE62[n];
        System.out.println("ch: "+x);
        if (x.length() < length) {
            // left justify the string
            x = x + String.join("", Collections.nCopies(length-x.length(), "0"));
        }
        return x;
    }

    protected static BigInteger decode_int_r(String str, int base) {

        /* Decode a Base X encoded string into the number */

        int strlen = str.length();
        BigInteger num = BigInteger.ZERO;
        int idx =0;
        // Todo: iterate with -1 steps
        for (char each : reverseString(str).toCharArray()) {
            int power = (strlen - (idx + 1));
            num = num.add(BigInteger.valueOf(BASE62STR.indexOf(each) * (long) (Math.pow(base, power))));
            idx += 1;
        }
        return num;
    }

    protected static String alphabetForBase(int base) {
        switch (base) {
            case 10:
                return "0123456789";
            case 26:
                return "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            case 36:
                return "0123456789" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            case 64:
                return "0123456789" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "abcdefghijklmnopqrstuvwxyz";
            default:
                throw new RuntimeException("Unsupported radix");
        }
    }

    // The recommendation in Draft SP 800-38G was strengthened to a requirement in Draft SP 800-38G Revision 1:
    // the minimum domain size for FF1 and FF3-1 is one million.
    public static int DOMAIN_MIN =  1000000;  // 1M
    public static int NUM_ROUNDS =   8;
    public static int BLOCK_SIZE =   16;      // aes.BlockSize
    public static int TWEAK_LEN =    8;       // Original FF3 64-bit tweak length
    public static int TWEAK_LEN_NEW =  7;     // FF3-1 56-bit tweak length
    public static int HALF_TWEAK_LEN = TWEAK_LEN/2;
    public static int MAX_RADIX =    36;      // Java BigInteger supports radix 2..36
    public static Logger logger = LogManager.getLogger(FF3Cipher.class.getName());
    public static String BASE62STR = ("0123456789" + "abcdefghijklmnopqrstuvwxyz" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    public static char[] BASE62 = BASE62STR.toCharArray();

    private final int radix;
    private final String alphabet;
    private byte[] tweakBytes;
    private final int minLen;
    private final int maxLen;
    private final Cipher aesCipher;
}
