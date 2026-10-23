package com.github.stjiege.ftpshare.filesystem;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地移动操作的进度汇总器。
 *
 * <p>RNTO / MOVE 这类操作可能持续几分钟（跨卷拷贝大目录），需要在通知栏给出进度反馈。
 * 这里按<b>字节</b>汇总所有正在进行的移动，给出一个全局百分比；每个移动完成后从
 * 活动列表移除，全部结束后进度自然归零。</p>
 */
public final class MoveProgress {

    public interface Listener {
        /**
         * @param active  是否还有未完成的移动
         * @param label   当前主要操作的简短描述（例如文件名）
         * @param percent 0–100 的全局进度
         */
        void onProgress(boolean active, String label, int percent);
    }

    private static final class Op {
        final String label;
        final long total;
        long current;

        Op(String label, long total) {
            this.label = label;
            this.total = total;
        }
    }

    /** 调用方持有的操作句柄，用于 update / finish。 */
    public static final class Token {
        private final Op op;
        private Token(Op op) { this.op = op; }
    }

    private static final Object LOCK = new Object();
    private static final List<Op> ACTIVE = new ArrayList<>();
    private static volatile Listener listener;

    private MoveProgress() { }

    public static void setListener(Listener listener) {
        MoveProgress.listener = listener;
        report();
    }

    /**
     * 开始追踪一个移动操作。
     *
     * @param label 显示在进度上的简短描述
     * @param total 操作涉及的总字节数（目录需预先统计）
     * @return token，用于后续更新和结束
     */
    public static Token start(String label, long total) {
        Op op = new Op(label != null ? label : "", Math.max(0, total));
        synchronized (LOCK) {
            ACTIVE.add(op);
            reportLocked();
        }
        return new Token(op);
    }

    /** 更新某个操作的已完成的字节数。 */
    public static void update(Token token, long current) {
        if (token == null) return;
        synchronized (LOCK) {
            if (!ACTIVE.contains(token.op)) return;
            token.op.current = Math.max(0, current);
            reportLocked();
        }
    }

    /** 标记一个移动操作完成，从活动列表移除。 */
    public static void finish(Token token) {
        if (token == null) return;
        synchronized (LOCK) {
            if (ACTIVE.contains(token.op)) {
                token.op.current = token.op.total;
            }
            ACTIVE.remove(token.op);
            reportLocked();
        }
    }

    private static void reportLocked() {
        long total = 0;
        long current = 0;
        String label = "";
        for (int i = 0; i < ACTIVE.size(); i++) {
            Op op = ACTIVE.get(i);
            total += op.total;
            current += op.current;
            if (i == ACTIVE.size() - 1) label = op.label;
        }
        Listener l = listener;
        if (l == null) return;
        boolean active = !ACTIVE.isEmpty();
        int percent = 0;
        if (total > 0) {
            percent = (int) (Math.min(current, total) * 100L / total);
        }
        l.onProgress(active, label, Math.min(100, percent));
    }

    private static void report() {
        synchronized (LOCK) {
            reportLocked();
        }
    }
}
