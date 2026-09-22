package com.syaru.ae2craftingoptimizer.testing;

import java.util.function.Supplier;
import javaa.maath.BigInteger;

/**
 * テスト用クリエイティブセルの「段」。
 *
 * <p>厳密値(10^100)から、層表現、次数そのものが巨大数になる領域、そしてグラハム数までを
 * 一段ずつ登れるように並べてある。段ごとに1つアイテムが登録され、各段のセルは設定された
 * キーを {@link #amount()} 個ずつ無限に保持する。
 *
 * <p>どの段で何が切り替わるかの目安:
 * <ul>
 *   <li>{@link #GOOGOL} だけが {@code isExact() == true}。ACOの厳密経路を通る</li>
 *   <li>{@link #E1E9} 以降は層表現(近似)。パケットもタワーのまま運ばれる</li>
 *   <li>{@link #HYPER} は次数(矢印の本数)自体が 10^100 の値。
 *       g1 より大きく g2 より小さいので、宣言順もそこに挟んである</li>
 *   <li>{@link #GRAHAM_G2} 以降は次数がネストした {@code BigInteger} になる</li>
 *   <li>{@link #GRAHAM} より上の3段は「グラハム数の先」。ネストをさらに深く積むだけで作れる</li>
 * </ul>
 *
 * <p><b>この表現の天井について。</b> グラハム数より上の段はどれも、やっていることは
 * 「ネストをもっと深く積む」だけで、急増加関数で言えば G と同じ f_(ω+1) の帯から出ていない。
 * ネストの深さは実際のJavaオブジェクトの段数なので、たとえば g_(g64)(= f_(ω+2) 相当)は
 * 「深さ G のネスト」を要求することになり、この表現では作れない。
 * 配列の長さが壁だった旧実装 → 次数のdoubleが壁 → 今度はネストの深さが壁、と
 * 壁が一段ずつ外側へ移動している。
 */
public enum OmegaNumTestCellTier {

    /** 10^100。厳密値のまま保持される唯一の段。 */
    GOOGOL("googol", "Googol", "10^100",
            () -> BigInteger.TEN.pow(100)),

    /** 10^1,000,000,000。桁を並べたら10億桁になるが、層表現なので軽い。 */
    E1E9("e1e9", "10^1e9", "10^1000000000",
            () -> BigInteger.TEN.pow(1_000_000_000)),

    /** 10^10^100。 */
    GOOGOLPLEX("googolplex", "Googolplex", "10^10^100",
            () -> BigInteger.TEN.pow(BigInteger.TEN.pow(100))),

    /** 10↑↑10(高さ10の冪乗塔)。 */
    TOWER("tower", "Tower", "10^^10",
            () -> BigInteger.TEN.tetrate(10)),

    /** 10↑↑(10^100)。テトレーションを10^100回。 */
    TETRATION("tetration", "Tetration", "10^^(10^100)",
            () -> BigInteger.TEN.arrow(2, 1e100)),

    /** 10↑↑↑(10^100)。ペンテーションを10^100回。 */
    PENTATION("pentation", "Pentation", "10^^^(10^100)",
            () -> BigInteger.TEN.arrow(3, 1e100)),

    /** g1 = 3↑↑↑↑3。グラハム数列の1段目で、次数はまだ4。 */
    GRAHAM_G1("graham_g1", "Graham g1", "3^^^^3",
            () -> BigInteger.g(1)),

    /** 次数(矢印の本数)そのものが10^100。doubleの次数で届く上限付近で、g1とg2の間に位置する。 */
    HYPER("hyper", "Hyper", "10{10^100}(10^100)",
            () -> BigInteger.TEN.arrow(1e100, 1e100)),

    /** g2 = 3↑^(g1)3。次数がネストした BigInteger になる最初の段。 */
    GRAHAM_G2("graham_g2", "Graham g2", "3^(g1)3",
            () -> BigInteger.g(2)),

    /** グラハム数 G = g64。 */
    GRAHAM("graham", "Graham's Number", "G = g64",
            BigInteger::grahamsNumber),

    /** G↑^(G)G。グラハム数自身を次数に使う。ネストが1段深くなるだけで作れる。 */
    GRAHAM_ARROW("graham_arrow", "Graham Arrow", "G^(G)G",
            () -> {
                BigInteger g = BigInteger.grahamsNumber();
                return g.arrow(g, 3);
            }),

    /** g128。グラハム数列そのものを64段目(=G)からさらに64段伸ばしたもの。 */
    GRAHAM_G128("graham_g128", "Graham g128", "g128",
            () -> BigInteger.g(128));

    private final String id;
    private final String label;
    private final String notation;
    private final Supplier<BigInteger> factory;

    /**
     * 段の値。初回参照時に組み立てる。
     *
     * <p>enumの静的初期化中に作ると、どれか1段でも失敗した時点でクラス全体が
     * {@link ExceptionInInitializerError} で死に、原因が分かりにくくなるため遅延させる。
     */
    private volatile BigInteger amount;

    OmegaNumTestCellTier(String id, String label, String notation, Supplier<BigInteger> factory) {
        this.id = id;
        this.label = label;
        this.notation = notation;
        this.factory = factory;
    }

    /** アイテムID(登録名は {@code omeganum_test_cell_<id>})。 */
    public String id() {
        return id;
    }

    /** アイテム名に出す短い呼び名。 */
    public String label() {
        return label;
    }

    /** ツールチップに出す記法。 */
    public String notation() {
        return notation;
    }

    /** この段のセルが各キーについて保持する量。 */
    public BigInteger amount() {
        BigInteger cached = amount;
        if (cached == null) {
            synchronized (this) {
                cached = amount;
                if (cached == null) {
                    cached = factory.get();
                    amount = cached;
                }
            }
        }
        return cached;
    }
}
