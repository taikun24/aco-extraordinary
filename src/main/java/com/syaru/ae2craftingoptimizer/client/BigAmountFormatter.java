package com.syaru.ae2craftingoptimizer.client;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import java.math.BigDecimal;
import javaa.maath.BigInteger;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/** AE2のlong表示範囲を越える数量を、丸めによる上限張り付きなしで表示する。 */
public final class BigAmountFormatter {
    private static final BigInteger THOUSAND = BigInteger.valueOf(1_000L);

    private BigAmountFormatter() {
    }

    public static String format(AEKey key, BigInteger amount, AmountFormat format) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(format, "format");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }

        if (format == AmountFormat.FULL) {
            return formatFull(key, amount);
        }
        return formatCompact(amount, key.getAmountPerUnit());
    }

    /**
     * 4桁以下は整数、以降は3桁の有効数字を持つ科学表記にする。
     * EをExa接尾辞と誤認しないよう、指数記号は小文字eを使う。
     */
    public static String formatCompact(BigInteger amount) {
        return formatCompact(amount, 1);
    }

    private static String formatCompact(BigInteger amount, int amountPerUnit) {
        // 層表現の数はBigDecimalへ載せられないので、OmegaNum表記(e1000000000 など)をそのまま使う。
        if (!amount.isExact()) {
            // 次数が深くネストした値(グラハム数など)はスロット幅に収まらないので省略表示にする。
            return amount.toString(16);
        }
        BigDecimal units = amount.toBigDecimal()
                .divide(BigDecimal.valueOf(Math.max(1, amountPerUnit)), 6, RoundingMode.DOWN)
                .stripTrailingZeros();
        BigInteger wholeUnits = BigInteger.of(units.toBigInteger());
        if (units.scale() <= 0 && wholeUnits.compareTo(THOUSAND) < 0) {
            return wholeUnits.toString();
        }

        int exponent = units.precision() - units.scale() - 1;
        BigDecimal mantissa = units.movePointLeft(exponent)
                .setScale(2, RoundingMode.DOWN)
                .stripTrailingZeros();
        return mantissa.toPlainString() + "e" + exponent;
    }

    private static String formatFull(AEKey key, BigInteger amount) {
        if (!amount.isExact()) {
            String unit = key.getUnitSymbol();
            String text = amount.toString(64);
            return unit == null ? text : text + " " + unit;
        }
        int amountPerUnit = Math.max(1, key.getAmountPerUnit());
        BigDecimal units = amount.toBigDecimal()
                .divide(BigDecimal.valueOf(amountPerUnit), 3, RoundingMode.DOWN)
                .stripTrailingZeros();

        NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.getDefault());
        numberFormat.setGroupingUsed(true);
        numberFormat.setMaximumFractionDigits(3);
        numberFormat.setMinimumFractionDigits(0);
        String text = numberFormat.format(units);
        String unit = key.getUnitSymbol();
        return unit == null ? text : text + " " + unit;
    }
}
