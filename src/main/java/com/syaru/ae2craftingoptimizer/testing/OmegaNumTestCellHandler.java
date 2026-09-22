package com.syaru.ae2craftingoptimizer.testing;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.world.item.ItemStack;

/**
 * ACOのテスト用セルをAE2のセルとして解決させるハンドラ。
 *
 * <p>段ごとの供給セル({@link OmegaNumTestCellItem})と、何でも飲み込む
 * 保管セル({@link OmegaNumSinkCellItem})の両方を受け持つ。
 */
public enum OmegaNumTestCellHandler implements ICellHandler {
    INSTANCE;

    @Override
    public boolean isCell(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        return stack.getItem() instanceof OmegaNumTestCellItem
                || stack.getItem() instanceof OmegaNumSinkCellItem;
    }

    @Override
    public StorageCell getCellInventory(ItemStack stack, ISaveProvider container) {
        if (stack == null) {
            return null;
        }
        if (stack.getItem() instanceof OmegaNumTestCellItem item) {
            return new OmegaNumTestCellInventory(stack, container, item.tier().amount());
        }
        if (stack.getItem() instanceof OmegaNumSinkCellItem) {
            return new OmegaNumSinkCellInventory(stack, container);
        }
        return null;
    }
}
