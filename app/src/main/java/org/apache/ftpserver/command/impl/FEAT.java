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

import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;

/**
 * Vendored from Apache FtpServer 1.1.1 with one addition: advertise {@code HASH SHA-256}
 * so clients that support the HASH extension can request file checksums.
 *
 * <p>The feature list is kept identical to the upstream resource bundle
 * {@code org/apache/ftpserver/message/FtpStatus.properties} key {@code 211.FEAT},
 * with {@code HASH SHA-256} appended.</p>
 */
public class FEAT extends AbstractCommand {

    public void execute(FtpIoSession session, FtpServerContext context,
            FtpRequest request) throws FtpException {
        session.resetState();

        StringBuilder sb = new StringBuilder();
        sb.append("Extensions supported\n");
        sb.append(" SIZE\n");
        sb.append(" MDTM\n");
        sb.append(" REST STREAM\n");
        sb.append(" LANG en;zh-tw;ja;is\n");
        sb.append(" MLST Size;Modify;Type;Perm\n");
        sb.append(" AUTH SSL\n");
        sb.append(" AUTH TLS\n");
        sb.append(" MODE Z\n");
        sb.append(" UTF8\n");
        sb.append(" TVFS\n");
        sb.append(" MFMT\n");
        sb.append(" HASH SHA-256\n");
        sb.append(" MOVE\n");
        sb.append(" AVBL\n");
        sb.append("End");

        session.write(new DefaultFtpReply(211, sb.toString()));
    }
}
