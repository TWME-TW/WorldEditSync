package dev.twme.worldeditsync.common.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

public class TransferMemoryBudgetTest {

    @Test
    public void atomicallyBoundsAndReusesReservations() {
        TransferMemoryBudget budget = new TransferMemoryBudget(10L);

        assertTrue(budget.tryReserve(6L));
        assertFalse(budget.tryReserve(5L));
        assertEquals(6L, budget.getReservedBytes());
        budget.release(6L);
        assertTrue(budget.tryReserve(10L));
        assertEquals(10L, budget.getReservedBytes());
    }

    @Test
    public void rejectsInvalidAndUnbalancedReservations() {
        TransferMemoryBudget budget = new TransferMemoryBudget(10L);

        assertFalse(budget.tryReserve(0L));
        assertFalse(budget.tryReserve(11L));
        assertThrows(IllegalStateException.class, () -> budget.release(1L));
        assertEquals(0L, budget.getReservedBytes());
    }

    @Test
    public void concurrentReservationsNeverExceedTheLimit() throws Exception {
        int contenders = 32;
        TransferMemoryBudget budget = new TransferMemoryBudget(8L);
        CyclicBarrier start = new CyclicBarrier(contenders);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                attempts.add(() -> {
                    start.await();
                    return budget.tryReserve(1L);
                });
            }

            List<Future<Boolean>> results = executor.invokeAll(attempts);
            long accepted = results.stream().filter(result -> {
                try {
                    return result.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).count();

            assertEquals(8L, accepted);
            assertEquals(8L, budget.getReservedBytes());
            for (long index = 0; index < accepted; index++) {
                budget.release(1L);
            }
            assertEquals(0L, budget.getReservedBytes());
        } finally {
            executor.shutdownNow();
        }
    }
}
