package com.syaru.ae2craftingoptimizer.engine;

import java.util.Arrays;

import javaa.maath.BigInteger;
import net.minecraft.network.FriendlyByteBuf;

public final class BigIntegerBufferCodec {

    /**
     * 2: layered表現のdegreeにネスト(巨大次数)を書けるようにした。
     */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * 厳密値として送信できる最大bit数。
     *
     * BigInteger側の exact 表現は java.math.BigInteger を使用している。
     */
    public static final int HARD_MAXIMUM_BYTES =
            (BigCountMath.HARD_MAXIMUM_BITS + Byte.SIZE) / Byte.SIZE;

    /**
     * 0 は layered 表現を表す。
     *
     * exact な値のbyte配列は必ず1byte以上なので衝突しない。
     */
    private static final int LAYERED_MARKER = 0;

    /**
     * 通常の値なら数個あれば十分。
     * packet floodによる異常なEntry配列確保を防ぐ。
     */
    private static final int MAXIMUM_LAYERS = 64;

    /**
     * degreeのネストの深さ上限。
     *
     * グラハム数(g64)で64段、テスト用の「グラハムの先」の段で最大128段ほど使う。
     * 1段あたり数十byteなので512段でも20KB程度に収まる。
     * 悪意あるpacketで無制限に再帰させないためのガード。
     */
    private static final int MAXIMUM_DEGREE_DEPTH = 512;

    /** degreeがdoubleで書かれていることを示す標識。 */
    private static final byte DEGREE_DOUBLE = 0;

    /** degreeがネストしたBigIntegerとして書かれていることを示す標識。 */
    private static final byte DEGREE_NESTED = 1;

    private BigIntegerBufferCodec() {
    }

