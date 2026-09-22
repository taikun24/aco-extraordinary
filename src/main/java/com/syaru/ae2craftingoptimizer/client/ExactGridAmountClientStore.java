package com.syaru.ae2craftingoptimizer.client;

import appeng.api.stacks.AEKey;
import appeng.menu.me.common.MEStorageMenu;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javaa.maath.BigInteger;
import net.minecraft.client.Minecraft;

/**
 * 現在開いているME端末だけへ、グリッド在庫のBigInteger正本を提供する。
 *
 * <p>AE2本来のGrid Payloadはlongしか運べないため、long表示と食い違うキーだけを
 * ACO側のPayloadで補い、描画時にこのStoreを参照する。
 * {@link BigCraftingPlanClientStore} と同じく、containerIdが一致する画面にだけ適用する。
 */
public final class ExactGridAmountClientStore {
    private static volatile Snapshot current;

    private ExactGridAmountClientStore() {
    }

    public static void accept(int containerId, Map<AEKey, BigInteger> amounts) {
        current = new Snapshot(containerId, amounts);
    }

    public static void clear(int containerId) {
        Snapshot snapshot = current;
        if (snapshot != null && snapshot.containerId() == containerId) {
            current = null;
        }
    }

    public static Optional<Snapshot> current() {
        Snapshot snapshot = current;
        var player = Minecraft.getInstance().player;
        if (snapshot == null
                || player == null
                || !(player.containerMenu instanceof MEStorageMenu menu)
                || menu.containerId != snapshot.containerId()) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    /** そのキーがlong表示と異なる正確量を持つ場合だけ返す。 */
    public static Optional<BigInteger> amount(AEKey key) {
        if (key == null) {
            return Optional.empty();
        }
        return current().map(snapshot -> snapshot.amounts().get(key));
    }

    public record Snapshot(int containerId, Map<AEKey, BigInteger> amounts) {
        public Snapshot {
            amounts = Map.copyOf(new LinkedHashMap<>(
                    Objects.requireNonNull(amounts, "amounts")));
        }
    }
}
