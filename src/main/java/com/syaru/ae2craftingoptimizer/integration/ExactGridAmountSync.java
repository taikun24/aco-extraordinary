package com.syaru.ae2craftingoptimizer.integration;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.menu.me.common.MEStorageMenu;
import com.syaru.ae2craftingoptimizer.config.ACOConfig;
import com.syaru.ae2craftingoptimizer.engine.BigKeyCounterSidecars;
import com.syaru.ae2craftingoptimizer.network.BigCraftingNetwork;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import javaa.maath.BigInteger;
import net.minecraft.server.level.ServerPlayer;

/**
 * ME端末のグリッド在庫のうち、AE2のlong Payloadでは表せないキーだけをClientへ送る。
 *
 * <p>AE2本来のPayloadは変更せず、long値と食い違うキーの正確量だけを別Payloadで補う。
 * broadcastChangesは毎tick走るため、前回送信内容と同じ場合は何も送らない。
 */
public final class ExactGridAmountSync {
    /** Menuが閉じられたら追跡も落とす。Menu自体は保持しない。 */
    private static final Map<MEStorageMenu, Map<AEKey, BigInteger>> LAST_SENT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ExactGridAmountSync() {
    }

    /** broadcastChangesがGrid Snapshotを取った直後に、その同じCounterから差分を作る。 */
    public static void observe(MEStorageMenu menu, KeyCounter counter) {
        if (menu == null
                || counter == null
                || !ACOConfig.enableExactBigIntegerInventorySnapshots()
                || !(menu.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        Map<AEKey, BigInteger> wide = wideAmounts(counter);
        // 同じ内容を毎tick送らない。空へ戻った場合は一度だけ空を送って表示を戻す。
        if (wide.equals(LAST_SENT.get(menu))) {
            return;
        }
        LAST_SENT.put(menu, wide);
        BigCraftingNetwork.sendExactGridAmounts(player, menu.containerId, wide);
    }

    private static Map<AEKey, BigInteger> wideAmounts(KeyCounter counter) {
        var snapshot = BigKeyCounterSidecars.snapshot(counter);
        if (snapshot.isEmpty()) {
            return Map.of();
        }
        int maximumEntries = ACOConfig.getBigIntegerStatusPageEntries();
        Map<AEKey, BigInteger> result = new LinkedHashMap<>();
        for (Map.Entry<AEKey, BigInteger> entry : snapshot.get().amounts().entrySet()) {
            AEKey key = entry.getKey();
            BigInteger exact = entry.getValue();
            if (key == null || exact == null || exact.signum() <= 0) {
                continue;
            }
            // AE2が送るlong値と一致するキーは、端末側の表示を変える必要がない。
            if (exact.equals(BigInteger.valueOf(counter.get(key)))) {
                continue;
            }
            // Payload上限を超える分はAE2本来のlong表示のまま残す。
            if (result.size() >= maximumEntries) {
                break;
            }
            result.put(key, exact);
        }
        return Map.copyOf(result);
    }
}
