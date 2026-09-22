package com.syaru.ae2craftingoptimizer.testing;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEKey;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;
import java.util.List;
import javaa.maath.BigInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * OmegaNum(= {@link BigInteger})の巨大数を実ネットワークへ流し込むための、テスト専用クリエイティブセル。
 *
 * <p>中身はMEセルワークベンチで設定したキーだけで、各キーを常に
 * {@link OmegaNumTestCellTier#amount()} 個保持しているものとして振る舞う。
 * 段({@link OmegaNumTestCellTier})ごとに別アイテムとして登録され、10^100からグラハム数までを
 * それぞれ試せる。
 * 搬出しても減らないため、long範囲を超えた在庫の表示・同期・クラフト計画を繰り返し試せる。
 *
 * <p>アセット(モデル・テクスチャ・翻訳)は持たない。見た目は未定義のままで、
 * 名前とツールチップはコード側のリテラルで出す。
 */
public final class OmegaNumTestCellItem extends Item implements appeng.api.storage.cells.ICellWorkbenchItem {

    private final OmegaNumTestCellTier tier;

    public OmegaNumTestCellItem(Properties properties, OmegaNumTestCellTier tier) {
        super(properties);
        this.tier = tier;
    }

    /** このセルが属する段。 */
    public OmegaNumTestCellTier tier() {
        return tier;
    }

    @Override
    public ConfigInventory getConfigInventory(ItemStack stack) {
        return CellConfig.create(stack);
    }

    /** テスト用途では曖昧一致を持ち込まない。 */
    @Override
    public FuzzyMode getFuzzyMode(ItemStack stack) {
        return FuzzyMode.IGNORE_ALL;
    }

    @Override
    public void setFuzzyMode(ItemStack stack, FuzzyMode fuzzyMode) {
        // 固定。ワークベンチ側から変更されても受け付けない。
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("ACO Test Cell: " + tier.label());
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> lines,
            TooltipFlag flag) {
        lines.add(Component.literal("Creative test cell (ACO) - " + tier.notation())
                .withStyle(ChatFormatting.DARK_PURPLE));
        BigInteger amount = tier.amount();
        // グラハム数はフル展開すると数百文字になるので、次数のネストは2段までに省略する。
        lines.add(Component.literal("Each configured key: " + amount.toString(2))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(amount.isExact() ? "exact" : "layered (approximate)")
                .withStyle(amount.isExact() ? ChatFormatting.GREEN : ChatFormatting.AQUA));
        ConfigInventory config = getConfigInventory(stack);
        if (config.isEmpty()) {
            lines.add(Component.literal("Configure contents in an ME Cell Workbench")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        for (AEKey key : config.keySet()) {
            lines.add(Component.literal("- ").append(key.getDisplayName())
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
