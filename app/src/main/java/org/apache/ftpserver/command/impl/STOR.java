/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ftpserver.command.impl;

import com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;

import com.github.stjiege.ftpshare.filesystem.MoveCancellation;
import com.github.stjiege.ftpshare.filesystem.NonClosingOutputStream;
import com.github.stjiege.ftpshare.filesystem.StagedOutputStream;

import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.DataConnection;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import org.apache.ftpserver.impl.LocalizedDataTransferFtpReply;
import org.apache.ftpserver.impl.LocalizedFtpReply;
import org.apache.ftpserver.impl.ServerDataConnectionFactory;
import org.apache.ftpserver.impl.ServerFtpStatistics;
import org.apache.ftpserver.util.IoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>Internal class, do not use directly.</strong>
 *
 * <p>ftpshare 从上游 jar 迁到源码，并在"提交/回滚"语义上做了修正：覆盖上传走
 * {@link StagedOutputStream} 时，数据接收<b>正常结束且未取消</b>才
 * {@code commit()}（原子替换原文件），任何失败/取消都 {@code rollback()}（保留原文件）。
 * 空文件覆盖由"传输成功与否"决定，与文件大小无关，因此零字节的取消不会再清空原文件。</p>
 *
 * <p>REST 续传（offset &gt; 0）与 APPE 追加<b>不走</b> staged 包装，保持各自语义不变。</p>
 */
public class STOR extends AbstractCommand {

    private final Logger LOG = LoggerFactory.getLogger(STOR.class);

    public void execute(FtpIoSession session, FtpServerContext context,
                        FtpRequest request) throws IOException, FtpException {

        // reset state variables
        long fileOffset = session.getFileOffset();
        session.resetState();

        ServerDataConnectionFactory dataCon = SessionDataContext.get(session);
        try {
            // argument check
            String fileName = request.getArgument();
            if (fileName == null) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply501, "STOR", null, null));
                return;
            }

            // get filenames
            FtpFile file = null;
            // 24-10-2009 - added check for null data connection
            // get data connection
            if (dataCon.getInetAddress() == null) {
                session.write(new org.apache.ftpserver.ftplet.DefaultFtpReply(503,
                        "PORT or PASV must be issued first"));
                return;
            }

            try {
                file = session.getFileSystemView().getFile(fileName);
            } catch (Exception ex) {
                com.github.stjiege.ftpshare.utils.LogManager.error(
                        "STOR 路径解析失败: " + fileName, ex);
                LOG.debug("Exception getting file object", ex);
            }
            if (file == null) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply550, "STOR.invalid", fileName, file));
                return;
            }
            fileName = file.getAbsolutePath();

            // check file exist
            if (!file.isWritable()) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply550, "STOR.permission", fileName, file));
                return;
            }

            // send reply 150
            session.write(LocalizedFtpReply.translate(session, request, context,
                    FtpReply150, "STOR", fileName));

            // get data connection
            DataConnection dataConnection;
            try {
                dataConnection = dataCon.openConnection();
            } catch (Exception e) {
                com.github.stjiege.ftpshare.utils.LogManager.warning(
                        "数据连接建立失败（上传）：" + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : " " + e.getMessage()));
                LOG.debug("Exception getting the input data stream", e);
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply425, "STOR", fileName, file));
                return;
            }

            // transfer data
            boolean failure = false;
            long transSz = 0L;
            OutputStream out = null;
            OutputStream transferOut = null;
            try {
                out = file.createOutputStream(fileOffset);
                // transferFromClient() closes its output stream in finally. If that stream is a
                // staged upload, auto-commit would fire before STOR has checked cancellation or
                // caught post-transfer exceptions. Wrap staged streams in a non-closing delegate
                // so STOR keeps the explicit commit/rollback decision.
                if (out instanceof StagedOutputStream) {
                    transferOut = new NonClosingOutputStream(out);
                } else {
                    transferOut = out;
                }
                transSz = dataConnection.transferFromClient(session.getFtpletSession(), transferOut);

                // Data reached EOF cleanly. Only a staged overwrite needs an explicit commit;
                // plain streams (new file / REST / APPE) commit on close.
                if (out instanceof StagedOutputStream) {
                    // 提交前再查一次取消：传输正常结束后、提交前到达的 ABOR 不能被忽略，
                    // 否则"刚检查未取消，随后收到 ABOR，却仍替换原文件"。
                    if (MoveCancellation.isCancelled()) {
                        failure = true;
                        ((StagedOutputStream) out).rollback();
                        session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                                FtpReply426, "STOR", fileName, file));
                    } else {
                        try {
                            ((StagedOutputStream) out).commit();
                        } catch (IOException commitError) {
                            // 提交失败：原文件已保留（commitFile 不做"先删后移"），回 551。
                            failure = true;
                            LOG.warn("Failed to commit staged upload for {}", fileName, commitError);
                            session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                                    MoveCancellation.isCancelled() ? FtpReply426 : FtpReply551, "STOR", fileName, file));
                        }
                    }
                }

                if (!failure) {
                    LOG.info("File uploaded {}", fileName);
                    ServerFtpStatistics stats = ((ServerFtpStatistics) context.getFtpStatistics());
                    if (stats != null) {
                        stats.setUpload(session, file, transSz);
                    }
                }
            } catch (SocketException e) {
                LOG.debug("Socket exception during data transfer", e);
                failure = true;
                rollbackQuietly(out);
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply426, "STOR", fileName, file));
            } catch (IOException e) {
                LOG.debug("IOException during data transfer", e);
                failure = true;
                rollbackQuietly(out);
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        MoveCancellation.isCancelled() ? FtpReply426 : FtpReply551, "STOR", fileName, file));
            } catch (RuntimeException e) {
                // 只捕 IOException 会漏掉 RuntimeException（ROM 层流实现包装的运行时异常、
                // TransferProgress 的防御性抛错、未来重构引入的意外异常）。漏掉的后果不止是
                // 应答错误：finally 的 IoUtils.close(out) 会让结果未决的 StagedUploadStream
                // 走 close() → commit()，把"半截上传"原子替换成正式文件，毁掉原文件。
                // 这里与 IOException 分支同构：回滚 + 551，保证任何异常路径下原文件都保留。
                LOG.warn("Unexpected exception during staged upload, rolling back", e);
                failure = true;
                rollbackQuietly(out);
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        MoveCancellation.isCancelled() ? FtpReply426 : FtpReply551, "STOR", fileName, file));
            } finally {
                IoUtils.close(out);
            }

            if (!failure) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply226, "STOR", fileName, file, transSz));
            }
        } finally {
            session.resetState();
            dataCon.closeDataConnection();
        }
    }

    private static void rollbackQuietly(OutputStream out) {
        if (out instanceof StagedOutputStream) {
            ((StagedOutputStream) out).rollback();
        }
    }

    private static final int FtpReply501 = 501;
    private static final int FtpReply550 = 550;
    private static final int FtpReply150 = 150;
    private static final int FtpReply425 = 425;
    private static final int FtpReply426 = 426;
    private static final int FtpReply551 = 551;
    private static final int FtpReply226 = 226;
}
