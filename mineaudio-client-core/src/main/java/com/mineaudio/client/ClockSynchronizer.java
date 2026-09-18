package com.mineaudio.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 单调时钟校时（NTP 风格四时间戳）：
 * <pre>
 * 服务端 t0 ──PING──► 客户端 t1
 *                     客户端 t2
 * 服务端 t3 ◄──PONG────
 * </pre>
 * offset = ((t1 - t0) + (t2 - t3)) / 2，RTT = (t3 - t0) - (t2 - t1)。
 * 所有时间使用单调毫秒（{@link System#nanoTime()}），不受系统时钟调整影响。
 */
public final class ClockSynchronizer {

    private static final int SAMPLES = 5;

    private final List<Sample> samples = new ArrayList<>();
    private volatile long offsetMs;
    private volatile long rttMs = -1;

    public record Sample(long rttMs, long offsetMs) {
    }

    public long monotonicMs() {
        return System.nanoTime() / 1_000_000;
    }

    /** 记录一次 PONG。 */
    public void onPong(long t0, long t1, long t2) {
        long t3 = monotonicMs();
        long rtt = (t3 - t0) - (t2 - t1);
        long offset = ((t1 - t0) + (t2 - t3)) / 2;
        samples.add(new Sample(rtt, offset));
        if (samples.size() > SAMPLES) {
            samples.remove(0);
        }
        apply();
    }

    /** 服务端当前时间（估计值）。 */
    public long serverNow() {
        return monotonicMs() + offsetMs;
    }

    public long offsetMs() {
        return offsetMs;
    }

    public long rttMs() {
        return rttMs;
    }

    public void reset() {
        samples.clear();
        offsetMs = 0;
        rttMs = -1;
    }

    private void apply() {
        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort((a, b) -> Long.compare(a.rttMs(), b.rttMs()));
        List<Sample> best = sorted.subList(0, Math.min(3, sorted.size()));
        List<Long> offsets = new ArrayList<>(best.stream().map(Sample::offsetMs).toList());
        offsets.sort(Long::compareTo);
        offsetMs = offsets.get(offsets.size() / 2);
        long totalRtt = 0;
        for (Sample sample : best) totalRtt += sample.rttMs();
        rttMs = totalRtt / best.size();
    }
}
