package com.syaru.ae2craftingoptimizer.testing;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 何でも無限に飲み込む、テスト専用のクリエイティブセル。
 *
 * <p>中身は {@link OmegaNumSinkCellInventory} が持つ。容量も型数の上限も無く、
 * 入れたものはBigIntegerとして積み上がる。ワークベンチ設定は要らない。
 *
 * <p>アセット(モデル・テクスチャ・翻訳)は持たない。名前とツールチップはコード側の
 * リテラルで出す。
 */
public final class OmegaNumSinkCellItem extends Item {

    public OmegaNumSinkCellItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("ACO Test Cell: Sink");
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> lines,
            TooltipFlag flag) {
        lines.add(Component.literal("Creative test cell (ACO) - unlimited sink")
                .withStyle(ChatFormatting.DARK_PURPLE));
        lines.add(Component.literal("Accepts anything, no capacity limit")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Stored amounts are kept as BigInteger")
                .withStyle(ChatFormatting.AQUA));
        lines.add(Component.literal("Contents are NOT saved on unload")
                .withStyle(ChatFormatting.RED));
        lines.add(Component.literal("Use a dedicated drive: it swallows everything")
                .withStyle(ChatFormatting.YELLOW));
    }
}
