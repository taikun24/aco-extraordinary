package com.syaru.ae2craftingoptimizer.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.Tooltips;
import com.syaru.ae2craftingoptimizer.client.BigAmountFormatter;
import com.syaru.ae2craftingoptimizer.client.ExactGridAmountClientStore;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ME端末グリッドの数量を、long飽和値ではなくBigInteger正本で描画する。
 *
 * <p>正本を持たないキーはAE2本来の整形をそのまま使うため、通常のネットワークでは
 * 表示は一切変わらない。
 */
@Mixin(value = MEStorageScreen.class, remap = false)
public abstract class MEStorageScreenExactAmountMixin {

    /** スロット右下の数量ラベル。 */
    @Redirect(
            method = "renderSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/stacks/AEKey;formatAmount(JLappeng/api/stacks/AmountFormat;)Ljava/lang/String;"),
            require = 1)
    private String aco$formatExactSlotAmount(AEKey key, long amount, AmountFormat format) {
        return ExactGridAmountClientStore.amount(key)
                .map(exact -> BigAmountFormatter.format(key, exact, format))
                .orElseGet(() -> key.formatAmount(amount, format));
    }

    /** ツールチップの「保管量」行。 */
    @Redirect(
            method = "renderGridInventoryEntryTooltip",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/core/localization/Tooltips;getAmountTooltip("
                            + "Lappeng/core/localization/ButtonToolTips;"
                            + "Lappeng/api/stacks/AEKey;J)"
                            + "Lnet/minecraft/network/chat/Component;"),
            require = 1)
    private Component aco$exactStoredTooltip(ButtonToolTips label, AEKey key, long amount) {
        return ExactGridAmountClientStore.amount(key)
                .<Component>map(exact -> label
                        .text(BigAmountFormatter.format(key, exact, AmountFormat.FULL))
                        .withStyle(Tooltips.MUTED_COLOR))
                .orElseGet(() -> Tooltips.getAmountTooltip(label, key, amount));
    }
}
