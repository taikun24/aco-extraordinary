package javaa.maath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ExpantaNum.js (Naruyoko) の考え方に寄せたJava移植版。
 *
 * <p>原典: https://github.com/Naruyoko/ExpantaNum.js
 *
 * <h2>OmegaNum.js版からの変更点</h2>
 * <p>旧実装(OmegaNum.js移植版)は {@code array[i]} の「添字 i」がそのまま
 * ハイパー演算子の次数(1=冪乗塔の高さ、2=テトレーション回数、3=ペンテーション回数…)を表していた。
 * この方式では、次数 d のハイパー演算に到達するには長さ {@code d+1} の配列が必要になり、
 * たとえば「次数1,000,000のハイパー演算」のような値は事実上表現できなかった
 * (配列を100万要素も確保・走査するのは非現実的なため)。
 *
 * <p>ExpantaNum.js はここを、{@code (count, degree)} のペアの「疎な列」に変えることで解決する。
 * つまり「次数が何であるか」を配列の位置ではなく値として直接持つため、
 * 次数がどれだけ飛び地であっても要素数は増えない。本移植でも同じ考え方を採用した:
 *
 * <pre>
 *   value = base(degree=0) に対して、
 *           degree=d1 のハイパー演算を count1 回、
 *           degree=d2 のハイパー演算を count2 回、
 *           ...(dの昇順)
 *           を順に適用したもの
 * </pre>
 *
 * <p>これにより、たとえば {@link #arrow(double, double)} を使えば
 * 「次数2(テトレーション)を 10^100 回反復した値」のような、
 * 旧実装の {@code for} ループでは到底ループしきれない回数のハイパー演算も O(1) で構築できる。
 *
 * <h2>この移植のスコープ</h2>
 * <p>ExpantaNum.js 原典にはさらに、次数(degree)自体が再帰的にExpantaNum型になる
 * 「X配列表記」(次数そのものが途方もなく大きい場合の表現)が存在するが、
 * これは本移植では扱っていない。次数(degree)・回数(count)はいずれも {@code double} であり、
 * 表現できる次数・回数の大きさは概ね {@code double} の範囲(〜1.8e308)に収まる。
 * それでも「配列の長さ＝次数」だった旧実装に比べれば桁違いに広い範囲を扱える。
 *
 * <p>厳密値(ACOが1個単位で数える領域)まわりのロジックは旧実装から変更していない。
 */
public final class BigInteger implements Comparable<BigInteger> {

    /** 2^53 - 1。doubleで正確に表現できる整数の上限。 */
    public static final double MAX_SAFE_INTEGER = 9007199254740991.0;

    /** base(degree=0)の値がこれを超えたら degree=1 へ繰り上げる閾値。 */
    private static final double LAYER0_OVERFLOW = 9e15; // ≒ MAX_SAFE_INTEGER

    /**
     * degree&gt;=1 の count がこれを超えたら、次の整数次数(degree+1)へ1回分だけ繰り上げる、
     * という「連続する次数間の自動繰り上げ」に使う閾値。
     * ExpantaNum本来の強みは {@link #arrow(double, double)} で次数を直接飛び地に指定できることなので、
     * ここは旧実装同様、実用上ほぼ発火しない値(Double.MAX_VALUE)のままにしてある。
     */
    private static final double LAYER_COUNT_SOFT_CAP = Double.MAX_VALUE;

    private static final double LOG10_OF_2 = Math.log10(2);

    /** 厳密値を保持し続ける上限bit長の既定値(ACOの契約上限と同じ)。 */
    public static final int DEFAULT_EXACT_LIMIT_BITS = 1_048_576;

    /** これを超えるbit長になった時点で厳密値を捨て、次数表現へ「昇格」する。 */
    private static volatile int exactLimitBits = DEFAULT_EXACT_LIMIT_BITS;

    /** java.math.BigInteger 自体が扱える magnitude の上限に対する安全域。 */
    private static final long EXACT_HARD_CEILING_BITS = Integer.MAX_VALUE - 64L;

    /**
     * (count, degree) の1エントリ。degree=0 のエントリは常にちょうど1つ存在し、
     * それが「生の値(base)」を表す。degree&gt;=1 のエントリは
     * 「そのdegreeのハイパー演算を何回適用したか」を count に持つ。
     */
    public static final class Entry {
        public double count;

        /**
         * ハイパー演算子の次数。{@link #degreeBig} が非nullの場合、こちらは
         * {@code Double.POSITIVE_INFINITY} になり、次数の正本は degreeBig 側にある。
         */
        public double degree;

        /**
         * 次数が {@code double}(〜1.8e308)に収まらない場合の正本。
         *
         * <p>ExpantaNum.js の「X配列表記」に相当する部分で、次数そのものが再帰的に
         * ExpantaNum になる。グラハム数のように
         * {@code g(n+1) = 3↑^(g(n))3} と次数が前段の値そのものになる数は、
         * これが無いと表現できない。
         */
        public BigInteger degreeBig;

        public Entry(double count, double degree) {
            this.count = count;
            this.degree = degree;
        }

        /** 次数がdoubleに収まらない場合のエントリ。 */
        public Entry(double count, BigInteger degree) {
            if (degree == null) {
                throw new IllegalArgumentException("degree must not be null");
            }
            this.count = count;
            this.degree = Double.POSITIVE_INFINITY;
            this.degreeBig = degree;
        }

        /** 次数がdoubleの範囲を超えているか。 */
        public boolean hasBigDegree() {
            return degreeBig != null;
        }

        Entry copy() {
            return degreeBig != null
                    ? new Entry(count, degreeBig)
                    : new Entry(count, degree);
        }
    }

    /**
     * エントリの次数を、doubleと巨大次数の両方を跨いで比較・集約できる形にしたもの。
     *
     * <p>doubleで表せる次数は、定義上つねに {@link Entry#degreeBig} 側の次数より小さい
     * (degreeBigはdoubleに収まらなかった場合にのみ使われるため)。
     */
    private static final class DegreeKey implements Comparable<DegreeKey> {
        static final DegreeKey ZERO = new DegreeKey(0.0);
        static final DegreeKey FIRST = new DegreeKey(1.0);

        final double small;
        final BigInteger big;

        DegreeKey(double small) {
            this.small = small;
            this.big = null;
        }

        DegreeKey(BigInteger big) {
            this.small = Double.POSITIVE_INFINITY;
            this.big = big;
        }

        static DegreeKey of(Entry entry) {
            return entry.degreeBig != null
                    ? new DegreeKey(entry.degreeBig)
                    : new DegreeKey(entry.degree);
        }

        boolean isZero() {
            return big == null && small == 0;
        }

        /** 次のひとつ上の整数次数。 */
        DegreeKey next() {
            return big == null ? new DegreeKey(small + 1) : new DegreeKey(big.add(ONE));
        }

        Entry toEntry(double count) {
            return big == null ? new Entry(count, small) : new Entry(count, big);
        }

        @Override
        public int compareTo(DegreeKey other) {
            // 巨大次数は前段の値をそのまま参照していることが多い。
            // 同じインスタンスなら中身を辿らずに決着させる(辿ると深さに対して指数的に膨らむ)。
            if (big != null && big == other.big) {
                return 0;
            }
            if (big == null && other.big == null) {
                return Double.compare(small, other.small);
            }
            // doubleで表せる次数は、巨大次数より必ず小さい。
            if (big == null) {
                return -1;
            }
            if (other.big == null) {
                return 1;
            }
            return big.compareTo(other.big);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof DegreeKey && compareTo((DegreeKey) obj) == 0;
        }

        @Override
        public int hashCode() {
            return big == null ? Double.hashCode(small) : big.hashCode();
        }
    }

    public int sign; // 1, -1, または 0(=値が0のとき)

    /** degree昇順にソートされたエントリ列。entries[0].degree == 0 が常に存在する。 */
    public Entry[] entries;

    /** 厳密値の正本(旧実装と同じ役割)。null でない間はこれが真の値。 */
    private java.math.BigInteger exact;

    // ---------- コンストラクタ ----------

    public BigInteger(double value) {
        if (Double.isNaN(value)) {
            setNaN();
            return;
        }
        if (value == 0) {
            this.sign = 0;
            this.entries = new Entry[]{new Entry(0, 0)};
            this.exact = java.math.BigInteger.ZERO;
            return;
        }
        this.sign = value < 0 ? -1 : 1;
        this.entries = new Entry[]{new Entry(Math.abs(value), 0)};
        if (value == Math.rint(value) && Math.abs(value) <= MAX_SAFE_INTEGER) {
            this.exact = java.math.BigInteger.valueOf((long) value);
        }
        normalize();
    }

    /** 厳密値から生成する。degree/count には次数演算用の近似を同時に埋める。 */
    public BigInteger(java.math.BigInteger value) {
        this.exact = value.bitLength() <= exactLimitBits ? value : null;
        this.sign = value.signum();
        double magnitude = value.abs().doubleValue();
        if (Double.isFinite(magnitude)) {
            this.entries = new Entry[]{new Entry(magnitude, 0)};
        } else {
            this.entries = new Entry[]{new Entry(approximateLog10(value.abs()), 0), new Entry(1, 1)};
        }
        normalize();
    }

    public BigInteger(byte[] twosComplement) {
        this(new java.math.BigInteger(twosComplement));
    }

    public BigInteger(int signum, byte[] magnitude) {
        this(new java.math.BigInteger(signum, magnitude));
    }

    public BigInteger(String decimal) {
        this(new java.math.BigInteger(decimal));
    }

    public BigInteger(String value, int radix) {
        this(new java.math.BigInteger(value, radix));
    }

    private static double approximateLog10(java.math.BigInteger magnitude) {
        int bits = magnitude.bitLength();
        int shift = Math.max(0, bits - 53);
        double head = magnitude.shiftRight(shift).doubleValue();
        return Math.log10(head) + shift * LOG10_OF_2;
    }

    /** base値と、(degree,count)ペアの列から直接組み立てるファクトリ。 */
    public static BigInteger fromArrow(int sign, double base, double... degreeCountPairs) {
        if (degreeCountPairs.length % 2 != 0) {
            throw new IllegalArgumentException("degreeCountPairs must be (degree,count) pairs");
        }
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(base, 0));
        for (int i = 0; i < degreeCountPairs.length; i += 2) {
            list.add(new Entry(degreeCountPairs[i + 1], degreeCountPairs[i]));
        }
        BigInteger r = new BigInteger();
        r.sign = sign;
        r.entries = list.toArray(new Entry[0]);
        r.normalize();
        return r;
    }

    private BigInteger() {
    }

    // ---------- BigInteger風ファクトリ ----------

    public static BigInteger valueOf(long val) {
        return new BigInteger(java.math.BigInteger.valueOf(val));
    }

    public static BigInteger valueOf(double val) {
        return new BigInteger(val);
    }

    public static BigInteger of(java.math.BigInteger value) {
        return new BigInteger(value);
    }

    public static int exactLimitBits() {
        return exactLimitBits;
    }

    public static void setExactLimitBits(int bits) {
        if (bits < 1) {
            throw new IllegalArgumentException("exact limit must be at least 1 bit");
        }
        exactLimitBits = bits;
    }

    private static boolean withinExactLimit(long estimatedBits) {
        return estimatedBits >= 0
                && estimatedBits <= Math.min((long) exactLimitBits, EXACT_HARD_CEILING_BITS);
    }

    private static long estimatedPowBits(java.math.BigInteger base, long exponent) {
        if (base.signum() == 0 || exponent == 0) {
            return 1;
        }
        double log2 = approximateLog10(base.abs()) / LOG10_OF_2;
        double bits = Math.floor(log2 * exponent) + 1;
        return bits >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) bits;
    }

    public static int decimalDigitsToBits(int digits) {
        if (digits < 1) {
            throw new IllegalArgumentException("digits must be at least 1");
        }
        double bits = digits / LOG10_OF_2;
        return bits >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.ceil(bits);
    }

    public static final BigInteger ZERO = new BigInteger(0);
    public static final BigInteger ONE = new BigInteger(1);
    public static final BigInteger TWO = new BigInteger(2);
    public static final BigInteger TEN = new BigInteger(10);
    public static final BigInteger NaN;
    public static final BigInteger POSITIVE_INFINITY;
    public static final BigInteger NEGATIVE_INFINITY;

    static {
        BigInteger nan = new BigInteger();
        nan.sign = 0;
        nan.entries = new Entry[]{new Entry(Double.NaN, 0)};
        NaN = nan;

        BigInteger pinf = new BigInteger();
        pinf.sign = 1;
        pinf.entries = new Entry[]{new Entry(Double.POSITIVE_INFINITY, 0)};
        POSITIVE_INFINITY = pinf;

        BigInteger ninf = new BigInteger();
        ninf.sign = -1;
        ninf.entries = new Entry[]{new Entry(Double.POSITIVE_INFINITY, 0)};
        NEGATIVE_INFINITY = ninf;
    }

    private void setNaN() {
        this.sign = 0;
        this.entries = new Entry[]{new Entry(Double.NaN, 0)};
    }

    // ---------- 内部ヘルパー: entries <-> TreeMap ----------

    private TreeMap<DegreeKey, Double> toMap() {
        TreeMap<DegreeKey, Double> map = new TreeMap<>();
        for (Entry e : entries) {
            map.merge(DegreeKey.of(e), e.count, Double::sum);
        }
        return map;
    }

    private static Entry[] mapToEntries(TreeMap<DegreeKey, Double> map) {
        Entry[] r = new Entry[map.size()];
        int idx = 0;
        for (Map.Entry<DegreeKey, Double> en : map.entrySet()) {
            r[idx++] = en.getKey().toEntry(en.getValue());
        }
        return r;
    }

    private double base() {
        return entries[0].count;
    }

    /** degree>=1 のうち最小の次数のエントリ(存在しなければnull)。 */
    private Entry lowestArrow() {
        return entries.length > 1 ? entries[1] : null;
    }

    /** degree>=1 のうち最大の次数のエントリ(存在しなければnull)。 */
    private Entry highestArrow() {
        return entries.length > 1 ? entries[entries.length - 1] : null;
    }

    // ---------- 正規化 ----------

    private void normalize() {
        if (containsNaN()) {
            setNaN();
            return;
        }

        TreeMap<DegreeKey, Double> map = toMap();
        map.putIfAbsent(DegreeKey.ZERO, 0.0);
        map.entrySet().removeIf(en -> !en.getKey().isZero() && en.getValue() <= 0);

        // baseのオーバーフロー処理 → degree=1 へ繰り上げ
        double base = map.get(DegreeKey.ZERO);
        int guard = 0;
        while (base > LAYER0_OVERFLOW && guard++ < 10000) {
            base = Math.log10(base);
            map.merge(DegreeKey.FIRST, 1.0, Double::sum);
        }
        map.put(DegreeKey.ZERO, base);

        // 連続する整数次数間でのcountオーバーフロー繰り上げ
        guard = 0;
        boolean changed = true;
        while (changed && guard++ < 10000) {
            changed = false;
            for (DegreeKey deg : new ArrayList<>(map.keySet())) {
                if (!deg.isZero() && map.get(deg) > LAYER_COUNT_SOFT_CAP) {
                    map.put(deg, 0.0);
                    map.merge(deg.next(), 1.0, Double::sum);
                    changed = true;
                    break;
                }
            }
        }
        map.entrySet().removeIf(en -> !en.getKey().isZero() && en.getValue() <= 0);

        entries = mapToEntries(map);

        if (entries.length == 1 && entries[0].count == 0) {
            sign = 0;
        }
    }

    private boolean containsNaN() {
        for (Entry e : entries) {
            if (Double.isNaN(e.count)) return true;
            if (e.degreeBig != null) {
                if (e.degreeBig.isNaN()) return true;
            } else if (Double.isNaN(e.degree)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 基本情報 ----------

    public boolean isNaN() {
        return sign == 0 && entries.length == 1 && Double.isNaN(entries[0].count);
    }

    public boolean isZero() {
        return sign == 0 && !isNaN();
    }

    public boolean isInfinite() {
        Entry top = entries[entries.length - 1];
        if (Double.isInfinite(top.count)) return true;
        // degreeBigが載っているエントリのdegreeは+Infだが、これは無限大ではなく
        // 「doubleでは書けないほど大きい有限の次数」を意味する。
        return top.degreeBig == null && Double.isInfinite(top.degree);
    }

    public boolean isFinite() {
        return !isNaN() && !isInfinite();
    }

    /** degree>=1のエントリが1つでもあるか(=単なるdoubleでは表せない高さがあるか)。 */
    public boolean isLayered() {
        return entries.length > 1;
    }

    /** 現在この数を表すのに使われている最大のハイパー演算子次数(base単体なら0)。 */
    public double topDegree() {
        return entries[entries.length - 1].degree;
    }

    /** 最大次数を、doubleに収まらない場合も含めて返す。 */
    public BigInteger topDegreeBig() {
        Entry top = entries[entries.length - 1];
        return top.degreeBig != null ? top.degreeBig.copy() : new BigInteger(top.degree);
    }

    public int signum() {
        if (exact != null) return exact.signum();
        if (isNaN()) throw new ArithmeticException("NaN has no signum");
        return isZero() ? 0 : sign;
    }

    public BigInteger abs() {
        if (exact != null) return new BigInteger(exact.abs());
        BigInteger r = copy();
        if (r.sign != 0) r.sign = 1;
        return r;
    }

    public BigInteger neg() {
        if (exact != null) return new BigInteger(exact.negate());
        BigInteger r = copy();
        r.sign = -r.sign;
        return r;
    }

    public BigInteger copy() {
        BigInteger r = new BigInteger();
        r.sign = this.sign;
        r.entries = new Entry[this.entries.length];
        for (int i = 0; i < entries.length; i++) r.entries[i] = this.entries[i].copy();
        r.exact = this.exact;
        return r;
    }

    // ---------- 比較 ----------

    /**
     * 絶対値を比較する。最大次数から順に(次数, 回数)を比較し、
     * 一致し続けた場合に最後 base(degree=0) を比較する。
     */
    private static int compareAbs(Entry[] a, Entry[] b) {
        int i = a.length - 1;
        int j = b.length - 1;
        while (i >= 1 && j >= 1) {
            int dc = DegreeKey.of(a[i]).compareTo(DegreeKey.of(b[j]));
            if (dc != 0) {
                return dc;
            }
            int c = Double.compare(a[i].count, b[j].count);
            if (c != 0) return c;
            i--;
            j--;
        }
        if (i >= 1) return 1;  // aにまだdegree>=1のエントリが残っている → aが大きい
        if (j >= 1) return -1; // bにまだ残っている → bが大きい
        return Double.compare(a[0].count, b[0].count);
    }

    @Override
    public int compareTo(BigInteger other) {
        if (this == other) {
            return 0;
        }
        if (this.exact != null && other.exact != null) {
            return this.exact.compareTo(other.exact);
        }
        if (this.isNaN() || other.isNaN()) {
            throw new ArithmeticException("Cannot compare NaN ExpantaNum");
        }
        if (this.sign != other.sign) {
            return Integer.compare(this.sign, other.sign);
        }
        if (this.sign == 0) return 0;
        int cmp = compareAbs(this.entries, other.entries);
        return this.sign > 0 ? cmp : -cmp;
    }

    public boolean gt(BigInteger o) { return compareTo(o) > 0; }
    public boolean gte(BigInteger o) { return compareTo(o) >= 0; }
    public boolean lt(BigInteger o) { return compareTo(o) < 0; }
    public boolean lte(BigInteger o) { return compareTo(o) <= 0; }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BigInteger)) return false;
        BigInteger o = (BigInteger) obj;
        if (this == o) return !isNaN();
        if (this.exact != null && o.exact != null) return this.exact.equals(o.exact);
        if (this.isNaN() || o.isNaN()) return false;
        if (this.sign != o.sign || this.entries.length != o.entries.length) return false;
        for (int i = 0; i < entries.length; i++) {
            if (!sameDegree(entries[i], o.entries[i]) || entries[i].count != o.entries[i].count) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (exact != null) return exact.hashCode();
        int h = sign;
        for (Entry e : entries) {
            h = h * 31 + (e.degreeBig != null ? e.degreeBig.hashCode() : Double.hashCode(e.degree));
            h = h * 31 + Double.hashCode(e.count);
        }
        return h;
    }

    public static BigInteger min(BigInteger a, BigInteger b) {
        return a.lte(b) ? a : b;
    }

    public static BigInteger max(BigInteger a, BigInteger b) {
        return a.gte(b) ? a : b;
    }

    // ---------- java.math.BigInteger 互換の整数API ----------

    public java.math.BigInteger toBigInteger() {
        return requireExact("this operation");
    }

    public boolean isExact() {
        return exact != null;
    }

    public java.math.BigDecimal toBigDecimal() {
        return new java.math.BigDecimal(requireExact("toBigDecimal"));
    }

    private java.math.BigInteger requireExact(String operation) {
        if (exact == null) {
            throw new ArithmeticException(
                    "ExpantaNum value is beyond the exact integer range; " + operation + " is not defined for " + this);
        }
        return exact;
    }

    public BigInteger negate() {
        return neg();
    }

    public BigInteger min(BigInteger other) {
        return this.lte(other) ? this : other;
    }

    public BigInteger max(BigInteger other) {
        return this.gte(other) ? this : other;
    }

    public int bitLength() {
        if (exact != null) return exact.bitLength();
        if (isNaN()) throw new ArithmeticException("NaN has no bitLength");
        if (isZero()) return 0;
        if (isInfinite()) {
            throw new ArithmeticException("ExpantaNum tower exceeds int bitLength: " + this);
        }
        if (isLayered()) {
            // 単段の冪乗塔(10^base、degree=1がちょうど1回)以外はintに収まらないので拒否する
            if (entries.length > 2 || entries[1].degree != 1 || entries[1].count != 1) {
                throw new ArithmeticException("ExpantaNum tower exceeds int bitLength: " + this);
            }
            double towerBits = entries[0].count / LOG10_OF_2 + 1;
            if (towerBits >= Integer.MAX_VALUE) {
                throw new ArithmeticException("ExpantaNum exceeds int bitLength: " + this);
            }
            return (int) Math.ceil(towerBits);
        }
        double magnitude = entries[0].count;
        double bits = Math.log(magnitude) / Math.log(2);
        if (bits >= Integer.MAX_VALUE) {
            throw new ArithmeticException("ExpantaNum exceeds int bitLength: " + this);
        }
        return (int) Math.ceil(bits);
    }

    public byte[] toByteArray() {
        return requireExact("toByteArray").toByteArray();
    }

    public BigInteger gcd(BigInteger other) {
        return new BigInteger(requireExact("gcd").gcd(other.requireExact("gcd")));
    }

    public BigInteger mod(BigInteger modulus) {
        return new BigInteger(requireExact("mod").mod(modulus.requireExact("mod")));
    }

    public BigInteger remainder(BigInteger divisor) {
        return new BigInteger(requireExact("remainder").remainder(divisor.requireExact("remainder")));
    }

    public BigInteger[] divideAndRemainder(BigInteger divisor) {
        java.math.BigInteger[] result =
                requireExact("divideAndRemainder").divideAndRemainder(divisor.requireExact("divideAndRemainder"));
        return new BigInteger[]{new BigInteger(result[0]), new BigInteger(result[1])};
    }

    public BigInteger shiftLeft(int distance) {
        java.math.BigInteger value = requireExact("shiftLeft");
        if (distance > 0 && !withinExactLimit((long) value.bitLength() + distance)) {
            return this.multiply(TWO.pow(distance));
        }
        return new BigInteger(value.shiftLeft(distance));
    }

    public BigInteger shiftRight(int distance) {
        return new BigInteger(requireExact("shiftRight").shiftRight(distance));
    }

    public boolean testBit(int index) {
        return requireExact("testBit").testBit(index);
    }

    public BigInteger modPow(BigInteger exponent, BigInteger modulus) {
        return new BigInteger(requireExact("modPow")
                .modPow(exponent.requireExact("modPow"), modulus.requireExact("modPow")));
    }

    // ---------- log10 / pow10 ----------

    /**
     * このExpantaNumの log10 を返す(値は0より大きい前提)。
     * degree>=1のエントリが無ければ通常のMath.log10。
     * あれば、最も小さい次数のエントリのcountを1減らす(0になれば除去)。
     * これは「タワーを1段低くする」操作に相当する。
     */
    public BigInteger log10() {
        if (isNaN() || sign < 0) return NaN;
        if (isZero()) return NEGATIVE_INFINITY;
        Entry lowest = lowestArrow();
        if (lowest == null) {
            return new BigInteger(Math.log10(base()));
        }
        TreeMap<DegreeKey, Double> map = toMap();
        map.merge(DegreeKey.of(lowest), -1.0, Double::sum);
        BigInteger r = new BigInteger();
        r.sign = 1;
        r.entries = mapToEntries(map);
        r.normalize();
        return r;
    }

    /**
     * 10^this を返す(「タワーを1段高くする」操作)。
     */
    public BigInteger pow10() {
        if (isNaN()) return NaN;
        if (isZero()) return ONE.copy();
        if (sign < 0) {
            return ONE.divide(this.neg().pow10());
        }
        if (!isLayered() && base() < 308) {
            return new BigInteger(Math.pow(10, base()));
        }
        return this.arrow(1, 1);
    }

    public BigInteger logBase(double base) {
        return this.log10().divide(new BigInteger(Math.log10(base)));
    }

    public BigInteger ln() {
        return logBase(Math.E);
    }

    /**
     * 次数 degree のハイパー演算(10を底とする)を、現在の値に対して times 回追加で適用する。
     *
     * <p>degree=1: 冪乗塔(10^)を times 段追加
     * <p>degree=2: テトレーション(10↑↑)を times 回追加
     * <p>degree=n: n番目のハイパー演算子を times 回追加
     *
     * <p>times がどれだけ大きくても(たとえば 1e100 のような値でも)O(1)で構築できる点が、
     * forループでheight回演算を反復していた旧OmegaNum移植版との最大の違い。
     */
    public BigInteger arrow(double degree, double times) {
        if (!Double.isFinite(degree)) {
            throw new IllegalArgumentException(
                    "degree must be finite; use arrow(BigInteger, double) for degrees beyond double");
        }
        if (!Double.isFinite(times)) {
            throw new IllegalArgumentException("times must be finite");
        }
        if (degree < 1) throw new IllegalArgumentException("degree must be >= 1");
        return arrowAt(new DegreeKey(degree), times);
    }

    /**
     * 次数そのものが巨大数である場合のハイパー演算。
     *
     * <p>ExpantaNum.js の「X配列表記」に相当する。たとえばグラハム数の
     * {@code g(n+1) = 3↑^(g(n))3} のように、次数が前段の値そのものになる数は
     * doubleの次数では表現できないため、こちらを使う。
     *
     * <p>次数がdoubleに収まる大きさであれば、無駄なネストを作らずに
     * {@link #arrow(double, double)} へ委ねる。
     */
    public BigInteger arrow(BigInteger degree, double times) {
        if (degree == null) throw new IllegalArgumentException("degree must not be null");
        if (!Double.isFinite(times)) throw new IllegalArgumentException("times must be finite");
        if (isNaN() || degree.isNaN()) return NaN;
        if (degree.lt(ONE)) throw new IllegalArgumentException("degree must be >= 1");
        double asDouble = degree.toDouble();
        if (Double.isFinite(asDouble)) {
            return arrow(asDouble, times);
        }
        if (degree.isInfinite()) {
            throw new IllegalArgumentException("degree must be finite");
        }
        return arrowAt(new DegreeKey(degree.copy()), times);
    }

    private BigInteger arrowAt(DegreeKey degree, double times) {
        if (isNaN()) return NaN;
        if (times == 0) return this.copy();
        BigInteger r = this.copy();
        r.exact = null; // 次数演算に入ったら厳密値は手放す
        TreeMap<DegreeKey, Double> map = r.toMap();
        map.merge(degree, times, Double::sum);
        r.entries = mapToEntries(map);
        if (r.sign == 0) r.sign = 1;
        r.normalize();
        return r;
    }

    /** this^exponent (実数乗) */
    public BigInteger pow(BigInteger exponent) {
        if (this.exact != null && exponent.exact != null
                && exponent.exact.signum() >= 0
                && exponent.exact.bitLength() < Integer.SIZE
                && withinExactLimit(
                estimatedPowBits(this.exact, exponent.exact.intValueExact()))) {
            return new BigInteger(this.exact.pow(exponent.exact.intValueExact()));
        }
        if (this.isZero()) {
            if (exponent.isZero()) return ONE.copy();
            return exponent.sign > 0 ? ZERO.copy() : POSITIVE_INFINITY;
        }
        if (this.sign < 0) {
            if (!exponent.isLayered() && exponent.base() == Math.floor(exponent.base())) {
                BigInteger posResult = this.abs().pow(exponent);
                long e = (long) exponent.toDouble();
                return (e % 2 == 0) ? posResult : posResult.neg();
            }
            return NaN;
        }
        // this^exp = 10^(exp * log10(this))
        return exponent.multiply(this.log10()).pow10();
    }

    public BigInteger pow(double exponent) {
        return pow(new BigInteger(exponent));
    }

    public BigInteger pow(int exponent) {
        if (exact != null
                && exponent >= 0
                && withinExactLimit(estimatedPowBits(exact, exponent))) {
            return new BigInteger(exact.pow(exponent));
        }
        return pow((double) exponent);
    }

    public BigInteger sqrt() {
        return pow(0.5);
    }

    public BigInteger cbrt() {
        return pow(1.0 / 3);
    }

    public BigInteger exp() {
        return this.multiply(new BigInteger(Math.log10(Math.E))).pow10();
    }

    // ---------- 四則演算 ----------

    public BigInteger add(BigInteger other) {
        if (this.exact != null && other.exact != null) {
            return new BigInteger(this.exact.add(other.exact));
        }
        if (this.isNaN() || other.isNaN()) return NaN;
        if (this.isZero()) return other.copy();
        if (other.isZero()) return this.copy();

        BigInteger big = this.abs().gte(other.abs()) ? this : other;
        BigInteger small = (big == this) ? other : this;

        if (!sameArrowStructure(big, small)) {
            return big.copy();
        }
        if (!big.isLayered()) {
            if (this.sign == other.sign) {
                return new BigInteger(this.sign * (this.base() + other.base()));
            }
            return this.subtract(other.neg());
        }
        if (this.sign != other.sign) {
            return this.subtract(other.neg());
        }
        // 同じ次数構造を持つ2つのタワーの加算: 10^a + 10^b = 10^b * (10^(a-b) + 1) の近似
        BigInteger diff = big.log10().subtract(small.log10());
        double diffD = diff.toDouble();
        double addend = Math.log10(1 + Math.pow(10, -diffD));
        return big.log10().add(new BigInteger(addend)).pow10().withSign(this.sign);
    }

    public BigInteger subtract(BigInteger other) {
        if (this.exact != null && other.exact != null) {
            return new BigInteger(this.exact.subtract(other.exact));
        }
        if (this.isNaN() || other.isNaN()) return NaN;
        if (other.isZero()) return this.copy();
        if (this.isZero()) return other.neg();

        if (this.sign != other.sign) {
            return this.add(other.neg());
        }
        int cmp = this.abs().compareTo(other.abs());
        if (cmp == 0) return ZERO.copy();
        BigInteger big = cmp > 0 ? this : other;
        BigInteger small = cmp > 0 ? other : this;
        int resultSign = (cmp > 0) ? this.sign : -this.sign;

        if (!sameArrowStructure(big, small)) {
            return big.copy().withSign(resultSign);
        }
        if (!big.isLayered()) {
            return new BigInteger(resultSign * (big.base() - small.base()));
        }
        BigInteger diff = big.log10().subtract(small.log10());
        double diffD = diff.toDouble();
        double subtrahend = Math.log10(1 - Math.pow(10, -diffD));
        return big.log10().add(new BigInteger(subtrahend)).pow10().withSign(resultSign);
    }

    /** degree&gt;=1のエントリ構成(次数・回数)が完全に一致するか(=同じ「高さ」のタワーか)。 */
    private static boolean sameArrowStructure(BigInteger a, BigInteger b) {
        if (a.entries.length != b.entries.length) return false;
        for (int i = 1; i < a.entries.length; i++) {
            if (!sameDegree(a.entries[i], b.entries[i]) || a.entries[i].count != b.entries[i].count) {
                return false;
            }
        }
        return true;
    }

    /** 2つのエントリの次数が等しいか(巨大次数同士は再帰比較)。 */
    private static boolean sameDegree(Entry a, Entry b) {
        if ((a.degreeBig == null) != (b.degreeBig == null)) return false;
        if (a.degreeBig != null) {
            return a.degreeBig == b.degreeBig || a.degreeBig.equals(b.degreeBig);
        }
        return a.degree == b.degree;
    }

    private BigInteger withSign(int newSign) {
        BigInteger r = this.copy();
        if (r.sign != 0) {
            if (r.exact != null && newSign != r.sign) {
                r.exact = r.exact.negate();
            }
            r.sign = newSign;
        }
        return r;
    }

    public BigInteger multiply(BigInteger other) {
        if (this.exact != null && other.exact != null
                && withinExactLimit((long) this.exact.bitLength() + other.exact.bitLength())) {
            return new BigInteger(this.exact.multiply(other.exact));
        }
        if (this.isNaN() || other.isNaN()) return NaN;
        if (this.isZero() || other.isZero()) return ZERO.copy();
        int resultSign = this.sign * other.sign;
        if (!this.isLayered() && !other.isLayered()) {
            return new BigInteger(resultSign * this.base() * other.base());
        }
        // log10(a*b) = log10(a) + log10(b)
        return this.log10().add(other.log10()).pow10().withSign(resultSign);
    }

    public BigInteger divide(BigInteger other) {
        if (this.isNaN() || other.isNaN()) return NaN;
        if (other.isZero()) return this.isZero() ? NaN : (this.sign > 0 ? POSITIVE_INFINITY : NEGATIVE_INFINITY);
        if (this.isZero()) return ZERO.copy();
        if (this.exact != null && other.exact != null) {
            return new BigInteger(this.exact.divide(other.exact));
        }
        int resultSign = this.sign * other.sign;
        if (!this.isLayered() && !other.isLayered()) {
            return new BigInteger(resultSign * this.base() / other.base());
        }
        return this.log10().subtract(other.log10()).pow10().withSign(resultSign);
    }

    public BigInteger reciprocal() {
        return ONE.divide(this);
    }

    // ---------- テトレーション(整数高さ、旧実装互換の任意底版) ----------

    /**
     * this ↑↑ height (整数高さのテトレーション、底は任意)。 height &gt;= 0。
     * height=0 -> 1, height=1 -> this, height=2 -> this^this, ...
     *
     * <p>底が10で、かつheightが巨大(たとえば1e50など)な場合は、
     * このforループ版ではなく {@link #arrow(double, double)} (底10専用・O(1)) を使うこと。
     */
    public BigInteger tetrate(int height) {
        if (height < 0) throw new IllegalArgumentException("height must be >= 0");
        if (height == 0) return ONE.copy();
        BigInteger result = this.copy();
        for (int i = 1; i < height; i++) {
            result = this.pow(result);
        }
        return result;
    }

    // ---------- 変換 ----------

    public double toDouble() {
        if (exact != null) return exact.doubleValue();
        if (isNaN()) return Double.NaN;
        if (isZero()) return 0;
        if (!isLayered()) {
            return sign * base();
        }
        return sign > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
    }

    public double doubleValue() {
        return toDouble();
    }

    public float floatValue() {
        return (float) toDouble();
    }

    public long longValue() {
        if (exact != null) {
            if (exact.bitLength() < Long.SIZE) return exact.longValue();
            return exact.signum() > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        double v = toDouble();
        if (Double.isNaN(v)) return 0L;
        if (v >= Long.MAX_VALUE) return Long.MAX_VALUE;
        if (v <= Long.MIN_VALUE) return Long.MIN_VALUE;
        return (long) v;
    }

    public long longValueExact() {
        if (exact != null) return exact.longValueExact();
        if (isNaN()) throw new ArithmeticException("NaN cannot be converted to long");
        if (isLayered()) throw new ArithmeticException("ExpantaNum overflow: value exceeds long range (tower present)");
        double v = sign * base();
        if (v != Math.floor(v) || Double.isInfinite(v)) {
            throw new ArithmeticException("ExpantaNum has nonzero fractional part or is infinite");
        }
        if (Math.abs(v) > MAX_SAFE_INTEGER) {
            throw new ArithmeticException("ExpantaNum out of long range (exceeds 2^53-1, precision not guaranteed)");
        }
        return (long) v;
    }

    public int intValue() {
        long l = longValue();
        if (l > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (l < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) l;
    }

    public int intValueExact() {
        long l = longValueExact();
        if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) {
            throw new ArithmeticException("ExpantaNum out of int range");
        }
        return (int) l;
    }

    @Override
    public String toString() {
        return toString(Integer.MAX_VALUE);
    }

    /**
     * 次数のネストを {@code maxDegreeDepth} 段までに省略して表示する。
     *
     * <p>グラハム数のように次数が63段ネストする値をそのまま文字列化すると
     * 数百文字になるため、GUIなど幅が限られる場所ではこちらを使う。
     * 省略した部分は {@code …} で表す。
     */
    public String toString(int maxDegreeDepth) {
        if (exact != null) return exact.toString();
        if (isNaN()) return "NaN";
        if (isZero()) return "0";
        StringBuilder sb = new StringBuilder();
        if (sign < 0) sb.append('-');

        if (!isLayered()) {
            double v = base();
            if (v < 1e21) {
                if (v == Math.floor(v) && v < 1e15) {
                    sb.append((long) v);
                } else {
                    sb.append(v);
                }
            } else {
                sb.append(String.format("%.6e", v));
            }
            return sb.toString();
        }

        // 単純な冪乗塔(degree=1のみ)は "e" を count 回重ねた記法で表示する。
        // ただし count が桁外れに大きい場合は "e"×count を実際に連結すると非現実的なため、
        // その場合は次数チェーン記法 "10{degree}count" に切り替える。
        if (entries.length == 2 && entries[1].degree == 1 && entries[1].count <= 1000) {
            long height = (long) entries[1].count;
            for (long i = 0; i < height; i++) sb.append('e');
            sb.append(formatDouble(entries[0].count));
            return sb.toString();
        }

        // 一般の次数チェーン記法: 高い次数から順に "10{degree}count " を並べ、最後にbaseを置く。
        for (int i = entries.length - 1; i >= 1; i--) {
            sb.append("10{").append(formatDegree(entries[i], maxDegreeDepth)).append('}')
                    .append(formatDouble(entries[i].count)).append(' ');
        }
        sb.append(formatDouble(entries[0].count));
        return sb.toString();
    }

    /** 次数の表示。巨大次数は深さ予算の範囲で再帰的に展開する。 */
    private static String formatDegree(Entry entry, int depthBudget) {
        if (entry.degreeBig == null) return formatDouble(entry.degree);
        if (depthBudget <= 0) return "…";
        return entry.degreeBig.toString(depthBudget - 1);
    }

    private static String formatDouble(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    // ---------- グラハム数 ----------

    /**
     * グラハム数 G = g(64)。
     *
     * <p>次数がネストできるようになったことで初めて表現できる値。
     * オブジェクトは64段のネストで済むので構築コストは実質ゼロ。
     */
    public static BigInteger grahamsNumber() {
        return g(64);
    }

    /**
     * グラハム数の定義列 g(n)。
     *
     * <pre>
     *   g(1)   = 3↑↑↑↑3            (次数4のハイパー演算)
     *   g(n+1) = 3↑^(g(n))3        (次数が前段の値そのもの)
     * </pre>
     *
     * <p>底が10前提の {@link #arrow(double, double)} を使うため値そのものは近似だが、
     * 次数の階層(どのハイパー演算子まで登ったか)は正確に保たれる。
     */
    public static BigInteger g(int n) {
        if (n < 1) throw new IllegalArgumentException("n must be >= 1");
        BigInteger three = valueOf(3L);
        BigInteger value = three.arrow(4.0, 3.0);
        for (int i = 2; i <= n; i++) {
            value = three.arrow(value, 3.0);
        }
        return value;
    }

    public static void main(String[] args) {
        BigInteger a = new BigInteger(1234.5678);
        BigInteger b = new BigInteger(1e300);
        BigInteger c = a.multiply(b);
        System.out.println("a = " + a);
        System.out.println("b = " + b);
        System.out.println("a*b = " + c);

        BigInteger googol = new BigInteger(1).multiply(new BigInteger(10).pow(100));
        System.out.println("10^100 = " + googol);

        BigInteger googolplex = new BigInteger(10).pow(googol);
        System.out.println("10^googol = " + googolplex);

        BigInteger tower = new BigInteger(10).tetrate(4);
        System.out.println("10↑↑4 (forループ版) = " + tower);

        // ExpantaNum.js風のO(1)構築: テトレーション(degree=2)を 1e50 回反復
        // 旧OmegaNum移植版のtetrate(int)ではforループでheight回反復する必要があり、
        // height=1e50 のような値は現実的な時間で計算できなかった。
        BigInteger hugeArrow = BigInteger.TEN.arrow(2, 1e50);
        System.out.println("10{2}(1e50回) = " + hugeArrow);
        System.out.println("  topDegree = " + hugeArrow.topDegree());

        BigInteger sum = googolplex.add(new BigInteger(1));
        System.out.println("googolplex + 1 == googolplex ? " + sum.equals(googolplex));

        BigInteger huge = new BigInteger(10).tetrate(3); // 10^10^10
        BigInteger hugeLog = huge.log10();
        System.out.println("log10(10↑↑3) = " + hugeLog + "  (期待値 ≈ 10↑↑2 = 10^10)");

        BigInteger n = BigInteger.valueOf(42L);
        System.out.println("valueOf(42).longValueExact() = " + n.longValueExact());
        System.out.println("valueOf(-5).signum() = " + BigInteger.valueOf(-5L).signum());
        System.out.println("ZERO.signum() = " + ZERO.signum());
        System.out.println("valueOf(3.9).intValue() = " + BigInteger.valueOf(3.9).intValue());
        try {
            googolplex.longValueExact();
            System.out.println("ERROR: expected ArithmeticException for googolplex.longValueExact()");
        } catch (ArithmeticException e) {
            System.out.println("googolplex.longValueExact() correctly threw: " + e.getMessage());
        }
        try {
            BigInteger.valueOf(1.5).longValueExact();
            System.out.println("ERROR: expected ArithmeticException for 1.5.longValueExact()");
        } catch (ArithmeticException e) {
            System.out.println("1.5.longValueExact() correctly threw: " + e.getMessage());
        }
    }
    /**
     * packet / serialization用。
     *
     * (sign, entries)からBigIntegerを復元する。
     */
    public static BigInteger fromComponents(
            int sign,
            Entry[] components) {

        if (sign < -1 || sign > 1) {
            throw new IllegalArgumentException(
                    "sign must be -1, 0, or 1");
        }

        if (components == null || components.length == 0) {
            throw new IllegalArgumentException(
                    "components must not be empty");
        }

        BigInteger result = new BigInteger();

        result.sign = sign;
        result.entries = new Entry[components.length];

        for (int i = 0; i < components.length; i++) {
            Entry entry = components[i];

            if (entry == null) {
                throw new IllegalArgumentException(
                        "components contains null entry");
            }

            if (!Double.isFinite(entry.count)
                    || (entry.degreeBig == null && !Double.isFinite(entry.degree))) {
                throw new IllegalArgumentException(
                        "components contains non-finite value");
            }

            if (entry.count < 0) {
                throw new IllegalArgumentException(
                        "components contains negative value");
            }

            if (entry.degreeBig != null) {
                if (entry.degreeBig.isNaN() || entry.degreeBig.sign < 0) {
                    throw new IllegalArgumentException(
                            "components contains invalid nested degree");
                }
            } else if (entry.degree < 0) {
                throw new IllegalArgumentException(
                        "components contains negative value");
            }

            result.entries[i] = entry.copy();
        }

        /*
         * normalize()によってdegree順序、0段目、
         * 不要なcountなどを正規化する。
         *
         * packetからの復元時にも内部表現をcanonicalにする。
         */
        result.exact = null;
        result.normalize();

        return result;
    }
}