    /**
     * BigIntegerをpacketへ書き込む。
     *
     * exact:
     *   [byteLength][two's-complement byte[]]
     *
     * layered:
     *   [0][tower]
     *
     *   tower := [sign][layerCount] ( [count][degreeKind][degree...] )*
     */
    public static void writeNonNegative(
            FriendlyByteBuf buffer,
            BigInteger value,
            int maximumBits) {

        validateMaximum(maximumBits);

        if (value == null) {
            throw new IllegalArgumentException("BigInteger value must not be null");
        }

        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    "BigInteger packet value must be non-negative");
        }

        BigCountMath.requireMaximumBits(
                value,
                "packet/value",
                maximumBits
        );

        /*
         * exact領域なら java.math.BigInteger のcanonicalな
         * two's-complement表現をそのまま送る。
         */
        if (value.isExact()) {
            byte[] encoded = value.toByteArray();

            if (encoded.length < 1) {
                throw new IllegalStateException(
                        "BigInteger.toByteArray() returned an empty array");
            }

            if (encoded.length > HARD_MAXIMUM_BYTES) {
                throw new IllegalArgumentException(
                        "BigInteger packet value exceeds hard byte cap");
            }

            buffer.writeVarInt(encoded.length);
            buffer.writeBytes(encoded);
            return;
        }

        /*
         * exactではない値はbyte[]へ変換できない。
         * BigInteger自身が持っているsparseな
         * (count, degree)列を送信する。
         */
        writeLayered(buffer, value);
    }

    /**
     * packetからBigIntegerを読み込む。
     */
    public static BigInteger readNonNegative(
            FriendlyByteBuf buffer,
            int maximumBits) {

        validateMaximum(maximumBits);

        int length = buffer.readVarInt();

        /*
         * 0はlayered表現。
         */
        if (length == LAYERED_MARKER) {
            BigInteger value = readLayered(buffer);

            if (value.signum() < 0) {
                throw new IllegalArgumentException(
                        "negative BigInteger packet value");
            }

            BigCountMath.requireMaximumBits(
                    value,
                    "packet/value",
                    maximumBits
            );

            return value;
        }

        /*
         * exact表現。
         */
        int maximumBytes = Math.addExact(maximumBits, Byte.SIZE - 1)
                / Byte.SIZE;

        if (length < 1
                || length > maximumBytes
                || length > HARD_MAXIMUM_BYTES) {

            throw new IllegalArgumentException(
                    "invalid BigInteger packet length " + length);
        }

        byte[] encoded = new byte[length];
        buffer.readBytes(encoded);

        /*
         * java.math.BigIntegerのtwo's-complement表現として復元。
         *
         * ACO側のBigInteger(byte[])コンストラクタも
         * java.math.BigInteger(byte[])へ委譲している。
         */
        BigInteger value = new BigInteger(encoded);

        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    "negative BigInteger packet value");
        }

        BigCountMath.requireMaximumBits(
                value,
                "packet/value",
                maximumBits
        );

        /*
         * canonical表現以外を拒否する。
         *
         * これにより同じ値について複数のbyte列を許容しない。
         */
        if (!Arrays.equals(encoded, value.toByteArray())) {
            throw new IllegalArgumentException(
                    "non-canonical BigInteger packet value");
        }

        return value;
    }

    /**
     * packet protocol versionを確認する。
     */
    public static void requireProtocol(int remoteVersion) {
        if (remoteVersion != PROTOCOL_VERSION) {
            throw new IllegalStateException(
                    "ACO BigInteger protocol mismatch: local "
                            + PROTOCOL_VERSION
                            + ", remote "
                            + remoteVersion
            );
        }
    }

    /**
     * layered BigIntegerを書き込む。
     *
     * format:
     *
     *   VarInt 0
     *   tower
     *
     * tower:
     *
     *   byte   sign
     *   VarInt layerCount
     *
     *   repeat layerCount:
     *       double count
     *       byte   degreeKind
     *       degreeKind==0: double degree
     *       degreeKind==1: tower   (次数そのものが巨大数の場合)
     */
    private static void writeLayered(
            FriendlyByteBuf buffer,
            BigInteger value) {

        buffer.writeVarInt(LAYERED_MARKER);
        writeTower(buffer, value, 0);
    }

    private static void writeTower(
            FriendlyByteBuf buffer,
            BigInteger value,
            int depth) {

        if (depth > MAXIMUM_DEGREE_DEPTH) {
            throw new IllegalArgumentException(
                    "BigInteger degree nesting too deep");
        }

        BigInteger.Entry[] entries = value.entries;

        if (entries == null || entries.length < 1) {
            throw new IllegalArgumentException(
                    "BigInteger has no entries");
        }

        if (entries.length > MAXIMUM_LAYERS) {
            throw new IllegalArgumentException(
                    "BigInteger has too many layers: "
                            + entries.length);
        }

        /*
         * sign:
         *  0 = zero
         *  1 = positive
         * -1 = negative
         *
         * writeByte/readByteなのでsigned byteとして扱う。
         */
        if (value.sign < -1 || value.sign > 1) {
            throw new IllegalArgumentException(
                    "invalid BigInteger sign: " + value.sign);
        }

        buffer.writeByte(value.sign);

        buffer.writeVarInt(entries.length);

        for (BigInteger.Entry entry : entries) {

            if (!Double.isFinite(entry.count)) {
                throw new IllegalArgumentException(
                        "non-finite BigInteger layer");
            }

            if (entry.count < 0) {
                throw new IllegalArgumentException(
                        "negative BigInteger layer count");
            }

            buffer.writeDouble(entry.count);

            /*
             * degreeがdoubleに収まらない場合(グラハム数など)は、
             * 次数そのものをtowerとして再帰的に書く。
             */
            if (entry.hasBigDegree()) {
                buffer.writeByte(DEGREE_NESTED);
                writeTower(buffer, entry.degreeBig, depth + 1);
                continue;
            }

            if (!Double.isFinite(entry.degree)) {
                throw new IllegalArgumentException(
                        "non-finite BigInteger layer");
            }

            if (entry.degree < 0) {
                throw new IllegalArgumentException(
                        "negative BigInteger layer degree");
            }

            buffer.writeByte(DEGREE_DOUBLE);
            buffer.writeDouble(entry.degree);
        }
    }

    /**
     * layered BigIntegerを読み込む。
     */
    private static BigInteger readLayered(
            FriendlyByteBuf buffer) {

        return readTower(buffer, 0);
    }

    private static BigInteger readTower(
            FriendlyByteBuf buffer,
            int depth) {

        if (depth > MAXIMUM_DEGREE_DEPTH) {
            throw new IllegalArgumentException(
                    "BigInteger degree nesting too deep");
        }

        int sign = buffer.readByte();

        if (sign < -1 || sign > 1) {
            throw new IllegalArgumentException(
                    "invalid BigInteger sign: " + sign);
        }

        int layers = buffer.readVarInt();

        if (layers < 1 || layers > MAXIMUM_LAYERS) {
            throw new IllegalArgumentException(
                    "invalid BigInteger layer count: " + layers);
        }

        BigInteger.Entry[] entries =
                new BigInteger.Entry[layers];

        for (int index = 0; index < layers; index++) {

            double count = buffer.readDouble();

            if (!Double.isFinite(count) || count < 0) {
                throw new IllegalArgumentException(
                        "invalid BigInteger layer count");
            }

            byte degreeKind = buffer.readByte();

            if (degreeKind == DEGREE_NESTED) {
                entries[index] = new BigInteger.Entry(
                        count,
                        readTower(buffer, depth + 1));
                continue;
            }

            if (degreeKind != DEGREE_DOUBLE) {
                throw new IllegalArgumentException(
                        "invalid BigInteger degree kind: " + degreeKind);
            }

            double degree = buffer.readDouble();

            if (!Double.isFinite(degree)) {
                throw new IllegalArgumentException(
                        "non-finite BigInteger layer");
            }

            if (degree < 0) {
                throw new IllegalArgumentException(
                        "negative BigInteger layer component");
            }

            entries[index] =
                    new BigInteger.Entry(count, degree);
        }

        /*
         * 現在のBigIntegerにはfromComponentsがないため、
         * Codec側から復元できるfactoryをBigIntegerへ追加する。
         */
        return BigInteger.fromComponents(sign, entries);
    }

    private static void validateMaximum(int maximumBits) {
        BigCountMath.requireMaximumBits(
                BigInteger.ZERO,
                "packet maximum",
                maximumBits
        );
    }
}