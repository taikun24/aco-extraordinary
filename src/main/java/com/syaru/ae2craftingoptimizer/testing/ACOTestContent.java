package com.syaru.ae2craftingoptimizer.testing;

import appeng.api.storage.StorageCells;
import com.syaru.ae2craftingoptimizer.AE2CraftingOptimizer;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * テスト専用コンテンツの登録。
 *
 * <p>ACOは本来アイテムを登録しないMODなので、ここに置くものは巨大数の経路を実機で
 * 確認するための足場に限る。アセットは用意しないため、見た目は未定義のままになる。
 *
 * <p>{@link OmegaNumTestCellTier} の段ごとに1つずつクリエイティブセルを登録する。
 * 10^100(厳密)から層表現、次数が巨大数の領域、そしてグラハム数までを並べて比較できる。
 * 加えて、それらを受ける側として {@link #SINK_CELL} を1つ登録する。
 */
public final class ACOTestContent {

    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(AE2CraftingOptimizer.MODID);

    /** 段ごとのセル。クリエイティブタブにも {@link OmegaNumTestCellTier} の宣言順で並ぶ。 */
    public static final Map<OmegaNumTestCellTier, DeferredItem<OmegaNumTestCellItem>> CELLS =
            registerCells();

    /** 何でも無限に飲み込む保管セル。供給セルの逆向きの経路を試すためのもの。 */
    public static final DeferredItem<OmegaNumSinkCellItem> SINK_CELL =
            ITEMS.register(
                    "omeganum_sink_cell",
                    () -> new OmegaNumSinkCellItem(new Item.Properties().stacksTo(1)));

    private ACOTestContent() {
    }

    private static Map<OmegaNumTestCellTier, DeferredItem<OmegaNumTestCellItem>> registerCells() {
        Map<OmegaNumTestCellTier, DeferredItem<OmegaNumTestCellItem>> cells =
                new EnumMap<>(OmegaNumTestCellTier.class);
        for (OmegaNumTestCellTier tier : OmegaNumTestCellTier.values()) {
            cells.put(tier, ITEMS.register(
                    "omeganum_test_cell_" + tier.id(),
                    () -> new OmegaNumTestCellItem(new Item.Properties().stacksTo(1), tier)));
        }
        return cells;
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(ACOTestContent::addToCreativeTab);
    }

    /** AE2側のセル解決は登録順に依存しないが、commonSetupで一度だけ差し込む。 */
    public static void registerCellHandler() {
        StorageCells.addCellHandler(OmegaNumTestCellHandler.INSTANCE);
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.TOOLS_AND_UTILITIES) {
            return;
        }
        for (OmegaNumTestCellTier tier : OmegaNumTestCellTier.values()) {
            event.accept(CELLS.get(tier));
        }
        event.accept(SINK_CELL);
    }
}
