package com.syaru.ae2craftingoptimizer.testing;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.syaru.ae2craftingoptimizer.access.ExtendedAePlusBigIntegerCellInventoryAccess;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.UUID;
import javaa.maath.BigInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * {@link OmegaNumTestCellItem} と対になる、書き込める側のテスト用クリエイティブセル。
 *
 * <p>{@link OmegaNumTestCellInventory} が「無限に湧く供給源」なのに対し、こちらは
 * 「無限に飲み込む保管先」。容量制限も型数制限も無く、入れたものはBigIntegerとして
 * そのまま積み上がる。long(約9.2e18)を超えて積んだ在庫がACOの経路をどう通るかを、
 * 供給側ではなく受け側から確かめるためのもの。
 *
 * <p>AE2のセルAPIはlongしか運べないため、供給セルと同じく2つの顔を持つ:
 * <ul>
 *   <li>AE2側 ({@link StorageCell}) には、long範囲へ丸めた量を見せる</li>
 *   <li>ACO側 ({@link ExtendedAePlusBigIntegerCellInventoryAccess}) には、積み上がった
 *       BigIntegerそのものを見せる</li>
 * </ul>
 *
 * <p><b>中身は保存されない。</b> BigIntegerの在庫をItemStackへ書くには層表現まで扱える
 * NBTコーデックが要る(現状の {@code CanonicalBigIntegerCodec} は厳密値専用)ため、
 * このセルはドライブがアンロードされた時点で中身を失う。テスト用の足場と割り切っている。
 *
 * <p>何でも受け入れるので、ネットワーク内の搬入先として他のセルより先に見つかると全部を
 * 吸い込む。専用のドライブへ入れて使うこと。
 */
public final class OmegaNumSinkCellInventory
        implements StorageCell, ExtendedAePlusBigIntegerCellInventoryAccess {

    /**
     * AE2のlong APIへ見せる量の上限。
     *
     * <p>複数マウントの合計がAE2側で加算されても溢れないよう、Long.MAX_VALUEそのものでは
     * なく1024マウント分の余裕を残す。
     */
    private static final long LONG_VIEW_CAP = Long.MAX_VALUE / 1024L;

    private final ItemStack stack;
    private final ISaveProvider container;

    /** ACOへ渡す厳密在庫。ACOは同一性(identity)で追跡するため、このMapは作り直さない。 */
    private final Object2ObjectMap<AEKey, BigInteger> amounts = new Object2ObjectOpenHashMap<>();

    private BigInteger total = BigInteger.ZERO;
    private int typeCount;
    private UUID storageUuid;

    public OmegaNumSinkCellInventory(ItemStack stack, ISaveProvider container) {
        this.stack = stack;
        this.container = container;
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

    /** BigIntegerの在庫を、AE2へ見せられるlongまで丸める。 */
    private static long toLongView(BigInteger amount) {
        if (amount.signum() <= 0) {
            return 0L;
        }
        long value = amount.longValue(); // 範囲外はLong.MAX_VALUEへ飽和する
        return Math.min(value, LONG_VIEW_CAP);
    }

    // ---------- AE2から見た顔 (long) ----------

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (Object2ObjectMap.Entry<AEKey, BigInteger> entry : amounts.object2ObjectEntrySet()) {
            long view = toLongView(entry.getValue());
            if (view > 0) {
                out.add(entry.getKey(), view);
            }
        }
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (what == null || amount <= 0) {
            return 0;
        }
        // 容量は無い。全量を受け入れる。
        if (mode == Actionable.MODULATE) {
            amounts.merge(what, BigInteger.valueOf(amount), BigInteger::add);
            recomputeTotals();
            aco$saveExactChanges();
        }
        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (what == null || amount <= 0) {
            return 0;
        }
        BigInteger stored = amounts.get(what);
        if (stored == null || stored.signum() <= 0) {
            return 0;
        }
        long available = toLongView(stored);
        long taken = Math.min(amount, available);
        if (taken <= 0) {
            return 0;
        }
        if (mode == Actionable.MODULATE) {
            BigInteger left = stored.subtract(BigInteger.valueOf(taken));
            if (left.signum() <= 0) {
                amounts.remove(what);
            } else {
                amounts.put(what, left);
            }
            recomputeTotals();
            aco$saveExactChanges();
        }
        return taken;
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        // 何でも受け入れるが、優先先として名乗るのは既に入っているものだけにする。
        // そうしないとネットワーク中の搬入を無差別に奪う。
        return amounts.containsKey(what);
    }

    @Override
    public CellState getStatus() {
        // 容量が無いので「満杯」にはならない。
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
        // 層表現まで書けるNBTコーデックが無いため保存しない(クラスのJavadoc参照)。
    }

    @Override
    public Component getDescription() {
        return stack.getHoverName();
    }

    // ---------- ACOから見た顔 (BigInteger) ----------

    @Override
    public Object2ObjectMap<AEKey, BigInteger> aco$getExactStoredAmounts() {
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
