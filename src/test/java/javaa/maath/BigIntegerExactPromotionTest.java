package javaa.maath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 閾値を超えた厳密値が、OmegaNum本来の層表現へ昇格することを確認する。 */
class BigIntegerExactPromotionTest {

    @AfterEach
    void restoreDefaultLimit() {
        BigInteger.setExactLimitBits(BigInteger.DEFAULT_EXACT_LIMIT_BITS);
    }

    @Test
    void keepsExactValuesBelowTheLimit() {
        BigInteger googol = BigInteger.TEN.pow(100);
        assertTrue(googol.isExact());
        assertEquals(java.math.BigInteger.TEN.pow(100).toString(), googol.toString());
    }

    @Test
    void promotesValuesAboveTheLimit() {
        BigInteger.setExactLimitBits(BigInteger.decimalDigitsToBits(100));

        BigInteger googol = BigInteger.TEN.pow(100);
        assertTrue(googol.isExact(), "10^100 is exactly at the limit");

        BigInteger promoted = googol.multiply(BigInteger.TEN);
        assertFalse(promoted.isExact(), "10^101 must fall back to the layered representation");
    }

    @Test
    void promotedValuesRefuseExactOnlyOperations() {
        BigInteger.setExactLimitBits(BigInteger.decimalDigitsToBits(100));
        BigInteger promoted = BigInteger.TEN.pow(101);

        assertThrows(ArithmeticException.class, promoted::toByteArray);
        assertThrows(ArithmeticException.class, () -> promoted.gcd(BigInteger.TEN));
        assertThrows(ArithmeticException.class, () -> promoted.shiftLeft(1));
    }

    @Test
    void promotedValuesStillSupportLayeredArithmetic() {
        BigInteger.setExactLimitBits(BigInteger.decimalDigitsToBits(100));
        BigInteger promoted = BigInteger.TEN.pow(101);

        assertTrue(promoted.multiply(promoted).gt(promoted));
        assertTrue(promoted.gt(BigInteger.TEN.pow(100)));
        // 桁数はおおよそ保たれる(層表現なので厳密一致は求めない)。
        int bits = promoted.bitLength();
        assertTrue(Math.abs(bits - 336) <= 2, "unexpected approximated bitLength " + bits);
    }

    @Test
    void doesNotPromoteWithinTheDefaultContract() {
        // ACOが厳密に扱うと約束している範囲(既定 1,048,576 bit)では昇格しない。
        BigInteger wide = BigInteger.ONE.shiftLeft(1024);
        assertTrue(wide.isExact());
        assertEquals(1025, wide.bitLength());
    }
}
