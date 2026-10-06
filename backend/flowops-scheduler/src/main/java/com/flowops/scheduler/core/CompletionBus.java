package com.flowops.scheduler.core;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * 完成总线 —— 执行回执到调度主循环的单向通道。
 *
 * <p><b>为什么需要它（单线程契约的守护者）</b>：SSH 执行在独立线程池完成（§3.2 ⑦），
 * 但所有状态变更只允许发生在主循环线程（§3.1：并发决策需要一致视图）。
 * 回执线程只做一件事：把结果放进本队列；下一 tick 的 drainCompletions 在主循环线程里
 * 统一消费 —— 状态机 CAS、账本、互斥锁全部回到单线程世界，无需任何锁。</p>
 *
 * <p><b>延迟代价</b>：回执到状态收敛最多 1 个 tick（≤1s）——可观测性要求里
 * 状态轮询本来就是 3~5s 粒度，这里用 1s 延迟换掉全部并发风险，值得。</p>
 */
public class CompletionBus {

    /** 执行回执：exitCode = null 表示进程未确认启动（DISPATCH_FAIL 语义，SshExecutorClient 契约）。 */
    public record Completion(DispatchSink.DispatchInstruction instruction, Integer exitCode, String failReason) {}

    private final Queue<Completion> queue = new ConcurrentLinkedQueue<>();

    /** 回执线程调用（唯一允许的写入口）。 */
    public void publish(Completion completion) {
        queue.offer(completion);
    }

    /** 主循环线程调用：取走一条回执；空返回 null。 */
    public Completion poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }

    /** 便捷方法：主循环线程内循环消费直到取空。 */
    public void drainEach(Consumer<Completion> handler) {
        Completion completion;
        while ((completion = queue.poll()) != null) {
            handler.accept(completion);
        }
    }
}
