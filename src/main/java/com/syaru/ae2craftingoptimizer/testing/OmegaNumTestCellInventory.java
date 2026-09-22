package com.syaru.ae2craftingoptimizer.testing;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import appeng.items.contents.CellConfig;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javaa.maath.BigInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import com.syaru.ae2craftingoptimizer.access.ExtendedAePlusBigIntegerCellInventoryAccess;

/**
 * {@link OmegaNumTestCellItem} の中身。
 *
 * <p>AE2のセルAPIはlongしか運べないため、この実装は2つの顔を持つ:
 * <ul>
 *   <li>AE2側 ({@link StorageCell}) には、桁あふれを起こさない十分大きなlong値を見せる</li>
 *   <li>ACO側 ({@link ExtendedAePlusBigIntegerCellInventoryAccess}) には、この段の在庫量
 *       そのものを厳密値として見せる</li>
 * </ul>
 *
 * <p>ACOはこの契約を満たすMEStorageを「厳密セル」として解決するので、ExtendedAE Plusを
 * 入れていなくても巨大数の経路を実機で試せる。
 */
public final class OmegaNumTestCellInventory
        implements StorageCell, ExtendedAePlusBigIntegerCellInventoryAccess {



    /**
     * AE2のlong APIへ見せる量。
     *
     * <p>複数マウントの合計がAE2側で加算されても溢れないよう、Long.MAX_VALUEそのものではなく
     * 1024マウント分の余裕を残した値を使う(AE2のクリエイティブセルも同じ理由で
     * Integer.MAX_VALUEに留めている)。
     */
    private static final long LONG_VIEW_AMOUNT = Long.MAX_VALUE / 1024L;

    private final ItemStack stack;
    private final ISaveProvider container;

    /** 各キーの在庫量。セルの段({@link OmegaNumTestCellTier})ごとに異なる。 */
    private final BigInteger infiniteAmount;

    /**
     * ACOへ渡す厳密在庫。ACOは同一性(identity)で追跡するため、このMapは作り直さない。
     * 中身の値だけを設定内容へ合わせて更新する。
     */
    private final Object2ObjectMap<AEKey, BigInteger> amounts = new Object2ObjectOpenHashMap<>();

    private BigInteger total = BigInteger.ZERO;
    private int typeCount;
    private UUID storageUuid;

    public OmegaNumTestCellInventory(ItemStack stack, ISaveProvider container, BigInteger amount) {
        this.stack = stack;
        this.container = container;
        this.infiniteAmount = amount;
        syncKeysFromConfig();
    }

    // ---------- 設定との同期 ----------

    /** ワークベンチ設定のキー集合へMapを合わせる。既存キーの現在量は保つ。 */
    private Set<AEKey> syncKeysFromConfig() {
        Set<AEKey> configured = new LinkedHashSet<>(CellConfig.create(stack).keySet());
        amounts.keySet().retainAll(configured);
        for (AEKey key : configured) {
            amounts.putIfAbsent(key, infiniteAmount);
        }
        recomputeTotals();
        return configured;
    }

    /**
     * 設定された全キーを満タンへ戻す。
     *
     * <p>ACOはこのMapを直接増減させるので、AE2側から触られた時にだけ呼ぶ。
     * ACOのTransaction中(= {@link #aco$getExactStoredAmounts()} 経由)には呼ばない。
     * 途中で値を戻すと、simulate時のbefore/afterと食い違って整合性検査に落ちるため。
     */
    private Set<AEKey> refill() {
        Set<AEKey> configured = syncKeysFromConfig();
        for (AEKey key : configured) {
            amounts.put(key, infiniteAmount);
        }
        recomputeTotals();
        return configured;
    }

    private void recomputeTotals() {
        BigInteger sum = BigInteger.ZERO;
        int types = 0;
        for (BigInteger amount : amounts.values()) {
            if (amount.signum() > 0) {
                types++;
            }
            sum = sum.add(amount);
        }
        this.total = sum;
        this.typeCount = types;
    }

    // ---------- AE2から見た顔 (long) ----------

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (AEKey key : refill()) {
            out.add(key, LONG_VIEW_AMOUNT);
        }
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        // 設定済みキーは無限に飲み込む(=消滅させる)。それ以外は受け取らない。
        return refill().contains(what) ? amount : 0;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        return refill().contains(what) ? amount : 0;
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return amounts.containsKey(what);
    }

    @Override
    public CellState getStatus() {
        return amounts.isEmpty() ? CellState.EMPTY : CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        return 0.0;
    }

    @Override
    public boolean canFitInsideCell() {
        return false;
    }

    @Override
    public void persist() {
        // 中身はワークベンチ設定から導出されるので、保存するものはない。
    }

    @Override
    public Component getDescription() {
        return stack.getHoverName();
    }

    // ---------- ACOから見た顔 (BigInteger) ----------

    @Override
    public Object2ObjectMap<AEKey, BigInteger> aco$getExactStoredAmounts() {
        syncKeysFromConfig();
        return amounts;
    }

    @Override
    public int aco$getExactStoredTypeCount() {
        return typeCount;
    }

    @Override
    public void aco$setExactStoredTypeCount(int value) {
        this.typeCount = value;
    }

    @Override
    public BigInteger aco$getExactStoredTotal() {
        return total;
    }

    @Override
    public void aco$setExactStoredTotal(BigInteger value) {
        this.total = value;
    }

    @Override
    public void aco$saveExactChanges() {
        if (container != null) {
            container.saveChanges();
        }
    }

    @Override
    public boolean aco$hasExactStorageUuid() {
        return storageUuid != null;
    }

    @Override
    public UUID aco$getExactStorageUuid() {
        return storageUuid;
    }

    @Override
    public UUID aco$assignExactStorageUuid() {
        if (storageUuid == null) {
            storageUuid = UUID.randomUUID();
        }
        return storageUuid;
    }
}
