package com.github.stjiege.ftpshare.filesystem;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 显式控制 commit/rollback 的上传输出流。
 *
 * <p>覆盖上传或 REST 续传会先写到临时文件，命令层在传输正常结束后调用
 * {@link #commit()} 原子替换正式文件；任何失败/取消都调用 {@link #rollback()}
 * 保留原文件。不知道的命令（如 APPE）只会走普通 {@link OutputStream#close()}，
 * 实现应保证 close 在已 commit/rollback 后不再出错。</p>
 */
public abstract class StagedOutputStream extends FilterOutputStream {
    public StagedOutputStream(OutputStream out) { super(out); }

    /** 原子提交；幂等。 */
    public abstract void commit() throws IOException;
    /** 回滚丢弃临时文件；幂等。 */
    public abstract void rollback();
}
