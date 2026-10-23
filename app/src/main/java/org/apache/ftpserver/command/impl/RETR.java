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
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketException;

import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.DataConnection;
import org.apache.ftpserver.ftplet.DataConnectionFactory;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.FtpReply;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import org.apache.ftpserver.impl.LocalizedDataTransferFtpReply;
import org.apache.ftpserver.impl.LocalizedFtpReply;
import org.apache.ftpserver.impl.ServerFtpStatistics;
import org.apache.ftpserver.util.IoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>Internal class, do not use directly.</strong>
 *
 * <code>RETR &lt;SP&gt; &lt;pathname&gt; &lt;CRLF&gt;</code><br>
 *
 * ftpshare 改动（相对上游 1.1.1，行为等价性逐条核对过）：
 * <ul>
 *   <li>**去掉 {@code BufferedInputStream(默认 8KB)}**：{@code IODataConnection.transfer()}
 *       恒以 {@code read(buff ≥ 64KB)} 读取，JDK 在「请求长度 ≥ 内部缓冲且未 mark」时直接
 *       透传底层流，这层缓冲每次传输白占一份数组，纯属上游遗留；</li>
 *   <li>ASCII restart is rejected by the command guard; binary restart uses
 *       {@code createInputStream(skipLen)}. ASCII transfers start at zero and
 *       are converted by IODataConnection.</li>
 *   <li>其余（回复码、消息 key、统计、resetState/closeDataConnection 的 finally
 *       时序）与上游逐行一致。</li>
 * </ul>
 */
public class RETR extends AbstractCommand {

    private final Logger LOG = LoggerFactory.getLogger(RETR.class);

    /**
     * Execute command.
     */
    public void execute(final FtpIoSession session,
            final FtpServerContext context, final FtpRequest request)
            throws IOException, FtpException {

        try {

            // get state variable
            long skipLen = session.getFileOffset();

            // argument check
            String fileName = request.getArgument();
            if (fileName == null) {
                session.write(LocalizedDataTransferFtpReply.translate(
                                        session,
                                        request,
                                        context,
                                        FtpReply.REPLY_501_SYNTAX_ERROR_IN_PARAMETERS_OR_ARGUMENTS,
                                        "RETR", null, null));
                return;
            }

            // get file object
            FtpFile file = null;
            try {
                file = session.getFileSystemView().getFile(fileName);
            } catch (Exception ex) {
                com.github.stjiege.ftpshare.utils.LogManager.error(
                        "RETR 路径解析失败: " + fileName, ex);
                LOG.debug("Exception getting file object", ex);
            }
            if (file == null) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        "RETR.missing", fileName, file));
                return;
            }
            fileName = file.getAbsolutePath();

            // check file existance
            if (!file.doesExist()) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        "RETR.missing", fileName, file));
                return;
            }

            // check valid file
            if (!file.isFile()) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        "RETR.invalid", fileName, file));
                return;
            }

            // check permission
            if (!file.isReadable()) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        "RETR.permission", fileName, file));
                return;
            }

            // 24-10-2007 - added check if PORT or PASV is issued, see
            // https://issues.apache.org/jira/browse/FTPSERVER-110
            if (SessionDataContext.get(session).getInetAddress() == null) {
                session.write(new DefaultFtpReply(503, "PORT or PASV must be issued first"));
                return;
            }

            // get data connection
            session.write(LocalizedFtpReply.translate(session, request, context,
                    FtpReply.REPLY_150_FILE_STATUS_OKAY, "RETR", null));

            // send file data to client
            boolean failure = false;
            InputStream is = null;

            DataConnection dataConnection;
            try {
                dataConnection = SessionDataContext.get(session).openConnection();
            } catch (Exception e) {
                // 425 只有一句模板文案，区分不了"客户端没连数据口"和"数据 TLS 握手被
                // 客户端重置"——后者是排查客户端掐断传输时的关键证据，写进应用日志。
                com.github.stjiege.ftpshare.utils.LogManager.warning(
                        "数据连接建立失败（下载）：" + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : " " + e.getMessage()));
                LOG.debug("Exception getting the output data stream", e);
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_425_CANT_OPEN_DATA_CONNECTION, "RETR",
                        null, file));
                return;
            }

            long transSz = 0L;
            try {

                // open streams (ftpshare: raw stream, no buffering layer here;
                // the transfer loop owns buffering on both sides)
                is = file.createInputStream(skipLen);

                // transfer data
                transSz = dataConnection.transferToClient(session.getFtpletSession(), is);
                // attempt to close the input stream so that errors in
                // closing it will return an error to the client (FTPSERVER-119)
                if (is != null) {
                    is.close();
                }

                LOG.info("File downloaded {}", fileName);

                // notify the statistics component
                ServerFtpStatistics ftpStat = (ServerFtpStatistics) context
                        .getFtpStatistics();
                if (ftpStat != null) {
                    ftpStat.setDownload(session, file, transSz);
                }

            } catch (SocketException ex) {
                LOG.debug("Socket exception during data transfer", ex);
                failure = true;
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_426_CONNECTION_CLOSED_TRANSFER_ABORTED,
                        "RETR", fileName, file, transSz));
            } catch (IOException ex) {
                // 只回 551 模板文案（"Error on input file"）而不留异常细节，现场就无法区分
                // EACCES（FUSE 权限）/EIO（存储拔出）/ENOENT（路径解析差异）——客户端重试
                // 循环的真实根因会被埋掉。这里把底层原因写进应用日志，正式包可见。
                com.github.stjiege.ftpshare.utils.LogManager.warning(
                        "读取失败 " + fileName + "：" + ex.getClass().getSimpleName()
                                + (ex.getMessage() == null ? "" : " " + ex.getMessage()));
                LOG.debug("IOException during data transfer", ex);
                failure = true;
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_551_REQUESTED_ACTION_ABORTED_PAGE_TYPE_UNKNOWN,
                        "RETR", fileName, file, transSz));
            } catch (RuntimeException ex) {
                // 与 STOR A1 对称：漏掉 RuntimeException 会让 finally 的 close 吞掉异常后
                // 客户端收不到任何应答，表现为下载挂起。回 551 并保留现场。
                LOG.warn("Unexpected exception during data transfer", ex);
                com.github.stjiege.ftpshare.utils.LogManager.warning(
                        "读取失败 " + fileName + "：" + ex.getClass().getSimpleName()
                                + (ex.getMessage() == null ? "" : " " + ex.getMessage()));
                failure = true;
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_551_REQUESTED_ACTION_ABORTED_PAGE_TYPE_UNKNOWN,
                        "RETR", fileName, file, transSz));
            } finally {
                // make sure we really close the input stream
                IoUtils.close(is);
            }

            // if data transfer ok - send transfer complete message
            if (!failure) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_226_CLOSING_DATA_CONNECTION, "RETR",
                        fileName, file, transSz));

            }
        } finally {
            session.resetState();
            SessionDataContext.get(session).closeDataConnection();
        }
    }
}
