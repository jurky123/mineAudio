package com.mineaudio.client.playback;

/**
 * 播放时钟 + 状态机：客户端唯一允许写“听感位置”的组件。
 *
 * <pre>
 * LOADING ──输出起播──► PLAYING ⇄ PAUSED
 *                         │  ▲
 *                    欠载 ▼  │ 恢复输出
 *                      BUFFERING
 *                         │
 * LOADING/PLAYING ──解码结束──► DRAINING ──输出耗尽──► FINISHED
 *                         ERROR（不可恢复）
 * </pre>
 *
 * 位置只在状态迁移时设置锚点，其余时间按本地单调时钟外推；暂停/缓冲时冻结。
 * 纯 Java（不依赖 MC），可单测。
 */
public final class PlaybackClock {

    public enum State {
        LOADING,
        BUFFERING,
        PLAYING,
        PAUSED,
        DRAINING,
        FINISHED,
        ERROR
    }

    private State state = State.LOADING;
    private long anchorPositionMs;
    private long anchorNanos;
    private boolean running;
    private long durationMs;
    private String errorCode;
    /**
     * 用户暂停意图：与“输出阶段”分离。LOADING 时暂停只记住意图（输出阶段不变），
     * 输出就绪或恢复时按意图决定是否真正进入 PAUSED/继续等待。
     */
    private boolean userPaused;

    public synchronized void reset(long durationMs) {
        this.state = State.LOADING;
        this.anchorPositionMs = 0;
        this.anchorNanos = 0;
        this.running = false;
        this.durationMs = Math.max(0, durationMs);
        this.errorCode = null;
        this.userPaused = false;
    }

    public synchronized void setDuration(long durationMs) {
        this.durationMs = Math.max(0, durationMs);
    }

    public synchronized long durationMs() {
        return durationMs;
    }

    /** 首批 PCM 真正开始输出：无暂停意图时进入 PLAYING，有意图时保持 PAUSED。 */
    public synchronized void onOutputStarted(long mediaPositionMs) {
        if (state == State.FINISHED || state == State.ERROR) return;
        anchor(mediaPositionMs);
        if (userPaused) {
            state = State.PAUSED;
            running = false;
        } else {
            state = State.PLAYING;
            running = true;
        }
    }

    /**
     * seek 请求：只记录（由调用方保存目标），时钟冻结在旧位置，不跳转到目标。
     * 真正生效由目标帧到达时的 {@link #onSeekApplied} 确认，避免“请求即成功”。
     */
    public synchronized void onSeekRequested() {
        if (state == State.FINISHED || state == State.ERROR) return;
        freeze();
        running = false;
        if (state != State.PAUSED) {
            state = State.BUFFERING;
        }
    }

    /** 目标位置首帧到达：锚定并恢复输出（有暂停意图则保持暂停）。 */
    public synchronized void onSeekApplied(long targetMs) {
        if (state == State.FINISHED || state == State.ERROR) return;
        anchor(targetMs);
        if (userPaused || state == State.PAUSED) {
            state = State.PAUSED;
            running = false;
        } else {
            state = State.PLAYING;
            running = true;
        }
    }

    /** 暂停：记录意图；PLAYING/BUFFERING 同时冻结并进入 PAUSED，LOADING 只记住意图。 */
    public synchronized void onPause() {
        userPaused = true;
        if (state != State.PLAYING && state != State.BUFFERING) return;
        freeze();
        state = State.PAUSED;
    }

    /** 恢复：清除意图；PAUSED 进入 BUFFERING，等重新定位后的首帧再进入 PLAYING。 */
    public synchronized void onResume() {
        userPaused = false;
        if (state != State.PAUSED) return;
        state = State.BUFFERING;
        running = false;
    }

    /** 欠载：冻结当前位置并等待补数。 */
    public synchronized void onUnderrun() {
        if (state != State.PLAYING) return;
        freeze();
        state = State.BUFFERING;
    }

    /** 解码结束但输出缓冲可能仍有音频：进入 DRAINING，位置继续外推。 */
    public synchronized void onDecoderEnded() {
        if (state == State.FINISHED || state == State.ERROR) return;
        state = State.DRAINING;
    }

    /** 输出耗尽：冻结并 FINISHED。 */
    public synchronized void onDrained() {
        if (state == State.FINISHED || state == State.ERROR) return;
        freeze();
        state = State.FINISHED;
    }

    public synchronized void onError(String code) {
        if (state == State.ERROR) return;
        freeze();
        state = State.ERROR;
        errorCode = code;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean playing() {
        return state == State.PLAYING;
    }

    /** 用户暂停意图（输出阶段可能仍是 LOADING/BUFFERING）。 */
    public synchronized boolean paused() {
        return userPaused;
    }

    public synchronized boolean finished() {
        return state == State.FINISHED;
    }

    public synchronized boolean ended() {
        return state == State.FINISHED || state == State.ERROR;
    }

    public synchronized String errorCode() {
        return errorCode;
    }

    /** STATE 上报用状态名（映射到服务端 PlaybackState 枚举：LOADING→BUFFERING，DRAINING→PLAYING）。 */
    public synchronized String stateName() {
        return switch (state) {
            case LOADING -> "BUFFERING";
            case DRAINING -> "PLAYING";
            default -> state.name();
        };
    }

    /** 听感位置（毫秒）；冻结状态下返回冻结值。 */
    public synchronized long positionMs() {
        long position = anchorPositionMs;
        if (running) {
            long elapsed = (System.nanoTime() - anchorNanos) / 1_000_000;
            position += Math.max(0, elapsed);
        }
        if (durationMs > 0 && position > durationMs) {
            position = durationMs;
        }
        return Math.max(0, position);
    }

    private void anchor(long mediaPositionMs) {
        anchorPositionMs = Math.max(0, mediaPositionMs);
        anchorNanos = System.nanoTime();
    }

    private void freeze() {
        long current = positionMs();
        anchorPositionMs = current;
        running = false;
    }
}
