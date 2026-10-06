package com.flowops.scheduler.match;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预留账本单测（D-22）：准入口径、幂等收放、重建、恰好满足边界（docs/06 §16 边界用例 1）。
 */
class ReservedLedgerTest {

    private final ReservedLedger ledger = new ReservedLedger();

    @Test
    void acquire后余量扣减_release后归还() {
        var totals = new ReservedLedger.Resource(8, 0, 16384, 102400);
        var request = new ReservedLedger.Resource(4, 0, 4096, 10240);

        ledger.acquire(1L, 100L, request);
        var afterAcquire = ledger.availableOf(1L, totals);
        assertThat(afterAcquire.cpu()).isEqualTo(4);
        assertThat(afterAcquire.memoryMB()).isEqualTo(12288);
        assertThat(ledger.reservedSteps(1L)).isEqualTo(1);

        ledger.release(1L, 100L);
        var afterRelease = ledger.availableOf(1L, totals);
        assertThat(afterRelease.cpu()).isEqualTo(8);          // 归还精确到分文
        assertThat(ledger.reservedSteps(1L)).isZero();
    }

    @Test
    void 恰好满足也算匹配_超一点都不行() {
        // docs/06 §16 边界用例 1：availCpu == step.cpu 必须匹配成功，超出即出局
        var totals = new ReservedLedger.Resource(4, 0, 0, 0);
        ledger.acquire(1L, 100L, new ReservedLedger.Resource(0.1, 0, 0, 0));   // 余量恰为 3.9

        var available = ledger.availableOf(1L, totals);
        assertThat(available.fits(new ReservedLedger.Resource(3.9, 0, 0, 0))).isTrue();   // == 边界
        assertThat(available.fits(new ReservedLedger.Resource(3.91, 0, 0, 0))).isFalse(); // 超 0.01 核即不匹配
    }

    @Test
    void release幂等_重复释放不产生负账() {
        var totals = new ReservedLedger.Resource(8, 0, 0, 0);
        ledger.acquire(1L, 100L, new ReservedLedger.Resource(2, 0, 0, 0));

        ledger.release(1L, 100L);
        ledger.release(1L, 100L);   // 终态收敛与超时扫描可能双触发（同 §6.3 释放幂等）

        assertThat(ledger.availableOf(1L, totals).cpu()).isEqualTo(8);
    }

    @Test
    void rebuild_以DB投影整体覆盖() {
        ledger.acquire(1L, 100L, new ReservedLedger.Resource(8, 0, 0, 0));   // 旧内存态（崩溃前的脏账）

        ledger.rebuild(List.of(
                new ReservedLedger.NodeReservation(1L, 200L, new ReservedLedger.Resource(2, 0, 0, 0)),
                new ReservedLedger.NodeReservation(1L, 300L, new ReservedLedger.Resource(3, 0, 0, 0))));

        // D-22：账本是派生态，重建后与 DB 一致 —— 旧的 8 核幽灵占用被清除
        var reserved = ledger.reservedOf(1L);
        assertThat(reserved.cpu()).isEqualTo(5);
        assertThat(ledger.reservedSteps(1L)).isEqualTo(2);
    }

    @Test
    void 多节点隔离_互不串账() {
        var totals = new ReservedLedger.Resource(8, 0, 0, 0);
        ledger.acquire(1L, 100L, new ReservedLedger.Resource(6, 0, 0, 0));

        assertThat(ledger.availableOf(1L, totals).cpu()).isEqualTo(2);
        assertThat(ledger.availableOf(2L, totals).cpu()).isEqualTo(8);   // 节点 2 不受影响
    }
}
